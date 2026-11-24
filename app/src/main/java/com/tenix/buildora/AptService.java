package com.tenix.buildora;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import com.tenix.buildora.build.CompilerWorker;
import com.tenix.buildora.build.JavaCompilerRunner;

import java.io.File;

/**
 * Hosts ECJ with annotation processors in its own process (see the manifest: android:process=":apt").
 * One request at a time; the process exits when the last client unbinds, which frees all processor memory.
 */
public class AptService extends Service {

    public static final int MSG_RUN = 1, MSG_STARTED = 2, MSG_DONE = 3;

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(final Message m) {
            if (m.what != MSG_RUN) return;
            final String req = m.getData().getString("req"), res = m.getData().getString("res");
            final Messenger replyTo = m.replyTo;
            try {
                Message started = Message.obtain(null, MSG_STARTED);
                started.arg1 = Process.myPid();
                replyTo.send(started);
            } catch (Exception ignored) { }
            Thread t = new Thread(null, new Runnable() {
                @Override public void run() {
                    JavaCompilerRunner.Result r = new JavaCompilerRunner.Result();
                    try {
                        r = new CompilerWorker().run(CompilerWorker.readArgs(new File(req)), 0, null);
                    } catch (Throwable e) {
                        r.ok = false;
                        r.crash = String.valueOf(e);
                    }
                    try { CompilerWorker.writeResult(new File(res), r); } catch (Throwable ignored) { }
                    try { replyTo.send(Message.obtain(null, MSG_DONE)); } catch (Exception ignored) { }
                }
            }, "apt-run", 8L * 1024 * 1024);
            t.start();
        }
    });

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        super.onDestroy();
        Process.killProcess(Process.myPid());       // give all processor memory back to the system
    }
}
