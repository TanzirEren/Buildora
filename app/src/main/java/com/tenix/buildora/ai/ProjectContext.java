package com.tenix.buildora.ai;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the project snapshot that is sent to the model (file tree + selected file contents). Pure Java. */
public final class ProjectContext {
    private ProjectContext() {}

    private static final String[] SKIP_DIRS = {".git", ".buildora", "build", "outputs", ".gradle", ".idea", "node_modules", "libs"};
    private static final String[] TEXT_EXT = {"java", "xml", "json", "gradle", "properties", "pro", "md", "txt", "html", "css", "js", "yml", "yaml", "smali", "cfg"};

    public static boolean isText(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int d = n.lastIndexOf('.');
        if (d < 0) return false;
        String e = n.substring(d + 1);
        for (String t : TEXT_EXT) if (t.equals(e)) return true;
        return false;
    }

    public static List<File> listFiles(File root, int max) {
        List<File> out = new ArrayList<>();
        walk(root, out, 0, max);
        return out;
    }

    private static void walk(File dir, List<File> out, int depth, int max) {
        if (depth > 14 || out.size() >= max) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? 1 : -1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : fs) {
            if (out.size() >= max) return;
            if (f.isDirectory()) {
                boolean skip = false;
                for (String s : SKIP_DIRS) if (f.getName().equals(s)) skip = true;
                if (!skip) walk(f, out, depth + 1, max);
            } else if (!f.getName().startsWith(".") || f.getName().equals(".gitignore")) out.add(f);
        }
    }

    private static int rank(File f) {
        String n = f.getName();
        if (n.equals("AndroidManifest.xml")) return 0;
        if (n.endsWith(".java")) return 1;
        if (n.endsWith(".xml") && f.getPath().contains("/layout")) return 2;
        if (n.endsWith(".xml")) return 3;
        if (n.endsWith(".gradle") || n.endsWith(".json")) return 4;
        return 5;
    }

    /**
     * @param priority relative paths that must come first (open files, files with errors, attachments)
     * @param budget   maximum characters for file contents
     */
    public static String build(File root, String name, String pkg, int minSdk, int targetSdk, List<String> priority, int budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("<project name=\"").append(name).append("\" package=\"").append(pkg)
                .append("\" minSdk=\"").append(minSdk).append("\" targetSdk=\"").append(targetSdk).append("\">\n<tree>\n");
        List<File> all = listFiles(root, 400);
        for (File f : all) sb.append(FileUtil.relPath(root, f)).append(" (").append(f.length()).append(")\n");
        sb.append("</tree>\n");
        Set<String> done = new LinkedHashSet<>();
        int used = 0;
        List<File> order = new ArrayList<>();
        if (priority != null) for (String p : priority) {
            File f = new File(root, p);
            if (f.isFile() && isText(f) && done.add(p)) order.add(f);
        }
        List<File> rest = new ArrayList<>();
        for (File f : all) {
            String rel = FileUtil.relPath(root, f);
            if (isText(f) && !done.contains(rel)) rest.add(f);
        }
        Collections.sort(rest, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return ra - rb;
                return Long.compare(a.length(), b.length());
            }
        });
        order.addAll(rest);
        int perFile = Math.max(4000, Math.min(24000, budget / 3));
        for (File f : order) {
            if (used >= budget) break;
            String rel = FileUtil.relPath(root, f);
            String t;
            try { t = FileUtil.readText(f); } catch (Exception e) { continue; }
            boolean pri = priority != null && priority.contains(rel);
            int lim = pri ? Math.min(perFile * 2, budget - used) : Math.min(perFile, budget - used);
            if (!pri && lim < 600) break;
            if (t.length() > lim) t = t.substring(0, Math.max(0, lim)) + "\n… [truncated, " + (t.length() - lim) + " more characters]";
            sb.append("<file path=\"").append(rel).append("\">\n").append(t);
            if (!t.endsWith("\n")) sb.append('\n');
            sb.append("</file>\n");
            used += t.length();
        }
        sb.append("</project>");
        return sb.toString();
    }

    /** Lines around each error, so the model sees exactly what failed. */
    public static String errorSnippets(File root, List<String[]> diags) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String[] d : diags) {          // {file, line, message}
            if (shown++ >= 25) break;
            sb.append("- ").append(d[0] == null ? "" : rel(root, d[0])).append(d[1] != null && !d[1].isEmpty() && !d[1].equals("0") ? ":" + d[1] : "").append("  ").append(d[2]).append('\n');
            try {
                int line = Integer.parseInt(d[1]);
                File f = new File(d[0]);
                if (line > 0 && f.isFile()) {
                    String[] ls = FileUtil.readText(f).split("\n", -1);
                    for (int i = Math.max(1, line - 2); i <= Math.min(ls.length, line + 2); i++)
                        sb.append(i == line ? "  > " : "    ").append(i).append(": ").append(ls[i - 1]).append('\n');
                }
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    private static String rel(File root, String abs) {
        File f = new File(abs);
        return f.isAbsolute() && FileUtil.isInside(root, f) ? FileUtil.relPath(root, f) : abs;
    }

    public static int estimateTokens(String s) { return s == null ? 0 : (s.length() + 3) / 4; }
}
