package com.tenix.buildora;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tenix.buildora.assist.ClassIndex;
import com.tenix.buildora.assist.Completion;
import com.tenix.buildora.assist.JavaCompleter;
import com.tenix.buildora.assist.ResourceIndex;
import com.tenix.buildora.assist.XmlCompleter;
import com.tenix.buildora.build.Toolchain;
import com.tenix.buildora.core.ModuleCfg;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.Variant;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs code completion off the UI thread. The android.jar index is built once and reused. */
final class CompletionEngine {
    interface Callback { void onResult(List<Completion> items); }

    private static ClassIndex index;
    private static String indexKey = "";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int generation;

    private CompletionEngine() {}

    private static synchronized ClassIndex index(Context c) {
        File jar = Toolchain.androidJar(c);
        String key = jar.getPath() + jar.length();
        if (index == null || !indexKey.equals(key)) {
            index = new ClassIndex(Collections.singletonList(jar));
            indexKey = key;
        }
        return index;
    }

    static void request(final Context c, final Project project, final File file, final String text, final int caret,
                        final boolean force, final Callback cb) {
        final int gen = ++generation;
        POOL.execute(new Runnable() {
            @Override public void run() {
                if (gen != generation) return;      // a newer keystroke already replaced this request
                List<Completion> out = new ArrayList<>();
                try {
                    String name = file == null ? "" : file.getName();
                    ClassIndex idx = index(c);
                    List<File> srcDirs = new ArrayList<>(project.javaDirs);
                    List<File> resDirs = new ArrayList<>();
                    if (project.resDir != null && project.resDir.isDirectory()) resDirs.add(project.resDir);
                    for (ModuleCfg m : project.orderedModules()) {
                        srcDirs.addAll(m.javaDirs);
                        if (m.resDir != null && m.resDir.isDirectory()) resDirs.add(m.resDir);
                    }
                    Variant v = project.activeVariant();
                    for (File d : new File[]{v.flavorDir, v.typeDir}) {
                        if (d == null) continue;
                        if (new File(d, "java").isDirectory()) srcDirs.add(new File(d, "java"));
                        if (new File(d, "res").isDirectory()) resDirs.add(new File(d, "res"));
                    }
                    ResourceIndex res = resources(resDirs, force);
                    if (gen != generation) return;
                    if (name.endsWith(".java")) out = new JavaCompleter(idx, srcDirs, res).complete(text, caret, force);
                    else if (name.endsWith(".xml")) out = new XmlCompleter(idx, res, srcDirs).complete(text, caret, file, force);
                } catch (Throwable ignored) { }
                final List<Completion> result = out;
                MAIN.post(new Runnable() {
                    @Override public void run() { if (gen == generation) cb.onResult(result); }
                });
            }
        });
    }

    private static ResourceIndex resCache;
    private static String resCacheKey = "";
    private static long resCacheAt;

    /** Scanning every resource file on every keystroke is wasteful; reuse the index for a few seconds (explicit requests rescan). */
    private static synchronized ResourceIndex resources(List<File> dirs, boolean force) {
        String key = dirs.toString();
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && resCache != null && key.equals(resCacheKey) && now - resCacheAt < 4000) return resCache;
        resCache = ResourceIndex.scan(dirs);
        resCacheKey = key;
        resCacheAt = now;
        return resCache;
    }

    static void cancelPending() { generation++; }
}
