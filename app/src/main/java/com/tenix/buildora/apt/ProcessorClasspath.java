package com.tenix.buildora.apt;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.tenix.buildora.build.MavenResolver;
import com.tenix.buildora.core.FileUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the annotation processor class path: resolves processor coordinates (with transitive
 * dependencies) from Google Maven / Maven Central / JitPack or the offline cache, accepts local jars,
 * discovers the processors, and (on Android) dexes every jar once so ART can load it.
 */
public final class ProcessorClasspath {

    public interface Log { void log(String s); }

    public static final class Result {
        /** Original jars: the processor path as Gradle would see it. */
        public final List<File> jars = new ArrayList<>();
        /** What the processor class loader loads (dexed zips on Android, the jars themselves on a JVM). */
        public final List<File> loadJars = new ArrayList<>();
        public final List<ProcessorScanner.Found> found = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String fingerprint = "";
    }

    private ProcessorClasspath() { }

    /**
     * @param dex      dex every jar (Android) instead of loading the jars directly (JVM)
     * @param dexCache where dexed zips are cached (shared between projects)
     */
    public static Result prepare(List<AptConfig.Entry> entries, File projectRoot, File m2, File androidJar, File dexCache,
                                 boolean dex, boolean offline, int minApi, final Log log,
                                 MavenResolver.Progress progress) throws IOException {
        Result r = new Result();
        List<String> coords = new ArrayList<>();
        Set<File> jars = new LinkedHashSet<>();
        List<File> own = new ArrayList<>();          // per entry: its own artifact (for explicit class lists)
        List<String> ownCoord = new ArrayList<>();
        for (AptConfig.Entry e : entries) {
            if (e.isLocal()) {
                File f = new File(e.coord);
                if (!f.isAbsolute()) f = new File(projectRoot, e.coord);
                if (!f.isFile()) throw new IOException("Annotation processor jar not found: " + e.coord
                        + "\nFix the path in Annotation processors, or remove the entry.");
                jars.add(f);
                own.add(f);
                ownCoord.add(e.coord);
                log.log("  + local processor jar " + f.getName());
            } else {
                coords.add(e.coord);
                own.add(null);
                ownCoord.add(e.coord);
            }
        }
        java.util.Map<String, File> byGa = new java.util.HashMap<>();
        if (!coords.isEmpty()) {
            MavenResolver mr = new MavenResolver(m2, new MavenResolver.Log() {
                @Override public void log(String s) { log.log(s); }
            });
            mr.setOffline(offline);
            if (progress != null) mr.setProgress(progress);
            log.log("Resolving " + coords.size() + " annotation processor" + (coords.size() == 1 ? "" : "s")
                    + (offline ? " (offline: using the local cache only)" : "") + "...");
            List<MavenResolver.Artifact> arts;
            try {
                arts = mr.resolve(coords);
            } catch (IOException ex) {
                throw new IOException("Annotation processor resolution failed: " + ex.getMessage()
                        + (offline ? "\nOffline mode is on and the artifact is not in the cache. Turn offline mode off once to download it."
                        : "\nCheck your internet connection and the coordinate (group:artifact:version)."));
            }
            for (MavenResolver.Artifact a : arts) {
                File f = a.file;
                if (a.aar) f = new File(a.extracted(), "classes.jar");
                if (f == null || !f.isFile()) continue;
                jars.add(f);
                byGa.put(a.group + ":" + a.artifact, f);
            }
            for (String s : mr.skipped) r.warnings.add("processor dependency not found: " + s);
            // the requested coordinates must really be present
            for (String c : coords) {
                String[] p = c.split(":");
                if (!byGa.containsKey(p[0] + ":" + p[1]))
                    throw new IOException("Annotation processor " + c + " has no jar" + (mr.skipped.isEmpty() ? "" : " (" + mr.skipped.get(0) + ")")
                            + (offline ? "\nOffline mode: not in the cache." : ""));
            }
        }
        for (int i = 0; i < own.size(); i++) {
            if (own.get(i) == null) {
                String[] p = ownCoord.get(i).split(":");
                own.set(i, byGa.get(p[0] + ":" + p[1]));
            }
        }
        r.jars.addAll(jars);

        // ---- discovery (all jars of the processor path, like javac / Gradle)
        List<ProcessorScanner.Found> all = ProcessorScanner.scan(r.jars);
        for (int i = 0; i < entries.size(); i++) {
            AptConfig.Entry e = entries.get(i);
            if (e.classes.isEmpty() || own.get(i) == null) continue;
            File ownJar = own.get(i);
            List<ProcessorScanner.Found> keep = new ArrayList<>();
            for (ProcessorScanner.Found f : all) {
                if (f.jar.equals(ownJar) && !e.classes.contains(f.className)) continue;
                keep.add(f);
            }
            for (String c : e.classes) {
                boolean ok = false;
                for (ProcessorScanner.Found f : keep) if (f.className.equals(c)) ok = true;
                if (!ok) throw new IOException("Processor class " + c + " is not declared in " + ownJar.getName());
            }
            all = keep;
        }
        r.found.addAll(all);

        StringBuilder fp = new StringBuilder();
        for (File j : r.jars) fp.append(j.getPath()).append(':').append(j.length()).append(':').append(j.lastModified()).append(';');
        for (ProcessorScanner.Found f : r.found) fp.append(f.className).append(f.kind).append(';');
        r.fingerprint = FileUtil.sha1(fp.toString());

        // ---- dex
        if (!dex) {
            r.loadJars.addAll(r.jars);
        } else {
            dexCache.mkdirs();
            for (File j : r.jars) r.loadJars.add(dexJar(j, r.jars, androidJar, dexCache, minApi, log));
        }
        return r;
    }

    // ------------------------------------------------------------------ dexing

    private static final String D8_TAG = "d8-8.2|";

    static File dexJar(File jar, List<File> all, File androidJar, File cacheDir, int minApi, final Log log) throws IOException {
        String key = FileUtil.sha1(D8_TAG + minApi + "|" + jar.getPath() + ":" + jar.length() + ":" + jar.lastModified());
        File out = new File(cacheDir, jar.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + key.substring(0, 10) + ".zip");
        if (out.isFile() && out.length() > 0) return out;
        long t0 = System.currentTimeMillis();
        File tmpDex = new File(cacheDir, out.getName() + ".dex.tmp.zip");
        tmpDex.delete();
        final List<String> errors = new ArrayList<>();
        DiagnosticsHandler h = new DiagnosticsHandler() {
            @Override public void error(Diagnostic d) { errors.add(d.getDiagnosticMessage()); }
            @Override public void warning(Diagnostic d) { }
            @Override public void info(Diagnostic d) { }
        };
        try {
            D8Command.Builder b = D8Command.builder(h);
            b.addProgramFiles(jar.toPath());
            if (androidJar != null && androidJar.isFile()) b.addLibraryFiles(androidJar.toPath());
            List<Path> cp = new ArrayList<>();
            for (File f : all) if (!f.equals(jar)) cp.add(f.toPath());
            if (!cp.isEmpty()) b.addClasspathFiles(cp);
            b.setMinApiLevel(minApi);
            b.setMode(CompilationMode.RELEASE);
            b.setOutput(tmpDex.toPath(), OutputMode.DexIndexed);
            D8.run(b.build());
        } catch (CompilationFailedException ex) {
            tmpDex.delete();
            throw new IOException("Could not convert annotation processor jar " + jar.getName() + " for Android (D8): "
                    + (errors.isEmpty() ? ex.getMessage() : errors.get(0)));
        }
        // dex + the jar's resources (META-INF/services etc.) in one zip: DexClassLoader reads resources from it
        File tmp = new File(cacheDir, out.getName() + ".tmp");
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            try (ZipFile dz = new ZipFile(tmpDex)) {
                Enumeration<? extends ZipEntry> en = dz.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    zo.putNextEntry(new ZipEntry(e.getName()));
                    copy(dz.getInputStream(e), zo);
                    zo.closeEntry();
                }
            }
            try (ZipFile jz = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> en = jz.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || n.endsWith(".class") || n.startsWith("META-INF/versions/")
                            || n.equals("META-INF/MANIFEST.MF") || n.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                    zo.putNextEntry(new ZipEntry(n));
                    copy(jz.getInputStream(e), zo);
                    zo.closeEntry();
                }
            }
        }
        tmpDex.delete();
        if (!tmp.renameTo(out)) throw new IOException("Cannot write " + out);
        out.setReadOnly();                        // Android 14+: dex files must be read-only to be loaded
        log.log("  dexed " + jar.getName() + " (" + (System.currentTimeMillis() - t0) + " ms)");
        return out;
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        try (InputStream i = new BufferedInputStream(in)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
