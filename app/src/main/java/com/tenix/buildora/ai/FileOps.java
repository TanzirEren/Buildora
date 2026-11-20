package com.tenix.buildora.ai;

import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.MiniJson;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns parsed AI operations into a reviewable plan, applies it with a backup, and can undo it. Pure Java. */
public final class FileOps {
    private FileOps() {}

    public static final int NEW = 0, MODIFY = 1, DELETE = 2, RENAME = 3, SAME = 4;

    public static final class Change {
        public int kind;
        public String path;        // relative, normalised
        public String to;          // RENAME
        public String oldText = "";
        public String newText = "";
        public String error;       // set when this change cannot be applied
        public int added, removed;
        public boolean selected = true;
        public File srcFile;       // raw copy: attachment / project file copied (or moved) byte for byte
        public boolean asset;      // raw copy: no text diff to show
        public boolean binary;     // existing binary file (deleted / moved / replaced, never edited as text)
        public String label;       // overrides the kind label (Import, Copy, Resize...)
        public int convSize;       // image conversion: longest side (0 = keep)
        public String convFormat;  // image conversion: png | jpg | webp
        public boolean convRound;  // image conversion: circular mask (round launcher icon)

        public boolean ok() { return error == null && kind != SAME; }
        public String kindLabel() {
            if (label != null) return label;
            switch (kind) { case NEW: return asset ? "Import" : "New"; case MODIFY: return asset ? "Replace" : "Edit"; case DELETE: return "Delete"; case RENAME: return "Move"; default: return "Same"; }
        }
    }

    private static final String[] BINARY = {"png", "jpg", "jpeg", "gif", "webp", "apk", "aab", "jar", "aar", "so", "zip", "dex", "ttf", "otf", "mp3", "mp4", "ogg", "wav", "pdf", "keystore", "jks", "class"};

    /** Turns raw bytes into an image of another size / format (set by the Android side). */
    public interface ImageConverter {
        void convert(File src, File dest, int size, String format, boolean round) throws IOException;
    }

    public static ImageConverter converter;

    private static final Set<String> SKIP_DIRS = new HashSet<>(java.util.Arrays.asList(".git", ".buildora", "build", ".gradle", "node_modules"));

    static void walk(File root, File dir, List<String> out, int depth) {
        if (depth > 14 || out.size() >= 400) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) { if (!SKIP_DIRS.contains(f.getName())) walk(root, f, out, depth + 1); }
            else out.add(rel(root, f));
        }
    }

    static String rel(File root, File f) {
        String r = root.getPath(), p = f.getPath();
        return p.substring(r.length()).replace('\\', '/').replaceFirst("^/", "");
    }

    /** Files matching a path pattern: a file, a folder (everything inside), or a wildcard such as res/mipmap-*&#47;ic_launcher*.png. */
    static List<String> expand(File root, String pattern) {
        List<String> out = new ArrayList<>();
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if (ch == '*') { if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') { re.append(".*"); i++; } else re.append("[^/]*"); }
                else if (ch == '?') re.append("[^/]");
                else re.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
            List<String> all = new ArrayList<>();
            walk(root, root, all, 0);
            java.util.regex.Pattern pt = java.util.regex.Pattern.compile(re.toString());
            for (String a : all) if (pt.matcher(a).matches()) out.add(a);
            return out;
        }
        File f = new File(root, pattern);
        if (f.isDirectory()) walk(root, f, out, 0);
        else if (f.isFile()) out.add(pattern);
        return out;
    }

    private static boolean binary(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        int d = l.lastIndexOf('.');
        if (d < 0) return false;
        String e = l.substring(d + 1);
        for (String b : BINARY) if (b.equals(e)) return true;
        return false;
    }

    /** Resolves a possibly bare file name (MainActivity.java) to a unique path inside the project. */
    public static String resolve(File root, String path) {
        if (path.contains("/") || new File(root, path).exists()) return path;
        List<String> hits = new ArrayList<>();
        find(root, root, path, hits, 0);
        return hits.size() == 1 ? hits.get(0) : path;
    }

    private static void find(File root, File dir, String name, List<String> hits, int depth) {
        if (depth > 12 || hits.size() > 2) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals(".git") || n.equals(".buildora") || n.equals("build") || n.equals("outputs")) continue;
                find(root, f, name, hits, depth + 1);
            } else if (n.equals(name)) hits.add(FileUtil.relPath(root, f));
        }
    }

    public static List<Change> plan(File root, List<Protocol.Op> ops) { return plan(root, ops, null); }

    private static final String[] ASSET_EXT = {"png", "jpg", "jpeg", "webp", "gif", "bmp", "ttf", "otf", "xml", "json", "txt", "csv", "md", "html", "css", "js",
            "mp3", "ogg", "wav", "mp4", "pdf", "svg", "properties", "so", "bin", "dat", "db", "pro", "kt", "java"};

    private static boolean assetExtOk(String path) {
        String e = FileDigest.ext(path);
        for (String a : ASSET_EXT) if (a.equals(e)) return true;
        return false;
    }

    /** Finds a stored attachment by name (exact, then case-insensitive). */
    static File findAttachment(File attachDir, String name) {
        if (attachDir == null || name == null || name.isEmpty()) return null;
        File f = new File(attachDir, name);
        if (f.isFile()) return f;
        File[] fs = attachDir.listFiles();
        if (fs != null) for (File x : fs) if (x.isFile() && x.getName().equalsIgnoreCase(name)) return x;
        return null;
    }

    /** A res/ file with the same resource name but another extension would break the build ("duplicate resource"). */
    static String dupResource(File root, String path, Set<String> deleted) {
        if (!path.contains("res/")) return null;
        int sl = path.lastIndexOf('/');
        String dir = sl < 0 ? "" : path.substring(0, sl), name = path.substring(sl + 1);
        if (dir.contains("/values")) return null;
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        File[] fs = new File(root, dir).listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            String b = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
            if (f.isFile() && b.equals(base) && !n.equals(name) && !deleted.contains(dir + "/" + n))
                return "Duplicate resource: " + n + " already exists in this folder - delete it first (earlier in the same buildora-ops block)";
        }
        return null;
    }

    /** True for res/ files whose name Android would reject (aapt2 wants lowercase letters, digits, underscore). */
    static String resNameProblem(String path) {
        int i = path.indexOf("/res/");
        String rel = i >= 0 ? path.substring(i + 5) : path.startsWith("res/") ? path.substring(4) : null;
        if (rel == null) return null;
        int sl = rel.lastIndexOf('/');
        String dir = sl < 0 ? "" : rel.substring(0, sl), file = sl < 0 ? rel : rel.substring(sl + 1);
        if (dir.startsWith("values")) return null;
        int dot = file.indexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        if (!base.matches("[a-z][a-z0-9_]*")) return "Resource file names must be lowercase letters, digits and _ (start with a letter)";
        return null;
    }

    public static List<Change> plan(File root, List<Protocol.Op> ops, File attachDir) {
        List<Change> out = new ArrayList<>();
        Map<String, String> virtual = new LinkedHashMap<>();     // later ops see earlier results of this reply
        Set<String> deleted = new LinkedHashSet<>();
        int count = 0;
        for (Protocol.Op op : ops) {
            if (++count > 120) break;
            Change c = new Change();
            c.path = resolve(root, op.path);
            File f = new File(root, c.path);
            try {
                if (!FileUtil.isInside(root, f)) { c.kind = SAME; c.error = "Path is outside the project"; out.add(c); continue; }
            } catch (Exception e) { c.kind = SAME; c.error = "Bad path"; out.add(c); continue; }
            if (out.size() > 600) break;
            if (op.type == Protocol.ICON) { planIcon(root, op, attachDir, out, deleted); continue; }
            if (op.type == Protocol.COPY) { planCopy(root, op, virtual, deleted, out); continue; }
            if (op.type == Protocol.DELETE || op.type == Protocol.RENAME) {
                if (planTree(root, op, virtual, deleted, out)) continue;
            }
            if (op.type == Protocol.ASSET || op.type == Protocol.IMGCONV) {
                c.asset = true;
                c.path = op.to;
                f = new File(root, c.path);
                try { if (!FileUtil.isInside(root, f)) { c.kind = SAME; c.error = "Path is outside the project"; out.add(c); continue; } }
                catch (Exception e) { c.kind = SAME; c.error = "Bad path"; out.add(c); continue; }
                File src = findAttachment(attachDir, op.attachment);
                String bad = resNameProblem(c.path);
                if (src == null) { c.kind = SAME; c.error = "Attachment \"" + op.attachment + "\" not found in this chat"; }
                else if (!assetExtOk(c.path)) { c.kind = SAME; c.error = "This file type cannot be imported"; }
                else if (bad != null) { c.kind = SAME; c.error = bad; }
                else if (dupResource(root, c.path, deleted) != null) { c.kind = SAME; c.error = dupResource(root, c.path, deleted); }
                else if (src.length() > 25L * 1024 * 1024) { c.kind = SAME; c.error = "File is larger than 25 MB"; }
                else {
                    c.srcFile = src;
                    c.kind = f.isFile() ? MODIFY : NEW;
                    c.added = 0; c.removed = 0;
                    c.newText = "";
                    if (op.type == Protocol.IMGCONV) {
                        c.convSize = op.size;
                        String fmt = op.format.isEmpty() ? FileDigest.ext(c.path) : op.format;
                        c.convFormat = fmt.equals("jpeg") ? "jpg" : fmt;
                        c.label = c.kind == NEW ? "Import image" : "Replace image";
                        if (!FileDigest.isImageExt(FileDigest.ext(src.getName()))) { c.kind = SAME; c.error = "The attachment is not a picture"; }
                        else if (converter == null && (op.size > 0 || !c.convFormat.equals(FileDigest.ext(src.getName())))) { c.kind = SAME; c.error = "Resizing is not available here"; }
                    }
                }
                out.add(c);
                continue;
            }
            boolean exists = (f.isFile() || virtual.containsKey(c.path)) && !deleted.contains(c.path);
            String cur = virtual.containsKey(c.path) ? virtual.get(c.path) : "";
            if (!virtual.containsKey(c.path) && f.isFile() && !deleted.contains(c.path)) {
                if (binary(c.path)) {
                    c.binary = true;
                    if (op.type == Protocol.PATCH || op.type == Protocol.WRITE) {
                        c.kind = SAME; c.error = "Binary file - cannot be edited as text. Use delete, import, import-image, copy or move for it";
                        out.add(c); continue;
                    }
                    cur = "";
                } else try {
                    if (f.length() > 600 * 1024) { c.kind = SAME; c.error = "File too large"; out.add(c); continue; }
                    cur = FileUtil.readText(f);
                } catch (IOException e) { c.kind = SAME; c.error = "Cannot read file"; out.add(c); continue; }
            }
            c.oldText = cur;
            switch (op.type) {
                case Protocol.DELETE:
                    if (!exists) { c.kind = SAME; c.error = "File does not exist"; break; }
                    c.kind = DELETE;
                    c.newText = "";
                    deleted.add(c.path);
                    virtual.remove(c.path);
                    break;
                case Protocol.RENAME: {
                    String to = op.to;
                    c.to = to;
                    File tf = new File(root, to);
                    if (!exists) { c.kind = SAME; c.error = "Source file does not exist"; break; }
                    if (tf.exists()) { c.kind = SAME; c.error = "Target already exists"; break; }
                    if (!FileUtil.isInside(root, tf)) { c.kind = SAME; c.error = "Target is outside the project"; break; }
                    c.kind = RENAME;
                    c.newText = cur;
                    if (!virtual.containsKey(c.path) && f.isFile()) { c.srcFile = f; c.asset = true; c.label = "Move"; }
                    deleted.add(c.path);
                    virtual.remove(c.path);
                    virtual.put(to, cur);
                    break;
                }
                case Protocol.PATCH: {
                    if (!exists) { c.kind = SAME; c.error = "Cannot patch - file does not exist"; break; }
                    String t = cur;
                    String err = null;
                    for (String[] pr : op.patches) {
                        String r = applyPatch(t, pr[0], pr[1]);
                        if (r == null) { err = "A search block was not found (or matched more than once)"; break; }
                        t = r;
                    }
                    if (err != null) { c.kind = SAME; c.error = err; break; }
                    c.kind = t.equals(cur) ? SAME : MODIFY;
                    c.newText = t;
                    virtual.put(c.path, t);
                    break;
                }
                default: {
                    if (binary(c.path) && !exists) { c.kind = SAME; c.error = "Binary files cannot be written as text. Use import, import-image, copy or app-icon"; break; }
                    String t = op.content;
                    if (t.length() > 600 * 1024) { c.kind = SAME; c.error = "Generated file too large"; break; }
                    c.kind = !exists ? NEW : (t.equals(cur) ? SAME : MODIFY);
                    c.newText = t;
                    deleted.remove(c.path);
                    virtual.put(c.path, t);
                }
            }
            if (c.kind == NEW || c.kind == MODIFY) {
                int[] st = Diff.stats(Diff.lines(c.oldText, c.newText));
                c.added = st[0]; c.removed = st[1];
            } else if (c.kind == DELETE) c.removed = c.oldText.isEmpty() ? 0 : c.oldText.split("\n", -1).length;
            out.add(c);
        }
        return out;
    }

    // ---- folder / wildcard / copy / icon operations (each file becomes its own change so backup and undo work as before)

    /** delete and move for folders and wildcards. Returns true when it handled the operation. */
    private static boolean planTree(File root, Protocol.Op op, Map<String, String> virtual, Set<String> deleted, List<Change> out) {
        boolean wild = op.path.indexOf('*') >= 0 || op.path.indexOf('?') >= 0;
        File base = new File(root, op.path);
        if (!wild && !base.isDirectory()) return false;
        List<String> files = expand(root, op.path);
        if (files.isEmpty()) {
            Change c = new Change(); c.path = op.path; c.kind = SAME; c.error = wild ? "Nothing matches this pattern" : "Folder is empty";
            out.add(c);
            return true;
        }
        for (String rel : files) {
            Change c = new Change();
            c.path = rel;
            c.binary = binary(rel);
            File f = new File(root, rel);
            if (deleted.contains(rel)) continue;
            if (op.type == Protocol.DELETE) {
                c.kind = DELETE;
                c.oldText = "";
                if (!c.binary && f.length() < 600 * 1024) { try { c.oldText = FileUtil.readText(f); c.removed = c.oldText.isEmpty() ? 0 : c.oldText.split("\n", -1).length; } catch (IOException ignored) { } }
                deleted.add(rel);
                virtual.remove(rel);
            } else {
                if (wild) { c.kind = SAME; c.error = "Move needs a folder or a single file, not a pattern"; out.add(c); continue; }
                String sub = rel.substring(op.path.length()).replaceFirst("^/", "");
                String to = op.to.endsWith("/") ? op.to + sub : op.to + "/" + sub;
                c.to = to;
                File tf = new File(root, to);
                if (tf.exists()) { c.kind = SAME; c.error = "Target already exists"; out.add(c); continue; }
                c.kind = RENAME;
                c.srcFile = f; c.asset = true; c.label = "Move";
                deleted.add(rel);
            }
            out.add(c);
        }
        return true;
    }

    private static void planCopy(File root, Protocol.Op op, Map<String, String> virtual, Set<String> deleted, List<Change> out) {
        File base = new File(root, op.path);
        List<String> files = expand(root, op.path);
        boolean isDir = base.isDirectory();
        if (files.isEmpty() && virtual.containsKey(op.path)) {
            Change c = new Change();
            c.path = op.to;
            File tf = new File(root, op.to);
            c.kind = tf.isFile() ? MODIFY : NEW;
            c.oldText = ""; c.newText = virtual.get(op.path); c.label = "Copy";
            virtual.put(op.to, c.newText);
            out.add(c);
            return;
        }
        if (files.isEmpty()) {
            Change c = new Change(); c.path = op.path; c.kind = SAME; c.error = "Source does not exist"; out.add(c);
            return;
        }
        for (String rel : files) {
            Change c = new Change();
            String to = isDir ? (op.to.endsWith("/") ? op.to : op.to + "/") + rel.substring(op.path.length()).replaceFirst("^/", "") : op.to;
            c.path = to;
            File tf = new File(root, to);
            try { if (!FileUtil.isInside(root, tf)) { c.kind = SAME; c.error = "Target is outside the project"; out.add(c); continue; } }
            catch (Exception e) { c.kind = SAME; c.error = "Bad path"; out.add(c); continue; }
            c.srcFile = new File(root, rel);
            c.asset = true;
            c.binary = binary(rel);
            c.label = tf.isFile() ? "Copy (replace)" : "Copy";
            c.kind = tf.isFile() ? MODIFY : NEW;
            deleted.remove(to);
            out.add(c);
        }
    }

    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] ICON_PX = {48, 72, 96, 144, 192};

    /** app-icon: launcher icons for every density from one picture; old ic_launcher files (any format) are removed first. */
    private static void planIcon(File root, Protocol.Op op, File attachDir, List<Change> out, Set<String> deleted) {
        File src = findAttachment(attachDir, op.attachment);
        if (src == null) {
            Change c = new Change(); c.path = op.path; c.kind = SAME; c.error = "Attachment \"" + op.attachment + "\" not found in this chat"; out.add(c); return;
        }
        if (!FileDigest.isImageExt(FileDigest.ext(src.getName()))) {
            Change c = new Change(); c.path = op.path; c.kind = SAME; c.error = "The attachment is not a picture"; out.add(c); return;
        }
        if (converter == null) {
            Change c = new Change(); c.path = op.path; c.kind = SAME; c.error = "Resizing is not available here"; out.add(c); return;
        }
        String res = op.path.replaceFirst("/$", "");
        File resDir = new File(root, res);
        Set<String> write = new HashSet<>();
        for (String d : DENSITIES) { write.add(res + "/mipmap-" + d + "/ic_launcher.png"); write.add(res + "/mipmap-" + d + "/ic_launcher_round.png"); }
        File[] dirs = resDir.listFiles();
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory() || !d.getName().startsWith("mipmap")) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                java.util.Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName(), base = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
                    String rel = res + "/" + d.getName() + "/" + n;
                    if (!(base.equals("ic_launcher") || base.equals("ic_launcher_round") || base.equals("ic_launcher_foreground") || base.equals("ic_launcher_background"))) continue;
                    if (write.contains(rel)) continue;                 // overwritten below
                    Change c = new Change();
                    c.path = rel; c.kind = DELETE; c.binary = binary(rel); c.label = "Delete old icon";
                    if (!c.binary) { try { c.oldText = FileUtil.readText(f); } catch (IOException ignored) { } }
                    deleted.add(rel);
                    out.add(c);
                }
            }
        }
        for (int i = 0; i < DENSITIES.length; i++) {
            for (int r = 0; r < 2; r++) {
                Change c = new Change();
                c.path = res + "/mipmap-" + DENSITIES[i] + (r == 0 ? "/ic_launcher.png" : "/ic_launcher_round.png");
                c.srcFile = src; c.asset = true;
                c.convSize = ICON_PX[i]; c.convFormat = "png"; c.convRound = r == 1;
                c.kind = new File(root, c.path).isFile() ? MODIFY : NEW;
                c.label = r == 0 ? "App icon " + ICON_PX[i] + " px" : "Round icon " + ICON_PX[i] + " px";
                out.add(c);
            }
        }
    }

    /** Replace exactly one occurrence; falls back to a whitespace-tolerant line match. Returns null on failure. */
    static String applyPatch(String text, String search, String replace) {
        if (search.isEmpty()) return null;
        int idx = text.indexOf(search);
        if (idx >= 0) {
            if (text.indexOf(search, idx + 1) >= 0) return null;
            return text.substring(0, idx) + replace + text.substring(idx + search.length());
        }
        String[] tl = text.split("\n", -1), sl = search.split("\n", -1);
        int found = -1;
        for (int i = 0; i + sl.length <= tl.length; i++) {
            boolean ok = true;
            for (int k = 0; k < sl.length && ok; k++) if (!tl[i + k].trim().equals(sl[k].trim())) ok = false;
            if (ok) { if (found >= 0) return null; found = i; }
        }
        if (found < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < found; i++) sb.append(tl[i]).append('\n');
        sb.append(replace);
        if (found + sl.length < tl.length) sb.append('\n');
        for (int i = found + sl.length; i < tl.length; i++) { sb.append(tl[i]); if (i < tl.length - 1) sb.append('\n'); }
        return sb.toString();
    }

    // ------------------------------------------------------------------ apply / undo

    /** Applies the selected, valid changes. Returns the backup id (or "" when nothing was applied). */
    public static String apply(File root, List<Change> changes, File backupRoot) throws IOException {
        List<Change> todo = new ArrayList<>();
        for (Change c : changes) if (c.selected && c.ok()) todo.add(c);
        if (todo.isEmpty()) return "";
        String id = "b" + System.currentTimeMillis();
        File bdir = new File(backupRoot, id);
        File files = new File(bdir, "files");
        files.mkdirs();
        Set<String> touched = new LinkedHashSet<>();
        for (Change c : todo) { touched.add(c.path); if (c.to != null) touched.add(c.to); }
        List<Object> manifest = new ArrayList<>();
        for (String p : touched) {
            File f = new File(root, p);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", p);
            e.put("existed", f.isFile());
            manifest.add(e);
            if (f.isFile()) FileUtil.copyFile(f, new File(files, p));
        }
        FileUtil.writeText(new File(bdir, "manifest.json"), Jx.write(manifest));
        for (Change c : todo) {
            File f = new File(root, c.path);
            if (c.kind == RENAME && c.srcFile != null) {
                File t = new File(root, c.to);
                t.getParentFile().mkdirs();
                FileUtil.copyFile(c.srcFile, t);
                f.delete();
                pruneEmpty(root, f.getParentFile());
                continue;
            }
            if (c.srcFile != null) {
                f.getParentFile().mkdirs();
                if (converter != null && (c.convSize > 0 || c.convRound || (c.convFormat != null && !c.convFormat.equals(FileDigest.ext(c.srcFile.getName()))))) {
                    converter.convert(c.srcFile, f, c.convSize, c.convFormat, c.convRound);
                } else FileUtil.copyFile(c.srcFile, f);
                continue;
            }
            switch (c.kind) {
                case DELETE: f.delete(); pruneEmpty(root, f.getParentFile()); break;
                case RENAME: {
                    File t = new File(root, c.to);
                    t.getParentFile().mkdirs();
                    FileUtil.writeText(t, c.newText);
                    f.delete();
                    pruneEmpty(root, f.getParentFile());
                    break;
                }
                default:
                    f.getParentFile().mkdirs();
                    FileUtil.writeText(f, c.newText);
            }
        }
        pruneBackups(backupRoot, 15);
        return id;
    }

    /** Restores the files saved by apply(). Returns the touched relative paths. */
    public static List<String> undo(File root, File backupRoot, String id) throws IOException {
        List<String> touched = new ArrayList<>();
        File bdir = new File(backupRoot, id);
        File mf = new File(bdir, "manifest.json");
        if (!mf.isFile()) throw new IOException("This change can no longer be undone (backup was removed).");
        List<Object> arr = MiniJson.array(MiniJson.parse(FileUtil.readText(mf)));
        if (arr == null) throw new IOException("Backup is damaged.");
        for (Object o : arr) {
            Map<String, Object> e = MiniJson.object(o);
            if (e == null) continue;
            String p = MiniJson.str(e.get("path"));
            if (p == null) continue;
            File f = new File(root, p);
            if (Boolean.TRUE.equals(e.get("existed"))) {
                File src = new File(new File(bdir, "files"), p);
                if (src.isFile()) { f.getParentFile().mkdirs(); FileUtil.copyFile(src, f); }
            } else if (f.isFile()) {
                f.delete();
                pruneEmpty(root, f.getParentFile());
            }
            touched.add(p);
        }
        FileUtil.deleteRecursive(bdir);
        return touched;
    }

    private static void pruneEmpty(File root, File dir) {
        int guard = 0;
        while (dir != null && guard++ < 12 && !dir.equals(root)) {
            String[] l = dir.list();
            if (l == null || l.length > 0) return;
            dir.delete();
            dir = dir.getParentFile();
        }
    }

    private static void pruneBackups(File backupRoot, int keep) {
        File[] ds = backupRoot.listFiles();
        if (ds == null || ds.length <= keep) return;
        java.util.Arrays.sort(ds, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        for (int i = 0; i < ds.length - keep; i++) FileUtil.deleteRecursive(ds[i]);
    }
}
