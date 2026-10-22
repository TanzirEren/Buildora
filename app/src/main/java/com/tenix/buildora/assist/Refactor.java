package com.tenix.buildora.assist;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rename (symbols, classes, resources), Extract method and Organize imports. */
public final class Refactor {
    private Refactor() {}

    public static final class Occ {
        public File file;
        public int start, end, line;
        public String lineText = "";
        public boolean enabled = true;
    }

    public static final class Result {
        public String text, error;
        public int callOffset;
        static Result err(String m) { Result r = new Result(); r.error = m; return r; }
    }

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "new", "package", "private", "protected", "public", "return",
            "short", "static", "super", "switch", "synchronized", "this", "throw", "throws", "try", "void", "volatile", "while", "true", "false", "null"));

    // ------------------------------------------------------------------ helpers

    public static String strip(String t) { return JavaCompleter.stripCommentsAndStrings(t); }

    public static String identifierAt(String text, int caret) {
        int s = caret, e = caret;
        while (s > 0 && Character.isJavaIdentifierPart(text.charAt(s - 1))) s--;
        while (e < text.length() && Character.isJavaIdentifierPart(text.charAt(e))) e++;
        return s == e ? null : text.substring(s, e);
    }

    /** If the caret sits on the name part of R.type.name or @type/name returns "type:name", else null. */
    public static String resourceAt(String text, int caret) {
        String id = identifierAt(text, caret);
        if (id == null) return null;
        int s = caret;
        while (s > 0 && Character.isJavaIdentifierPart(text.charAt(s - 1))) s--;
        String before = text.substring(Math.max(0, s - 24), s);
        Matcher m = Pattern.compile("R\\s*\\.\\s*(\\w+)\\s*\\.\\s*$").matcher(before);
        if (m.find() && Arrays.asList(ResourceIndex.TYPES).contains(m.group(1))) return m.group(1) + ":" + id;
        m = Pattern.compile("@\\+?(\\w+)/$").matcher(before);
        if (m.find()) return m.group(1) + ":" + id;
        return null;
    }

    private static Occ occ(File f, String text, int s, int e) {
        Occ o = new Occ();
        o.file = f; o.start = s; o.end = e;
        int line = 1, ls = 0;
        for (int i = 0; i < s; i++) if (text.charAt(i) == '\n') { line++; ls = i + 1; }
        int le = text.indexOf('\n', s);
        o.line = line;
        o.lineText = text.substring(ls, le < 0 ? text.length() : le).trim();
        return o;
    }

    // ------------------------------------------------------------------ rename: Java symbols / classes

    public static List<Occ> javaOccurrences(List<File> files, String name) {
        List<Occ> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
        for (File f : files) {
            try {
                String text = FileUtil.readText(f);
                Matcher m = p.matcher(strip(text));
                while (m.find()) out.add(occ(f, text, m.start(), m.end()));
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** Layout / manifest references to a class: &lt;pkg.Name and android:name=".Name" (or full name). */
    public static List<Occ> xmlClassOccurrences(List<File> xmlFiles, String cls) {
        List<Occ> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?<=[<./\"])" + Pattern.quote(cls) + "(?=[\\s/>\"])");
        for (File f : xmlFiles) {
            try {
                String text = FileUtil.readText(f);
                Matcher m = p.matcher(text);
                while (m.find()) {
                    int ls = text.lastIndexOf('<', m.start()), le = text.indexOf('>', m.start());
                    String tagPart = text.substring(Math.max(ls, 0), le < 0 ? text.length() : le);
                    boolean tagName = m.start() > 0 && (text.charAt(m.start() - 1) == '<' || text.charAt(m.start() - 1) == '.' && ls >= 0 && text.substring(ls, m.start()).matches("</?[\\w.]*\\.?"));
                    boolean attrName = tagPart.matches("(?s).*android:name\\s*=\\s*\"[\\w.]*$") || tagPart.matches("(?s).*android:name\\s*=\\s*\"[\\w.]*" + Pattern.quote(cls) + "\".*");
                    if (tagName || attrName) out.add(occ(f, text, m.start(), m.end()));
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------ rename: resources

    public static List<Occ> resourceOccurrences(List<File> javaFiles, List<File> xmlFiles, String type, String name) {
        List<Occ> out = new ArrayList<>();
        Pattern jp = Pattern.compile("R\\s*\\.\\s*" + type + "\\s*\\.\\s*(" + Pattern.quote(name) + ")(?![\\w$])");
        for (File f : javaFiles) {
            try {
                String text = FileUtil.readText(f);
                Matcher m = jp.matcher(strip(text));
                while (m.find()) out.add(occ(f, text, m.start(1), m.end(1)));
            } catch (IOException ignored) { }
        }
        Pattern xp = Pattern.compile("@\\+?" + type + "/(" + Pattern.quote(name) + ")(?![\\w.])");
        Pattern decl = Pattern.compile("<(?:" + (type.equals("array") ? "string-array|integer-array|array" : type) + ")\\b[^>]*?\\bname\\s*=\\s*\"(" + Pattern.quote(name) + ")\"");
        Pattern idDecl = Pattern.compile("<item\\b[^>]*type\\s*=\\s*\"id\"[^>]*name\\s*=\\s*\"(" + Pattern.quote(name) + ")\"");
        for (File f : xmlFiles) {
            try {
                String text = FileUtil.readText(f);
                Matcher m = xp.matcher(text);
                while (m.find()) out.add(occ(f, text, m.start(1), m.end(1)));
                if (f.getParentFile() != null && f.getParentFile().getName().startsWith("values")) {
                    m = decl.matcher(text);
                    while (m.find()) out.add(occ(f, text, m.start(1), m.end(1)));
                    if (type.equals("id")) { m = idDecl.matcher(text); while (m.find()) out.add(occ(f, text, m.start(1), m.end(1))); }
                }
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** Resource files (layout/drawable/...) that must be renamed together with the references. */
    public static List<File[]> resourceFileRenames(List<File> resDirs, String type, String oldName, String newName) {
        List<File[]> out = new ArrayList<>();
        if (type.equals("id") || type.equals("string") || type.equals("color") || type.equals("dimen") || type.equals("style")) return out;
        for (File res : resDirs) {
            File[] dirs = res.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.getName().equals(type) && !d.getName().startsWith(type + "-")) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    int dot = n.indexOf('.');
                    if (dot > 0 && n.substring(0, dot).equals(oldName)) out.add(new File[]{f, new File(d, newName + n.substring(dot))});
                }
            }
        }
        return out;
    }

    /** Applies enabled occurrences (replaces with newName). Returns the changed files. */
    public static Set<File> apply(List<Occ> occs, String newName) throws IOException {
        Map<File, List<Occ>> byFile = new LinkedHashMap<>();
        for (Occ o : occs) if (o.enabled) { byFile.putIfAbsent(o.file, new ArrayList<Occ>()); byFile.get(o.file).add(o); }
        for (Map.Entry<File, List<Occ>> e : byFile.entrySet()) {
            String text = FileUtil.readText(e.getKey());
            List<Occ> l = e.getValue();
            Collections.sort(l, (a, b) -> b.start - a.start);
            StringBuilder sb = new StringBuilder(text);
            for (Occ o : l) sb.replace(o.start, o.end, newName);
            FileUtil.writeText(e.getKey(), sb.toString());
        }
        return byFile.keySet();
    }

    public static boolean validIdentifier(String s) {
        if (s == null || s.isEmpty() || KEYWORDS.contains(s) || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    // ------------------------------------------------------------------ organize imports

    public static String organizeImports(String text) {
        Matcher m = Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.*]+)\\s*;[ \\t]*\\r?\\n?").matcher(text);
        int first = -1, last = -1;
        List<String[]> imports = new ArrayList<>();
        while (m.find()) {
            if (first < 0) first = m.start();
            last = m.end();
            imports.add(new String[]{m.group(1) == null ? "" : "static ", m.group(2)});
        }
        if (first < 0) return text;
        String rest = strip(text.substring(0, first) + text.substring(last));
        Set<String> tokens = new HashSet<>();
        Matcher tm = Pattern.compile("[A-Za-z_$][\\w$]*").matcher(rest);
        while (tm.find()) tokens.add(tm.group());
        TreeSet<String> normal = new TreeSet<>(), statics = new TreeSet<>();
        for (String[] im : imports) {
            String fq = im[1];
            String simple = fq.substring(fq.lastIndexOf('.') + 1);
            boolean used = simple.equals("*") || tokens.contains(simple);
            if (!used) continue;
            (im[0].isEmpty() ? normal : statics).add(fq);
        }
        StringBuilder sb = new StringBuilder();
        String prevGroup = null;
        for (String fq : normal) {
            String g = fq.startsWith("java.") || fq.startsWith("javax.") ? "java" : fq.substring(0, fq.indexOf('.') < 0 ? fq.length() : fq.indexOf('.'));
            if (prevGroup != null && !prevGroup.equals(g)) sb.append('\n');
            sb.append("import ").append(fq).append(";\n");
            prevGroup = g;
        }
        if (!statics.isEmpty()) { if (sb.length() > 0) sb.append('\n'); for (String fq : statics) sb.append("import static ").append(fq).append(";\n"); }
        return text.substring(0, first) + sb + text.substring(last);
    }

    // ------------------------------------------------------------------ extract method

    private static final Pattern DECL = Pattern.compile("(?<![\\w.])(?:final\\s+)?((?:(?:[a-z_]\\w*\\.)*[A-Z][\\w.]*(?:<[^;(){}=]*?>)?(?:\\[\\])?|int|long|boolean|float|double|char|byte|short))\\s+(\\w+)\\s*(?==|;|,|\\)|:)");

    private static boolean classLike(String header) {
        String h = header.trim();
        return h.matches("(?s).*\\b(class|interface|enum)\\b.*") || h.matches("(?s).*\\bnew\\s+[\\w.<>]+\\s*\\([^;{}]*\\)\\s*$");
    }

    private static boolean methodLike(String header) {
        String h = header.trim();
        if (!h.contains("(") || !h.matches("(?s).*\\)\\s*(throws\\s+[\\w.,\\s]+)?$")) return false;
        return !h.matches("(?s).*\\b(if|for|while|switch|catch|synchronized|try|else)\\b\\s*\\(?[^;]*$") || h.matches("(?s).*\\w+\\s+\\w+\\s*\\(.*");
    }

    public static Result extractMethod(String text, int selStart, int selEnd, String name) {
        if (!validIdentifier(name)) return Result.err("'" + name + "' is not a valid method name");
        if (selStart < 0 || selEnd > text.length() || selStart >= selEnd) return Result.err("Select the statements you want to move into a new method.");
        int a = selStart, b = selEnd;
        while (a < b && Character.isWhitespace(text.charAt(a))) a++;
        while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
        if (a >= b) return Result.err("Select the statements you want to move into a new method.");
        char last = text.charAt(b - 1);
        if (last != ';' && last != '}') return Result.err("Select whole statements (the selection must end with ; or }).");
        String clean = strip(text);

        // enclosing blocks at the start of the selection
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < a; i++) {
            char c = clean.charAt(i);
            if (c == '{') open.add(i);
            else if (c == '}' && !open.isEmpty()) open.remove(open.size() - 1);
        }
        int depth = 0;
        for (int i = a; i < b; i++) {
            char c = clean.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth < 0) return Result.err("The selection cuts through a { } block. Select complete statements."); }
        }
        if (depth != 0) return Result.err("The selection has an unclosed { block.");
        int methodOpen = -1;
        String header = "";
        for (int k = 0; k < open.size(); k++) {
            int p = open.get(k);
            String h = headerBefore(clean, p);
            boolean parentClass = k == 0 || classLike(headerBefore(clean, open.get(k - 1)));
            if (methodLike(h) && !classLike(h) && parentClass) { methodOpen = p; header = h; }
        }
        if (methodOpen < 0) return Result.err("Put the selection inside a method body.");
        int methodEnd = matchingBrace(clean, methodOpen);
        if (methodEnd < 0 || b > methodEnd) return Result.err("Could not find the end of the method.");

        // variables declared before the selection (parameters + locals)
        Map<String, String> decl = new LinkedHashMap<>();
        Matcher hm = Pattern.compile("\\(([^)]*)\\)").matcher(header);
        if (hm.find()) for (String prm : hm.group(1).split(",")) {
            String s = prm.trim().replaceAll("final\\s+", "");
            int sp = s.lastIndexOf(' ');
            if (sp > 0) decl.put(s.substring(sp + 1), s.substring(0, sp).trim());
        }
        Matcher dm = DECL.matcher(clean.substring(methodOpen + 1, a));
        while (dm.find()) decl.put(dm.group(2), dm.group(1));
        String selClean = clean.substring(a, b);
        Map<String, String> inside = new LinkedHashMap<>();
        dm = DECL.matcher(selClean);
        while (dm.find()) inside.put(dm.group(2), dm.group(1));
        String after = clean.substring(b, methodEnd);

        Matcher rm = Pattern.compile("(?<![\\w$])return(?![\\w$])").matcher(selClean);
        if (rm.find()) return Result.err("The selection contains 'return'. Extract only code that always continues with the next statement.");
        if (Pattern.compile("(?<![\\w$])(break|continue)(?![\\w$])").matcher(selClean).find()
                && !Pattern.compile("(?<![\\w$])(for|while|do|switch)(?![\\w$])").matcher(selClean).find())
            return Result.err("The selection uses break/continue of a loop outside of it.");

        // identifiers used (not member accesses)
        Set<String> used = new java.util.LinkedHashSet<>();
        Matcher tm = Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*").matcher(selClean);
        while (tm.find()) if (!KEYWORDS.contains(tm.group())) used.add(tm.group());

        List<String> params = new ArrayList<>(), paramTypes = new ArrayList<>();
        Set<String> outputs = new java.util.LinkedHashSet<>();
        for (String v : used) {
            if (decl.containsKey(v) && !inside.containsKey(v)) {
                params.add(v);
                paramTypes.add(decl.get(v));
                Pattern assign = Pattern.compile("(?<![\\w$.])" + Pattern.quote(v) + "\\s*(=(?!=)|\\+=|-=|\\*=|/=|%=|\\+\\+|--)|(\\+\\+|--)\\s*" + Pattern.quote(v) + "(?![\\w$])");
                if (assign.matcher(selClean).find() && Pattern.compile("(?<![\\w$.])" + Pattern.quote(v) + "(?![\\w$])").matcher(after).find()) outputs.add(v);
            }
        }
        for (String v : inside.keySet()) if (Pattern.compile("(?<![\\w$.])" + Pattern.quote(v) + "(?![\\w$])").matcher(after).find()) outputs.add(v);
        if (outputs.size() > 1) return Result.err("The selection changes more than one variable that is used afterwards (" + String.join(", ", outputs) + "). Select less code.");
        String out = outputs.isEmpty() ? null : outputs.iterator().next();
        String retType = out == null ? "void" : (decl.containsKey(out) && !inside.containsKey(out) ? decl.get(out) : inside.get(out));
        if (out != null && retType == null) return Result.err("Could not find the type of '" + out + "'.");
        // a declared-inside variable that is also a parameter name cannot happen; params exclude inside names

        boolean isStatic = header.matches("(?s).*\\bstatic\\b.*");
        String throwsClause = "";
        Matcher thm = Pattern.compile("throws\\s+([\\w.,\\s]+)\\s*$").matcher(header.trim());
        if (thm.find()) throwsClause = " throws " + thm.group(1).trim();

        // indentation
        int headerLineStart = text.lastIndexOf('\n', methodOpen) ;
        int hs = headerStart(clean, methodOpen);
        int mls = text.lastIndexOf('\n', hs) + 1;
        String baseIndent = text.substring(mls, mls + leadingWs(text, mls));
        int sls = text.lastIndexOf('\n', a - 1) + 1;
        String selIndent = text.substring(sls, sls + leadingWs(text, sls));
        String body = reindent(text.substring(a, b), baseIndent + "    ");

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < params.size(); i++) { if (i > 0) sig.append(", "); sig.append(paramTypes.get(i)).append(' ').append(params.get(i)); }
        StringBuilder call = new StringBuilder();
        for (int i = 0; i < params.size(); i++) { if (i > 0) call.append(", "); call.append(params.get(i)); }
        String callText = (out == null ? "" : (inside.containsKey(out) ? retType + " " + out + " = " : out + " = ")) + name + "(" + call + ");";

        StringBuilder method = new StringBuilder();
        method.append("\n\n").append(baseIndent).append("private ").append(isStatic ? "static " : "").append(retType).append(' ').append(name)
                .append('(').append(sig).append(')').append(throwsClause).append(" {\n").append(body).append('\n');
        if (out != null) method.append(baseIndent).append("    return ").append(out).append(";\n");
        method.append(baseIndent).append('}');

        StringBuilder res = new StringBuilder(text);
        res.insert(methodEnd + 1, method);
        res.replace(a, b, callText);
        Result r = new Result();
        r.text = res.toString();
        r.callOffset = a;
        return r;
    }

    private static int leadingWs(String t, int from) {
        int i = from;
        while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
        return i - from;
    }

    private static String reindent(String block, String indent) {
        String[] lines = block.split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            min = Math.min(min, leadingWs(lines[i], 0));
        }
        if (min == Integer.MAX_VALUE) min = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i == 0) sb.append(indent).append(l.trim().isEmpty() ? "" : l);
            else if (l.trim().isEmpty()) sb.append("");
            else sb.append(indent).append(l.length() >= min ? l.substring(Math.min(min, l.length())) : l.trim());
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static int headerStart(String clean, int brace) {
        int i = brace - 1;
        while (i >= 0) {
            char c = clean.charAt(i);
            if (c == ';' || c == '{' || c == '}') break;
            i--;
        }
        int s = i + 1;
        while (s < brace && Character.isWhitespace(clean.charAt(s))) s++;
        return s;
    }

    private static String headerBefore(String clean, int brace) {
        return clean.substring(headerStart(clean, brace), brace).trim().replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
    }

    private static int matchingBrace(String clean, int open) {
        int d = 0;
        for (int i = open; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') d++;
            else if (c == '}') { d--; if (d == 0) return i; }
        }
        return -1;
    }
}
