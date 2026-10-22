package com.tenix.buildora.assist;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tenix.buildora.core.FileUtil;

/** Names of the project's resources (strings, ids, layouts, drawables...) for completion and refactoring. */
public final class ResourceIndex {
    private final Map<String, TreeSet<String>> names = new HashMap<>();

    public static final String[] TYPES = {"id", "string", "layout", "drawable", "color", "dimen", "style", "mipmap", "menu", "anim",
            "array", "integer", "bool", "xml", "raw", "font", "attr", "styleable"};

    private TreeSet<String> set(String type) {
        TreeSet<String> s = names.get(type);
        if (s == null) { s = new TreeSet<>(); names.put(type, s); }
        return s;
    }

    public List<String> names(String type) { return new ArrayList<>(set(type)); }

    private static final Pattern VALUE = Pattern.compile("<(string|color|dimen|style|integer|bool|string-array|integer-array|array|declare-styleable|attr|item)\\s+[^>]*?name\\s*=\\s*\"([^\"]+)\"([^>]*)>");
    private static final Pattern ID = Pattern.compile("@\\+?id/([A-Za-z_][\\w]*)");

    public static ResourceIndex scan(List<File> resDirs) {
        ResourceIndex r = new ResourceIndex();
        for (File res : resDirs) {
            File[] dirs = res.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String type = d.getName();
                int dash = type.indexOf('-');
                if (dash > 0) type = type.substring(0, dash);
                File[] files = d.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String n = f.getName();
                    int dot = n.indexOf('.');
                    String stem = dot > 0 ? n.substring(0, dot) : n;
                    if (type.equals("values")) {
                        if (!n.endsWith(".xml")) continue;
                        try {
                            Matcher m = VALUE.matcher(FileUtil.readText(f));
                            while (m.find()) {
                                String tag = m.group(1), name = m.group(2).replace('.', '_');
                                if (tag.equals("item")) { if (m.group(3).contains("type=\"id\"")) r.set("id").add(name); continue; }
                                if (tag.equals("string-array") || tag.equals("integer-array") || tag.equals("array")) r.set("array").add(name);
                                else if (tag.equals("declare-styleable")) r.set("styleable").add(name);
                                else r.set(tag).add(name);
                            }
                        } catch (IOException ignored) { }
                    } else {
                        r.set(type).add(stem);
                        if (type.equals("layout") || type.equals("menu") || type.equals("drawable") || type.equals("xml")) {
                            if (n.endsWith(".xml")) {
                                try {
                                    Matcher m = ID.matcher(FileUtil.readText(f));
                                    while (m.find()) r.set("id").add(m.group(1));
                                } catch (IOException ignored) { }
                            }
                        }
                    }
                }
            }
        }
        return r;
    }
}
