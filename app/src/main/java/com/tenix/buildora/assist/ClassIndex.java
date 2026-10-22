package com.tenix.buildora.assist;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads class files straight from android.jar (and library jars) to list classes, members and view tags. */
public final class ClassIndex {

    public static final class Member {
        public String name, desc, owner, ret, retInternal;
        public boolean method, isStatic;
        public List<String> params = new ArrayList<>();
        public String signature() { return method ? name + "(" + String.join(", ", params) + ")" : name; }
    }

    public static final class Info {
        public String name, superName;
        public int access;
        public List<String> interfaces = new ArrayList<>();
        public List<Member> fields = new ArrayList<>(), methods = new ArrayList<>();
    }

    private final List<ZipFile> zips = new ArrayList<>();
    private final Map<String, Info> cache = new HashMap<>();
    private Map<String, List<String>> bySimple;

    public ClassIndex(List<File> jars) {
        for (File f : jars) {
            try { zips.add(new ZipFile(f)); } catch (IOException ignored) { }
        }
    }

    public void close() { for (ZipFile z : zips) try { z.close(); } catch (IOException ignored) { } }

    // ------------------------------------------------------------------ lookup

    public synchronized Info get(String internal) {
        if (internal == null) return null;
        if (cache.containsKey(internal)) return cache.get(internal);
        Info info = null;
        for (ZipFile z : zips) {
            ZipEntry e = z.getEntry(internal + ".class");
            if (e == null) continue;
            try (InputStream in = z.getInputStream(e)) { info = parse(new DataInputStream(new java.io.BufferedInputStream(in))); }
            catch (IOException ignored) { }
            if (info != null) break;
        }
        cache.put(internal, info);
        return info;
    }

    private synchronized Map<String, List<String>> simpleMap() {
        if (bySimple != null) return bySimple;
        Map<String, List<String>> m = new HashMap<>();
        for (ZipFile z : zips) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class") || n.indexOf('$') >= 0 || n.endsWith("package-info.class")) continue;
                if (n.startsWith("android/internal") || n.startsWith("com/android/") || n.startsWith("java/lang/invoke/")
                        || n.startsWith("sun/") || n.startsWith("libcore/") || n.startsWith("dalvik/") || n.startsWith("org/apache/harmony")) continue;
                String internal = n.substring(0, n.length() - 6);
                List<String> l = m.get(simple(internal));
                if (l == null) { l = new ArrayList<>(); m.put(simple(internal), l); }
                l.add(internal);
            }
        }
        bySimple = m;
        return m;
    }

    public static String simple(String internal) {
        int i = internal.lastIndexOf('/');
        return i < 0 ? internal : internal.substring(i + 1);
    }

    public static String fqcn(String internal) { return internal.replace('/', '.'); }

    private static int rank(String internal) {
        String[] best = {"android/widget/", "android/view/", "android/app/", "android/content/", "android/os/", "android/graphics/",
                "android/util/", "android/net/", "android/text/", "android/media/", "android/webkit/", "android/animation/"};
        if (internal.startsWith("java/lang/") && internal.indexOf('/', 10) < 0) return 0;
        for (int i = 0; i < best.length; i++) if (internal.startsWith(best[i])) return 1 + i;
        if (internal.startsWith("java/util/") || internal.startsWith("java/io/")) return 20;
        if (internal.startsWith("android/")) return 30;
        if (internal.startsWith("java/")) return 40;
        return 50;
    }

    /** Internal name for a simple class name using imports, java.lang, same package, then the best match anywhere. */
    public String resolve(String simple, Collection<String> importedFqcns, String currentPkg) {
        if (simple == null || simple.isEmpty()) return null;
        int lt = simple.indexOf('<');
        if (lt > 0) simple = simple.substring(0, lt);
        if (simple.endsWith("[]")) return null;
        if (simple.indexOf('.') >= 0) {
            String in = simple.replace('.', '/');
            return get(in) != null ? in : null;
        }
        for (String imp : importedFqcns) {
            if (imp.endsWith("." + simple)) { String in = imp.replace('.', '/'); if (get(in) != null) return in; }
        }
        String jl = "java/lang/" + simple;
        if (get(jl) != null) return jl;
        if (currentPkg != null && !currentPkg.isEmpty()) {
            String sp = currentPkg.replace('.', '/') + "/" + simple;
            if (get(sp) != null) return sp;
        }
        List<String> c = simpleMap().get(simple);
        if (c == null || c.isEmpty()) return null;
        List<String> sorted = new ArrayList<>(c);
        Collections.sort(sorted, (a, b) -> rank(a) - rank(b));
        return sorted.get(0);
    }

    /** Candidate classes whose simple name starts with prefix (case-insensitive), best first. */
    public List<String> classesWithPrefix(String prefix, int limit) {
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : simpleMap().entrySet()) {
            if (e.getKey().toLowerCase().startsWith(p)) out.addAll(e.getValue());
        }
        Collections.sort(out, (a, b) -> {
            int d = rank(a) - rank(b);
            return d != 0 ? d : simple(a).length() - simple(b).length();
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public boolean isSubclassOf(String internal, String ancestor) {
        Set<String> seen = new HashSet<>();
        String cur = internal;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(ancestor)) return true;
            Info i = get(cur);
            if (i == null) return false;
            cur = i.superName;
        }
        return false;
    }

    // ------------------------------------------------------------------ members

    /** Public / protected members of a class including everything inherited. */
    public List<Member> members(String internal, boolean staticOnly) {
        Map<String, Member> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        collect(internal, staticOnly, out, seen, 0);
        return new ArrayList<>(out.values());
    }

    private void collect(String internal, boolean staticOnly, Map<String, Member> out, Set<String> seen, int depth) {
        if (internal == null || depth > 12 || !seen.add(internal)) return;
        Info i = get(internal);
        if (i == null) return;
        for (Member m : i.fields) if (m.isStatic == staticOnly || (!staticOnly && false)) out.putIfAbsent(m.name + "#f", m);
        for (Member m : i.methods) if (m.isStatic == staticOnly) out.putIfAbsent(m.name + m.desc, m);
        if (!staticOnly || (i.access & 0x200) == 0) collect(i.superName, staticOnly, out, seen, depth + 1);
        for (String itf : i.interfaces) collect(itf, staticOnly, out, seen, depth + 1);
        if (!staticOnly) collect("java/lang/Object", false, out, seen, depth + 1);
    }

    /** android.R.attr field names (the android: XML attributes). */
    public List<String> attrNames() {
        Info i = get("android/R$attr");
        List<String> out = new ArrayList<>();
        if (i != null) for (Member m : i.fields) out.add(m.name);
        Collections.sort(out);
        return out;
    }

    /** Concrete public view classes (android.widget / view / webkit) usable as layout tags. */
    public List<String> viewTags() {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : simpleMap().entrySet()) {
            for (String internal : e.getValue()) {
                if (!(internal.startsWith("android/widget/") || internal.startsWith("android/view/") || internal.startsWith("android/webkit/"))) continue;
                Info i = get(internal);
                if (i == null || (i.access & 0x1) == 0 || (i.access & 0x200) != 0 || (i.access & 0x400) != 0) continue;
                if (!internal.equals("android/view/View") && !isSubclassOf(internal, "android/view/View")) continue;
                out.add(internal.startsWith("android/widget/") || internal.equals("android/view/View") ? e.getKey() : fqcn(internal));
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ class file parser

    private static Info parse(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) return null;
        in.readUnsignedShort();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        String[] utf = new String[n];
        int[] clsIdx = new int[n];
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf[i] = in.readUTF(); break;
                case 3: case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 7: clsIdx[i] = in.readUnsignedShort(); break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                default: throw new IOException("bad constant pool tag " + tag);
            }
        }
        Info info = new Info();
        info.access = in.readUnsignedShort();
        info.name = utf[clsIdx[in.readUnsignedShort()]];
        int sup = in.readUnsignedShort();
        info.superName = sup == 0 ? null : utf[clsIdx[sup]];
        int ni = in.readUnsignedShort();
        for (int i = 0; i < ni; i++) info.interfaces.add(utf[clsIdx[in.readUnsignedShort()]]);
        for (int pass = 0; pass < 2; pass++) {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int acc = in.readUnsignedShort();
                String name = utf[in.readUnsignedShort()];
                String desc = utf[in.readUnsignedShort()];
                int na = in.readUnsignedShort();
                for (int a = 0; a < na; a++) { in.readUnsignedShort(); int len = in.readInt(); in.skipBytes(len); }
                boolean pub = (acc & 0x1) != 0 || (acc & 0x4) != 0;
                if (!pub || (acc & 0x1000) != 0 || (acc & 0x40) != 0 && pass == 1) continue;
                if (name.startsWith("<")) continue;
                Member m = new Member();
                m.name = name; m.desc = desc; m.owner = info.name; m.method = pass == 1; m.isStatic = (acc & 0x8) != 0;
                if (pass == 1) {
                    int close = desc.indexOf(')');
                    parseParams(desc.substring(1, close), m.params);
                    m.retInternal = internalOf(desc.substring(close + 1));
                    m.ret = readable(desc.substring(close + 1));
                    info.methods.add(m);
                } else {
                    m.retInternal = internalOf(desc);
                    m.ret = readable(desc);
                    info.fields.add(m);
                }
            }
        }
        return info;
    }

    private static void parseParams(String d, List<String> out) {
        int i = 0;
        while (i < d.length()) {
            int st = i;
            while (d.charAt(i) == '[') i++;
            if (d.charAt(i) == 'L') i = d.indexOf(';', i);
            i++;
            out.add(readable(d.substring(st, i)));
        }
    }

    static String internalOf(String d) {
        int dims = 0;
        while (d.startsWith("[")) { d = d.substring(1); dims++; }
        if (dims > 0) return null;
        return d.startsWith("L") ? d.substring(1, d.length() - 1) : null;
    }

    static String readable(String d) {
        int dims = 0;
        while (d.startsWith("[")) { d = d.substring(1); dims++; }
        String t;
        switch (d.charAt(0)) {
            case 'I': t = "int"; break; case 'J': t = "long"; break; case 'Z': t = "boolean"; break; case 'F': t = "float"; break;
            case 'D': t = "double"; break; case 'C': t = "char"; break; case 'B': t = "byte"; break; case 'S': t = "short"; break;
            case 'V': t = "void"; break;
            default: t = simple(d.substring(1, d.length() - 1)).replace('$', '.');
        }
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }
}
