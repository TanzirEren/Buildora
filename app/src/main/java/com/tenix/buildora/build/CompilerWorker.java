package com.tenix.buildora.build;

import org.eclipse.jdt.core.compiler.CompilationProgress;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Runs ECJ in the current process on a thread with a big stack (deeply nested expressions). */
public final class CompilerWorker implements JavaCompilerRunner {

    private static final long STACK = 512L * 1024 * 1024;

    @Override public Result run(final String[] argv, final long timeoutMs, final Cancel cancel) {
        final Result r = new Result();
        final StringWriter sw = new StringWriter();
        final Throwable[] fail = new Throwable[1];
        Thread t = new Thread(null, new Runnable() {
            @Override public void run() {
                try {
                    PrintWriter pw = new PrintWriter(sw, true);
                    r.ok = BatchCompiler.compile(argv, pw, pw, new CompilationProgress() {
                        @Override public void begin(int remainingWork) { }
                        @Override public void done() { }
                        @Override public boolean isCanceled() { return cancel != null && cancel.isCancelled(); }
                        @Override public void setTaskName(String name) { }
                        @Override public void worked(int workIncrement, int remainingWork) { }
                    });
                } catch (Throwable e) {
                    fail[0] = e;
                }
            }
        }, "ecj", STACK);
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs > 0 ? timeoutMs : 0);
        } catch (InterruptedException e) {
            r.cancelled = true;
            return r;
        }
        if (t.isAlive()) {                      // cannot kill a thread safely: report and let the caller decide
            r.timedOut = true;
            r.output = sw.toString();
            return r;
        }
        r.output = sw.toString();
        if (fail[0] != null) {
            StringWriter ew = new StringWriter();
            fail[0].printStackTrace(new PrintWriter(ew));
            r.crash = fail[0] + "\n" + ew;
            r.ok = false;
        }
        if (cancel != null && cancel.isCancelled()) r.cancelled = true;
        return r;
    }

    // ------------------------------------------------------------------ file protocol for the isolated process

    /** Reads an argument file: one argument per line. */
    public static String[] readArgs(java.io.File f) throws java.io.IOException {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : com.tenix.buildora.core.FileUtil.readText(f).split("\n", -1)) if (!l.isEmpty()) out.add(l);
        return out.toArray(new String[0]);
    }

    public static void writeArgs(java.io.File f, String[] argv) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) sb.append(a).append('\n');
        com.tenix.buildora.core.FileUtil.writeText(f, sb.toString());
    }

    /** Result file: first line ok|fail|crash, then the compiler output (or the crash text). */
    public static void writeResult(java.io.File f, Result r) throws java.io.IOException {
        String head = r.crash != null ? "crash" : r.ok ? "ok" : "fail";
        com.tenix.buildora.core.FileUtil.writeText(f, head + "\n" + (r.crash != null ? r.crash + "\n" : "") + r.output);
    }

    public static Result readResult(java.io.File f) throws java.io.IOException {
        Result r = new Result();
        String t = com.tenix.buildora.core.FileUtil.readText(f);
        int nl = t.indexOf('\n');
        String head = nl < 0 ? t : t.substring(0, nl);
        String rest = nl < 0 ? "" : t.substring(nl + 1);
        if (head.equals("ok")) { r.ok = true; r.output = rest; }
        else if (head.equals("crash")) {
            int cut = rest.indexOf("\n\n");
            r.crash = rest; r.output = "";
        } else r.output = rest;
        return r;
    }
}
