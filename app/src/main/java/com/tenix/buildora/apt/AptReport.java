package com.tenix.buildora.apt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-based event log written by {@link ProcessorHost} (possibly in another process) and read back by the
 * build. Each event is flushed immediately, so the log survives a crash or a killed process.
 * Format: TAB-separated fields, first field = event tag; TAB/newline inside fields are escaped.
 *
 * Tags: L processor loaded | R round start | C processor call | E round end | G generated file |
 *       M processor message | X crash/load failure | T per-processor totals | S summary
 */
public final class AptReport {

    private Writer w;

    public AptReport(File file) {
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8"));
        } catch (IOException e) { w = null; }
    }

    public synchronized void event(String... fields) {
        if (w == null) return;
        try {
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) w.write('\t');
                w.write(esc(fields[i]));
            }
            w.write('\n');
            w.flush();
        } catch (IOException ignored) { }
    }

    public synchronized void close() {
        try { if (w != null) w.close(); } catch (IOException ignored) { }
        w = null;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n");
    }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    /** Parsed events (each = list of fields). */
    public static List<String[]> read(File file) {
        List<String[]> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            for (String line : com.tenix.buildora.core.FileUtil.readText(file).split("\n")) {
                if (line.isEmpty()) continue;
                String[] f = line.split("\t", -1);
                for (int i = 0; i < f.length; i++) f[i] = unesc(f[i]);
                out.add(f);
            }
        } catch (IOException ignored) { }
        return out;
    }
}
