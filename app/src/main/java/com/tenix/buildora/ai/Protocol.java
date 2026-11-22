package com.tenix.buildora.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the structured parts of an AI reply: file blocks, patches, delete/rename lines,
 * library lines, file requests and a project header. Pure Java.
 */
public final class Protocol {
    private Protocol() {}

    public static final int WRITE = 0, PATCH = 1, DELETE = 2, RENAME = 3, ASSET = 4, COPY = 5, IMGCONV = 6, ICON = 7;

    public static final class Op {
        public int type;
        public String path;
        public String to;                       // RENAME / ASSET target path
        public String attachment = "";          // ASSET / IMGCONV / ICON: name of the chat attachment to use
        public int size;                        // IMGCONV: longest side in px (0 = keep)
        public String format = "";              // IMGCONV: png | jpg | webp (empty = from the target extension)
        public String content = "";             // WRITE
        public final List<String[]> patches = new ArrayList<>();   // PATCH: {search, replace}
        public String lang = "";
    }

    /** One agent tool call from a buildora-tool block: name, one-line argument and an optional multi-line body. */
    public static final class Tool {
        public String name = "", arg = "", body = "";
        public Tool() {}
        public Tool(String name, String arg, String body) { this.name = name; this.arg = arg; this.body = body; }
    }

    /** Tools that take no body: several of them may share one block, one per line. */
    public static final Set<String> LINE_TOOLS = new HashSet<>(java.util.Arrays.asList(
            "list_files", "read_file", "search_text", "find_files", "file_info", "find_usages", "list_resources", "project_info",
            "class_outline", "project_stats", "delete_path", "move_path", "copy_path", "make_dir", "rename_class", "rename_resource",
            "diagnostics", "build_project", "search_web", "fetch_url", "read_crash_logs", "add_dependency", "remove_dependency",
            "set_version", "git_status", "git_commit", "undo_last", "install_app", "replace_all"));
    public static final Set<String> BODY_TOOLS = new HashSet<>(java.util.Arrays.asList(
            "write_file", "append_file", "replace_lines", "insert_lines", "run_shell", "run_python", "plan"));

    public static boolean isTool(String name) { return LINE_TOOLS.contains(name) || BODY_TOOLS.contains(name); }

    public static final class Parsed {
        public final List<Tool> tools = new ArrayList<>();
        public boolean hasTools() { return !tools.isEmpty(); }
        public final List<Op> ops = new ArrayList<>();
        public final List<String> deps = new ArrayList<>();
        public final List<String> reads = new ArrayList<>();
        public String projectName = "", projectPackage = "";
        public boolean hasProject;
        public boolean any() { return !ops.isEmpty() || !deps.isEmpty() || hasProject; }
    }

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*(.*)$");
    private static final Pattern KV = Pattern.compile("(path|file|delete|rename)\\s*=\\s*[\"']?([^\\s\"']+)[\"']?");
    private static final Pattern PATHLIKE = Pattern.compile("^[\\w.\\-$@+/\\\\]+\\.[A-Za-z0-9]{1,8}$");
    private static final Pattern COPYLINE = Pattern.compile("(?i)^copy\\s+(.+?)\\s*(?:->|=>)\\s*(\\S.*)$");
    private static final Pattern IMGLINE = Pattern.compile("(?i)^import-image\\s+(.+?)\\s*(?:->|=>)\\s*(\\S+)((?:\\s+\\w+=\\S+)*)\\s*$");
    private static final Pattern ICONLINE = Pattern.compile("(?i)^app-icon\\s+(.+?)(?:\\s*(?:->|=>)\\s*(\\S+))?\\s*$");
    private static final Pattern ASSETLINE = Pattern.compile("(?i)^(?:import|use|copy-attachment)\\s+(.+?)\\s*(?:->|=>)\\s*(\\S.*)$");
    private static final Pattern DEP = Pattern.compile("^[\\w.\\-]+:[\\w.\\-]+(:[\\w.\\-+]+)?$");
    private static final Set<String> LANGS = new HashSet<>();
    private static final Set<String> EXTS = new HashSet<>();
    static {
        for (String s : new String[]{"java", "xml", "json", "gradle", "kotlin", "kt", "text", "txt", "md", "markdown", "html", "css", "js",
                "properties", "bash", "sh", "shell", "diff", "patch", "yaml", "yml", "smali", "c", "cpp", "python", "py", "groovy", "sql", "toml", "none"}) LANGS.add(s);
        for (String s : new String[]{"java", "xml", "json", "gradle", "kt", "txt", "md", "html", "css", "js", "properties", "pro", "yml", "yaml", "smali", "cfg", "ini", "csv", "svg", "cpp", "c", "h"}) EXTS.add(s);
    }

    public static Parsed parse(String reply) {
        Parsed out = new Parsed();
        if (reply == null) return out;
        String[] lines = reply.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        String prevLine = "";
        while (i < lines.length) {
            Matcher fm = FENCE.matcher(lines[i]);
            if (!fm.matches()) {
                if (!lines[i].trim().isEmpty()) prevLine = lines[i];
                i++;
                continue;
            }
            String fence = fm.group(1);
            String info = fm.group(2).trim();
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            boolean closed = false;
            while (j < lines.length) {
                String t = lines[j].trim();
                if (t.length() >= fence.length() && t.charAt(0) == fence.charAt(0) && t.replace(String.valueOf(fence.charAt(0)), "").isEmpty()) { closed = true; break; }
                body.append(lines[j]).append('\n');
                j++;
            }
            String content = body.toString();
            handle(out, info, content, prevLine, closed);
            i = closed ? j + 1 : j;
            prevLine = "";
        }
        return out;
    }

    private static void handle(Parsed out, String info, String content, String prevLine, boolean closed) {
        String lang = info.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (lang.equals("buildora-deps")) {
            for (String l : content.split("\n")) { String d = l.trim(); if (DEP.matcher(d).matches() && !out.deps.contains(d)) out.deps.add(d); }
            return;
        }
        if (lang.equals("buildora-read")) {
            for (String l : content.split("\n")) {
                String t = l.trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("attach:")) { String a = "attach:" + attachName(t); if (a.length() > 7 && !out.reads.contains(a)) out.reads.add(a); continue; }
                String p = clean(l);
                if (p != null && !out.reads.contains(p)) out.reads.add(p);
            }
            return;
        }
        if (lang.equals("buildora-project")) {
            out.hasProject = true;
            for (String l : content.split("\n")) {
                int c = l.indexOf(':');
                if (c < 0) continue;
                String k = l.substring(0, c).trim().toLowerCase(Locale.ROOT), v = l.substring(c + 1).trim();
                if (k.equals("name") || k.equals("app")) out.projectName = v;
                else if (k.equals("package")) out.projectPackage = v;
            }
            return;
        }
        if (lang.equals("buildora-tool") || lang.equals("buildora-tools")) {
            parseTools(out, content);
            return;
        }
        if (lang.equals("buildora-ops")) {
            for (String l : content.split("\n")) {
                String t = l.trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("delete ")) {
                    String p = clean(t.substring(7));
                    if (p != null) { Op o = new Op(); o.type = DELETE; o.path = p; out.ops.add(o); }
                } else if (IMGLINE.matcher(t).matches()) {
                    Matcher im = IMGLINE.matcher(t);
                    im.matches();
                    String src = attachName(im.group(1)), dst = clean(im.group(2));
                    if (!src.isEmpty() && dst != null) {
                        Op o = new Op(); o.type = IMGCONV; o.attachment = src; o.path = dst; o.to = dst;
                        Matcher kv2 = Pattern.compile("(\\w+)=(\\S+)").matcher(im.group(3));
                        while (kv2.find()) {
                            String k = kv2.group(1).toLowerCase(Locale.ROOT), v = kv2.group(2);
                            if (k.equals("size")) { try { o.size = Math.max(0, Math.min(4096, Integer.parseInt(v))); } catch (NumberFormatException ignored) { } }
                            else if (k.equals("format")) o.format = v.toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
                        }
                        out.ops.add(o);
                    }
                } else if (ICONLINE.matcher(t).matches()) {
                    Matcher ic = ICONLINE.matcher(t);
                    ic.matches();
                    String src = attachName(ic.group(1));
                    String res = ic.group(2) == null ? "app/src/main/res" : clean(ic.group(2));
                    if (!src.isEmpty() && res != null) { Op o = new Op(); o.type = ICON; o.attachment = src; o.path = res; o.to = res; out.ops.add(o); }
                } else if (COPYLINE.matcher(t).matches()) {
                    Matcher cm = COPYLINE.matcher(t);
                    cm.matches();
                    String a = clean(cm.group(1)), b = clean(cm.group(2));
                    if (a != null && b != null) { Op o = new Op(); o.type = COPY; o.path = a; o.to = b; out.ops.add(o); }
                } else if (ASSETLINE.matcher(t).matches()) {
                    Matcher am = ASSETLINE.matcher(t);
                    am.matches();
                    String src = attachName(am.group(1)), dst = clean(am.group(2));
                    if (!src.isEmpty() && dst != null) { Op o = new Op(); o.type = ASSET; o.attachment = src; o.path = dst; o.to = dst; out.ops.add(o); }
                } else if (t.toLowerCase(Locale.ROOT).startsWith("rename ") || t.toLowerCase(Locale.ROOT).startsWith("move ")) {
                    String rest = t.substring(t.indexOf(' ') + 1);
                    String[] parts = rest.split("\\s*(->|=>|\\s)\\s*");
                    if (parts.length >= 2) {
                        String a = clean(parts[0]), b = clean(parts[parts.length - 1]);
                        if (a != null && b != null) { Op o = new Op(); o.type = RENAME; o.path = a; o.to = b; out.ops.add(o); }
                    }
                }
            }
            return;
        }
        String path = null;
        boolean del = false;
        Matcher kv = KV.matcher(info);
        while (kv.find()) {
            if (kv.group(1).equals("delete")) { del = true; path = clean(kv.group(2)); }
            else if (path == null) path = clean(kv.group(2));
        }
        if (path == null) {
            // lang:path  or  path alone
            for (String tok : info.split("\\s+")) {
                int c = tok.indexOf(':');
                String cand = c > 0 && c < tok.length() - 1 ? tok.substring(c + 1) : tok;
                String p = clean(cand);
                if (p != null && looksLikePath(p) && !LANGS.contains(p.toLowerCase(Locale.ROOT))) { path = p; break; }
            }
        }
        if (path == null && !prevLine.isEmpty()) {
            String p = fromHeading(prevLine);
            if (p != null) path = p;
        }
        if (path == null) return;
        Op o = new Op();
        o.path = path;
        o.lang = lang;
        if (del) { o.type = DELETE; out.ops.add(o); return; }
        if (!closed) return;     // an unfinished block is not safe to apply
        if (lang.equals("patch") || content.contains("<<<<<<< SEARCH")) {
            o.type = PATCH;
            parsePatches(o, content);
            if (o.patches.isEmpty()) return;
        } else {
            o.type = WRITE;
            o.content = content;
        }
        out.ops.add(o);
    }

    private static String[] splitName(String line) {
        String t = line.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("tool:")) t = t.substring(5).trim();
        t = t.replace("`", "");
        int i = 0;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) i++;
        String name = t.substring(0, i).toLowerCase(Locale.ROOT);
        String rest = t.substring(i).trim();
        if (rest.startsWith(":") || rest.startsWith("(")) { rest = rest.substring(1).trim(); if (rest.endsWith(")")) rest = rest.substring(0, rest.length() - 1).trim(); }
        return new String[]{name, rest};
    }

    static void parseTools(Parsed out, String content) {
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].trim().isEmpty()) first++;
        if (first >= lines.length) return;
        String[] head = splitName(lines[first]);
        if (!isTool(head[0])) return;
        if (LINE_TOOLS.contains(head[0])) {
            // one call per line (every line must start with a known read/line tool)
            for (int i = first; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                String[] nv = splitName(lines[i]);
                if (!LINE_TOOLS.contains(nv[0])) continue;
                out.tools.add(new Tool(nv[0], nv[1], ""));
            }
            return;
        }
        StringBuilder body = new StringBuilder();
        int last = lines.length - 1;
        if (last > first && lines[last].isEmpty()) last--;      // content ends with a newline
        for (int i = first + 1; i <= last; i++) { if (i > first + 1) body.append('\n'); body.append(lines[i]); }
        String b = body.toString();
        // single-line shell/python calls may put everything on the first line
        out.tools.add(new Tool(head[0], head[1], b));
    }

    /** Attachment names are flat: drop quotes, an "attach:" prefix and any folder part. */
    static String attachName(String raw) {
        String n = raw.trim().replace("`", "").replace("\"", "").replace("'", "");
        if (n.toLowerCase(Locale.ROOT).startsWith("attach:")) n = n.substring(7);
        n = n.replace('\\', '/');
        int sl = n.lastIndexOf('/');
        if (sl >= 0) n = n.substring(sl + 1);
        return n.trim().equals("..") ? "" : n.trim();
    }

    private static void parsePatches(Op o, String content) {
        String[] ls = content.split("\n", -1);
        int i = 0;
        while (i < ls.length) {
            if (ls[i].trim().equals("<<<<<<< SEARCH")) {
                StringBuilder a = new StringBuilder(), b = new StringBuilder();
                int j = i + 1;
                while (j < ls.length && !ls[j].trim().equals("=======")) { a.append(ls[j]).append('\n'); j++; }
                j++;
                while (j < ls.length && !ls[j].trim().equals(">>>>>>> REPLACE")) { b.append(ls[j]).append('\n'); j++; }
                if (j < ls.length) o.patches.add(new String[]{trimOneNl(a.toString()), trimOneNl(b.toString())});
                i = j + 1;
            } else i++;
        }
    }

    private static String trimOneNl(String s) { return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s; }

    static String fromHeading(String line) {
        String t = line.trim();
        t = t.replaceAll("^#{1,6}\\s*", "").replaceAll("^[-*]\\s+", "");
        t = t.replaceAll("(?i)^(file|path|filename|new file|updated file|create|update|edit|modify)\\s*[:\\-]?\\s*", "");
        t = t.replace("**", "").replace("`", "").replace("__", "").trim();
        if (t.endsWith(":")) t = t.substring(0, t.length() - 1).trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        String p = clean(t);
        return p != null && looksLikePath(p) ? p : null;
    }

    static boolean looksLikePath(String p) {
        if (p.contains("://") || !PATHLIKE.matcher(p).matches()) return false;
        String ext = p.substring(p.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return EXTS.contains(ext) && (p.contains("/") || !LANGS.contains(p.toLowerCase(Locale.ROOT)));
    }

    /** Normalises a path from a reply; returns null when it is unsafe or empty. */
    public static String clean(String raw) {
        if (raw == null) return null;
        String p = raw.trim().replace('\\', '/');
        p = p.replace("`", "").replace("\"", "").replace("'", "");
        while (p.startsWith("./") || p.startsWith("/")) p = p.startsWith("/") ? p.substring(1) : p.substring(2);
        if (p.endsWith(",") || p.endsWith(";")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return null;
        for (String seg : p.split("/")) {
            if (seg.equals("..")) return null;
        }
        String low = p.toLowerCase(Locale.ROOT);
        if (low.startsWith(".git/") || low.equals(".git") || low.startsWith(".buildora/") || low.startsWith("../")) return null;
        return p;
    }
}
