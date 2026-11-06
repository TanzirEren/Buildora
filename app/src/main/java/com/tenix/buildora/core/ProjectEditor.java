package com.tenix.buildora.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits the "info" of an existing project: package id, app name, version, SDK levels, internet permission.
 * Plain Java (no Android classes) so it can be tested on a JVM. Everything here works on files only.
 */
public final class ProjectEditor {
    private ProjectEditor() {}

    public static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");
    private static final String INTERNET = "android.permission.INTERNET";

    /** Result of a package change. */
    public static final class PackageResult {
        public int filesRewritten;
        public int dirsMoved;
    }

    // ------------------------------------------------------------------ package id

    /**
     * Renames the package everywhere in the app module: package declarations, imports, manifest class names,
     * gradle files, string literals, and the java/&lt;old/package&gt; folder itself.
     * srcRoot is the folder holding AndroidManifest.xml (usually app/src/main).
     */
    public static PackageResult changePackage(File moduleDir, File srcRoot, List<File> javaDirs, String oldPkg, String newPkg)
            throws IOException {
        PackageResult r = new PackageResult();
        if (oldPkg == null || oldPkg.isEmpty() || oldPkg.equals(newPkg)) return r;
        if (!PACKAGE.matcher(newPkg).matches()) throw new IOException("Invalid package id: " + newPkg);

        Pattern p = Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(oldPkg) + "(?![A-Za-z0-9_])");
        String repl = Matcher.quoteReplacement(newPkg);

        // 1. rewrite text (before moving anything)
        Set<File> roots = new LinkedHashSet<>();
        File srcSets = srcRoot.getName().equals("main") && srcRoot.getParentFile() != null ? srcRoot.getParentFile() : srcRoot;
        roots.add(srcSets);
        for (File d : javaDirs) roots.add(d);
        for (File f : new File[]{new File(moduleDir, "build.gradle"), new File(moduleDir, "build.gradle.kts"),
                new File(moduleDir, "proguard-rules.pro"), new File(moduleDir, "google-services.json")}) {
            if (f.isFile()) r.filesRewritten += rewrite(f, p, repl);
        }
        Set<String> done = new LinkedHashSet<>();
        for (File root : roots) r.filesRewritten += walkRewrite(root, p, repl, done, 0);

        // 2. move the package folder in every source set
        Set<File> javaRoots = new LinkedHashSet<>(javaDirs);
        File[] sets = srcSets.listFiles();
        if (sets != null) for (File s : sets) {
            File j = new File(s, "java");
            if (j.isDirectory()) javaRoots.add(j);
        }
        for (File jr : javaRoots) {
            File oldDir = new File(jr, oldPkg.replace('.', '/'));
            if (!oldDir.isDirectory()) continue;
            File newDir = new File(jr, newPkg.replace('.', '/'));
            File tmp = new File(jr, ".pkgmove_" + System.nanoTime());
            if (!oldDir.renameTo(tmp)) throw new IOException("Cannot move " + oldDir);
            File parent = newDir.getParentFile();
            if (parent != null) parent.mkdirs();
            if (newDir.exists()) {                                  // merge into an existing folder
                moveInto(tmp, newDir);
                deleteEmpty(tmp);
            } else if (!tmp.renameTo(newDir)) {
                tmp.renameTo(oldDir);
                throw new IOException("Cannot move to " + newDir);
            }
            pruneEmptyParents(oldDir.getParentFile(), jr);
            r.dirsMoved++;
        }
        return r;
    }

    private static int walkRewrite(File f, Pattern p, String repl, Set<String> done, int depth) throws IOException {
        if (depth > 24) return 0;
        String key;
        try { key = f.getCanonicalPath(); } catch (IOException e) { key = f.getAbsolutePath(); }
        if (!done.add(key)) return 0;
        if (f.isDirectory()) {
            String n = f.getName();
            if (n.equals(".buildora") || n.equals("build") || n.startsWith(".pkgmove_")) return 0;
            File[] kids = f.listFiles();
            int c = 0;
            if (kids != null) for (File k : kids) c += walkRewrite(k, p, repl, done, depth + 1);
            return c;
        }
        return isTextFile(f) ? rewrite(f, p, repl) : 0;
    }

    private static boolean isTextFile(File f) {
        String n = f.getName().toLowerCase();
        if (f.length() > 3L * 1024 * 1024) return false;
        return n.endsWith(".java") || n.endsWith(".xml") || n.endsWith(".gradle") || n.endsWith(".kts") || n.endsWith(".pro")
                || n.endsWith(".properties") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".kt") || n.endsWith(".smali") || n.endsWith(".html") || n.endsWith(".js");
    }

    private static int rewrite(File f, Pattern p, String repl) throws IOException {
        String t = FileUtil.readText(f);
        Matcher m = p.matcher(t);
        if (!m.find()) return 0;
        FileUtil.writeText(f, m.replaceAll(repl));
        return 1;
    }

    private static void moveInto(File from, File to) throws IOException {
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File dst = new File(to, k.getName());
            if (k.isDirectory()) {
                dst.mkdirs();
                moveInto(k, dst);
            } else if (!dst.exists() && !k.renameTo(dst)) {
                throw new IOException("Cannot move " + k);
            }
        }
    }

    private static void deleteEmpty(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteEmpty(k);
        d.delete();
    }

    private static void pruneEmptyParents(File d, File stop) {
        while (d != null && !d.equals(stop)) {
            String[] kids = d.list();
            if (kids == null || kids.length > 0 || !d.delete()) break;
            d = d.getParentFile();
        }
    }

    // ------------------------------------------------------------------ app name

    /** Sets the app_name string. Returns false when no app_name string exists. */
    public static boolean setAppName(File resDir, String appName) throws IOException {
        File sx = new File(resDir, "values/strings.xml");
        if (!sx.isFile()) return false;
        String t = FileUtil.readText(sx);
        String esc = appName.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'");
        Matcher m = Pattern.compile("(<string\\s+name=\"app_name\"[^>]*>)[^<]*(</string>)").matcher(t);
        if (!m.find()) return false;
        String n = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + esc + m.group(2)));
        if (!n.equals(t)) FileUtil.writeText(sx, n);
        return true;
    }

    // ------------------------------------------------------------------ gradle sync

    /** Keeps the Gradle-compatible build file in step with buildora.json (only touches values that are present). */
    public static void syncGradle(File moduleDir, int minSdk, int targetSdk, int versionCode, String versionName) throws IOException {
        for (String name : new String[]{"build.gradle", "build.gradle.kts"}) {
            File g = new File(moduleDir, name);
            if (!g.isFile()) continue;
            String t = FileUtil.readText(g);
            String n = t;
            n = n.replaceAll("(minSdk(?:Version)?\\s*=?\\s*)\\d+", "$1" + minSdk);
            n = n.replaceAll("(targetSdk(?:Version)?\\s*=?\\s*)\\d+", "$1" + targetSdk);
            n = n.replaceAll("(versionCode\\s*=?\\s*)\\d+", "$1" + versionCode);
            Matcher vm = Pattern.compile("(versionName\\s*=?\\s*)([\"'])[^\"']*([\"'])").matcher(n);
            StringBuffer sb = new StringBuffer();
            String vn = versionName.replace("\"", "").replace("'", "");
            while (vm.find()) vm.appendReplacement(sb, Matcher.quoteReplacement(vm.group(1) + vm.group(2) + vn + vm.group(3)));
            vm.appendTail(sb);
            n = sb.toString();
            if (!n.equals(t)) FileUtil.writeText(g, n);
        }
    }

    // ------------------------------------------------------------------ internet permission

    public static boolean hasInternet(File manifest) {
        try { return manifest.isFile() && FileUtil.readText(manifest).contains(INTERNET); } catch (IOException e) { return false; }
    }

    /** Adds or removes the INTERNET permission line in the manifest. Returns true when the file changed. */
    public static boolean setInternet(File manifest, boolean on) throws IOException {
        if (!manifest.isFile()) return false;
        String t = FileUtil.readText(manifest);
        boolean has = t.contains(INTERNET);
        if (on == has) return false;
        if (on) {
            Matcher m = Pattern.compile("<manifest\\b[^>]*>").matcher(t);
            if (!m.find()) return false;
            String line = "\n\n    <uses-permission android:name=\"" + INTERNET + "\" />";
            t = t.substring(0, m.end()) + line + t.substring(m.end());
        } else {
            t = t.replaceAll("[ \\t]*<uses-permission\\s+android:name=\"" + Pattern.quote(INTERNET) + "\"\\s*/>[ \\t]*\\r?\\n?", "");
        }
        FileUtil.writeText(manifest, t);
        return true;
    }

    // ------------------------------------------------------------------ caches

    /** Drops incremental-build state (paths and R.java change with the package or folder). Keeps the saved icon source. */
    public static void clearBuildCache(File workDir) {
        File[] kids = workDir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().equals("icon_src.png")) continue;
            FileUtil.deleteRecursive(k);
        }
    }

    // ------------------------------------------------------------------ names

    /** Folder name for a project name; null when it is unchanged. */
    public static String newFolderName(String currentFolder, String typedName) {
        String s = Project.sanitize(typedName);
        return s.equals(currentFolder) ? null : s;
    }

    public static List<String> sdkNames(int from, int to) {
        List<String> l = new ArrayList<>();
        for (int i = from; i <= to; i++) l.add(String.valueOf(i));
        return l;
    }
}
