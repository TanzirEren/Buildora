package com.tenix.buildora.assist;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Context-aware Java completion: members (with type resolution through call chains), locals, classes, R.* resources. */
public final class JavaCompleter {

    private static final String[] KEYWORDS = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import",
            "instanceof", "int", "interface", "long", "new", "package", "private", "protected", "public", "return", "short", "static",
            "super", "switch", "synchronized", "this", "throw", "throws", "try", "void", "volatile", "while", "true", "false", "null"};

    /** Names developers reach for most: they float to the top of equal matches. */
    static final Set<String> POPULAR = new HashSet<>(java.util.Arrays.asList(
            "setText", "getText", "setOnClickListener", "findViewById", "getString", "startActivity", "setContentView", "setVisibility",
            "setTextSize", "setTextColor", "setBackgroundColor", "setImageResource", "addView", "setLayoutParams", "getContext",
            "runOnUiThread", "setEnabled", "setTitle", "setMessage", "show", "finish", "getIntent", "putExtra", "getSystemService",
            "setAdapter", "setLayoutManager", "notifyDataSetChanged", "postDelayed", "post", "length", "size", "add", "get", "put", "toString",
            "equals", "isEmpty", "append", "makeText", "TextView", "Button", "EditText", "ImageView", "LinearLayout", "RelativeLayout",
            "FrameLayout", "Toast", "Intent", "Bundle", "Activity", "View", "Context", "ArrayList", "List", "Map", "HashMap", "String",
            "RecyclerView", "ListView", "ScrollView", "Handler", "Log", "AlertDialog", "SharedPreferences", "File", "Uri", "Color"));

    private static final String[] ANNOTATIONS = {"Override", "SuppressWarnings", "Deprecated", "SafeVarargs", "FunctionalInterface"};

    /** Unified member (platform or project class). */
    private static final class Mem {
        String name, ret, retKey, owner;
        List<String> params = new ArrayList<>();
        boolean method, isStatic;
    }

    private static final class Ctx {
        String pkg = "", cls = "", superSimple = "";
        List<String> imports = new ArrayList<>();
    }

    private final ClassIndex idx;
    private final List<File> sourceDirs;
    private final ResourceIndex res;
    private Map<String, File> projectFiles;

    public JavaCompleter(ClassIndex idx, List<File> sourceDirs, ResourceIndex res) {
        this.idx = idx;
        this.sourceDirs = sourceDirs;
        this.res = res;
    }

    // ------------------------------------------------------------------ entry point

    public List<Completion> complete(String text, int caret, boolean force) {
        if (inStringOrComment(text, caret)) return new ArrayList<>();
        int p = caret;
        while (p > 0 && Character.isJavaIdentifierPart(text.charAt(p - 1))) p--;
        String prefix = text.substring(p, caret);
        int q = p;
        while (q > 0 && Character.isWhitespace(text.charAt(q - 1))) q--;
        Ctx ctx = parseCtx(text);
        List<Completion> out;
        if (q > 0 && text.charAt(q - 1) == '.' && !(q >= 2 && Character.isDigit(text.charAt(q - 2)))) {
            out = memberItems(text, q - 1, prefix, ctx);
        } else if (q > 0 && text.charAt(q - 1) == '@' && q == p) {
            out = new ArrayList<>();
            for (String a : ANNOTATIONS) if (matchScore(a, prefix) > 0) out.add(Completion.simple(a, "annotation", 'k', matchScore(a, prefix)));
        } else {
            if (prefix.isEmpty() && !force) return new ArrayList<>();
            out = generalItems(text, caret, prefix, ctx);
        }
        return finish(out, prefix);
    }

    static List<Completion> finish(List<Completion> in, String prefix) {
        Collections.sort(in, (a, b) -> b.score != a.score ? b.score - a.score : a.label.compareToIgnoreCase(b.label));
        List<Completion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Completion c : in) {
            if (!seen.add(c.label + "|" + c.kind)) continue;
            out.add(c);
            if (out.size() >= 80) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ matching

    static int matchScore(String name, String prefix) {
        if (prefix.isEmpty()) return 50;
        if (name.startsWith(prefix)) return 100;
        String ln = name.toLowerCase(), lp = prefix.toLowerCase();
        if (ln.startsWith(lp)) return 80;
        // camel humps: "stx" -> setText, "tv" -> TextView
        int pi = 0;
        for (int i = 0; i < name.length() && pi < lp.length(); i++) {
            char c = name.charAt(i);
            if ((i == 0 || Character.isUpperCase(c)) && Character.toLowerCase(c) == lp.charAt(pi)) pi++;
        }
        if (pi == lp.length()) return 60;
        if (ln.contains(lp) && lp.length() >= 3) return 30;
        // subsequence ("sTx" -> setText): first letter must match
        if (lp.length() >= 2 && ln.charAt(0) == lp.charAt(0)) {
            int k = 0;
            for (int i = 0; i < ln.length() && k < lp.length(); i++) if (ln.charAt(i) == lp.charAt(k)) k++;
            if (k == lp.length()) return 20;
        }
        return 0;
    }

    private static boolean inStringOrComment(String t, int caret) {
        int i = 0, n = Math.min(caret, t.length());
        while (i < n) {
            char c = t.charAt(i);
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '/') { while (i < n && t.charAt(i) != '\n') i++; if (i >= n) return true; continue; }
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2);
                if (e < 0 || e + 2 > n) return true;
                i = e + 2; continue;
            }
            if (c == '"' || c == '\'') {
                char q = c; i++;
                while (i < n && t.charAt(i) != q && t.charAt(i) != '\n') { if (t.charAt(i) == '\\') i++; i++; }
                if (i >= n) return true;
                i++; continue;
            }
            i++;
        }
        return false;
    }

    // ------------------------------------------------------------------ file context

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS = Pattern.compile("\\b(?:class|interface|enum)\\s+(\\w+)(?:\\s+extends\\s+([\\w.]+))?");

    static Ctx parseCtx(String text) {
        Ctx c = new Ctx();
        Matcher m = PACKAGE.matcher(text);
        if (m.find()) c.pkg = m.group(1);
        m = IMPORT.matcher(text);
        while (m.find()) c.imports.add(m.group(1));
        m = CLASS.matcher(stripCommentsAndStrings(text));
        if (m.find()) { c.cls = m.group(1); if (m.group(2) != null) c.superSimple = m.group(2); }
        return c;
    }

    static String stripCommentsAndStrings(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        int i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '/') { while (i < n && t.charAt(i) != '\n') { sb.append(' '); i++; } continue; }
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2); e = e < 0 ? n : e + 2;
                for (; i < e; i++) sb.append(t.charAt(i) == '\n' ? '\n' : ' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c; sb.append(q); i++;
                while (i < n && t.charAt(i) != q && t.charAt(i) != '\n') { if (t.charAt(i) == '\\') { sb.append(' '); i++; } sb.append(' '); i++; }
                if (i < n) { sb.append(t.charAt(i)); i++; }
                continue;
            }
            sb.append(c); i++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ own (source) members

    private static List<Mem> ownMembers(String text) {
        String t = stripCommentsAndStrings(text);
        List<Mem> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inClass = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{') {
                if (depth == 1 && inClass) addDecl(out, t.substring(start, i), true);
                if (depth == 0) inClass = true;
                depth++;
                if (depth == 1) start = i + 1;
            } else if (c == '}') {
                depth--;
                if (depth == 1) start = i + 1;
            } else if (c == ';' && depth == 1) {
                addDecl(out, t.substring(start, i), false);
                start = i + 1;
            }
        }
        return out;
    }

    private static final Pattern DECL_METHOD = Pattern.compile("((?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*([\\w.<>\\[\\],? ]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[\\w\\s,.]+)?$");
    private static final Pattern DECL_FIELD = Pattern.compile("((?:public|protected|private|static|final|volatile|transient)\\s+)*([\\w.<>\\[\\],? ]+?)\\s+(\\w+)\\s*(?:=.*)?$", Pattern.DOTALL);

    private static void addDecl(List<Mem> out, String header, boolean block) {
        String h = header.trim().replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        if (h.isEmpty() || h.startsWith("class") || h.contains(" class ") || h.contains("interface ") || h.contains(" enum ") || h.startsWith("enum")) return;
        Mem m = new Mem();
        if (h.contains("(") && block) {
            Matcher mm = DECL_METHOD.matcher(h);
            if (!mm.find()) return;
            m.method = true;
            m.name = mm.group(3);
            m.ret = mm.group(2).trim();
            m.isStatic = h.contains("static ");
            for (String pr : mm.group(4).split(",")) {
                String s = pr.trim().replaceAll("final\\s+", "");
                if (s.isEmpty()) continue;
                int sp = s.lastIndexOf(' ');
                m.params.add(sp > 0 ? s.substring(0, sp).trim() : s);
            }
            if (m.ret.equals("new") || m.name.equals("if") || m.name.equals("for") || m.name.equals("while") || m.name.equals("switch")) return;
        } else if (!h.contains("(")) {
            String head = h.contains("=") ? h.substring(0, h.indexOf('=')).trim() : h;
            Matcher mm = DECL_FIELD.matcher(head);
            if (!mm.find()) return;
            m.name = mm.group(3);
            m.ret = mm.group(2).trim();
            m.isStatic = h.contains("static ");
        } else return;
        if (m.ret == null || m.ret.isEmpty() || m.ret.equals("return") || m.ret.equals("new")) return;
        out.add(m);
    }

    // ------------------------------------------------------------------ project classes

    private synchronized Map<String, File> projectFiles() {
        if (projectFiles != null) return projectFiles;
        Map<String, File> m = new LinkedHashMap<>();
        for (File d : sourceDirs) for (File f : FileUtil.listFiles(d, ".java")) {
            String n = f.getName();
            m.putIfAbsent(n.substring(0, n.length() - 5), f);
        }
        projectFiles = m;
        return m;
    }

    private String projectFqcn(File f) {
        for (File d : sourceDirs) {
            if (FileUtil.isInside(d, f)) {
                String rel = FileUtil.relPath(d, f);
                return rel.substring(0, rel.length() - 5).replace('/', '.');
            }
        }
        return f.getName().replace(".java", "");
    }

    /** "src:/path/Foo.java" for project classes, internal name for platform classes, null when unknown. */
    private String resolveKey(String simple, Ctx ctx) {
        if (simple == null) return null;
        int lt = simple.indexOf('<');
        if (lt > 0) simple = simple.substring(0, lt);
        simple = simple.trim();
        if (simple.isEmpty() || simple.endsWith("]") || simple.equals("void")) return null;
        String last = simple.contains(".") ? simple.substring(simple.lastIndexOf('.') + 1) : simple;
        File pf = projectFiles().get(last);
        if (pf != null) return "src:" + pf.getPath();
        return idx.resolve(simple, ctx.imports, ctx.pkg);
    }

    private List<Mem> membersOf(String key, boolean statics, int depth) {
        List<Mem> out = new ArrayList<>();
        if (key == null || depth > 8) return out;
        if (key.startsWith("src:")) {
            File f = new File(key.substring(4));
            String text;
            try { text = FileUtil.readText(f); } catch (IOException e) { return out; }
            Ctx c = parseCtx(text);
            for (Mem m : ownMembers(text)) {
                if (m.isStatic != statics) continue;
                m.retKey = resolveKey(m.ret, c);
                m.owner = c.cls;
                out.add(m);
            }
            if (!c.superSimple.isEmpty()) out.addAll(membersOf(resolveKey(c.superSimple, c), statics, depth + 1));
            return out;
        }
        for (ClassIndex.Member m : idx.members(key, statics)) {
            Mem x = new Mem();
            x.name = m.name; x.method = m.method; x.isStatic = m.isStatic; x.ret = m.ret; x.retKey = m.retInternal;
            x.params = m.params; x.owner = ClassIndex.simple(m.owner);
            out.add(x);
        }
        return out;
    }

    // ------------------------------------------------------------------ declared types of variables

    private static final String TYPE_RX = "(?:(?:[a-z_]\\w*\\.)*[A-Z][\\w.]*(?:<[^;(){}=]*?>)?(?:\\[\\])?|int|long|boolean|float|double|char|byte|short)";

    static String declaredType(String text, String name, int caret) {
        String t = stripCommentsAndStrings(text.substring(0, Math.min(caret, text.length())));
        Pattern p = Pattern.compile("(?<![\\w.])(" + TYPE_RX + ")\\s+" + Pattern.quote(name) + "\\s*(?==|;|,|\\)|:)");
        Matcher m = p.matcher(t);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    // ------------------------------------------------------------------ member completion

    private List<Completion> memberItems(String text, int dot, String prefix, Ctx ctx) {
        String expr = extractExpr(text, dot);
        List<Completion> out = new ArrayList<>();
        if (expr.isEmpty()) return out;
        List<String> segs = splitTop(expr);
        // R.type / R.type.name
        if (segs.get(0).equals("R") && segs.size() <= 2) {
            if (segs.size() == 1) {
                int ti = 0; for (String t : ResourceIndex.TYPES) { int s = matchScore(t, prefix); ti++; if (s > 0) out.add(Completion.simple(t, "R." + t, 'r', s + 40 - ti * 2)); }
            } else {
                for (String n : res.names(segs.get(1))) { int s = matchScore(n, prefix); if (s > 0) out.add(Completion.simple(n, "R." + segs.get(1), 'r', s)); }
            }
            return out;
        }
        String key = null;
        boolean statics = false;
        String first = segs.get(0);
        String name = first.contains("(") ? first.substring(0, first.indexOf('(')).trim() : first;
        if (first.startsWith("new ")) {
            Matcher nm = Pattern.compile("^new\\s+([\\w.]+)").matcher(first);
            if (nm.find()) key = resolveKey(nm.group(1), ctx);
        } else if (first.startsWith("(")) {
            String inner = first.substring(1, first.length() - 1).trim();
            Matcher cm = Pattern.compile("^\\(\\s*([A-Za-z_][\\w.<>]*)\\s*\\)").matcher(inner);
            if (cm.find()) key = resolveKey(cm.group(1), ctx);
            else if (!inner.isEmpty()) { List<String> in = splitTop(inner); key = resolveSegments(in, ctx, text, dot); if (key != null && in.size() > 0) { /* falls through */ } }
        } else if (name.equals("this")) {
            key = "this";
        } else if (name.equals("super")) {
            key = resolveKey(ctx.superSimple, ctx);
        } else if (first.contains("(")) {
            key = "this:" + name;
        } else {
            String type = declaredType(text, name, dot);
            if (type != null) key = resolveKey(type, ctx);
            if (key == null && Character.isUpperCase(name.charAt(0))) { key = resolveKey(name, ctx); statics = true; }
            if (key == null) {
                for (Mem m : ownMembers(text)) if (!m.method && m.name.equals(name)) { key = resolveKey(m.ret, ctx); break; }
            }
            if (key == null && Character.isUpperCase(name.charAt(0)) == false) { String c = fieldTypeIn(name, ctx, text); if (c != null) key = c; }
        }
        // walk the rest of the chain
        List<Mem> current = null;
        key = walk(key, statics, segs, ctx, text);
        if (key == null) return out;
        boolean lastStatic = statics && segs.size() == 1;
        current = membersOf(key.equals("this") ? "this" : key, lastStatic, 0);
        if (key.equals("this")) current = thisMembers(text, ctx, false);
        int pos = 0;
        for (Mem m : current) {
            int s = matchScore(m.name, prefix);
            pos++;
            if (s == 0) continue;
            out.add(toCompletion(m, s + Math.max(0, 40 - pos / 6)));
        }
        return out;
    }

    private String fieldTypeIn(String name, Ctx ctx, String text) { return null; }

    private String resolveSegments(List<String> segs, Ctx ctx, String text, int caret) {
        String first = segs.get(0);
        String name = first.contains("(") ? first.substring(0, first.indexOf('(')) : first;
        String type = declaredType(text, name, caret);
        String key = type == null ? null : resolveKey(type, ctx);
        return walk(key, false, segs, ctx, text);
    }

    /** Applies segments 1..n-1 (the last segment is the one before the dot, included). */
    private String walk(String key, boolean statics, List<String> segs, Ctx ctx, String text) {
        String cur = key;
        boolean st = statics;
        if (cur != null && cur.startsWith("this:")) {
            String mname = cur.substring(5);
            cur = null;
            for (Mem m : thisMembers(text, ctx, true)) if (m.method && m.name.equals(mname)) { cur = m.retKey; break; }
            if (cur == null) return null;
        }
        for (int i = 1; i < segs.size(); i++) {
            if (cur == null) return null;
            String seg = segs.get(i);
            boolean call = seg.contains("(");
            String n = call ? seg.substring(0, seg.indexOf('(')).trim() : seg.trim();
            List<Mem> ms = cur.equals("this") ? thisMembers(text, ctx, false) : membersOf(cur, st, 0);
            String next = null;
            for (Mem m : ms) if (m.name.equals(n) && m.method == call) { next = m.retKey; break; }
            if (next == null && !call) {
                // nested class reference like Foo.Bar is not resolved: stop
                return null;
            }
            cur = next;
            st = false;
        }
        return cur;
    }

    private List<Mem> thisMembers(String text, Ctx ctx, boolean includeStatics) {
        List<Mem> out = new ArrayList<>();
        for (Mem m : ownMembers(text)) { m.retKey = resolveKey(m.ret, ctx); m.owner = ctx.cls; out.add(m); }
        if (!ctx.superSimple.isEmpty()) {
            String sk = resolveKey(ctx.superSimple, ctx);
            out.addAll(membersOf(sk, false, 0));
        }
        return out;
    }

    static int rankBonus(String name) {
        return (POPULAR.contains(name) ? 25 : 0) - Math.min(18, name.length() / 3);
    }

    private static Completion toCompletion(Mem m, int score) {
        score += rankBonus(m.name);
        if (m.method) {
            String label = m.name + "(" + String.join(", ", m.params) + ")";
            return new Completion(label, m.ret, m.name + "()", m.params.isEmpty() ? 0 : 1, 'm', null, score);
        }
        return new Completion(m.name, m.ret, m.name, 0, 'f', null, score);
    }

    /** Text of the expression that ends right before the dot at position dot. */
    static String extractExpr(String t, int dot) {
        int i = dot;
        while (i > 0) {
            char c = t.charAt(i - 1);
            if (c == ')' || c == ']') {
                char open = c == ')' ? '(' : '[';
                int depth = 0, j = i - 1;
                while (j >= 0) {
                    char d = t.charAt(j);
                    if (d == c) depth++;
                    else if (d == open) { depth--; if (depth == 0) break; }
                    j--;
                }
                if (j < 0) break;
                i = j;
                continue;
            }
            if (Character.isJavaIdentifierPart(c) || c == '.') { i--; continue; }
            if (Character.isWhitespace(c)) {
                int k = i;
                while (k > 0 && Character.isWhitespace(t.charAt(k - 1))) k--;
                if (i < dot && t.charAt(i) == '.' && k > 0 && (t.charAt(k - 1) == ')' || Character.isJavaIdentifierPart(t.charAt(k - 1)))) { i = k; continue; }
                String before = t.substring(Math.max(0, i - 4), i);
                if (before.endsWith("new")) { i = i - 3; while (i > 0 && Character.isWhitespace(t.charAt(i - 1))) i--; }
                else if (k >= 3 && t.substring(k - 3, k).equals("new")) { i = k - 3; continue; }
                break;
            }
            break;
        }
        String e = t.substring(i, dot).trim();
        return e;
    }

    /** Splits a.b(c.d).e at top level dots. */
    static List<String> splitTop(String e) {
        List<String> out = new ArrayList<>();
        int depth = 0, st = 0;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '.' && depth == 0) { out.add(e.substring(st, i).trim()); st = i + 1; }
        }
        out.add(e.substring(st).trim());
        out.removeIf(String::isEmpty);
        if (out.isEmpty()) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ general completion

    private List<Completion> generalItems(String text, int caret, String prefix, Ctx ctx) {
        List<Completion> out = new ArrayList<>();
        int p = caret - prefix.length();
        // locals + parameters of the member we are in
        String scope = memberScope(text, p);
        Set<String> seen = new HashSet<>();
        Matcher m = Pattern.compile("(?<![\\w.])(" + TYPE_RX + ")\\s+(\\w+)\\s*(?==|;|,|\\)|:)").matcher(stripCommentsAndStrings(scope));
        while (m.find()) {
            String n = m.group(2);
            int s = matchScore(n, prefix);
            if (s > 0 && seen.add(n) && !n.equals(prefix)) out.add(Completion.simple(n, m.group(1), 'v', s + 40));
        }
        // own + inherited members of this class
        int mpos = 0;
        for (Mem mem : thisMembers(text, ctx, false)) {
            int s = matchScore(mem.name, prefix);
            mpos++;
            if (s == 0 || prefix.length() < 1) continue;
            out.add(toCompletion(mem, s + 20 + Math.max(0, 30 - mpos / 6)));
        }
        // keywords
        for (String k : KEYWORDS) { int s = matchScore(k, prefix); if (s >= 80) out.add(Completion.simple(k, "keyword", 'k', s)); }
        // project classes
        for (Map.Entry<String, File> e : projectFiles().entrySet()) {
            int s = matchScore(e.getKey(), prefix);
            if (s == 0) continue;
            String fq = projectFqcn(e.getValue());
            boolean samePkg = fq.contains(".") && fq.substring(0, fq.lastIndexOf('.')).equals(ctx.pkg);
            out.add(new Completion(e.getKey(), fq, e.getKey(), 0, 'c', samePkg || ctx.imports.contains(fq) ? null : fq, s + 10 + rankBonus(e.getKey())));
        }
        if (prefix.length() >= 2 || prefix.equals("R")) {
            if ("R".startsWith(prefix) || prefix.isEmpty()) out.add(Completion.simple("R", "resources", 'r', 70));
            for (String in : idx.classesWithPrefix(prefix, 25)) {
                String simple = ClassIndex.simple(in);
                int s = matchScore(simple, prefix);
                if (s == 0) continue;
                String fq = ClassIndex.fqcn(in);
                boolean noImport = in.startsWith("java/lang/") && in.indexOf('/', 10) < 0 || ctx.imports.contains(fq);
                out.add(new Completion(simple, fq, simple, 0, 'c', noImport ? null : fq, s + rankBonus(simple)));
            }
        }
        return out;
    }

    /** Text from the start of the member (method header incl. parameters) containing pos up to pos. */
    private static String memberScope(String text, int pos) {
        String t = stripCommentsAndStrings(text.substring(0, Math.min(pos, text.length())));
        int depth = 0, boundary = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth <= 1) boundary = i + 1; }
            else if (c == ';' && depth <= 1) boundary = i + 1;
            else if (c == '{' && depth == 2) { /* method body start */ }
        }
        // include fields too: when depth <= 1 we are in a class body
        return text.substring(Math.min(boundary, pos), pos) + " ";
    }
}
