package com.tenix.buildora;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import com.tenix.buildora.build.CompilerWorker;
import com.tenix.buildora.build.JavaCompilerRunner;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs ECJ + annotation processors in the separate ":apt" process ({@link AptService}). A processor that
 * crashes, runs out of memory or hangs can only take down that process: it is killed on timeout, on cancel
 * and after every run, so Buildora itself and other projects are unaffected.
 * Must be called from a worker thread (the callbacks arrive on the main thread).
 */
public final class IsolatedCompiler implements JavaCompilerRunner {

    private final Context ctx;
    private final File workDir;

    public IsolatedCompiler(Context ctx, File workDir) {
        this.ctx = ctx.getApplicationContext();
        this.workDir = workDir;
    }

    @Override public Result run(String[] argv, long timeoutMs, Cancel cancel) {
        Result r = new Result();
        final File req = new File(workDir, "isolated-request.txt");
        final File res = new File(workDir, "isolated-result.txt");
        try {
            workDir.mkdirs();
            res.delete();
            CompilerWorker.writeArgs(req, argv);
        } catch (Exception e) {
            r.unavailable = true;
            return r;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger pid = new AtomicInteger(-1);
        final AtomicBoolean died = new AtomicBoolean(false);
        final Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) {
                if (m.what == AptService.MSG_STARTED) pid.set(m.arg1);
                else if (m.what == AptService.MSG_DONE) done.countDown();
            }
        });
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                Message m = Message.obtain(null, AptService.MSG_RUN);
                Bundle d = new Bundle();
                d.putString("req", req.getPath());
                d.putString("res", res.getPath());
                m.setData(d);
                m.replyTo = reply;
                try { new Messenger(b).send(m); }
                catch (RemoteException e) { died.set(true); done.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                if (done.getCount() > 0) { died.set(true); done.countDown(); }
            }
            @Override public void onBindingDied(ComponentName n) { onServiceDisconnected(n); }
        };
        boolean bound;
        try { bound = ctx.bindService(new Intent(ctx, AptService.class), conn, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { bound = false; }
        if (!bound) {
            r.unavailable = true;
            return r;
        }
        long end = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : Long.MAX_VALUE / 4);
        boolean killed = false;
        try {
            while (!done.await(400, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.isCancelled()) { r.cancelled = true; killed = true; break; }
                if (System.currentTimeMillis() > end) { r.timedOut = true; killed = true; break; }
            }
        } catch (InterruptedException e) {
            r.cancelled = true;
            killed = true;
        } finally {
            if (killed && pid.get() > 0) Process.killProcess(pid.get());
            try { ctx.unbindService(conn); } catch (RuntimeException ignored) { }
        }
        r.died = died.get() && !r.timedOut && !r.cancelled;
        if (r.timedOut || r.cancelled) return r;
        try {
            if (res.isFile()) {
                Result x = CompilerWorker.readResult(res);
                x.died = false;
                return x;
            }
        } catch (Exception ignored) { }
        r.died = true;                         // no result file: the process died (out of memory, native crash, kill)
        return r;
    }
}
