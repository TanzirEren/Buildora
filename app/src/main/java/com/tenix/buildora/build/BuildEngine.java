package com.tenix.buildora.build;

import android.content.Context;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.Project;

import org.eclipse.jdt.core.compiler.CompilationProgress;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The on-device build pipeline:
 * deps (Maven/AAR) -> manifest merge -> aapt2 compile+link -> ECJ -> D8 -> package -> apksig.
 * Call {@link #build()} on a background thread.
 */
public final class BuildEngine {

    public interface Listener {
        void onStage(String stage, int percent);
        void onLog(String line);
    }

    public static final class Diag {
        public String file;
        public int line;
        public String message;
        public boolean error = true;
        @Override public String toString() {
            return (file == null ? "" : file + (line > 0 ? ":" + line : "") + ": ") + message;
        }
    }

    public static final class Result {
        public boolean success, cancelled;
        public File apk;
        public String errorText = "";
        public String log = "";
        public long millis;
        public final List<Diag> diags = new ArrayList<>();
    }

    private static final class BuildException extends Exception {
        final boolean cancel;
        BuildException(String m) { super(m); cancel = false; }
        BuildException(String m, boolean cancel) { super(m); this.cancel = cancel; }
    }

    private static final class Libs {
        final List<File> jars = new ArrayList<>();
        final List<File> resDirs = new ArrayList<>();
        final List<File> manifests = new ArrayList<>();
        final List<File> assetDirs = new ArrayList<>();
        final List<File> jniDirs = new ArrayList<>();
        final Set<String> packages = new LinkedHashSet<>();
    }

    private final Context ctx;
    private final Project p;
    private final Listener listener;
    private final StringBuilder fullLog = new StringBuilder();
    private final StringBuilder stageOut = new StringBuilder();
    private final List<Diag> diags = new ArrayList<>();
    private volatile boolean cancelled;
    private volatile Process current;
    private String stageName = "";

    public BuildEngine(Context ctx, Project p, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.p = p;
        this.listener = l;
    }

    public void cancel() {
        cancelled = true;
        Process pr = current;
        if (pr != null) pr.destroy();
    }

    // ------------------------------------------------------------------ main flow

    public Result build() {
        long t0 = System.currentTimeMillis();
        Result r = new Result();
        File work = p.workDir();
        try {
            stage("Preparing", 2);
            Toolchain.ensure(ctx);
            File aapt2 = Toolchain.aapt2(ctx);
            File androidJar = Toolchain.androidJar(ctx);
            if (!aapt2.isFile())
                throw new BuildException("aapt2 is not installed.\nOpen Settings > Toolchain and import an Android (ARM) aapt2 binary,\nor rebuild Buildora with the aapt2 asset (see README).");
            if (!androidJar.isFile())
                throw new BuildException("android.jar is missing. Open Settings > Toolchain and import it.");
            String ver = Toolchain.aapt2Version(ctx);
            log("aapt2: " + ver);
            if (ver.startsWith("cannot execute") || ver.startsWith("no output"))
                throw new BuildException("aapt2 cannot run on this device:\n" + ver);

            p.detectLayout();
            if (!p.manifestFile.isFile())
                throw new BuildException("AndroidManifest.xml not found in " + p.srcRoot);
            if (p.javaDirs.isEmpty())
                throw new BuildException("No Java source folder found (expected 'java' or 'src' next to AndroidManifest.xml).");
            String pkg = ManifestMerger.packageOf(p.manifestFile);
            if (pkg.isEmpty()) throw new BuildException("The manifest has no package attribute.");
            p.packageName = pkg;
            int minSdk = Math.max(1, p.minSdk);
            int targetSdk = Math.max(minSdk, p.targetSdk);

            File b = new File(work, "build");
            FileUtil.deleteRecursive(b);
            File gen = new File(b, "gen"), classes = new File(b, "classes"), resOut = new File(b, "res"),
                    dexOut = new File(b, "dex");
            gen.mkdirs(); classes.mkdirs(); resOut.mkdirs(); dexOut.mkdirs();
            check();

            // ---- 1 dependencies
            stage("Resolving libraries", 8);
            Libs libs = resolveLibs(work);
            check();

            // ---- 2 manifest
            stage("Merging manifest", 16);
            File manifest = new File(b, "AndroidManifest.xml");
            try {
                ManifestMerger.merge(p.manifestFile, libs.manifests, pkg, manifest);
            } catch (Exception e) {
                throw new BuildException("Manifest merge failed: " + e);
            }
            check();

            // ---- 3 resources
            stage("Compiling resources", 26);
            List<File> flats = new ArrayList<>();
            List<File> libRes = new ArrayList<>(libs.resDirs);
            Collections.reverse(libRes);
            int idx = 0;
            for (File rd : libRes) {
                if (!hasFiles(rd)) continue;
                File z = new File(resOut, "lib" + (idx++) + ".zip");
                aapt(Arrays.asList(aapt2.getPath(), "compile", "--dir", rd.getPath(), "-o", z.getPath()));
                flats.add(z);
            }
            if (hasFiles(p.resDir)) {
                File z = new File(resOut, "app.zip");
                aapt(Arrays.asList(aapt2.getPath(), "compile", "--dir", p.resDir.getPath(), "-o", z.getPath()));
                flats.add(z);
            }
            check();

            stage("Linking resources", 38);
            File assetsStage = new File(b, "assets");
            boolean anyAssets = false;
            for (File ad : libs.assetDirs) { if (hasFiles(ad)) { FileUtil.copy(ad, assetsStage); anyAssets = true; } }
            if (hasFiles(p.assetsDir)) { FileUtil.copy(p.assetsDir, assetsStage); anyAssets = true; }
            File apRes = new File(b, "resources.ap_");
            List<String> link = new ArrayList<>(Arrays.asList(aapt2.getPath(), "link",
                    "-o", apRes.getPath(), "-I", androidJar.getPath(), "--manifest", manifest.getPath(),
                    "--java", gen.getPath(), "--min-sdk-version", String.valueOf(minSdk),
                    "--target-sdk-version", String.valueOf(targetSdk),
                    "--version-code", String.valueOf(p.versionCode), "--version-name", p.versionName,
                    "--auto-add-overlay"));
            if (Prefs.bool(ctx, Prefs.DEBUGGABLE, true)) link.add("--debug-mode");
            if (anyAssets) { link.add("-A"); link.add(assetsStage.getPath()); }
            Set<String> extra = new LinkedHashSet<>(libs.packages);
            extra.remove(pkg);
            if (!extra.isEmpty()) { link.add("--extra-packages"); link.add(join(extra, ":")); }
            for (int i = 0; i < flats.size(); i++) {
                if (i > 0) link.add("-R");
                link.add(flats.get(i).getPath());
            }
            aapt(link);
            check();

            // ---- 4 java
            stage("Compiling Java", 52);
            compileJava(p, androidJar, libs, gen, classes);
            check();

            // ---- 5 dex
            stage("Dexing (D8)", 72);
            File classesJar = new File(b, "classes.jar");
            zipDir(classes, classesJar);
            List<File> projectDex = new ArrayList<>();
            List<File> libDex = new ArrayList<>();
            if (!libs.jars.isEmpty()) {
                String key = FileUtil.sha1(minSdk + "|" + describe(libs.jars));
                File cacheDir = new File(ctx.getFilesDir(), "dexcache/" + key);
                if (!new File(cacheDir, "classes.dex").isFile()) {
                    log("Dexing " + libs.jars.size() + " librar" + (libs.jars.size() == 1 ? "y" : "ies") + " (cached for next time)...");
                    FileUtil.deleteRecursive(cacheDir);
                    cacheDir.mkdirs();
                    dex(libs.jars, androidJar, null, cacheDir, minSdk);
                } else log("Libraries dex: cache hit");
                libDex.addAll(sortedDex(cacheDir));
            }
            dex(Collections.singletonList(classesJar), androidJar, libs.jars, dexOut, minSdk);
            projectDex.addAll(sortedDex(dexOut));
            check();

            // ---- 6 package
            stage("Packaging APK", 86);
            File unsigned = new File(b, "unsigned.apk");
            try (ApkBuilder ab = new ApkBuilder(unsigned)) {
                ab.addAllFromZip(apRes);
                int n = 1;
                List<File> all = new ArrayList<>(projectDex);
                all.addAll(libDex);
                for (File d : all) {
                    byte[] data;
                    try (InputStream in = new java.io.FileInputStream(d)) { data = FileUtil.readAll(in); }
                    ab.addDeflated(n == 1 ? "classes.dex" : "classes" + n + ".dex", data);
                    n++;
                }
                for (File jar : libs.jars) ab.addJavaResources(jar);
                for (File jni : libs.jniDirs) ab.addNativeLibs(jni);
            }
            check();

            // ---- 7 sign
            stage("Signing APK", 94);
            File out = p.apkFile();
            String keyName = Signer.sign(ctx, unsigned, out, minSdk);
            log("Signed with: " + keyName);
            log("Output: " + out.getAbsolutePath() + " (" + FileUtil.human(out.length()) + ")");

            if (Prefs.bool(ctx, Prefs.AUTO_VERSION, false)) {
                p.versionCode++;
                try { p.save(); } catch (IOException ignored) { }
            }
            if (Prefs.bool(ctx, Prefs.CLEAN_BEFORE, true)) FileUtil.deleteRecursive(b);
            stage("Done", 100);
            r.success = true;
            r.apk = out;
        } catch (BuildException e) {
            r.cancelled = e.cancel;
            r.errorText = failureText(e.getMessage());
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            r.errorText = failureText("Unexpected error: " + t + "\n" + trim(sw.toString(), 3000));
        }
        r.millis = System.currentTimeMillis() - t0;
        r.diags.addAll(diags);
        log((r.success ? "BUILD SUCCESSFUL" : r.cancelled ? "BUILD CANCELLED" : "BUILD FAILED") + " in " + (r.millis / 1000.0) + " s");
        r.log = fullLog.toString();
        try { FileUtil.writeText(p.buildLog(), r.log); } catch (IOException ignored) { }
        return r;
    }

    // ------------------------------------------------------------------ libraries

    private Libs resolveLibs(File work) throws BuildException {
        Libs libs = new Libs();
        try {
            for (File dir : p.libDirs) {
                File[] fs = dir.listFiles();
                if (fs == null) continue;
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".jar")) { libs.jars.add(f); log("  + local " + f.getName()); }
                    else if (n.endsWith(".aar")) {
                        File ex = new File(work, "aar/" + f.getName() + "-" + f.lastModified());
                        if (!new File(ex, ".ok").isFile()) {
                            FileUtil.deleteRecursive(ex);
                            ex.mkdirs();
                            unzipAar(f, ex);
                            new File(ex, ".ok").createNewFile();
                        }
                        addAar(libs, ex);
                        log("  + local " + f.getName());
                    }
                }
            }
            if (!p.dependencies.isEmpty()) {
                log("Resolving " + p.dependencies.size() + " dependenc" + (p.dependencies.size() == 1 ? "y" : "ies") + "...");
                MavenResolver mr = new MavenResolver(new File(ctx.getFilesDir(), "m2"), new MavenResolver.Log() {
                    @Override public void log(String s) { BuildEngine.this.log(s); }
                });
                for (MavenResolver.Artifact a : mr.resolve(p.dependencies)) {
                    if (a.aar) addAar(libs, a.extracted());
                    else if (!libs.jars.contains(a.file)) libs.jars.add(a.file);
                }
            }
        } catch (IOException e) {
            throw new BuildException("Dependency resolution failed: " + e.getMessage() +
                    "\nCheck your internet connection or remove the dependency in Libraries.");
        }
        return libs;
    }

    private static void unzipAar(File aar, File dest) throws IOException {
        FileUtil.unzip(aar, dest);
    }

    private void addAar(Libs libs, File dir) {
        File cj = new File(dir, "classes.jar");
        if (cj.isFile() && !libs.jars.contains(cj)) libs.jars.add(cj);
        File[] inner = new File(dir, "libs").listFiles();
        if (inner != null) for (File f : inner) if (f.getName().endsWith(".jar") && !libs.jars.contains(f)) libs.jars.add(f);
        File res = new File(dir, "res");
        if (res.isDirectory()) libs.resDirs.add(res);
        File mf = new File(dir, "AndroidManifest.xml");
        if (mf.isFile()) {
            libs.manifests.add(mf);
            String pk = ManifestMerger.packageOf(mf);
            if (!pk.isEmpty()) libs.packages.add(pk);
        }
        File as = new File(dir, "assets");
        if (as.isDirectory()) libs.assetDirs.add(as);
        File jni = new File(dir, "jni");
        if (jni.isDirectory()) libs.jniDirs.add(jni);
    }

    // ------------------------------------------------------------------ aapt2

    private void aapt(List<String> cmd) throws BuildException {
        File aapt2 = new File(cmd.get(0));
        aapt2.setExecutable(true, false);
        log("$ aapt2 " + cmd.get(1) + " ...");
        int code;
        try {
            code = exec(cmd, p.workDir());
        } catch (IOException | InterruptedException e) {
            throw new BuildException("Could not run aapt2: " + e.getMessage(), cancelled);
        }
        if (cancelled) throw new BuildException("Build cancelled", true);
        if (code != 0) throw new BuildException("aapt2 " + cmd.get(1) + " failed (exit code " + code + ")");
    }

    private static final Pattern AAPT_LINE = Pattern.compile("^(.+?):(\\d+): (error|warning): (.*)$");
    private static final Pattern AAPT_FILE_ERR = Pattern.compile("^(/.+?): error: (.*)$");

    private int exec(List<String> cmd, File dir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        dir.mkdirs();
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        current = pr;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                log(line);
                Matcher m = AAPT_LINE.matcher(line);
                if (m.matches()) {
                    addDiag(m.group(1), Integer.parseInt(m.group(2)), m.group(4), m.group(3).equals("error"));
                } else {
                    Matcher m2 = AAPT_FILE_ERR.matcher(line);
                    if (m2.matches()) addDiag(m2.group(1), 0, m2.group(2), true);
                }
            }
        }
        int code = pr.waitFor();
        current = null;
        return code;
    }

    private void addDiag(String file, int line, String msg, boolean error) {
        Diag d = new Diag();
        d.file = file;
        d.line = line;
        d.message = msg;
        d.error = error;
        diags.add(d);
    }

    // ------------------------------------------------------------------ ECJ

    private void compileJava(Project p, File androidJar, Libs libs, File gen, File classes) throws BuildException {
        List<String> args = new ArrayList<>();
        args.addAll(Arrays.asList("-1.8", "-encoding", "UTF-8", "-nowarn", "-proc:none", "-g",
                "-bootclasspath", androidJar.getPath()));
        List<String> cp = new ArrayList<>();
        cp.add(Toolchain.lambdaStubs(ctx).getPath());   // LambdaMetafactory stub: needed for lambdas / method references
        for (File f : libs.jars) cp.add(f.getPath());
        args.add("-classpath");
        args.add(join(cp, File.pathSeparator));
        args.add("-d");
        args.add(classes.getPath());
        for (File jd : p.javaDirs) args.add(jd.getPath());
        args.add(gen.getPath());

        log("$ ecj (" + p.javaDirs.size() + " source folder" + (p.javaDirs.size() == 1 ? "" : "s") + ")");
        final StringWriter sw = new StringWriter();
        final boolean[] ok = new boolean[1];
        final String[] argv = args.toArray(new String[0]);
        final Throwable[] fail = new Throwable[1];
        Thread t = new Thread(null, new Runnable() {
            @Override public void run() {
                try {
                    PrintWriter pw = new PrintWriter(sw, true);
                    ok[0] = BatchCompiler.compile(argv, pw, pw, new CompilationProgress() {
                        @Override public void begin(int remainingWork) { }
                        @Override public void done() { }
                        @Override public boolean isCanceled() { return cancelled; }
                        @Override public void setTaskName(String name) { }
                        @Override public void worked(int workIncrement, int remainingWork) { }
                    });
                } catch (Throwable e) {
                    fail[0] = e;
                }
            }
        }, "ecj", 512L * 1024 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new BuildException("Interrupted", true);
        }
        String output = sw.toString();
        for (String line : output.split("\n")) if (!line.trim().isEmpty()) log(line);
        if (fail[0] != null) {
            StringWriter ew = new StringWriter();
            fail[0].printStackTrace(new PrintWriter(ew));
            throw new BuildException("The Java compiler crashed: " + fail[0] + "\n" + trim(ew.toString(), 2500));
        }
        if (cancelled) throw new BuildException("Build cancelled", true);
        if (!ok[0]) {
            parseEcj(output);
            throw new BuildException("Java compilation failed");
        }
    }

    private static final Pattern ECJ_HEAD = Pattern.compile("^\\d+\\. (ERROR|WARNING) in (.+?) \\(at line (\\d+)\\)\\s*$");

    private void parseEcj(String output) {
        String[] blocks = output.split("----------\\n?");
        for (String blk : blocks) {
            String[] lines = blk.split("\\n");
            int i = 0;
            while (i < lines.length && lines[i].trim().isEmpty()) i++;
            if (i >= lines.length) continue;
            Matcher m = ECJ_HEAD.matcher(lines[i].trim());
            if (!m.matches()) continue;
            int caret = -1;
            for (int k = i + 1; k < lines.length; k++) {
                if (lines[k].trim().matches("\\^+")) { caret = k; break; }
            }
            StringBuilder msg = new StringBuilder();
            for (int k = caret < 0 ? i + 1 : caret + 1; k < lines.length; k++) {
                String s = lines[k].trim();
                if (s.isEmpty()) continue;
                if (msg.length() > 0) msg.append(' ');
                msg.append(s);
            }
            addDiag(m.group(2), Integer.parseInt(m.group(3)), msg.toString(), m.group(1).equals("ERROR"));
        }
    }

    // ------------------------------------------------------------------ D8

    private void dex(List<File> program, File androidJar, List<File> classpath, File out, int minSdk) throws BuildException {
        final List<String> msgs = new ArrayList<>();
        DiagnosticsHandler handler = new DiagnosticsHandler() {
            @Override public void error(Diagnostic d) { msgs.add("error: " + d.getDiagnosticMessage()); log("D8 error: " + d.getDiagnosticMessage()); }
            @Override public void warning(Diagnostic d) { }
            @Override public void info(Diagnostic d) { }
        };
        try {
            D8Command.Builder bld = D8Command.builder(handler);
            List<Path> prog = new ArrayList<>();
            for (File f : program) prog.add(f.toPath());
            bld.addProgramFiles(prog);
            bld.addLibraryFiles(androidJar.toPath());
            if (classpath != null && !classpath.isEmpty()) {
                List<Path> cp = new ArrayList<>();
                for (File f : classpath) cp.add(f.toPath());
                bld.addClasspathFiles(cp);
            }
            bld.setMinApiLevel(minSdk);
            bld.setMode(Prefs.bool(ctx, Prefs.DEX_RELEASE, false) ? CompilationMode.RELEASE : CompilationMode.DEBUG);
            bld.setOutput(out.toPath(), OutputMode.DexIndexed);
            D8.run(bld.build());
        } catch (CompilationFailedException e) {
            throw new BuildException("D8 dexing failed:\n" + join(msgs, "\n") + (msgs.isEmpty() ? String.valueOf(e.getMessage()) : ""));
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            throw new BuildException("D8 crashed: " + t + "\n" + trim(sw.toString(), 2500));
        }
    }

    private static List<File> sortedDex(File dir) {
        File[] fs = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        for (File f : fs) if (f.getName().endsWith(".dex")) out.add(f);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Integer.compare(num(a), num(b)); }
            int num(File f) {
                String n = f.getName().replace("classes", "").replace(".dex", "");
                return n.isEmpty() ? 1 : Integer.parseInt(n);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ misc helpers

    private void stage(String name, int percent) throws BuildException {
        check();
        stageName = name;
        stageOut.setLength(0);
        log("\n== " + name + " ==");
        if (listener != null) listener.onStage(name, percent);
    }

    private void check() throws BuildException {
        if (cancelled) throw new BuildException("Build cancelled", true);
    }

    private void log(String s) {
        fullLog.append(s).append('\n');
        stageOut.append(s).append('\n');
        if (listener != null) listener.onLog(s);
    }

    private String failureText(String headline) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build failed at: ").append(stageName).append("\n").append(headline).append("\n");
        int errs = 0;
        for (Diag d : diags) if (d.error) errs++;
        if (errs > 0) {
            sb.append("\n").append(errs).append(" error").append(errs == 1 ? "" : "s").append(":\n");
            for (Diag d : diags) if (d.error) sb.append("\n• ").append(shortPath(d)).append("\n").append(d.message).append("\n");
        }
        String raw = stageOut.toString().trim();
        if (errs == 0 && !raw.isEmpty()) sb.append("\n--- output ---\n").append(trim(raw, 12000)).append("\n");
        return sb.toString().trim();
    }

    private String shortPath(Diag d) {
        String f = d.file == null ? "" : d.file;
        String root = p.root.getAbsolutePath() + "/";
        if (f.startsWith(root)) f = f.substring(root.length());
        return f + (d.line > 0 ? ":" + d.line : "");
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n…";
    }

    private static String join(Iterable<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    private static String describe(List<File> jars) {
        StringBuilder sb = new StringBuilder();
        for (File f : jars) sb.append(f.getPath()).append(':').append(f.length()).append(':').append(f.lastModified()).append(';');
        return sb.toString();
    }

    private static boolean hasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            if (k.isFile()) return true;
            if (hasFiles(k)) return true;
        }
        return false;
    }

    private static void zipDir(File dir, File out) throws BuildException {
        try (ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(out)))) {
            addDir(z, dir, "");
        } catch (IOException e) {
            throw new BuildException("Could not create classes.jar: " + e.getMessage());
        }
    }

    private static void addDir(ZipOutputStream z, File d, String prefix) throws IOException {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) addDir(z, k, prefix + k.getName() + "/");
            else {
                z.putNextEntry(new ZipEntry(prefix + k.getName()));
                try (InputStream in = new java.io.FileInputStream(k)) { FileUtil.copyStream(in, z); }
                z.closeEntry();
            }
        }
    }
}
