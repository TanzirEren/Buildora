package com.tenix.buildora.apt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * The single processor that ECJ's JSR 269 engine runs ({@code -processor com.tenix.buildora.apt.ProcessorHost}).
 * It loads the real processors from the processor class path and drives them with the same round/claiming
 * rules as javac, so that Buildora can
 * <ul>
 *   <li>name the exact processor in every message and crash,</li>
 *   <li>record which source type each generated file came from (incremental processing),</li>
 *   <li>time every processor and log every round,</li>
 *   <li>survive a processor that throws (reported as a build error, not a compiler crash).</li>
 * </ul>
 * Configuration comes through {@code -Abuildora.apt.config=<properties file>}.
 */
public final class ProcessorHost implements Processor {

    public static final String OPT_CONFIG = "buildora.apt.config";

    private static final class Slot {
        Processor proc;
        String className, simple;
        ProcessorScanner.Kind kind;
        List<String> patterns = new ArrayList<>();
        boolean contributed, dead;
        long nanos;
        int calls, generated;
        ProcessingEnvironment env;
    }

    private final List<Slot> slots = new ArrayList<>();
    private final Set<String> options = new LinkedHashSet<>();
    private AptReport report;
    private ClassLoader loader;
    private ProcessingEnvironment env;
    private boolean raw;
    private int round;
    private int totalGenerated;
    private long startNanos;
    private boolean finished;

    @Override public Set<String> getSupportedOptions() { return options; }

    @Override public Set<String> getSupportedAnnotationTypes() { return Collections.singleton("*"); }

    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }

    @Override public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                                                                  ExecutableElement member, String userText) {
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------ init

    @Override public synchronized void init(ProcessingEnvironment pe) {
        this.env = pe;
        startNanos = System.nanoTime();
        String cfgPath = pe.getOptions().get(OPT_CONFIG);
        if (cfgPath == null) {
            pe.getMessager().printMessage(Diagnostic.Kind.ERROR, "Buildora annotation processing: -A" + OPT_CONFIG + " is missing");
            return;
        }
        Properties cfg = new Properties();
        try (InputStream in = new FileInputStream(cfgPath)) {
            cfg.load(new java.io.InputStreamReader(in, "UTF-8"));
        } catch (Exception e) {
            pe.getMessager().printMessage(Diagnostic.Kind.ERROR, "Buildora annotation processing: cannot read " + cfgPath + ": " + e);
            return;
        }
        report = new AptReport(new File(cfg.getProperty("report", cfgPath + ".report")));
        raw = "true".equals(cfg.getProperty("raw"));
        List<File> jars = new ArrayList<>();
        for (int i = 0; cfg.getProperty("jar." + i) != null; i++) jars.add(new File(cfg.getProperty("jar." + i)));
        try {
            loader = AptLoaders.create(jars, ProcessorHost.class.getClassLoader());
        } catch (Throwable t) {
            fail(null, "Cannot create the processor class loader: " + t);
            return;
        }
        for (int i = 0; cfg.getProperty("proc." + i) != null; i++) {
            String[] a = cfg.getProperty("proc." + i).split("\\|", -1);
            Slot s = new Slot();
            s.className = a[0];
            s.simple = s.className.substring(s.className.lastIndexOf('.') + 1);
            s.kind = a.length > 1 && !a[1].isEmpty() ? ProcessorScanner.Kind.valueOf(a[1]) : ProcessorScanner.Kind.UNKNOWN;
            try {
                Class<?> c = Class.forName(s.className, true, loader);
                s.proc = (Processor) c.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                fail(s, loadHint(s, t));
                continue;
            }
            s.env = raw ? pe : wrapEnv(pe, s);
            try {
                final Slot fs = s;
                withLoader(new Runnable() {
                    @Override public void run() {
                        fs.proc.init(fs.env);
                        for (String p : safe(fs.proc.getSupportedAnnotationTypes())) fs.patterns.add(stripModule(p));
                        options.addAll(safe(fs.proc.getSupportedOptions()));
                    }
                });
            } catch (Throwable t) {
                fail(s, "failed in init(): " + describe(t));
                continue;
            }
            slots.add(s);
            report.event("L", s.className, s.kind.label(), s.patterns.toString());
        }
    }

    private static Set<String> safe(Set<String> s) { return s == null ? Collections.<String>emptySet() : s; }

    private static String stripModule(String p) {
        int i = p.indexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private String loadHint(Slot s, Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        if (c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError) {
            return "cannot load " + s.className + ": class " + c.getMessage() + " was not found. "
                    + "The processor or one of its dependencies is missing from the processor class path "
                    + "(check the processor's Maven coordinate and its dependencies in Annotation processors).";
        }
        if (c instanceof UnsupportedClassVersionError || c instanceof VerifyError)
            return "cannot load " + s.className + ": " + c + ". It was probably built for a newer Java than Android supports.";
        return "cannot load " + s.className + ": " + describe(t);
    }

    // ------------------------------------------------------------------ rounds

    @Override public synchronized boolean process(Set<? extends TypeElement> annotations, RoundEnvironment re) {
        if (finished) return false;
        round++;
        long roundStart = System.nanoTime();
        int genBefore = totalGenerated;
        boolean over = re.processingOver();
        if (report != null) report.event("R", String.valueOf(round), over ? "final" : "", String.valueOf(annotations.size()),
                rootSummary(re));
        if (over) {
            for (Slot s : slots) if (s.contributed && !s.dead) call(s, Collections.<TypeElement>emptySet(), re);
            finish();
            return false;
        }
        Map<String, TypeElement> unmatched = new LinkedHashMap<>();
        for (TypeElement a : annotations) unmatched.put(a.getQualifiedName().toString(), a);
        if (unmatched.isEmpty()) unmatched.put("", null);       // lets "*" processors run with no annotations
        int i = 0;
        for (; i < slots.size() && !unmatched.isEmpty(); i++) {
            Slot s = slots.get(i);
            if (s.dead) continue;
            Set<TypeElement> matched = new LinkedHashSet<>();
            boolean any = false;
            for (Map.Entry<String, TypeElement> e : unmatched.entrySet()) {
                if (matches(s, e.getKey())) { any = true; if (e.getValue() != null) matched.add(e.getValue()); }
            }
            if (any || s.contributed) {
                boolean claimed = call(s, matched, re);
                s.contributed = true;
                if (claimed) for (TypeElement t : matched) unmatched.remove(t.getQualifiedName().toString());
                if (claimed && matched.isEmpty()) unmatched.remove("");
            }
        }
        for (; i < slots.size(); i++) {                          // javac: previously contributing processors run again
            Slot s = slots.get(i);
            if (s.contributed && !s.dead) call(s, Collections.<TypeElement>emptySet(), re);
        }
        if (report != null) report.event("E", String.valueOf(round), String.valueOf((System.nanoTime() - roundStart) / 1000000L),
                String.valueOf(totalGenerated - genBefore));
        return false;
    }

    private static boolean matches(Slot s, String annotationName) {
        for (String p : s.patterns) {
            if (p.equals("*")) return true;
            if (p.endsWith(".*") ? annotationName.startsWith(p.substring(0, p.length() - 1)) : p.equals(annotationName)) return true;
        }
        return false;
    }

    private boolean call(final Slot s, final Set<? extends TypeElement> annos, final RoundEnvironment re) {
        long t0 = System.nanoTime();
        if (report != null) report.event("B", String.valueOf(round), s.className);   // lets a hang/kill name the culprit
        final boolean[] result = new boolean[1];
        final int genBefore = s.generated;
        try {
            withLoader(new Runnable() {
                @Override public void run() { result[0] = s.proc.process(annos, re); }
            });
        } catch (Throwable t) {
            s.dead = true;
            String where = rootSummary(re);
            crash(s, t, where);
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        s.nanos += System.nanoTime() - t0;
        s.calls++;
        if (report != null) {
            StringBuilder names = new StringBuilder();
            for (TypeElement a : annos) { if (names.length() > 0) names.append(','); names.append(a.getSimpleName()); }
            report.event("C", String.valueOf(round), s.className, names.toString(), String.valueOf(ms), String.valueOf(s.generated - genBefore));
        }
        return result[0];
    }

    private void withLoader(Runnable r) {
        Thread th = Thread.currentThread();
        ClassLoader old = th.getContextClassLoader();
        if (loader != null) th.setContextClassLoader(loader);
        try { r.run(); } finally { th.setContextClassLoader(old); }
    }

    private void finish() {
        finished = true;
        if (report == null) return;
        for (Slot s : slots) report.event("T", s.className, s.kind.label(), String.valueOf(s.nanos / 1000000L), String.valueOf(s.calls), String.valueOf(s.generated));
        report.event("S", String.valueOf(round), String.valueOf(totalGenerated), String.valueOf((System.nanoTime() - startNanos) / 1000000L));
        report.close();
    }

    // ------------------------------------------------------------------ diagnostics

    private void fail(Slot s, String msg) {
        String who = s == null ? "Buildora annotation processing" : s.simple;
        if (report != null) report.event("X", s == null ? "" : s.className, "load", msg, "");
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, "[" + who + "] " + msg);
    }

    private void crash(Slot s, Throwable t, String where) {
        String trace = trimmedTrace(t, s.className);
        if (report != null) report.event("X", s.className, "crash", describe(t) + (where.isEmpty() ? "" : "  (round " + round + ", sources: " + where + ")"), trace);
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, "[" + s.simple + "] annotation processor crashed in round " + round
                + (where.isEmpty() ? "" : " while processing " + where) + ": " + describe(t));
    }

    static String describe(Throwable t) {
        String m = t.getMessage();
        if (m != null && m.length() > 400) m = m.substring(0, 400) + "...";
        return t.getClass().getName() + (m == null ? "" : ": " + m);
    }

    /** Exception line + the first frames (where it was thrown), then every cause with its first frames. */
    private static String trimmedTrace(Throwable t, String processor) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            sb.append(depth == 0 ? "" : "Caused by: ").append(describe(cur)).append('\n');
            StackTraceElement[] st = cur.getStackTrace();
            int max = depth == 0 ? 9 : 6;
            for (int i = 0; i < st.length && i < max; i++) sb.append("    at ").append(st[i]).append('\n');
            if (st.length > max) sb.append("    ... ").append(st.length - max).append(" more\n");
            cur = cur.getCause() == cur ? null : cur.getCause();
            depth++;
        }
        return sb.toString().trim();
    }

    private static String rootSummary(RoundEnvironment re) {
        try {
            List<String> names = new ArrayList<>();
            List<String> generated = new ArrayList<>();
            for (Element e : re.getRootElements()) {
                String n = top(e);
                if (n.endsWith(".R") || n.equals("R") || n.endsWith(".BuildConfig")) generated.add(n); else names.add(n);
            }
            if (names.isEmpty()) names = generated;          // nothing but generated classes: show those
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size() && i < 4; i++) { if (i > 0) sb.append(", "); sb.append(names.get(i)); }
            if (names.size() > 4) sb.append(" (+").append(names.size() - 4).append(" more)");
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    /** Qualified name of the top-level type (or package) that contains e. */
    static String top(Element e) {
        Element cur = e;
        TypeElement last = null;
        while (cur != null) {
            if (cur instanceof TypeElement) last = (TypeElement) cur;
            if (cur instanceof PackageElement) {
                if (last != null) return last.getQualifiedName().toString();
                String n = ((PackageElement) cur).getQualifiedName().toString();
                return n.isEmpty() ? "package-info" : n + ".package-info";
            }
            cur = cur.getEnclosingElement();
        }
        return last != null ? last.getQualifiedName().toString() : "";
    }

    // ------------------------------------------------------------------ wrapped environment

    private ProcessingEnvironment wrapEnv(final ProcessingEnvironment real, final Slot s) {
        final Filer filer = (Filer) Proxy.newProxyInstance(Filer.class.getClassLoader(), new Class<?>[]{Filer.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                        Object r;
                        try { r = m.invoke(real.getFiler(), a); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                        String n = m.getName();
                        if (n.equals("createSourceFile") || n.equals("createClassFile")) {
                            record(s, n.equals("createSourceFile") ? "S" : "C", a[0].toString(), (Element[]) a[a.length - 1]);
                        } else if (n.equals("createResource")) {
                            String pkg = a[1].toString();
                            String loc = a[0] instanceof javax.tools.JavaFileManager.Location ? ((javax.tools.JavaFileManager.Location) a[0]).getName() : "";
                            record(s, loc.equals("SOURCE_OUTPUT") ? "RS" : "RC", (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + a[2],
                                    (Element[]) a[a.length - 1]);
                        }
                        return r;
                    }
                });
        final Messager messager = (Messager) Proxy.newProxyInstance(Messager.class.getClassLoader(), new Class<?>[]{Messager.class},
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                        if (m.getName().equals("printMessage") && a != null && a.length >= 2 && a[1] instanceof CharSequence) {
                            Diagnostic.Kind k = (Diagnostic.Kind) a[0];
                            String where = a.length >= 3 && a[2] instanceof Element ? top((Element) a[2]) : "";
                            if (report != null) report.event("M", k.name(), s.className, where, a[1].toString());
                            a = a.clone();
                            a[1] = "[" + s.simple + "] " + a[1];
                        }
                        try { return m.invoke(real.getMessager(), a); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
        return (ProcessingEnvironment) Proxy.newProxyInstance(ProcessingEnvironment.class.getClassLoader(),
                new Class<?>[]{ProcessingEnvironment.class}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                        String n = m.getName();
                        if (n.equals("getFiler")) return filer;
                        if (n.equals("getMessager")) return messager;
                        try { return m.invoke(real, a); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }
                });
    }

    private void record(Slot s, String kind, String name, Element[] origins) {
        s.generated++;
        totalGenerated++;
        StringBuilder o = new StringBuilder();
        if (origins != null) for (Element e : origins) {
            if (e == null) continue;
            String t = top(e);
            if (t.isEmpty()) continue;
            if (o.indexOf("," + t + ",") < 0) o.append(',').append(t).append(',');
        }
        String origin = o.length() == 0 ? "*" : o.toString().replace(",,", ",").replaceAll("^,|,$", "");
        if (report != null) report.event("G", kind, name, s.className, origin);
    }
}
