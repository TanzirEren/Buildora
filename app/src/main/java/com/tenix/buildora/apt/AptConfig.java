package com.tenix.buildora.apt;

import com.tenix.buildora.core.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Annotation-processing settings of a project (the "annotationProcessing" object of buildora.json).
 * Pure Java: no Android dependency, so it can be unit-tested on a JVM.
 *
 * Gradle interop: {@link #parseGradle(String)} reads {@code annotationProcessor 'g:a:v'},
 * {@code annotationProcessorOptions { arguments = [...] }} and reports {@code kapt}/{@code ksp} lines as
 * unsupported instead of silently pretending to support them. {@link #toGradleLines()} writes the
 * enabled processors back in Gradle syntax.
 */
public final class AptConfig {

    /** One processor dependency: a Maven coordinate (group:artifact[:version]) or a local .jar path. */
    public static final class Entry {
        public String coord = "";
        public boolean enabled = true;
        /** Optional: build types / variant names this processor applies to (empty = all). */
        public final List<String> variants = new ArrayList<>();
        /** Optional explicit processor class names; empty = discover through META-INF/services. */
        public final List<String> classes = new ArrayList<>();

        public Entry() { }
        public Entry(String coord) { this.coord = coord; }

        public boolean isLocal() {
            String l = coord.toLowerCase();
            return l.endsWith(".jar") || l.contains("/") || l.contains("\\");
        }

        public boolean appliesTo(String variantName, String buildType) {
            if (variants.isEmpty()) return true;
            for (String v : variants) if (v.equals(variantName) || v.equals(buildType)) return true;
            return false;
        }

        public String artifactName() {
            if (isLocal()) return coord.substring(Math.max(coord.lastIndexOf('/'), coord.lastIndexOf('\\')) + 1);
            String[] p = coord.split(":");
            return p.length >= 2 ? p[1] : coord;
        }
    }

    public boolean enabled = true;
    public int timeoutSec = 600;
    /** Run annotation processing in a separate process (crash/memory isolation). */
    public boolean isolate = true;
    /** Allow incremental processing when every processor is declared isolating. */
    public boolean incremental = true;
    /** Pass the unwrapped ProcessingEnvironment to processors (disables attribution and origin tracking). */
    public boolean rawEnvironment = false;
    public final List<Entry> processors = new ArrayList<>();
    public final Map<String, String> options = new LinkedHashMap<>();
    /** Lines from an imported Gradle file that Buildora cannot honour (kapt, ksp, project(':x')...). */
    public final List<String> unsupported = new ArrayList<>();

    public boolean isEmpty() { return processors.isEmpty(); }

    public Entry find(String coord) {
        for (Entry e : processors) if (e.coord.equals(coord)) return e;
        return null;
    }

    /** Adds a processor (replacing a different version of the same group:artifact). */
    public Entry add(String coord) {
        coord = coord.trim();
        String ga = ga(coord);
        for (int i = 0; i < processors.size(); i++) {
            Entry e = processors.get(i);
            if (ga(e.coord).equals(ga)) { e.coord = coord; return e; }
        }
        Entry e = new Entry(coord);
        processors.add(e);
        return e;
    }

    public boolean remove(String coord) {
        for (int i = 0; i < processors.size(); i++) if (processors.get(i).coord.equals(coord)) { processors.remove(i); return true; }
        return false;
    }

    private static String ga(String coord) {
        String[] p = coord.split(":");
        return p.length >= 2 ? p[0] + ":" + p[1] : coord;
    }

    /** Active entries for a variant. */
    public List<Entry> active(String variantName, String buildType) {
        List<Entry> out = new ArrayList<>();
        if (!enabled) return out;
        for (Entry e : processors) if (e.enabled && e.appliesTo(variantName, buildType)) out.add(e);
        return out;
    }

    /** Stable text describing everything that influences processing (used in build fingerprints). */
    public String fingerprint(String variantName, String buildType) {
        StringBuilder sb = new StringBuilder("apt|").append(enabled).append('|').append(rawEnvironment).append('|');
        for (Entry e : active(variantName, buildType)) {
            sb.append(e.coord).append(e.classes).append(';');
        }
        sb.append('|').append(options);
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"enabled\":").append(enabled)
          .append(",\"timeoutSec\":").append(timeoutSec)
          .append(",\"isolate\":").append(isolate)
          .append(",\"incremental\":").append(incremental)
          .append(",\"rawEnvironment\":").append(rawEnvironment)
          .append(",\"processors\":[");
        for (int i = 0; i < processors.size(); i++) {
            Entry e = processors.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"coord\":").append(q(e.coord)).append(",\"enabled\":").append(e.enabled);
            if (!e.variants.isEmpty()) sb.append(",\"variants\":").append(arr(e.variants));
            if (!e.classes.isEmpty()) sb.append(",\"classes\":").append(arr(e.classes));
            sb.append('}');
        }
        sb.append("],\"options\":{");
        boolean first = true;
        for (Map.Entry<String, String> o : options.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(q(o.getKey())).append(':').append(q(o.getValue()));
        }
        sb.append("}");
        if (!unsupported.isEmpty()) sb.append(",\"unsupported\":").append(arr(unsupported));
        sb.append("}");
        return sb.toString();
    }

    private static String arr(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); sb.append(q(l.get(i))); }
        return sb.append(']').toString();
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Lenient: unknown/missing fields keep their defaults, so old projects (no object) load unchanged. */
    public static AptConfig fromJson(String json) {
        AptConfig c = new AptConfig();
        if (json == null || json.trim().isEmpty()) return c;
        try {
            Map<String, Object> o = MiniJson.object(MiniJson.parse(json));
            if (o == null) return c;
            if (o.get("enabled") instanceof Boolean) c.enabled = (Boolean) o.get("enabled");
            if (o.get("timeoutSec") instanceof Number) c.timeoutSec = Math.max(30, ((Number) o.get("timeoutSec")).intValue());
            if (o.get("isolate") instanceof Boolean) c.isolate = (Boolean) o.get("isolate");
            if (o.get("incremental") instanceof Boolean) c.incremental = (Boolean) o.get("incremental");
            if (o.get("rawEnvironment") instanceof Boolean) c.rawEnvironment = (Boolean) o.get("rawEnvironment");
            List<Object> ps = MiniJson.array(o.get("processors"));
            if (ps != null) for (Object x : ps) {
                Map<String, Object> m = MiniJson.object(x);
                if (m == null || MiniJson.str(m.get("coord")) == null) continue;
                Entry e = new Entry(MiniJson.str(m.get("coord")).trim());
                if (m.get("enabled") instanceof Boolean) e.enabled = (Boolean) m.get("enabled");
                List<Object> vs = MiniJson.array(m.get("variants"));
                if (vs != null) for (Object v : vs) if (v instanceof String) e.variants.add((String) v);
                List<Object> cs = MiniJson.array(m.get("classes"));
                if (cs != null) for (Object v : cs) if (v instanceof String) e.classes.add((String) v);
                if (!e.coord.isEmpty()) c.processors.add(e);
            }
            List<Object> un = MiniJson.array(o.get("unsupported"));
            if (un != null) for (Object v : un) if (v instanceof String) c.unsupported.add((String) v);
            Map<String, Object> opts = MiniJson.object(o.get("options"));
            if (opts != null) for (Map.Entry<String, Object> e : opts.entrySet()) if (e.getValue() instanceof String) c.options.put(e.getKey(), (String) e.getValue());
        } catch (RuntimeException ignored) { }
        return c;
    }

    // ------------------------------------------------------------------ Gradle interop

    private static final Pattern AP_COORD = Pattern.compile(
            "(?m)^[ \\t]*(?:annotationProcessor|testAnnotationProcessor|androidTestAnnotationProcessor)[ \\t]*\\(?[ \\t]*[\"']([^\"'\\s]+)[\"'][ \\t]*\\)?");
    private static final Pattern AP_FILES = Pattern.compile(
            "(?m)^[ \\t]*annotationProcessor[ \\t]*\\(?[ \\t]*files[ \\t]*\\([ \\t]*[\"']([^\"']+)[\"']");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "(?m)^[ \\t]*(kapt|ksp|kaptTest|kaptAndroidTest)[ \\t]*\\(?[ \\t]*[\"']([^\"'\\s]+)[\"']");
    private static final Pattern AP_PROJECT = Pattern.compile("(?m)^[ \\t]*annotationProcessor[ \\t]*\\(?[ \\t]*project[ \\t]*\\(([^)]*)\\)");
    private static final Pattern ARGS_BLOCK = Pattern.compile("arguments[ \\t]*(?:\\+?=|\\()[ \\t]*\\[([^\\]]*)\\]");
    private static final Pattern ARG_PAIR = Pattern.compile("[\"']?([\\w.\\-]+)[\"']?[ \\t]*:[ \\t]*[\"']([^\"']*)[\"']");
    private static final Pattern ARG_CALL = Pattern.compile("(?m)^[ \\t]*argument[ \\t]*\\(?[ \\t]*[\"']([^\"']+)[\"'][ \\t]*,[ \\t]*[\"']([^\"']*)[\"']");

    /** Reads annotation-processor related lines of a Gradle build file into this config (additive). */
    public void parseGradle(String gradle) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = AP_COORD.matcher(gradle);
        while (m.find()) {
            String line = gradle.substring(m.start(), m.end()).trim();
            if (line.startsWith("test") || line.startsWith("androidTest")) continue;
            String c = m.group(1);
            if (c.startsWith("files(")) continue;
            if (c.split(":").length >= 2 && seen.add(c)) { if (find(c) == null) add(c); }
        }
        m = AP_FILES.matcher(gradle);
        while (m.find()) if (find(m.group(1)) == null) processors.add(new Entry(m.group(1)));
        m = UNSUPPORTED.matcher(gradle);
        while (m.find()) {
            String note = m.group(1) + " " + m.group(2) + "  (needs the Kotlin compiler / KSP, which Buildora does not have)";
            if (!unsupported.contains(note)) unsupported.add(note);
        }
        m = AP_PROJECT.matcher(gradle);
        while (m.find()) {
            String note = "annotationProcessor project(" + m.group(1).trim() + ")  (processor modules are not built on the device; add its .jar)";
            if (!unsupported.contains(note)) unsupported.add(note);
        }
        m = ARGS_BLOCK.matcher(gradle);
        while (m.find()) {
            Matcher kv = ARG_PAIR.matcher(m.group(1));
            while (kv.find()) options.put(kv.group(1), kv.group(2));
        }
        m = ARG_CALL.matcher(gradle);
        while (m.find()) options.put(m.group(1), m.group(2));
    }

    /** Enabled processors in Gradle syntax (for the Gradle-compatible build files of the project). */
    public String toGradleLines() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : processors) {
            if (!e.enabled) continue;
            if (e.isLocal()) sb.append("    annotationProcessor files('").append(e.coord).append("')\n");
            else sb.append("    annotationProcessor '").append(e.coord).append("'\n");
        }
        return sb.toString();
    }

    public String toGradleOptions() {
        if (options.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("        javaCompileOptions {\n            annotationProcessorOptions {\n                arguments = [");
        boolean first = true;
        for (Map.Entry<String, String> o : options.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append('\'').append(o.getKey()).append("': '").append(o.getValue().replace("'", "\\'")).append('\'');
        }
        return sb.append("]\n            }\n        }\n").toString();
    }
}
