package com.tenix.buildora.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Crash reports sent by apps built with Buildora (debug builds), newest first. */
public final class CrashStore {
    private CrashStore() {}

    public static final class Crash {
        public File file;
        public String pkg = "", thread = "", trace = "";
        public long time;
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "crashes");
        d.mkdirs();
        return d;
    }

    public static void add(Context c, String pkg, String thread, long time, String trace) {
        try {
            String safe = pkg.replaceAll("[^A-Za-z0-9._]", "_");
            FileUtil.writeText(new File(dir(c), time + "-" + safe + ".txt"),
                    "pkg=" + pkg + "\nthread=" + thread + "\ntime=" + time + "\n\n" + trace);
            File[] fs = dir(c).listFiles();
            if (fs != null && fs.length > 60) {
                Arrays.sort(fs);
                for (int i = 0; i < fs.length - 60; i++) fs[i].delete();
            }
        } catch (IOException ignored) { }
    }

    public static List<Crash> list(Context c) {
        List<Crash> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            try {
                String t = FileUtil.readText(f);
                int blank = t.indexOf("\n\n");
                Crash cr = new Crash();
                cr.file = f;
                cr.trace = blank < 0 ? t : t.substring(blank + 2);
                for (String l : (blank < 0 ? "" : t.substring(0, blank)).split("\n")) {
                    if (l.startsWith("pkg=")) cr.pkg = l.substring(4);
                    else if (l.startsWith("thread=")) cr.thread = l.substring(7);
                    else if (l.startsWith("time=")) try { cr.time = Long.parseLong(l.substring(5)); } catch (NumberFormatException ignored) { }
                }
                out.add(cr);
            } catch (IOException ignored) { }
        }
        Collections.sort(out, new Comparator<Crash>() {
            @Override public int compare(Crash a, Crash b) { return Long.compare(b.time, a.time); }
        });
        return out;
    }

    public static void clear(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
