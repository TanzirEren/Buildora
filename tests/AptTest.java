import com.tenix.buildora.apt.*;
import com.tenix.buildora.build.CompilerWorker;
import com.tenix.buildora.build.JavaCompilerRunner;
import com.tenix.buildora.core.FileUtil;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * JVM regression tests for annotation processing (run by tests/run_apt_tests.sh on a JVM WITHOUT the JDK's
 * java.compiler module: the JSR 269 API comes from Buildora's own sources, exactly like on Android).
 * Env: TC (android.jar, lambda-stubs.jar), API (compiled javax.* classes), WORK (scratch dir),
 *      M2 (optional offline Maven fixture), REAL (optional dir with the real AutoValue/AutoService jars).
 */
public class AptTest {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL: " + what); }
    }
    static void eq(Object exp, Object act, String what) {
        boolean ok = Objects.equals(exp, act);
        if (!ok) System.out.println("  expected <" + exp + "> but was <" + act + ">");
        check(ok, what);
    }

    static File TC, API, WORK;

    public static void main(String[] args) throws Exception {
        TC = new File(System.getenv("TC")); API = new File(System.getenv("API")); WORK = new File(System.getenv("WORK"));
        FileUtil.deleteRecursive(WORK); WORK.mkdirs();
        config();
        scanner();
        Map<String, File> jars = buildFixtureJars();
        hostSemantics(jars);
        stateAndIncremental();
        if (System.getenv("M2") != null) resolutionAndDex();
        System.out.println("apt tests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ AptConfig

    static void config() {
        AptConfig c = AptConfig.fromJson("");
        check(c.enabled && c.isEmpty() && c.isolate && c.incremental && c.timeoutSec == 600, "defaults (old projects have no object)");
        c = AptConfig.fromJson("{\"junk\":1}");
        check(c.isEmpty(), "unknown fields ignored");
        c = AptConfig.fromJson("not json");
        check(c.isEmpty(), "invalid json tolerated");

        c = new AptConfig();
        AptConfig.Entry e = c.add("com.google.dagger:dagger-compiler:2.50");
        e.variants.add("release");
        c.add("local/libs/proc.jar");
        c.add("com.google.dagger:dagger-compiler:2.51");              // replaces the other version of the same artifact
        eq(2, c.processors.size(), "add() replaces same group:artifact");
        eq("com.google.dagger:dagger-compiler:2.51", c.processors.get(0).coord, "version replaced");
        check(c.processors.get(1).isLocal(), "local jar recognized");
        c.options.put("room.schemaLocation", "/sdcard/x \"y\"");
        c.rawEnvironment = true; c.timeoutSec = 90;
        AptConfig d = AptConfig.fromJson(c.toJson());
        eq(c.toJson(), d.toJson(), "JSON round trip (escaping, variants)");
        eq("/sdcard/x \"y\"", d.options.get("room.schemaLocation"), "option value preserved");
        check(d.rawEnvironment && d.timeoutSec == 90, "flags preserved");

        c.processors.get(0).variants.clear(); c.processors.get(0).variants.add("release");
        eq(1, c.active("debug", "debug").size(), "variant filter: only local jar for debug");
        eq(2, c.active("freeRelease", "release").size(), "build-type filter matches release");
        c.processors.get(1).enabled = false;
        eq(0, c.active("debug", "debug").size(), "disabled entries are inactive");
        c.enabled = false;
        eq(0, c.active("freeRelease", "release").size(), "master switch off");
        String f1 = c.fingerprint("debug", "debug");
        c.options.put("a", "b");
        check(!f1.equals(c.fingerprint("debug", "debug")), "option change changes the fingerprint");

        AptConfig g = new AptConfig();
        g.parseGradle("dependencies {\n  implementation 'androidx.room:room-runtime:2.6.1'\n"
                + "  annotationProcessor 'androidx.room:room-compiler:2.6.1'\n  annotationProcessor(\"com.github.bumptech.glide:compiler:4.16.0\")\n"
                + "  kapt 'androidx.room:room-compiler:2.6.1'\n  ksp(\"com.google.dagger:hilt-compiler:2.50\")\n"
                + "  annotationProcessor project(':proc')\n  annotationProcessor files('libs/my.jar')\n  testAnnotationProcessor 'x:y:1'\n}\n"
                + "android { defaultConfig { javaCompileOptions { annotationProcessorOptions {\n arguments = [\"room.schemaLocation\": \"$projectDir/schemas\", 'k2': 'v2']\n } } } }");
        eq(3, g.processors.size(), "gradle: 2 coordinates + 1 files() (test configs ignored)");
        eq("androidx.room:room-compiler:2.6.1", g.processors.get(0).coord, "gradle coordinate");
        eq("libs/my.jar", g.processors.get(2).coord, "gradle files()");
        eq(3, g.unsupported.size(), "kapt, ksp and project() are reported, not faked");
        check(g.unsupported.get(0).contains("kapt") && g.unsupported.get(1).contains("ksp"), "unsupported text names kapt/ksp");
        eq("v2", g.options.get("k2"), "gradle option 'k2'");
        check(g.options.containsKey("room.schemaLocation"), "gradle option room.schemaLocation");
        String lines = g.toGradleLines();
        check(lines.contains("annotationProcessor 'androidx.room:room-compiler:2.6.1'") && lines.contains("files('libs/my.jar')"), "gradle export");
        check(AptConfig.fromJson(g.toJson()).unsupported.size() == 3, "unsupported survives JSON");
    }

    // ------------------------------------------------------------------ ProcessorScanner

    static void scanner() {
        eq(Arrays.asList("a.B", "c.D"), ProcessorScanner.parseServices("# c\n a.B # x\n\nc.D\na.B\n"), "services parsing: comments, blanks, duplicates");
        Map<String, ProcessorScanner.Kind> k = ProcessorScanner.parseKinds("a.B,isolating\nc.D,aggregating # x\ne.F,dynamic\ng.H\nbad,weird\n");
        eq(ProcessorScanner.Kind.ISOLATING, k.get("a.B"), "kind isolating");
        eq(ProcessorScanner.Kind.AGGREGATING, k.get("c.D"), "kind aggregating");
        eq(ProcessorScanner.Kind.DYNAMIC, k.get("e.F"), "kind dynamic");
        check(!k.containsKey("g.H"), "line without kind ignored");
        eq(ProcessorScanner.Kind.UNKNOWN, k.get("bad"), "unknown kind word");
    }

    // ------------------------------------------------------------------ fixture jars (real processors compiled here)

    static Map<String, File> buildFixtureJars() throws Exception {
        File src = new File("tests/apt/fixtures"), cls = new File(WORK, "fixcls");
        cls.mkdirs();
        List<String> a = new ArrayList<>(Arrays.asList("-1.8", "-proc:none", "-nowarn", "-bootclasspath", new File(TC, "android.jar").getPath(),
                "-classpath", API.getPath() + File.pathSeparator + new File(TC, "lambda-stubs.jar").getPath(), "-d", cls.getPath()));
        for (File f : FileUtil.listFiles(src, ".java")) a.add(f.getPath());
        JavaCompilerRunner.Result r = new CompilerWorker().run(a.toArray(new String[0]), 0, null);
        check(r.ok, "fixture processors compile: " + r.output);
        Map<String, File> jars = new LinkedHashMap<>();
        jar(jars, "hello", "p.HelloProcessor", "isolating", "p/HelloProcessor");
        jar(jars, "bad", "q.BadProc", null, "q/BadProc");
        jar(jars, "crash", "q.CrashProc", null, "q/CrashProc");
        jar(jars, "star", "q.StarProc", null, "q/StarProc");
        jar(jars, "claimab", "q.ClaimA\nq.ClaimB", null, "q/ClaimA", "q/ClaimB");
        jar(jars, "opt", "q.OptProc", "isolating", "q/OptProc");
        jar(jars, "noorigin", "q.NoOriginProc", "isolating", "q/NoOriginProc");
        jar(jars, "missingdep", "q.NeedsDep", null, "q/NeedsDep");
        // annotation jar used on the compile class path of the sample app
        File ann = new File(WORK, "hello-annotations.jar");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(ann))) { put(z, "p/Hello.class", rb(new File(cls, "p/Hello.class"))); }
        jars.put("annotations", ann);
        // the processors are loaded from jars; Dep is deliberately not in missingdep.jar
        return jars;
    }

    static void jar(Map<String, File> out, String name, String services, String incremental, String... classes) throws IOException {
        File f = new File(WORK, name + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, ProcessorScanner.SERVICE, (services + "\n").getBytes("UTF-8"));
            if (incremental != null) put(z, ProcessorScanner.INCREMENTAL, (services.split("\n")[0] + "," + incremental + "\n").getBytes("UTF-8"));
            for (String c : classes) {
                File cf = new File(WORK, "fixcls/" + c + ".class");
                put(z, c + ".class", rb(cf));
                File[] inner = cf.getParentFile().listFiles();
                if (inner != null) for (File i : inner) if (i.getName().startsWith(cf.getName().replace(".class", "") + "$"))
                    put(z, c.substring(0, c.lastIndexOf('/') + 1) + i.getName(), rb(i));
            }
            if (name.equals("bad") || name.equals("crash") || name.equals("opt") || name.equals("claimab") || name.equals("noorigin") || name.equals("star"))
                put(z, "p/Hello.class", rb(new File(WORK, "fixcls/p/Hello.class")));
        }
        out.put(name, f);
    }

    static byte[] rb(File f) throws IOException { try (InputStream in = new FileInputStream(f)) { return FileUtil.readAll(in); } }

    static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name)); z.write(data); z.closeEntry();
    }

    // ------------------------------------------------------------------ ProcessorHost + ECJ (end to end)

    static final class Run { boolean ok; String out; List<String[]> report; AptSupport apt; File classes, gen; }

    static int runNo;

    /** Runs real ECJ with annotation processing through ProcessorHost on a tiny project. */
    static Run run(Map<String, File> jars, String[] processorJars, String[] processorClasses, Map<String, String> options, boolean raw,
                   Map<String, String> sources) throws Exception {
        File dir = new File(WORK, "run" + (++runNo));
        File srcDir = new File(dir, "src");
        for (Map.Entry<String, String> s : sources.entrySet()) { File f = new File(srcDir, s.getKey()); f.getParentFile().mkdirs(); FileUtil.writeText(f, s.getValue()); }
        AptConfig cfg = new AptConfig();
        cfg.rawEnvironment = raw;
        cfg.options.putAll(options);
        for (String j : processorJars) cfg.add(jars.get(j).getPath());
        AptSupport apt = new AptSupport(cfg, new File(dir, "apt"));
        ProcessorClasspath.Result cp = ProcessorClasspath.prepare(cfg.processors, dir, new File(dir, "m2"), new File(TC, "android.jar"),
                new File(dir, "dex"), false, true, 26, new ProcessorClasspath.Log() { public void log(String s) { } }, null);
        if (processorClasses != null) { // keep only the wanted processors (jar with several)
            List<ProcessorScanner.Found> keep = new ArrayList<>();
            for (ProcessorScanner.Found f : cp.found) for (String c : processorClasses) if (f.className.equals(c)) keep.add(f);
            cp.found.clear(); cp.found.addAll(keep);
        }
        apt.bind(cp);
        File classes = new File(dir, "classes"); classes.mkdirs();
        List<String> a = new ArrayList<>(Arrays.asList("-1.8", "-encoding", "UTF-8", "-nowarn", "-bootclasspath", new File(TC, "android.jar").getPath()));
        a.addAll(apt.compilerArgs(false));
        a.add("-classpath"); a.add(classes.getPath() + File.pathSeparator + jars.get("annotations").getPath() + File.pathSeparator + new File(TC, "lambda-stubs.jar").getPath());
        a.add("-d"); a.add(classes.getPath()); a.add(srcDir.getPath());
        Run r = new Run();
        JavaCompilerRunner.Result res = new CompilerWorker().run(a.toArray(new String[0]), 60000, null);
        r.ok = res.ok; r.out = res.output + (res.crash == null ? "" : res.crash);
        r.apt = apt; r.classes = classes; r.gen = apt.gen; r.report = AptReport.read(apt.reportFile);
        return r;
    }

    static Map<String, String> src(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static List<String[]> events(Run r, String tag) {
        List<String[]> o = new ArrayList<>();
        for (String[] e : r.report) if (e[0].equals(tag)) o.add(e);
        return o;
    }

    static boolean hasNote(Run r, String text) {
        for (String[] e : events(r, "M")) if (e.length >= 5 && e[4].contains(text)) return true;
        return false;
    }

    static void hostSemantics(Map<String, File> j) throws Exception {
        Map<String, String> two = src("demo/Foo.java", "package demo; @p.Hello public class Foo { }", "demo/Use.java", "package demo; public class Use { String s = Foo_Hello.hi(); }");

        Run r = run(j, new String[]{"hello"}, null, new HashMap<String, String>(), false, two);
        check(r.ok, "generate + compile in rounds: " + r.out);
        check(new File(r.gen, "demo/Foo_Hello.java").isFile(), "generated source is written to the gen dir");
        check(new File(r.classes, "demo/Foo_Hello.class").isFile() && new File(r.classes, "demo/Use.class").isFile(), "generated class compiled, user class sees it");
        List<String[]> g = events(r, "G");
        eq(1, g.size(), "one generated file recorded");
        eq("demo.Foo", g.get(0)[4], "originating element recorded (top-level type)");
        eq("p.HelloProcessor", g.get(0)[3], "generating processor recorded");
        check(events(r, "S").size() == 1 && Integer.parseInt(events(r, "S").get(0)[1]) >= 2, "summary with rounds");
        check(!events(r, "B").isEmpty() && !events(r, "C").isEmpty(), "begin/call events (hang attribution + timing)");
        AptSupport.Summary sum = r.apt.finish(true);
        check(sum.complete && sum.generated == 1 && sum.crashes.isEmpty(), "summary parsed");
        eq(1, r.apt.generated().size(), "state holds generated file");
        eq("generated by HelloProcessor from demo.Foo", r.apt.generatedBy(new File(r.gen, "demo/Foo_Hello.java").getPath()), "error in generated code is attributed");

        // ---- errors: exact processor and source line
        r = run(j, new String[]{"bad"}, null, new HashMap<String, String>(), false, src("demo/Main.java", "package demo;\n\n@p.Hello public class Main { }\n"));
        check(!r.ok, "processor error fails the build");
        check(r.out.contains("Main.java (at line 3)") && r.out.contains("[BadProc] class must not be named Main"), "error names processor and points at the source line: " + r.out);
        check(r.out.contains("ERROR: [BadProc] global problem without element"), "message without element keeps the processor name");
        check(hasNote(r, "just a note"), "NOTE messages are logged");

        // ---- crash
        r = run(j, new String[]{"crash"}, null, new HashMap<String, String>(), false, two);
        check(!r.ok && r.out.contains("[CrashProc] annotation processor crashed in round 1 while processing demo.Foo"), "crash reported with processor + sources: " + r.out);
        sum = r.apt.finish(true);
        eq(1, sum.crashes.size(), "crash event");
        check(sum.crashes.get(0).trace.contains("q.CrashProc.process"), "stack trace trimmed to the processor");
        check(!r.out.contains("Internal compiler error"), "a processor crash is not a compiler crash");

        // ---- missing class in the processor class path
        r = run(j, new String[]{"missingdep"}, null, new HashMap<String, String>(), false, two);
        check(!r.ok && r.out.contains("class missing.Dep was not found") && r.out.contains("[NeedsDep]"), "missing processor dependency explained: " + r.out);

        Map<String, String> plain = src("demo/Foo.java", "package demo; @p.Hello public class Foo { }");
        // ---- claiming: A claims @Hello, B must not see it; "*" processor runs in every round, also with no annotations
        r = run(j, new String[]{"star", "claimab"}, null, new HashMap<String, String>(), false, plain);
        check(r.ok, "claim test builds: " + r.out);
        check(hasNote(r, "A claimed 1"), "processor A got the annotation");
        check(!hasNote(r, "B saw"), "claimed annotation is not passed to B (javac semantics)");
        check(hasNote(r, "star call 1 annos=1") && hasNote(r, "over=true"), "'*' processor runs in the first and the final round");
        int stars = 0;
        for (String[] e : events(r, "M")) if (e[4].startsWith("star call")) stars++;
        check(stars >= 2, "'*' processor called with and without annotations (" + stars + " calls)");

        // ---- options and source version
        Map<String, String> o = new HashMap<>(); o.put("my.option", "abc");
        r = run(j, new String[]{"opt"}, null, o, false, plain);
        check(hasNote(r, "my.option=abc") && hasNote(r, "source=RELEASE_8"), "-A options reach the processor, source version is Java 8");

        // ---- ECJ must not believe it runs on Java 9+ (it would use the jrt: file system, which Android does not have)
        check(javax.lang.model.SourceVersion.latest() == javax.lang.model.SourceVersion.RELEASE_8, "SourceVersion.latest() is RELEASE_8 (no jrt probing on Android)");
        org.eclipse.jdt.internal.compiler.tool.EclipseFileManager fm = new org.eclipse.jdt.internal.compiler.tool.EclipseFileManager(Locale.getDefault(), java.nio.charset.Charset.forName("UTF-8"));
        java.lang.reflect.Field jvm9 = org.eclipse.jdt.internal.compiler.tool.EclipseFileManager.class.getDeclaredField("isOnJvm9");
        jvm9.setAccessible(true);
        check(!jvm9.getBoolean(fm), "ECJ file manager runs in classpath mode (isOnJvm9 == false)");
        fm.close();

        // ---- no originating element -> blocks incremental processing
        r = run(j, new String[]{"noorigin"}, null, new HashMap<String, String>(), false, plain);
        check(r.ok && new File(r.gen, "demo/NoOrigin.java").isFile(), "processor without origin generates: " + r.out);
        r.apt.finish(true);
        check(r.apt.generated().get(0).unattributed(), "unattributed output detected");

        // ---- raw environment: processors get the compiler's own environment (no tracking)
        r = run(j, new String[]{"hello"}, null, new HashMap<String, String>(), true, two);
        check(r.ok && events(r, "G").isEmpty(), "raw mode: works, but generated files are not tracked");
    }

    // ------------------------------------------------------------------ state, stale cleanup, incremental decisions

    static void stateAndIncremental() throws Exception {
        File dir = new File(WORK, "state");
        AptConfig cfg = new AptConfig();
        AptSupport s = new AptSupport(cfg, new File(dir, "apt"));
        ProcessorClasspath.Result cp = new ProcessorClasspath.Result();
        s.bind(cp);
        check(s.incrementalBlocker() != null, "no processors -> no incremental");
        File classes = new File(dir, "classes");
        // fake outputs of an earlier run
        FileUtil.writeText(new File(s.gen, "demo/Foo_Hello.java"), "x");
        FileUtil.writeText(new File(s.gen, "demo/Bar_Hello.java"), "x");
        for (String n : new String[]{"Foo_Hello.class", "Foo_Hello$Inner.class", "Bar_Hello.class", "Foo.class"}) FileUtil.writeText(new File(classes, "demo/" + n), "c");
        FileUtil.writeText(new File(classes, "META-INF/services/x.Y"), "r");
        FileUtil.writeText(s.reportFile, "G\tS\tdemo.Foo_Hello\tp.HelloProcessor\tdemo.Foo\nG\tS\tdemo.Bar_Hello\tp.HelloProcessor\tdemo.Bar\n"
                + "G\tRC\tMETA-INF/services/x.Y\tp.HelloProcessor\tdemo.Foo\nS\t2\t3\t5\n");
        s.finish(true);
        s.saveState("FP1");
        s.loadState("FP2");
        check(s.generated().isEmpty() && s.incrementalBlocker() != null, "state written for another fingerprint is ignored");
        s.loadState("FP1");
        eq(3, s.generated().size(), "state round trip");

        ProcessorScanner.Found iso = foundOf("p.HelloProcessor", ProcessorScanner.Kind.ISOLATING);
        cp.found.add(iso);
        eq(null, s.incrementalBlocker(), "all isolating + valid state -> incremental");
        cp.found.add(foundOf("q.Agg", ProcessorScanner.Kind.AGGREGATING));
        check(s.incrementalBlocker().contains("aggregating"), "aggregating processor blocks incremental");
        cp.found.remove(1);
        cfg.incremental = false;
        check(s.incrementalBlocker().contains("switched off"), "setting can turn incremental off");
        cfg.incremental = true;

        int n = s.cleanStale(new HashSet<>(Arrays.asList("demo.Foo")), classes);
        eq(2, n, "only outputs of the dirty type are removed");
        check(!new File(s.gen, "demo/Foo_Hello.java").exists() && new File(s.gen, "demo/Bar_Hello.java").exists(), "stale source deleted, other kept");
        check(!new File(classes, "demo/Foo_Hello.class").exists() && !new File(classes, "demo/Foo_Hello$Inner.class").exists(), "stale classes (incl. inner) deleted");
        check(new File(classes, "demo/Bar_Hello.class").exists() && new File(classes, "demo/Foo.class").exists(), "other classes untouched");
        check(!new File(classes, "META-INF/services/x.Y").exists(), "generated resource deleted");

        s.generated().add(new AptSupport.Gen("S", "demo.NoOrigin", "q.P"));
        check(s.incrementalBlocker().contains("without an originating element"), "unattributed output forces a full run");
        s.discardAfterFailure();
        check(!s.gen.exists() || s.gen.list().length == 0, "failure drops generated sources");
        check(s.generated().isEmpty(), "failure drops state");
        s.resetOutputs();
        check(s.gen.isDirectory(), "reset recreates the gen dir");

        // host.properties + args
        cp.loadJars.add(new File("/x/a.jar"));
        cfg.options.put("a.b", "c d");
        cfg.options.put("bad key", "x");
        List<String> args = s.compilerArgs(false);
        check(args.contains("-processor") && args.contains(AptSupport.HOST_CLASS) && args.contains("-Aa.b=c d"), "compiler args");
        check(!args.contains("-Abad key=x"), "invalid option keys are dropped");
        String hp = FileUtil.readText(s.hostFile);
        check(hp.contains("jar.0=/x/a.jar") && hp.contains("proc.0=p.HelloProcessor|ISOLATING"), "host.properties lists jars and processors");
    }

    static ProcessorScanner.Found foundOf(String cls, ProcessorScanner.Kind k) throws Exception {
        java.lang.reflect.Constructor<ProcessorScanner.Found> c = ProcessorScanner.Found.class.getDeclaredConstructor(String.class, ProcessorScanner.Kind.class, File.class);
        c.setAccessible(true);
        return c.newInstance(cls, k, new File("x.jar"));
    }

    // ------------------------------------------------------------------ resolution + dexing (needs the offline fixture)

    static void resolutionAndDex() throws Exception {
        File m2 = new File(System.getenv("M2"));
        ProcessorClasspath.Log log = new ProcessorClasspath.Log() { public void log(String s) { } };
        AptConfig cfg = new AptConfig();
        cfg.add("com.google.auto.value:auto-value:1.8.2");
        cfg.add("com.google.auto.service:auto-service:1.0.1");
        ProcessorClasspath.Result r = ProcessorClasspath.prepare(cfg.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null);
        eq(8, r.jars.size(), "transitive processor dependencies resolved from the offline cache");
        boolean guava32 = false;
        for (File f : r.jars) if (f.getName().equals("guava-32.0.1-jre.jar")) guava32 = true;
        check(guava32, "version conflict: highest guava wins");
        Map<String, ProcessorScanner.Kind> kinds = new HashMap<>();
        for (ProcessorScanner.Found f : r.found) kinds.put(f.className, f.kind);
        eq(8, kinds.size(), "8 processors discovered (7 AutoValue + AutoService)");
        eq(ProcessorScanner.Kind.AGGREGATING, kinds.get("com.google.auto.service.processor.AutoServiceProcessor"), "AutoService declared aggregating");
        eq(ProcessorScanner.Kind.UNKNOWN, kinds.get("com.google.auto.value.processor.AutoValueProcessor"), "AutoValue 1.8.2 declares nothing -> unknown");
        check(!r.fingerprint.isEmpty(), "fingerprint");

        // Maven managed scope: a dependency without scope whose dependencyManagement says "test" is not resolved
        AptConfig cm = new AptConfig();
        cm.add("com.example:mgd:1.0");
        r = ProcessorClasspath.prepare(cm.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null);
        eq(1, r.jars.size(), "test-scoped managed dependency is not pulled into the processor class path");

        // restricted to explicit classes
        AptConfig c2 = new AptConfig();
        AptConfig.Entry e = c2.add("com.google.auto.value:auto-value:1.8.2");
        e.classes.add("com.google.auto.value.processor.AutoValueProcessor");
        r = ProcessorClasspath.prepare(c2.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null);
        eq(1, r.found.size(), "explicit processor class list restricts discovery");
        e.classes.set(0, "no.Such");
        try { ProcessorClasspath.prepare(c2.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null); check(false, "unknown class must fail"); }
        catch (IOException ex) { check(ex.getMessage().contains("no.Such"), "unknown explicit class is an error"); }

        // offline + missing artifact -> actionable message
        AptConfig c3 = new AptConfig();
        c3.add("com.example:not-cached:1.0");
        try { ProcessorClasspath.prepare(c3.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null); check(false, "missing artifact must fail"); }
        catch (IOException ex) { check(ex.getMessage().contains("Offline") || ex.getMessage().contains("offline"), "offline miss explained: " + ex.getMessage()); }
        AptConfig c4 = new AptConfig();
        c4.add("procs/missing.jar");
        try { ProcessorClasspath.prepare(c4.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), false, true, 26, log, null); check(false, "missing local jar must fail"); }
        catch (IOException ex) { check(ex.getMessage().contains("not found"), "missing local jar explained"); }

        // D8: every real processor jar converts for Android; resources survive (META-INF/services)
        ProcessorClasspath.Result d = ProcessorClasspath.prepare(cfg.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), true, true, 26, log, null);
        eq(8, d.loadJars.size(), "all processor jars dexed");
        int withDex = 0, withServices = 0;
        for (File f : d.loadJars) {
            check(!f.canWrite() || System.getProperty("user.name").equals("root"), "dexed zip is read-only (Android 14+ requirement)");
            try (ZipFile z = new ZipFile(f)) {
                if (z.getEntry("classes.dex") != null) withDex++;
                if (z.getEntry(ProcessorScanner.SERVICE) != null) withServices++;
            }
        }
        eq(8, withDex, "each dexed zip has classes.dex");
        eq(2, withServices, "service files are kept next to the dex");
        long t = System.currentTimeMillis();
        ProcessorClasspath.prepare(cfg.processors, WORK, m2, new File(TC, "android.jar"), new File(WORK, "dex"), true, true, 26, log, null);
        check(System.currentTimeMillis() - t < 3000, "dex cache hit is fast");
    }
}
