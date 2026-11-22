package com.tenix.buildora.ai;

import com.tenix.buildora.assist.ClassIndex;
import com.tenix.buildora.assist.Refactor;
import com.tenix.buildora.assist.ResourceIndex;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.Project;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Buildora AI agent's toolbox: 35 tools the AI calls with buildora-tool blocks. File tools go through
 * {@link FileOps} so every change has a backup and can be undone. Everything that needs Android (build,
 * install, git, crash reports, permission prompts) goes through {@link Host}.
 */
public final class AgentTools {

    /** Things only the app can do. All methods are called on a worker thread and may block. */
    public interface Host {
        /** Builds the project and returns a text report (success + output, or the errors with code lines). */
        String build(boolean release, boolean aab);
        String crashReports(String pkg);
        String git(String op, String arg);
        String install();
        /** Asks the user before a risky tool runs (shell, python). Blocks until answered. */
        boolean approve(String title, String detail);
    }

    public static final class Config {
        public int shellMode = 0;          // 0 ask each time, 1 always allow, 2 never
        public boolean web = true;
        public File androidJar;            // for import checks in diagnostics (optional)
        public File home;                  // HOME for shell / python (optional)
    }

    public static final class Result {
        public String name = "", arg = "";
        public boolean ok = true;
        public String output = "";
        public final List<String> touched = new ArrayList<>();
        /** One short line for the chat. */
        public String summary() {
            String first = output.trim();
            int nl = first.indexOf('\n');
            if (nl > 0) first = first.substring(0, nl);
            if (first.length() > 70) first = first.substring(0, 70) + "…";
            String a = arg.replace('\n', ' ').trim();
            if (a.length() > 44) a = a.substring(0, 44) + "…";
            return (ok ? "✓ " : "✗ ") + name + (a.isEmpty() ? "" : " " + a) + (first.isEmpty() ? "" : "  →  " + first);
        }
    }

    public static final String[] NAMES = {
            "list_files", "read_file", "search_text", "find_files", "file_info", "find_usages", "list_resources", "project_info",
            "class_outline", "project_stats", "write_file", "append_file", "replace_lines", "insert_lines", "replace_all",
            "make_dir", "delete_path", "move_path", "copy_path", "rename_class", "rename_resource", "diagnostics",
            "build_project", "run_shell", "run_python", "search_web", "fetch_url", "read_crash_logs", "add_dependency",
            "remove_dependency", "set_version", "git_status", "git_commit", "undo_last", "install_app", "plan"};

    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(".git", ".buildora", "build", ".gradle", "node_modules", "outputs"));
    private static final int MAX_OUT = 7000;

    private final File root;
    private final File backupRoot;
    private final Host host;
    private final Config cfg;
    private final List<String> backups = new ArrayList<>();
    private volatile Process proc;
    private volatile boolean cancelled;

    public AgentTools(File root, File backupRoot, Host host, Config cfg) {
        this.root = root;
        this.backupRoot = backupRoot;
        this.host = host;
        this.cfg = cfg == null ? new Config() : cfg;
    }

    /** Backup ids created by this agent run (newest last). */
    public List<String> backups() { return backups; }

    public void cancel() {
        cancelled = true;
        Process p = proc;
        if (p != null) p.destroy();
    }

    // ------------------------------------------------------------------ entry point

    public Result run(Protocol.Tool t) {
        Result r = new Result();
        r.name = t.name;
        r.arg = t.arg;
        if (cancelled) { r.ok = false; r.output = "Stopped by the user."; return r; }
        try {
            String out;
            switch (t.name) {
                case "list_files": out = listFiles(t.arg); break;
                case "read_file": out = readFile(t.arg); break;
                case "search_text": out = searchText(t.arg); break;
                case "find_files": out = findFiles(t.arg); break;
                case "file_info": out = fileInfo(t.arg); break;
                case "find_usages": out = findUsages(t.arg); break;
                case "list_resources": out = listResources(t.arg); break;
                case "project_info": out = projectInfo(); break;
                case "class_outline": out = classOutline(t.arg); break;
                case "project_stats": out = projectStats(); break;
                case "write_file": out = writeFile(r, t.arg, t.body, false); break;
                case "append_file": out = writeFile(r, t.arg, t.body, true); break;
                case "replace_lines": out = replaceLines(r, t.arg, t.body); break;
                case "insert_lines": out = insertLines(r, t.arg, t.body); break;
                case "replace_all": out = replaceAll(r, t.arg); break;
                case "make_dir": out = makeDir(t.arg); break;
                case "delete_path": out = simpleOp(r, Protocol.DELETE, t.arg); break;
                case "move_path": out = simpleOp(r, Protocol.RENAME, t.arg); break;
                case "copy_path": out = simpleOp(r, Protocol.COPY, t.arg); break;
                case "rename_class": out = renameClass(r, t.arg); break;
                case "rename_resource": out = renameResource(r, t.arg); break;
                case "diagnostics": out = diagnostics(t.arg); break;
                case "build_project": out = buildProject(t.arg); break;
                case "run_shell": out = runShell(t.arg, t.body); break;
                case "run_python": out = runPython(t.arg, t.body); break;
                case "search_web": out = searchWeb(t.arg); break;
                case "fetch_url": out = fetchUrl(t.arg); break;
                case "read_crash_logs": out = host == null ? "Not available." : host.crashReports(t.arg.trim()); break;
                case "add_dependency": out = addDependency(t.arg); break;
                case "remove_dependency": out = removeDependency(t.arg); break;
                case "set_version": out = setVersion(t.arg); break;
                case "git_status": out = host == null ? "Not available." : host.git("status", ""); break;
                case "git_commit": out = host == null ? "Not available." : host.git("commit", t.arg.trim()); break;
                case "undo_last": out = undoLast(r); break;
                case "install_app": out = host == null ? "Not available." : host.install(); break;
                case "plan": out = plan(t.arg, t.body); break;
                default: r.ok = false; out = "Unknown tool '" + t.name + "'. Available: " + String.join(", ", NAMES);
            }
            if (out != null && out.startsWith("ERROR:")) { r.ok = false; out = out.substring(6).trim(); }
            r.output = out == null ? "" : out;
        } catch (Throwable e) {
            r.ok = false;
            r.output = t.name + " failed: " + e;
        }
        if (r.output.length() > MAX_OUT) r.output = r.output.substring(0, MAX_OUT * 2 / 3) + "\n… [output cut] …\n" + r.output.substring(r.output.length() - MAX_OUT / 3);
        return r;
    }

    /** The text sent back to the model for one round of tool calls. */
    public static String toModelText(List<Result> results) {
        StringBuilder sb = new StringBuilder("<tool_results>\n");
        int i = 1;
        for (Result r : results) {
            sb.append("[").append(i++).append("] ").append(r.name);
            if (!r.arg.isEmpty()) sb.append(' ').append(r.arg.replace('\n', ' ').trim().length() > 160 ? r.arg.replace('\n', ' ').trim().substring(0, 160) + "…" : r.arg.replace('\n', ' ').trim());
            sb.append(r.ok ? "  (ok)\n" : "  (FAILED)\n").append(r.output).append("\n\n");
        }
        sb.append("</tool_results>\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ path helpers

    private File file(String rel) {
        String p = Protocol.clean(rel == null ? "" : rel.trim());
        if (p == null) return null;
        File f = new File(root, p);
        return FileUtil.isInside(root, f) || f.equals(root) ? f : null;
    }

    private String rel(File f) { return FileUtil.relPath(root, f); }

    private static String firstToken(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String afterFirst(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i).trim();
    }

    private List<File> allFiles(File dir, int max) {
        List<File> out = new ArrayList<>();
        collect(dir, out, 0, max);
        return out;
    }

    private void collect(File dir, List<File> out, int depth, int max) {
        if (depth > 12 || out.size() >= max) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (out.size() >= max) return;
            if (f.isDirectory()) { if (!SKIP.contains(f.getName())) collect(f, out, depth + 1, max); }
            else out.add(f);
        }
    }

    private List<File> textFiles(File dir) {
        List<File> out = new ArrayList<>();
        for (File f : allFiles(dir, 4000)) if (ProjectContext.isText(f) && f.length() < 600000) out.add(f);
        return out;
    }

    private List<File> byExt(String ext) {
        List<File> out = new ArrayList<>();
        for (File f : allFiles(root, 6000)) if (f.getName().endsWith(ext)) out.add(f);
        return out;
    }

    private List<File> resDirs() {
        List<File> out = new ArrayList<>();
        findDirs(root, "res", out, 0);
        return out;
    }

    private void findDirs(File dir, String name, List<File> out, int depth) {
        if (depth > 7) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isDirectory() || SKIP.contains(f.getName())) continue;
            if (f.getName().equals(name)) out.add(f); else findDirs(f, name, out, depth + 1);
        }
    }

    private static String strip(String t) { return Refactor.strip(t); }

    private static int lineOf(String text, int pos) {
        int n = 1;
        for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    // ------------------------------------------------------------------ read tools

    private String listFiles(String arg) {
        File dir = arg.trim().isEmpty() || arg.trim().equals(".") ? root : file(arg);
        if (dir == null || !dir.isDirectory()) return "ERROR: not a folder: " + arg;
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        tree(dir, "", 0, sb, count);
        if (count[0] >= 400) sb.append("… (more entries not shown; list a subfolder)\n");
        return sb.toString().trim().isEmpty() ? "(empty)" : sb.toString();
    }

    private void tree(File dir, String indent, int depth, StringBuilder sb, int[] count) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 9) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (count[0] >= 400) return;
            if (f.isDirectory()) {
                if (SKIP.contains(f.getName())) continue;
                count[0]++;
                sb.append(indent).append(f.getName()).append("/\n");
                tree(f, indent + "  ", depth + 1, sb, count);
            } else {
                count[0]++;
                sb.append(indent).append(f.getName()).append("  (").append(FileUtil.human(f.length())).append(")\n");
            }
        }
    }

    private String readFile(String arg) throws IOException {
        String a = arg.trim();
        int from = 1, to = Integer.MAX_VALUE;
        Matcher rm = Pattern.compile("^(.*?)\\s+(\\d+)(?:\\s*[-:]\\s*(\\d+))?$").matcher(a);
        if (rm.matches()) {
            a = rm.group(1);
            from = Integer.parseInt(rm.group(2));
            to = rm.group(3) == null ? from + 399 : Integer.parseInt(rm.group(3));
        }
        File f = file(a);
        if (f == null || !f.isFile()) return "ERROR: file not found: " + a + ". Use list_files or find_files to look for it.";
        if (!ProjectContext.isText(f) && FileUtil.looksBinary(f)) return "Binary file (" + FileUtil.human(f.length()) + "): " + a + ". It cannot be shown as text.";
        String text = FileUtil.readText(f);
        String[] lines = text.split("\n", -1);
        int total = lines.length - (text.endsWith("\n") ? 1 : 0);
        if (from < 1) from = 1;
        if (to - from > 599) to = from + 599;
        StringBuilder sb = new StringBuilder();
        sb.append(rel(f)).append("  (").append(total).append(" lines)\n");
        int end = Math.min(to, total);
        for (int i = from; i <= end; i++) {
            sb.append(i).append("| ").append(lines[i - 1]).append('\n');
            if (sb.length() > 24000) { sb.append("… cut; continue with read_file ").append(rel(f)).append(' ').append(i + 1).append("-").append(end).append('\n'); return sb.toString(); }
        }
        if (end < total) sb.append("… ").append(total - end).append(" more lines; use read_file ").append(rel(f)).append(' ').append(end + 1).append("-").append(Math.min(total, end + 400)).append('\n');
        return sb.toString();
    }

    private String searchText(String arg) throws IOException {
        String a = arg.trim();
        String where = "";
        int in = a.lastIndexOf(" in ");
        if (in > 0) { where = a.substring(in + 4).trim(); a = a.substring(0, in).trim(); }
        if (a.length() >= 2 && (a.startsWith("\"") && a.endsWith("\"") || a.startsWith("'") && a.endsWith("'"))) a = a.substring(1, a.length() - 1);
        if (a.isEmpty()) return "ERROR: give a text or regex to search for.";
        File base = where.isEmpty() ? root : file(where);
        if (base == null || !base.exists()) return "ERROR: path not found: " + where;
        Pattern p;
        try { p = Pattern.compile(a, Pattern.CASE_INSENSITIVE); } catch (RuntimeException e) { p = Pattern.compile(Pattern.quote(a), Pattern.CASE_INSENSITIVE); }
        List<File> files = base.isFile() ? Arrays.asList(base) : textFiles(base);
        StringBuilder sb = new StringBuilder();
        int hits = 0, filesHit = 0;
        for (File f : files) {
            String[] lines = FileUtil.readText(f).split("\n", -1);
            boolean any = false;
            for (int i = 0; i < lines.length; i++) {
                if (p.matcher(lines[i]).find()) {
                    if (hits++ < 60) { sb.append(rel(f)).append(':').append(i + 1).append(": ").append(lines[i].trim().length() > 160 ? lines[i].trim().substring(0, 160) + "…" : lines[i].trim()).append('\n'); }
                    any = true;
                }
            }
            if (any) filesHit++;
        }
        if (hits == 0) return "No matches for: " + a;
        return hits + " match" + (hits == 1 ? "" : "es") + " in " + filesHit + " file" + (filesHit == 1 ? "" : "s") + (hits > 60 ? " (first 60 shown)" : "") + "\n" + sb;
    }

    private static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*"); else if (c == '?') sb.append('.'); else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private String findFiles(String arg) {
        String g = arg.trim();
        if (g.isEmpty()) return "ERROR: give a file name or pattern, for example Main*.java";
        Pattern p = glob(g.contains("*") || g.contains("?") ? g : "*" + g + "*");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (File f : allFiles(root, 8000)) {
            String r = rel(f);
            if (p.matcher(f.getName()).matches() || (g.contains("/") && p.matcher(r).matches())) { if (n++ < 80) sb.append(r).append('\n'); }
        }
        return n == 0 ? "No files match: " + g : n + " file" + (n == 1 ? "" : "s") + "\n" + sb;
    }

    private String fileInfo(String arg) throws IOException {
        File f = file(arg);
        if (f == null || !f.exists()) return "Does not exist: " + arg;
        StringBuilder sb = new StringBuilder();
        sb.append(rel(f)).append(f.isDirectory() ? "  (folder)" : "  (file)").append("\nsize: ").append(FileUtil.human(FileUtil.sizeOf(f)))
                .append("\nmodified: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(f.lastModified())));
        if (f.isFile() && ProjectContext.isText(f)) {
            String t = FileUtil.readText(f);
            sb.append("\nlines: ").append(t.split("\n", -1).length);
            if (f.getName().endsWith(".java")) {
                Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(t);
                if (pm.find()) sb.append("\npackage: ").append(pm.group(1));
                Matcher cm = Pattern.compile("(?m)^\\s*(?:public\\s+|abstract\\s+|final\\s+)*(class|interface|enum)\\s+(\\w+)").matcher(strip(t));
                while (cm.find()) sb.append("\n").append(cm.group(1)).append(": ").append(cm.group(2));
            }
        } else if (f.isDirectory()) {
            File[] c = f.listFiles();
            sb.append("\nentries: ").append(c == null ? 0 : c.length);
        }
        return sb.toString();
    }

    private String findUsages(String arg) throws IOException {
        String sym = arg.trim();
        if (sym.isEmpty()) return "ERROR: give a class, method, field or resource name.";
        Pattern p = Pattern.compile("(?<![\\w$])" + Pattern.quote(sym) + "(?![\\w$])");
        StringBuilder sb = new StringBuilder();
        int total = 0, files = 0;
        for (File f : textFiles(root)) {
            String t = FileUtil.readText(f);
            String scan = f.getName().endsWith(".java") ? strip(t) : t;
            Matcher m = p.matcher(scan);
            int c = 0;
            StringBuilder lines = new StringBuilder();
            while (m.find()) {
                c++;
                if (c <= 3) { int ln = lineOf(t, m.start()); String[] ls = t.split("\n", -1); lines.append("    ").append(ln).append(": ").append(ls[Math.min(ln - 1, ls.length - 1)].trim()).append('\n'); }
            }
            if (c > 0) { files++; total += c; if (files <= 25) sb.append(rel(f)).append("  (").append(c).append(")\n").append(lines); }
        }
        return total == 0 ? "No usages of " + sym : total + " usage" + (total == 1 ? "" : "s") + " in " + files + " file" + (files == 1 ? "" : "s") + "\n" + sb;
    }

    private String listResources(String arg) {
        ResourceIndex ri = ResourceIndex.scan(resDirs());
        String type = arg.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (String t : ResourceIndex.TYPES) {
            if (!type.isEmpty() && !t.equals(type)) continue;
            List<String> n = ri.names(t);
            if (n.isEmpty()) continue;
            sb.append(t).append(" (").append(n.size()).append("): ");
            int k = 0;
            for (String s : n) { if (k++ >= 60) { sb.append("…"); break; } sb.append(s).append(' '); }
            sb.append('\n');
        }
        return sb.length() == 0 ? "No resources found" + (type.isEmpty() ? "." : " of type " + type) : sb.toString();
    }

    private String projectInfo() {
        Project p = Project.load(root);
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(p.name).append("\npackage: ").append(p.packageName).append("\nversion: ").append(p.versionName).append(" (code ").append(p.versionCode)
                .append(")\nminSdk ").append(p.minSdk).append(", targetSdk ").append(p.targetSdk)
                .append("\nactive variant: ").append(p.activeVariant().name);
        sb.append("\nbuild types:");
        for (com.tenix.buildora.core.BuildTypeCfg b : p.buildTypes) sb.append(' ').append(b.name).append(b.debuggable ? "(debug)" : "").append(b.minify ? "(R8)" : "");
        if (!p.flavors.isEmpty()) { sb.append("\nflavors:"); for (com.tenix.buildora.core.FlavorCfg f : p.flavors) sb.append(' ').append(f.name); }
        sb.append("\nlibraries: ").append(p.dependencies.isEmpty() ? "none" : String.join(", ", p.dependencies));
        if (!p.modules.isEmpty()) { sb.append("\nmodules:"); for (com.tenix.buildora.core.ModuleCfg m : p.modules) sb.append(' ').append(m.name); }
        return sb.toString();
    }

    private String classOutline(String arg) throws IOException {
        File f = file(arg);
        if (f == null || !f.isFile() || !f.getName().endsWith(".java")) return "ERROR: give a .java file path.";
        String text = FileUtil.readText(f);
        String s = strip(text);
        String[] lines = s.split("\n", -1);
        Pattern method = Pattern.compile("^\\s*(?:@\\w+\\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\\s+)*(?:<[^>]+>\\s*)?[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(([^)]*)\\)?.*$");
        Pattern type = Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract)\\s+)*(class|interface|enum)\\s+(\\w+).*$");
        Pattern field = Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|volatile|transient)\\s+)+([\\w<>\\[\\],.? ]+?)\\s+(\\w+)\\s*(?:=.*|;)\\s*$");
        String[] orig = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(rel(f)).append('\n');
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (depth <= 3) {
                String o = i < orig.length ? orig[i].trim() : ln.trim();
                Matcher tm = type.matcher(ln);
                if (tm.matches()) sb.append(i + 1).append(": ").append(o.length() > 120 ? o.substring(0, 120) : o).append('\n');
                else if (depth >= 1 && ln.contains("(") && !ln.trim().startsWith("return") && !ln.trim().startsWith("new ") && method.matcher(ln).matches() && (ln.trim().endsWith("{") || ln.trim().endsWith(";") || ln.trim().endsWith(",") || ln.contains(") {")))
                    sb.append("  ").append(i + 1).append(": ").append(o.length() > 120 ? o.substring(0, 120) : o).append('\n');
                else if (depth >= 1 && field.matcher(ln).matches()) sb.append("  ").append(i + 1).append(": ").append(o.length() > 120 ? o.substring(0, 120) : o).append('\n');
            }
            for (int k = 0; k < ln.length(); k++) { char c = ln.charAt(k); if (c == '{') depth++; else if (c == '}') depth--; }
        }
        return sb.toString();
    }

    private String projectStats() throws IOException {
        Map<String, int[]> by = new TreeMap<>();
        int files = 0;
        for (File f : allFiles(root, 8000)) {
            files++;
            String n = f.getName();
            String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "(none)";
            int[] c = by.get(ext);
            if (c == null) { c = new int[2]; by.put(ext, c); }
            c[0]++;
            if (ProjectContext.isText(f) && f.length() < 600000) c[1] += FileUtil.readText(f).split("\n", -1).length;
        }
        StringBuilder sb = new StringBuilder(files + " files\n");
        for (Map.Entry<String, int[]> e : by.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" files").append(e.getValue()[1] > 0 ? ", " + e.getValue()[1] + " lines" : "").append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ change tools (all undoable)

    private String applyOps(Result r, List<Protocol.Op> ops) throws IOException {
        List<FileOps.Change> plan = FileOps.plan(root, ops);
        StringBuilder bad = new StringBuilder();
        int ok = 0;
        for (FileOps.Change c : plan) {
            if (c.error != null) bad.append(c.path).append(": ").append(c.error).append('\n');
            else if (c.ok()) ok++;
        }
        if (ok == 0) return "ERROR: " + (bad.length() > 0 ? bad.toString().trim() : "nothing to change");
        String id = FileOps.apply(root, plan, backupRoot);
        if (!id.isEmpty()) backups.add(id);
        for (FileOps.Change c : plan) if (c.selected && c.ok()) { r.touched.add(c.path); if (c.to != null && !c.asset) r.touched.add(c.to); }
        return ok + " change" + (ok == 1 ? "" : "s") + " applied" + (bad.length() > 0 ? "\nskipped:\n" + bad.toString().trim() : "");
    }

    private Protocol.Op write(String path, String content) {
        Protocol.Op o = new Protocol.Op();
        o.type = Protocol.WRITE;
        o.path = path;
        o.content = content;
        return o;
    }

    private String writeFile(Result r, String arg, String body, boolean append) throws IOException {
        String path = Protocol.clean(firstToken(arg));
        if (path == null) return "ERROR: invalid path.";
        String text = body;
        if (arg.trim().length() > firstToken(arg).length() && body.isEmpty()) text = afterFirst(arg);   // one-line form
        if (!text.endsWith("\n")) text += "\n";
        if (append) {
            File f = new File(root, path);
            String old = f.isFile() ? FileUtil.readText(f) : "";
            if (!old.isEmpty() && !old.endsWith("\n")) old += "\n";
            text = old + text;
        }
        List<Protocol.Op> ops = new ArrayList<>();
        ops.add(write(path, text));
        return path + ": " + applyOps(r, ops);
    }

    private String replaceLines(Result r, String arg, String body) throws IOException {
        Matcher m = Pattern.compile("^(.*?)\\s+(\\d+)\\s*[-:]\\s*(\\d+)$").matcher(arg.trim());
        Matcher one = Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(arg.trim());
        String path; int from, to;
        if (m.matches()) { path = m.group(1); from = Integer.parseInt(m.group(2)); to = Integer.parseInt(m.group(3)); }
        else if (one.matches()) { path = one.group(1); from = to = Integer.parseInt(one.group(2)); }
        else return "ERROR: use replace_lines <path> <from>-<to> with the new lines in the body.";
        File f = file(path);
        if (f == null || !f.isFile()) return "ERROR: file not found: " + path;
        String text = FileUtil.readText(f);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        boolean trail = text.endsWith("\n");
        if (trail) lines.remove(lines.size() - 1);
        if (from < 1 || to < from || from > lines.size()) return "ERROR: line range " + from + "-" + to + " is outside the file (" + lines.size() + " lines).";
        to = Math.min(to, lines.size());
        for (int i = to; i >= from; i--) lines.remove(i - 1);
        if (!body.isEmpty()) lines.addAll(from - 1, Arrays.asList(body.split("\n", -1)));
        List<Protocol.Op> ops = new ArrayList<>();
        ops.add(write(Protocol.clean(path), String.join("\n", lines) + (trail ? "\n" : "")));
        return rel(f) + " lines " + from + "-" + to + " replaced: " + applyOps(r, ops);
    }

    private String insertLines(Result r, String arg, String body) throws IOException {
        Matcher m = Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(arg.trim());
        if (!m.matches()) return "ERROR: use insert_lines <path> <after line number> (0 = at the top), new lines in the body.";
        File f = file(m.group(1));
        if (f == null || !f.isFile()) return "ERROR: file not found: " + m.group(1);
        int after = Integer.parseInt(m.group(2));
        String text = FileUtil.readText(f);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        boolean trail = text.endsWith("\n");
        if (trail) lines.remove(lines.size() - 1);
        if (after < 0 || after > lines.size()) return "ERROR: the file has " + lines.size() + " lines.";
        lines.addAll(after, Arrays.asList(body.split("\n", -1)));
        List<Protocol.Op> ops = new ArrayList<>();
        ops.add(write(Protocol.clean(m.group(1)), String.join("\n", lines) + (trail ? "\n" : "")));
        return rel(f) + ": inserted after line " + after + ": " + applyOps(r, ops);
    }

    private String replaceAll(Result r, String arg) throws IOException {
        String a = arg.trim();
        String path = firstToken(a);
        String rest = afterFirst(a);
        int k = rest.indexOf("=>");
        int k2 = rest.indexOf("->");
        int sep = k >= 0 && (k2 < 0 || k < k2) ? k : k2;
        if (path.isEmpty() || sep < 0) return "ERROR: use replace_all <file or folder> <old text> => <new text>";
        String from = unquote(rest.substring(0, sep).trim()), to = unquote(rest.substring(sep + 2).trim());
        if (from.isEmpty()) return "ERROR: the old text is empty.";
        File base = path.equals(".") ? root : file(path);
        if (base == null || !base.exists()) return "ERROR: path not found: " + path;
        List<File> files = base.isFile() ? Arrays.asList(base) : textFiles(base);
        List<Protocol.Op> ops = new ArrayList<>();
        int occ = 0;
        for (File f : files) {
            String t = FileUtil.readText(f);
            int c = 0, i = 0;
            while ((i = t.indexOf(from, i)) >= 0) { c++; i += from.length(); }
            if (c == 0) continue;
            occ += c;
            ops.add(write(rel(f), t.replace(from, to)));
        }
        if (ops.isEmpty()) return "ERROR: '" + from + "' was not found.";
        return occ + " replacement" + (occ == 1 ? "" : "s") + " in " + ops.size() + " file" + (ops.size() == 1 ? "" : "s") + ": " + applyOps(r, ops);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'") || s.startsWith("`") && s.endsWith("`"))) return s.substring(1, s.length() - 1);
        return s;
    }

    private String makeDir(String arg) {
        File d = file(arg);
        if (d == null) return "ERROR: invalid path.";
        if (d.isDirectory()) return rel(d) + " already exists.";
        return d.mkdirs() ? "Created " + rel(d) : "ERROR: could not create " + arg;
    }

    private String simpleOp(Result r, int type, String arg) throws IOException {
        List<Protocol.Op> ops = new ArrayList<>();
        Protocol.Op o = new Protocol.Op();
        o.type = type;
        if (type == Protocol.DELETE) {
            o.path = Protocol.clean(arg);
            if (o.path == null) return "ERROR: invalid path.";
        } else {
            String[] parts = arg.trim().split("\\s*(?:->|=>)\\s*");
            if (parts.length < 2) { parts = arg.trim().split("\\s+"); }
            if (parts.length < 2) return "ERROR: use " + (type == Protocol.RENAME ? "move_path" : "copy_path") + " <from> -> <to>";
            o.path = Protocol.clean(parts[0]);
            o.to = Protocol.clean(parts[parts.length - 1]);
            if (o.path == null || o.to == null) return "ERROR: invalid path.";
        }
        ops.add(o);
        return applyOps(r, ops);
    }

    private String undoLast(Result r) throws IOException {
        if (backups.isEmpty()) return "ERROR: this agent run has not changed anything yet.";
        String id = backups.remove(backups.size() - 1);
        List<String> t = FileOps.undo(root, backupRoot, id);
        r.touched.addAll(t);
        return "Undone: " + t.size() + " file" + (t.size() == 1 ? "" : "s") + " restored (" + String.join(", ", t.subList(0, Math.min(5, t.size()))) + (t.size() > 5 ? ", …" : "") + ")";
    }

    // ------------------------------------------------------------------ refactoring

    private String renameClass(Result r, String arg) throws IOException {
        String[] p = arg.trim().split("\\s*(?:->|=>)\\s*|\\s+");
        if (p.length < 2) return "ERROR: use rename_class <OldName> -> <NewName>";
        String from = p[0], to = p[p.length - 1];
        if (!Refactor.validIdentifier(to) || !Refactor.validIdentifier(from)) return "ERROR: '" + to + "' is not a valid Java class name.";
        List<File> java = byExt(".java"), xml = byExt(".xml");
        File decl = null;
        Pattern dp = Pattern.compile("(?m)\\b(?:class|interface|enum)\\s+" + Pattern.quote(from) + "\\b");
        for (File f : java) if (f.getName().equals(from + ".java") || dp.matcher(strip(FileUtil.readText(f))).find()) { if (decl == null || f.getName().equals(from + ".java")) decl = f; }
        if (decl == null) return "ERROR: no class named " + from + " found in the project.";
        for (File f : java) if (f.getName().equals(to + ".java")) return "ERROR: a class named " + to + " already exists (" + rel(f) + ").";
        List<Refactor.Occ> occs = Refactor.javaOccurrences(java, from);
        List<Refactor.Occ> xocc = Refactor.xmlClassOccurrences(xml, from);
        Map<File, List<Refactor.Occ>> by = new LinkedHashMap<>();
        for (Refactor.Occ o : occs) { if (!by.containsKey(o.file)) by.put(o.file, new ArrayList<Refactor.Occ>()); by.get(o.file).add(o); }
        for (Refactor.Occ o : xocc) { if (!by.containsKey(o.file)) by.put(o.file, new ArrayList<Refactor.Occ>()); by.get(o.file).add(o); }
        boolean moveFile = decl.getName().equals(from + ".java");
        List<Protocol.Op> ops = new ArrayList<>();
        for (Map.Entry<File, List<Refactor.Occ>> e : by.entrySet()) {
            ops.add(write(moveFile && e.getKey().equals(decl) ? rel(new File(decl.getParentFile(), to + ".java")) : rel(e.getKey()), applyOccs(FileUtil.readText(e.getKey()), e.getValue(), to)));
        }
        if (moveFile) {
            Protocol.Op del = new Protocol.Op();
            del.type = Protocol.DELETE;
            del.path = rel(decl);
            if (!by.containsKey(decl)) ops.add(write(rel(new File(decl.getParentFile(), to + ".java")), FileUtil.readText(decl)));
            ops.add(del);
        }
        String res = applyOps(r, ops);
        return "Renamed " + from + " to " + to + ": " + occs.size() + " code reference" + (occs.size() == 1 ? "" : "s") + ", " + xocc.size() + " XML reference" + (xocc.size() == 1 ? "" : "s")
                + (moveFile ? ", file renamed" : "") + "\n" + res;
    }

    private static String applyOccs(String text, List<Refactor.Occ> occs, String to) {
        List<Refactor.Occ> l = new ArrayList<>(occs);
        java.util.Collections.sort(l, new java.util.Comparator<Refactor.Occ>() { @Override public int compare(Refactor.Occ a, Refactor.Occ b) { return b.start - a.start; } });
        StringBuilder sb = new StringBuilder(text);
        int lastStart = Integer.MAX_VALUE;
        for (Refactor.Occ o : l) { if (o.end > lastStart) continue; sb.replace(o.start, o.end, to); lastStart = o.start; }
        return sb.toString();
    }

    private String renameResource(Result r, String arg) throws IOException {
        String[] p = arg.trim().split("\\s*(?:->|=>)\\s*|\\s+");
        if (p.length < 2 || !p[0].contains("/")) return "ERROR: use rename_resource <type>/<old name> -> <new name>, for example layout/activity_main -> activity_home";
        String type = p[0].substring(0, p[0].indexOf('/')), from = p[0].substring(p[0].indexOf('/') + 1), to = p[p.length - 1];
        if (to.contains("/")) to = to.substring(to.indexOf('/') + 1);
        if (!to.matches("[A-Za-z_][A-Za-z0-9_]*") || (!type.equals("id") && !to.matches("[a-z_][a-z0-9_]*"))) return "ERROR: '" + to + "' is not a valid resource name (lowercase letters, digits, underscore).";
        List<File> res = resDirs();
        List<File> java = byExt(".java"), xml = new ArrayList<>();
        for (File x : byExt(".xml")) xml.add(x);
        List<Refactor.Occ> occs = Refactor.resourceOccurrences(java, xml, type, from);
        List<File[]> renames = Refactor.resourceFileRenames(res, type, from, to);
        Map<File, List<Refactor.Occ>> by = new LinkedHashMap<>();
        for (Refactor.Occ o : occs) { if (!by.containsKey(o.file)) by.put(o.file, new ArrayList<Refactor.Occ>()); by.get(o.file).add(o); }
        Map<File, File> moved = new LinkedHashMap<>();
        for (File[] rn : renames) moved.put(rn[0], rn[1]);
        List<Protocol.Op> ops = new ArrayList<>();
        for (Map.Entry<File, List<Refactor.Occ>> e : by.entrySet()) {
            File target = moved.containsKey(e.getKey()) ? moved.get(e.getKey()) : e.getKey();
            ops.add(write(rel(target), applyOccs(FileUtil.readText(e.getKey()), e.getValue(), to)));
            if (moved.containsKey(e.getKey())) { Protocol.Op d = new Protocol.Op(); d.type = Protocol.DELETE; d.path = rel(e.getKey()); ops.add(d); }
        }
        for (Map.Entry<File, File> m : moved.entrySet()) {
            if (by.containsKey(m.getKey())) continue;
            Protocol.Op o = new Protocol.Op();
            o.type = Protocol.RENAME;
            o.path = rel(m.getKey());
            o.to = rel(m.getValue());
            ops.add(o);
        }
        if (ops.isEmpty()) return "ERROR: resource " + type + "/" + from + " was not found.";
        return "Renamed " + type + "/" + from + " to " + to + ": " + occs.size() + " reference" + (occs.size() == 1 ? "" : "s") + ", " + renames.size() + " file" + (renames.size() == 1 ? "" : "s") + " renamed\n" + applyOps(r, ops);
    }

    // ------------------------------------------------------------------ diagnostics

    private static final String[] RES_TYPES = {"id", "string", "layout", "drawable", "color", "dimen", "style", "mipmap", "menu", "anim", "array", "integer", "bool", "xml", "raw", "font"};

    private String diagnostics(String arg) throws IOException {
        List<String> issues = new ArrayList<>();
        int errors = 0, warns = 0;
        List<File> java = byExt(".java"), xml = byExt(".xml");
        String only = arg.trim();
        ResourceIndex ri = ResourceIndex.scan(resDirs());
        Map<String, Set<String>> have = new LinkedHashMap<>();
        for (String t : RES_TYPES) have.put(t, new HashSet<>(ri.names(t)));
        ClassIndex ci = null;
        if (cfg.androidJar != null && cfg.androidJar.isFile()) ci = new ClassIndex(Arrays.asList(cfg.androidJar));
        Project proj = null;
        try { proj = Project.load(root); } catch (Throwable ignored) { }
        String pkg = proj == null ? "" : proj.packageName;

        // manifest
        File manifest = null;
        for (File x : xml) if (x.getName().equals("AndroidManifest.xml") && manifest == null) manifest = x;
        String manText = manifest == null ? "" : FileUtil.readText(manifest);
        if (manifest == null) issues.add("E  AndroidManifest.xml not found");

        // 1. XML well-formed + resource references
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        Pattern refP = Pattern.compile("@(?!android:)(?:\\+)?(id|string|layout|drawable|color|dimen|style|mipmap|menu|anim|array|integer|bool|xml|raw|font)/([\\w.]+)");
        for (File x : xml) {
            String rp = rel(x);
            if (!only.isEmpty() && !rp.startsWith(only)) continue;
            String t = FileUtil.readText(x);
            try {
                javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
                db.setErrorHandler(null);
                db.parse(new org.xml.sax.InputSource(new java.io.StringReader(t)));
            } catch (org.xml.sax.SAXParseException e) {
                issues.add("E  " + rp + ":" + e.getLineNumber() + "  XML error: " + e.getMessage());
                errors++;
                continue;
            } catch (Exception ignored) { }
            if (!rp.contains("/res/") && !x.getName().equals("AndroidManifest.xml")) continue;
            Matcher m = refP.matcher(t);
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                String type = m.group(1), name = m.group(2).replace('.', '_');
                if (t.charAt(m.start() + 1) == '+') continue;     // @+id declares
                if (!seen.add(type + "/" + name)) continue;
                Set<String> hs = have.get(type);
                if (hs != null && !hs.contains(name) && !(type.equals("style") && hs.contains(m.group(2)))) {
                    issues.add("E  " + rp + ":" + lineOf(t, m.start()) + "  @" + type + "/" + m.group(2) + " is not defined");
                    errors++;
                }
            }
        }

        // 2. Java checks
        Pattern rP = Pattern.compile("(?<![\\w.])R\\.(id|layout|string|drawable|color|dimen|style|mipmap|menu|anim|array|xml|raw|integer|bool)\\.(\\w+)");
        Set<String> declared = new HashSet<>();
        Matcher dm = Pattern.compile("<(activity|service|receiver|provider)\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"").matcher(manText);
        while (dm.find()) { String n = dm.group(2); declared.add(n.substring(n.lastIndexOf('.') + 1)); }
        Set<String> projectClasses = new HashSet<>();
        for (File j : java) projectClasses.add(j.getName().substring(0, j.getName().length() - 5));
        boolean usesNet = false;
        for (File j : java) {
            String rp = rel(j);
            String t = FileUtil.readText(j);
            String s = strip(t);
            if (s.contains("HttpURLConnection") || Pattern.compile("\\bURL\\s*\\(").matcher(s).find() || s.contains("WebView") && s.contains("loadUrl") || s.contains("okhttp3") || s.contains("Socket(")) usesNet = true;
            if (!only.isEmpty() && !rp.startsWith(only)) continue;
            int open = 0, par = 0, br = 0;
            for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '{') open++; else if (c == '}') open--; else if (c == '(') par++; else if (c == ')') par--; else if (c == '[') br++; else if (c == ']') br--; }
            if (open != 0) { issues.add("E  " + rp + "  unbalanced braces { } (" + (open > 0 ? open + " not closed" : -open + " extra }") + ")"); errors++; }
            if (par != 0) { issues.add("E  " + rp + "  unbalanced parentheses"); errors++; }
            if (br != 0) { issues.add("E  " + rp + "  unbalanced brackets [ ]"); errors++; }
            Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(t);
            if (pm.find()) {
                String dir = rp.contains("/java/") ? rp.substring(rp.indexOf("/java/") + 6) : "";
                dir = dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')).replace('/', '.') : "";
                if (!dir.isEmpty() && !dir.equals(pm.group(1))) { issues.add("E  " + rp + ":" + lineOf(t, pm.start()) + "  package " + pm.group(1) + " does not match the folder (" + dir + ")"); errors++; }
            }
            String fname = j.getName().substring(0, j.getName().length() - 5);
            Matcher pc = Pattern.compile("(?m)^(?:public\\s+)(?:abstract\\s+|final\\s+)*(?:class|interface|enum)\\s+(\\w+)").matcher(s);
            if (pc.find() && !pc.group(1).equals(fname)) { issues.add("E  " + rp + ":" + lineOf(t, pc.start()) + "  public class " + pc.group(1) + " must be in a file named " + pc.group(1) + ".java"); errors++; }
            // imports
            Matcher im = Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;").matcher(t);
            String body = t.replaceAll("(?m)^import\\s.*$", "");
            String bodyStripped = strip(body);
            while (im.find()) {
                String fq = im.group(2);
                boolean wildcard = im.group(3) != null, stat = im.group(1) != null;
                String simple = fq.substring(fq.lastIndexOf('.') + 1);
                int line = lineOf(t, im.start());
                if (ci != null && !wildcard && !stat && (fq.startsWith("android.") || fq.startsWith("java.") || fq.startsWith("javax."))) {
                    if (!classExists(ci, fq)) { issues.add("E  " + rp + ":" + line + "  import " + fq + " cannot be resolved"); errors++; continue; }
                }
                if (!wildcard && !stat && !pkg.isEmpty() && fq.startsWith(pkg + ".") && !fq.endsWith(".R") && !projectClasses.contains(simple) && !projectClasses.contains(fq.substring(0, fq.lastIndexOf('.')).substring(fq.substring(0, fq.lastIndexOf('.')).lastIndexOf('.') + 1))) {
                    issues.add("E  " + rp + ":" + line + "  import " + fq + ": no such class in the project"); errors++; continue;
                }
                if (!wildcard && !stat && !Pattern.compile("(?<![\\w$])" + Pattern.quote(simple) + "(?![\\w$])").matcher(bodyStripped).find()) { issues.add("W  " + rp + ":" + line + "  unused import " + fq); warns++; }
            }
            // R references
            Matcher rm = rP.matcher(s);
            Set<String> seen = new HashSet<>();
            while (rm.find()) {
                String type = rm.group(1), name = rm.group(2);
                Set<String> hs = have.get(type);
                if (hs == null || hs.contains(name) || !seen.add(type + "." + name)) continue;
                issues.add("E  " + rp + ":" + lineOf(t, rm.start()) + "  R." + type + "." + name + " is not defined in res/");
                errors++;
            }
            // components missing in the manifest
            Matcher cm = Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:abstract\\s+|final\\s+)*class\\s+(\\w+)\\s+extends\\s+(\\w*Activity|Service|IntentService|BroadcastReceiver|ContentProvider)\\b").matcher(s);
            while (cm.find()) {
                String kind = cm.group(2).endsWith("Activity") ? "activity" : cm.group(2).equals("BroadcastReceiver") ? "receiver" : cm.group(2).equals("ContentProvider") ? "provider" : "service";
                if (!declared.contains(cm.group(1)) && !manText.isEmpty() && !cm.group(1).startsWith("Base") && !s.contains("abstract class " + cm.group(1))) {
                    issues.add("W  " + rp + ":" + lineOf(t, cm.start()) + "  " + cm.group(1) + " is not declared as <" + kind + "> in AndroidManifest.xml");
                    warns++;
                }
            }
        }
        if (ci != null) ci.close();

        // 3. manifest sanity
        if (!manText.isEmpty()) {
            if (!manText.contains("android.intent.action.MAIN")) { issues.add("W  AndroidManifest.xml  no launcher activity (action MAIN + category LAUNCHER)"); warns++; }
            if (usesNet && !manText.contains("android.permission.INTERNET")) { issues.add("E  AndroidManifest.xml  the code uses the network but INTERNET permission is missing"); errors++; }
            Matcher dn = Pattern.compile("<(activity|service|receiver|provider)\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"").matcher(manText);
            while (dn.find()) {
                String n = dn.group(2), simple = n.substring(n.lastIndexOf('.') + 1);
                if (!n.startsWith("android.") && !n.startsWith("androidx.") && !n.startsWith("com.google.") && !projectClasses.contains(simple) && (n.startsWith(".") || (!pkg.isEmpty() && n.startsWith(pkg)))) {
                    issues.add("E  AndroidManifest.xml:" + lineOf(manText, dn.start()) + "  " + dn.group(1) + " " + n + " has no Java class in the project"); errors++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(errors).append(" error").append(errors == 1 ? "" : "s").append(", ").append(warns).append(" warning").append(warns == 1 ? "" : "s")
                .append("  (static check of ").append(java.size()).append(" Java and ").append(xml.size()).append(" XML files; the compiler may find more: use build_project)\n");
        int n = 0;
        for (String s : issues) { if (n++ >= 100) { sb.append("… ").append(issues.size() - 100).append(" more\n"); break; } sb.append(s).append('\n'); }
        if (issues.isEmpty()) sb.append("No problems found.");
        return sb.toString();
    }

    private static boolean classExists(ClassIndex ci, String fq) {
        String[] parts = fq.split("\\.");
        for (int cut = parts.length; cut >= 1; cut--) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < cut; i++) { if (i > 0) in.append(i >= 1 && Character.isUpperCase(parts[i].charAt(0)) && i > 0 && Character.isUpperCase(parts[i - 1].charAt(0)) ? '$' : '/'); in.append(parts[i]); }
            if (ci.get(in.toString()) != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ build

    private String buildProject(String arg) {
        if (host == null) return "ERROR: building is not available here.";
        String a = arg.toLowerCase(Locale.ROOT);
        boolean aab = a.contains("aab") || a.contains("bundle");
        boolean release = aab || a.contains("release");
        return host.build(release, aab);
    }

    // ------------------------------------------------------------------ shell & python

    private static final Pattern DANGEROUS = Pattern.compile("(?i)(^|[;&|`(\\s])(rm\\s+(-[a-z]+\\s+)*(/|~|\\$HOME|/sdcard|/storage|/data|/system|\\*)\\s*($|[;&|])|mkfs|dd\\s+if=|:\\(\\)\\s*\\{|reboot|shutdown|su\\s+-?c?\\s|pm\\s+(uninstall|clear|disable)|chmod\\s+-R\\s+\\d*7\\d*\\s+/|mount\\s|wipe)");

    private boolean allowed(String title, String detail) {
        if (cfg.shellMode == 2) return false;
        if (cfg.shellMode == 1 || host == null) return cfg.shellMode == 1;
        return host.approve(title, detail);
    }

    private ProcessBuilder pb(String... cmd) {
        ProcessBuilder b = new ProcessBuilder(cmd);
        b.directory(root);
        b.redirectErrorStream(true);
        Map<String, String> env = b.environment();
        File home = cfg.home != null ? cfg.home : new File(root, ".buildora/home");
        home.mkdirs();
        env.put("HOME", home.getPath());
        env.put("TMPDIR", home.getPath());
        return b;
    }

    private String exec(ProcessBuilder b, int timeoutSec) throws IOException, InterruptedException {
        Process p = b.start();
        proc = p;
        final StringBuilder out = new StringBuilder();
        final InputStream in = p.getInputStream();
        Thread rd = new Thread(new Runnable() {
            @Override public void run() {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                    String ln;
                    while ((ln = br.readLine()) != null) { synchronized (out) { if (out.length() < 60000) out.append(ln).append('\n'); } }
                } catch (IOException ignored) { }
            }
        }, "agent-out");
        rd.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        rd.join(1500);
        proc = null;
        String text;
        synchronized (out) { text = out.toString(); }
        if (cancelled) return text + "\n[stopped by the user]";
        if (!done) return text + "\n[timed out after " + timeoutSec + " s and was stopped]";
        int code = p.exitValue();
        return (text.trim().isEmpty() ? "(no output)" : text.trim()) + "\n[exit code " + code + "]";
    }

    private String runShell(String arg, String body) throws Exception {
        String a = arg.trim();
        int timeout = 60;
        Matcher tm = Pattern.compile("^timeout=(\\d+)\\s*(.*)$", Pattern.DOTALL).matcher(a);
        if (tm.matches()) { timeout = Math.max(5, Math.min(300, Integer.parseInt(tm.group(1)))); a = tm.group(2); }
        String cmd = (a + (body.isEmpty() ? "" : "\n" + body)).trim();
        if (cmd.isEmpty()) return "ERROR: give a shell command.";
        if (DANGEROUS.matcher(cmd).find()) return "ERROR: this command was blocked because it could damage the phone or delete files outside the project.";
        if (!allowed("Run shell command", cmd)) return "ERROR: the user did not allow running shell commands" + (cfg.shellMode == 2 ? " (turned off in Settings)." : ".");
        return "$ " + (cmd.length() > 200 ? cmd.substring(0, 200) + "…" : cmd) + "\n" + exec(pb("sh", "-c", cmd), timeout);
    }

    private String findPython() {
        try {
            Process p = pb("sh", "-c", "command -v python3 || command -v python").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = br.readLine();
            p.waitFor(5, TimeUnit.SECONDS);
            if (l != null && !l.trim().isEmpty()) return l.trim();
        } catch (Exception ignored) { }
        for (String c : new String[]{"/system/bin/python3", "/system/xbin/python3", "/data/data/com.termux/files/usr/bin/python3"}) if (new File(c).canExecute()) return c;
        return null;
    }

    private String runPython(String arg, String body) throws Exception {
        String code = (arg.trim().isEmpty() ? "" : arg.trim() + "\n") + body;
        if (code.trim().isEmpty()) return "ERROR: give Python code.";
        String py = findPython();
        if (py == null) return "ERROR: no Python interpreter was found on this phone, and Buildora does not bundle one. Do the same work with run_shell (sh, awk, sed), or with Java code in the project.";
        if (!allowed("Run Python code", code)) return "ERROR: the user did not allow running code" + (cfg.shellMode == 2 ? " (turned off in Settings)." : ".");
        File dir = new File(root, ".buildora/agent");
        dir.mkdirs();
        File f = new File(dir, "run_" + System.currentTimeMillis() + ".py");
        FileUtil.writeText(f, code + "\n");
        try {
            return exec(pb(py, f.getPath()), 90);
        } finally { f.delete(); }
    }

    // ------------------------------------------------------------------ web

    private static String httpGet(String url, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Buildora) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "en");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < maxBytes) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }

    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)\\b.*?</\\1>", " ");
        s = s.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/pre)\\b[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
        s = s.replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private String searchWeb(String query) throws IOException {
        if (!cfg.web) return "ERROR: web access is turned off in Settings.";
        String q = query.trim();
        if (q.isEmpty()) return "ERROR: give a search query.";
        String html = httpGet("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), 400000);
        List<String[]> res = new ArrayList<>();
        Matcher a = Pattern.compile("(?s)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").matcher(html);
        List<String> snippets = new ArrayList<>();
        Matcher sn = Pattern.compile("(?s)class=\"result__snippet\"[^>]*>(.*?)</a>").matcher(html);
        while (sn.find()) snippets.add(htmlToText(sn.group(1)));
        int i = 0;
        while (a.find() && res.size() < 8) {
            String href = a.group(1).replace("&amp;", "&");
            int u = href.indexOf("uddg=");
            if (u >= 0) { String enc = href.substring(u + 5); int amp = enc.indexOf('&'); if (amp > 0) enc = enc.substring(0, amp); try { href = URLDecoder.decode(enc, "UTF-8"); } catch (Exception ignored) { } }
            if (href.startsWith("//")) href = "https:" + href;
            res.add(new String[]{htmlToText(a.group(2)), href, i < snippets.size() ? snippets.get(i) : ""});
            i++;
        }
        if (res.isEmpty()) {
            String b = httpGet("https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8"), 400000);
            Matcher m = Pattern.compile("(?s)<li class=\"b_algo\".*?<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>(.*?)</li>").matcher(b);
            while (m.find() && res.size() < 8) {
                Matcher pm = Pattern.compile("(?s)<p[^>]*>(.*?)</p>").matcher(m.group(3));
                res.add(new String[]{htmlToText(m.group(2)), m.group(1).replace("&amp;", "&"), pm.find() ? htmlToText(pm.group(1)) : ""});
            }
        }
        if (res.isEmpty()) return "No results found for: " + q;
        StringBuilder sb = new StringBuilder("Results for: " + q + "\n");
        int n = 1;
        for (String[] r : res) sb.append(n++).append(". ").append(r[0]).append("\n   ").append(r[1]).append("\n   ").append(r[2].length() > 220 ? r[2].substring(0, 220) + "…" : r[2]).append('\n');
        sb.append("\nUse fetch_url <url> to read a page.");
        return sb.toString();
    }

    private String fetchUrl(String arg) throws IOException {
        if (!cfg.web) return "ERROR: web access is turned off in Settings.";
        String u = arg.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "ERROR: give a full http(s) address.";
        String body = httpGet(u, 600000);
        String text = u.toLowerCase(Locale.ROOT).matches(".*\\.(json|txt|md|java|xml|js|py|kt|gradle|properties)(\\?.*)?$") || !body.contains("<html") && !body.contains("<HTML") && !body.contains("<body") ? body : htmlToText(body);
        if (text.length() > 12000) text = text.substring(0, 12000) + "\n… [page cut]";
        return u + "\n\n" + text;
    }

    // ------------------------------------------------------------------ project settings

    private String addDependency(String arg) {
        List<String> deps = new ArrayList<>();
        for (String t : arg.trim().split("[\\s,]+")) if (t.matches("[\\w.\\-]+:[\\w.\\-]+(:[\\w.\\-+]+)?")) deps.add(t);
        if (deps.isEmpty()) return "ERROR: use add_dependency group:artifact:version";
        int n = AiApply.addDeps(root, deps);
        return n + " librar" + (n == 1 ? "y" : "ies") + " added to the project (" + String.join(", ", deps) + "). They are downloaded on the next build.";
    }

    private String removeDependency(String arg) throws Exception {
        Project p = Project.load(root);
        int n = 0;
        for (String t : arg.trim().split("[\\s,]+")) {
            if (t.isEmpty()) continue;
            String ga = t.split(":").length >= 2 ? t.split(":")[0] + ":" + t.split(":")[1] : t;
            for (int i = 0; i < p.dependencies.size(); i++) { String d = p.dependencies.get(i); if (d.equals(t) || d.equals(ga) || d.startsWith(ga + ":")) { p.dependencies.remove(i--); n++; } }
        }
        if (n > 0) p.save();
        return n == 0 ? "No matching library in the project." : n + " librar" + (n == 1 ? "y" : "ies") + " removed.";
    }

    private String setVersion(String arg) throws Exception {
        Project p = Project.load(root);
        Matcher m = Pattern.compile("(name|code)\\s*=\\s*(\\S+)").matcher(arg);
        boolean any = false;
        while (m.find()) {
            if (m.group(1).equals("name")) { p.versionName = m.group(2); any = true; }
            else { try { p.versionCode = Integer.parseInt(m.group(2)); any = true; } catch (NumberFormatException e) { return "ERROR: version code must be a number."; } }
        }
        if (!any) return "ERROR: use set_version name=1.2.0 code=5";
        p.save();
        return "Version is now " + p.versionName + " (code " + p.versionCode + ")";
    }

    private String plan(String arg, String body) throws IOException {
        String text = (arg.trim().isEmpty() ? "" : arg.trim() + "\n") + body;
        if (text.trim().isEmpty()) return "ERROR: give the plan as a checklist.";
        File f = new File(root, ".buildora/ai_plan.md");
        f.getParentFile().mkdirs();
        FileUtil.writeText(f, text.trim() + "\n");
        return "Plan saved:\n" + text.trim();
    }
}
