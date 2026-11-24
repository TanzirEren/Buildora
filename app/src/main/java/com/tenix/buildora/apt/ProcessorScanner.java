package com.tenix.buildora.apt;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds annotation processors in jars: {@code META-INF/services/javax.annotation.processing.Processor}
 * plus Gradle's {@code META-INF/gradle/incremental.annotation.processors} (isolating / aggregating).
 */
public final class ProcessorScanner {

    public static final String SERVICE = "META-INF/services/javax.annotation.processing.Processor";
    public static final String INCREMENTAL = "META-INF/gradle/incremental.annotation.processors";

    public enum Kind {
        ISOLATING, AGGREGATING, DYNAMIC, UNKNOWN;
        public String label() { return name().toLowerCase(); }
    }

    public static final class Found {
        public final String className;
        public final Kind kind;
        public final File jar;
        Found(String c, Kind k, File j) { className = c; kind = k; jar = j; }
        public String simpleName() { return className.substring(className.lastIndexOf('.') + 1); }
    }

    private ProcessorScanner() { }

    public static List<Found> scan(List<File> jars) throws IOException {
        List<Found> out = new ArrayList<>();
        for (File jar : jars) {
            if (!jar.isFile()) continue;
            try (ZipFile z = new ZipFile(jar)) {
                ZipEntry svc = z.getEntry(SERVICE);
                if (svc == null) continue;
                Map<String, Kind> kinds = new HashMap<>();
                ZipEntry inc = z.getEntry(INCREMENTAL);
                if (inc != null) kinds = parseKinds(read(z, inc));
                for (String cls : parseServices(read(z, svc))) {
                    Kind k = kinds.get(cls);
                    out.add(new Found(cls, k == null ? Kind.UNKNOWN : k, jar));
                }
            } catch (java.util.zip.ZipException e) {
                throw new IOException(jar.getName() + " is not a valid jar: " + e.getMessage());
            }
        }
        return out;
    }

    public static List<String> parseServices(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            int h = line.indexOf('#');
            if (h >= 0) line = line.substring(0, h);
            line = line.trim();
            if (!line.isEmpty() && !out.contains(line)) out.add(line);
        }
        return out;
    }

    /** Lines: {@code com.example.Proc,isolating}. */
    public static Map<String, Kind> parseKinds(String text) {
        Map<String, Kind> out = new HashMap<>();
        for (String line : text.split("\r?\n")) {
            int h = line.indexOf('#');
            if (h >= 0) line = line.substring(0, h);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split(",");
            if (p.length < 2) continue;
            String t = p[1].trim().toLowerCase();
            Kind k = t.equals("isolating") ? Kind.ISOLATING : t.equals("aggregating") ? Kind.AGGREGATING
                    : t.equals("dynamic") ? Kind.DYNAMIC : Kind.UNKNOWN;
            out.put(p[0].trim(), k);
        }
        return out;
    }

    private static String read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }
}
