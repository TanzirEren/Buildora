package com.tenix.buildora.apt;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-side half of annotation processing for one variant: the generated-source directory, the
 * persisted generation state (which file came from which source type), the ECJ arguments, stale-output
 * cleanup for incremental builds, and the human-readable summary of what the processors did.
 * Pure Java (JVM-testable); the build engine drives it.
 *
 * Layout under {@code <variant cache>/apt/}: gen/ (generated sources) · state.txt · host.properties · report.txt · empty/
 */
public final class AptSupport {

    public static final String HOST_CLASS = "com.tenix.buildora.apt.ProcessorHost";

    /** One generated output. */
    public static final class Gen {
        public final String kind, name, proc;      // kind: S source, C class, RS/RC resource (source/class output)
        public final List<String> origins = new ArrayList<>();
        public Gen(String kind, String name, String proc) { this.kind = kind; this.name = name; this.proc = proc; }
        public boolean unattributed() { return origins.isEmpty(); }
    }

    public static final class Crash {
        public String processor, phase, message, trace;
    }

    /** Result of one processing run. */
    public static final class Summary {
        public final List<String> lines = new ArrayList<>();
        public final List<Crash> crashes = new ArrayList<>();
        public int rounds, generated;
        public long millis;
        public boolean complete;     // the final summary event was written (the host ran to its end)
        /** Processor that had started a call that never finished (hang, kill, out of memory); null if none. */
        public String running;
        public int runningRound;
    }

    private final AptConfig cfg;
    public final File dir, gen, stateFile, hostFile, reportFile, emptyDir;
    private ProcessorClasspath.Result cp;
    private final List<Gen> state = new ArrayList<>();
    private boolean stateValid;

    public AptSupport(AptConfig cfg, File dir) {
        this.cfg = cfg;
        this.dir = dir;
        this.gen = new File(dir, "gen");
        this.stateFile = new File(dir, "state.txt");
        this.hostFile = new File(dir, "host.properties");
        this.reportFile = new File(dir, "report.txt");
        this.emptyDir = new File(dir, "empty");
    }

    public void bind(ProcessorClasspath.Result r) { this.cp = r; }

    public ProcessorClasspath.Result classpath() { return cp; }

    /** Everything that, when changed, invalidates generated code. */
    public String fingerprint(String variant, String buildType) {
        return cfg.fingerprint(variant, buildType) + "|" + (cp == null ? "" : cp.fingerprint);
    }

    public boolean allIsolating() {
        if (cp == null || cp.found.isEmpty()) return false;
        for (ProcessorScanner.Found f : cp.found) if (f.kind != ProcessorScanner.Kind.ISOLATING) return false;
        return true;
    }

    /** Why incremental processing is not possible (null = it is possible). */
    public String incrementalBlocker() {
        if (!cfg.incremental) return "incremental processing is switched off";
        if (cfg.rawEnvironment) return "raw environment mode does not track generated files";
        if (cp == null || cp.found.isEmpty()) return "no processors found";
        for (ProcessorScanner.Found f : cp.found)
            if (f.kind != ProcessorScanner.Kind.ISOLATING) return f.simpleName() + " is " + f.kind.label() + " (not declared isolating)";
        if (!stateValid) return "no valid generation state yet";
        for (Gen g : state) if (g.unattributed()) return g.proc.substring(g.proc.lastIndexOf('.') + 1) + " generated " + g.name + " without an originating element";
        return null;
    }

    // ------------------------------------------------------------------ state

    /** Loads state.txt; it is valid only when it was written for the same fingerprint. */
    public void loadState(String fp) {
        state.clear();
        stateValid = false;
        try {
            if (!stateFile.isFile()) return;
            String[] lines = FileUtil.readText(stateFile).split("\n");
            if (lines.length == 0 || !lines[0].equals("fp=" + fp)) return;
            for (int i = 1; i < lines.length; i++) {
                String[] a = lines[i].split("\t", -1);
                if (a.length < 4) continue;
                Gen g = new Gen(a[0], a[1], a[2]);
                for (String o : a[3].split(",")) if (!o.isEmpty() && !o.equals("*")) g.origins.add(o);
                state.add(g);
            }
            stateValid = true;
        } catch (IOException ignored) { }
    }

    public void saveState(String fp) throws IOException {
        StringBuilder sb = new StringBuilder("fp=").append(fp).append('\n');
        for (Gen g : state) {
            sb.append(g.kind).append('\t').append(g.name).append('\t').append(g.proc).append('\t');
            if (g.origins.isEmpty()) sb.append('*');
            for (int i = 0; i < g.origins.size(); i++) { if (i > 0) sb.append(','); sb.append(g.origins.get(i)); }
            sb.append('\n');
        }
        FileUtil.writeText(stateFile, sb.toString());
    }

    public List<Gen> generated() { return state; }

    /** After a failed run: generated sources and state are dropped (the report is kept for the log). */
    public void discardAfterFailure() {
        FileUtil.deleteRecursive(gen);
        stateFile.delete();
        state.clear();
        stateValid = false;
    }

    /** Deletes generated sources, state, report: the next run is a full one. */
    public void resetOutputs() {
        FileUtil.deleteRecursive(gen);
        stateFile.delete();
        reportFile.delete();
        state.clear();
        stateValid = false;
        gen.mkdirs();
    }

    /**
     * Incremental: removes everything that was generated from a dirty source type (generated source,
     * its class files, generated resources) so the processors can recreate it.
     * @param dirtyTypes qualified names of the top-level types declared in recompiled files
     * @return number of generated outputs removed
     */
    public int cleanStale(Set<String> dirtyTypes, File classesDir) {
        int n = 0;
        List<Gen> keep = new ArrayList<>();
        for (Gen g : state) {
            boolean stale = false;
            for (String o : g.origins) if (dirtyTypes.contains(o)) { stale = true; break; }
            if (!stale) { keep.add(g); continue; }
            n++;
            String path = g.name.replace('.', '/');
            if (g.kind.equals("S")) { new File(gen, path + ".java").delete(); deleteClasses(classesDir, path); }
            else if (g.kind.equals("C")) deleteClasses(classesDir, path);
            else if (g.kind.equals("RS")) new File(gen, g.name).delete();
            else if (g.kind.equals("RC")) new File(classesDir, g.name).delete();
        }
        state.clear();
        state.addAll(keep);
        return n;
    }

    private static void deleteClasses(File classesDir, String path) {
        File f = new File(classesDir, path + ".class");
        f.delete();
        File parent = f.getParentFile();
        String base = f.getName().substring(0, f.getName().length() - 6) + "$";
        File[] kids = parent == null ? null : parent.listFiles();
        if (kids != null) for (File k : kids) if (k.getName().startsWith(base) && k.getName().endsWith(".class")) k.delete();
    }

    // ------------------------------------------------------------------ compiler arguments

    /** Writes host.properties and returns the ECJ arguments that turn annotation processing on. */
    public List<String> compilerArgs(boolean incremental) throws IOException {
        dir.mkdirs();
        gen.mkdirs();
        emptyDir.mkdirs();
        reportFile.delete();                       // one report per run (the host appends to it)
        StringBuilder sb = new StringBuilder();
        sb.append("report=").append(esc(reportFile.getPath())).append('\n');
        sb.append("raw=").append(cfg.rawEnvironment).append('\n');
        for (int i = 0; i < cp.loadJars.size(); i++) sb.append("jar.").append(i).append('=').append(esc(cp.loadJars.get(i).getPath())).append('\n');
        for (int i = 0; i < cp.found.size(); i++) {
            ProcessorScanner.Found f = cp.found.get(i);
            sb.append("proc.").append(i).append('=').append(f.className).append('|').append(f.kind.name()).append('\n');
        }
        FileUtil.writeText(hostFile, sb.toString());
        List<String> a = new ArrayList<>();
        a.add("-processor"); a.add(HOST_CLASS);
        a.add("-processorpath"); a.add(emptyDir.getPath());
        a.add("-s"); a.add(gen.getPath());
        a.add("-A" + ProcessorHost.OPT_CONFIG + "=" + hostFile.getPath());
        for (Map.Entry<String, String> o : cfg.options.entrySet()) {
            if (!o.getKey().matches("[\\w.\\-]+")) continue;
            a.add("-A" + o.getKey() + "=" + o.getValue());
        }
        return a;
    }

    private static String esc(String path) { return path.replace("\\", "\\\\"); }

    // ------------------------------------------------------------------ after the run

    /**
     * Reads the report, merges the generated files into the state (full: replaces it) and builds the log text.
     * @param full true when this run started from scratch
     */
    public Summary finish(boolean full) {
        Summary s = new Summary();
        List<String[]> ev = AptReport.read(reportFile);
        if (full) state.clear();
        List<String> genLines = new ArrayList<>();
        List<String> totals = new ArrayList<>();
        for (String[] e : ev) {
            String tag = e[0];
            if (tag.equals("B") && e.length >= 3) { s.running = e[2]; s.runningRound = (int) parse(e[1]); }
            else if (tag.equals("C")) s.running = null;
            if (tag.equals("L") && e.length >= 4) {
                s.lines.add("  \u2713 " + simple(e[1]) + "  [" + e[2] + "]  " + e[1]);
            } else if (tag.equals("C") && e.length >= 6) {
                boolean show = !e[3].isEmpty() || !e[5].equals("0") || parse(e[4]) >= 100;
                if (show) s.lines.add("  round " + e[1] + "  " + simple(e[2]) + (e[3].isEmpty() ? "" : "  @" + e[3].replace(",", " @"))
                        + "  " + e[4] + " ms" + (e[5].equals("0") ? "" : "  \u2192 " + e[5] + " file" + (e[5].equals("1") ? "" : "s")));
            } else if (tag.equals("G") && e.length >= 5) {
                Gen g = new Gen(e[1], e[2], e[3]);
                for (String o : e[4].split(",")) if (!o.isEmpty() && !o.equals("*")) g.origins.add(o);
                state.add(g);
                s.generated++;
                genLines.add("  + " + describeGen(g) + "  \u2190 " + (g.origins.isEmpty() ? "(no originating element)" : join(g.origins))
                        + "  (" + simple(g.proc) + ")");
            } else if (tag.equals("M") && e.length >= 5) {
                String k = e[1];
                if (k.equals("NOTE") || k.equals("OTHER"))
                    s.lines.add("  note [" + simple(e[2]) + "] " + e[4]);
            } else if (tag.equals("X") && e.length >= 5) {
                Crash c = new Crash();
                c.processor = e[1]; c.phase = e[2]; c.message = e[3]; c.trace = e[4];
                s.crashes.add(c);
            } else if (tag.equals("T") && e.length >= 6) {
                totals.add(simple(e[1]) + " " + e[3] + " ms");
            } else if (tag.equals("S") && e.length >= 4) {
                s.rounds = (int) parse(e[1]);
                s.millis = parse(e[3]);
                s.complete = true;
            }
        }
        int max = 40;
        for (int i = 0; i < genLines.size() && i < max; i++) s.lines.add(genLines.get(i));
        if (genLines.size() > max) s.lines.add("  ... and " + (genLines.size() - max) + " more generated files");
        if (s.complete) {
            s.lines.add("  done: " + s.rounds + " round" + (s.rounds == 1 ? "" : "s") + ", " + s.generated + " file"
                    + (s.generated == 1 ? "" : "s") + " generated, " + s.millis + " ms"
                    + (totals.isEmpty() ? "" : "   (" + join(totals) + ")"));
        }
        for (Crash c : s.crashes) {
            s.lines.add("  \u2717 " + simple(c.processor) + " " + c.phase + ": " + c.message);
            if (!c.trace.isEmpty()) for (String t : c.trace.split("\n")) s.lines.add("      " + t);
        }
        return s;
    }

    private static String describeGen(Gen g) {
        switch (g.kind) {
            case "S": return g.name.replace('.', '/') + ".java";
            case "C": return g.name.replace('.', '/') + ".class";
            default: return g.name;
        }
    }

    private static String simple(String cls) { return cls.substring(cls.lastIndexOf('.') + 1); }

    private static long parse(String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; } }

    private static String join(Iterable<String> it) {
        StringBuilder sb = new StringBuilder();
        for (String s : it) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    /**
     * If the file is a generated source: "generated by AutoValueProcessor from com.example.Person", else null.
     * Lets an error inside generated code point at the processor and the user source that caused it.
     */
    public String generatedBy(String filePath) {
        if (filePath == null) return null;
        String g = gen.getPath().replace('\\', '/') + "/";
        String f = filePath.replace('\\', '/');
        if (!f.startsWith(g) || !f.endsWith(".java")) return null;
        String name = f.substring(g.length(), f.length() - 5).replace('/', '.');
        for (Gen x : state) {
            if (x.kind.equals("S") && x.name.equals(name)) {
                return "generated by " + simple(x.proc) + (x.origins.isEmpty() ? "" : " from " + join(x.origins));
            }
        }
        return "generated by an annotation processor";
    }

    /** All generated .java files (for listing in the UI). */
    public List<File> generatedSources() { return FileUtil.listFiles(gen, ".java"); }

    public Set<String> classNames() {
        Set<String> out = new LinkedHashSet<>();
        for (ProcessorScanner.Found f : cp.found) out.add(f.className);
        return out;
    }
}
