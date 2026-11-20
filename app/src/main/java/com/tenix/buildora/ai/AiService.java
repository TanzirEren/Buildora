package com.tenix.buildora.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.tenix.buildora.R;
import com.tenix.buildora.core.Prefs;

import java.util.List;

/**
 * Foreground service that keeps Buildora AI requests alive while the app is closed or the screen is
 * locked. It starts with the first running request and stops itself when the last one ends.
 */
public class AiService extends Service {
    public static final String ACTION_STOP = "com.tenix.buildora.AI_STOP";
    private static final String CHANNEL = "buildora_ai_work";
    private static final int ID = 61;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (AiJobs.running() == 0) { shutdown(); return; }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build());
            h.postDelayed(this, 2000);
        }
    };

    /** Called when a request starts. */
    public static void ensure(Context c) {
        if (!Prefs.bool(c, Prefs.AI_BG, true)) return;
        try {
            Intent i = new Intent(c, AiService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException ignored) { /* not allowed to start from the background: the request still runs while the process lives */ }
    }

    /** Called when a request ends. */
    public static void check(Context c) { /* the running service notices on its next tick */ }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AiJobs.cancelAll();
            return START_NOT_STICKY;
        }
        createChannel();
        startForeground(ID, build());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wake == null || !wake.isHeld())) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buildora:ai");
            wake.acquire(40 * 60 * 1000L);
        }
        if (!running) { running = true; h.postDelayed(tick, 1500); }
        return START_NOT_STICKY;
    }

    private void shutdown() {
        running = false;
        h.removeCallbacks(tick);
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (RuntimeException ignored) { }
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Buildora AI working", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shown while Buildora AI is writing code in the background");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification build() {
        List<AiJobs.Job> jobs = AiJobs.all();
        String text;
        if (jobs.isEmpty()) text = "Finishing…";
        else {
            AiJobs.Job j = jobs.get(0);
            long sec = (System.currentTimeMillis() - j.started) / 1000;
            int chars;
            synchronized (j.stream) { chars = j.stream.length(); }
            text = (j.title.isEmpty() ? "Chat" : com.tenix.buildora.core.UI.ellipsize(j.title, 28)) + " · " + j.model + " · " + sec + "s"
                    + (chars > 0 ? " · " + Ai.compact(ChatClient.estimateTokens(j.snapshot())) + " tokens" : " · thinking")
                    + (jobs.size() > 1 ? "  (+" + (jobs.size() - 1) + " more)" : "");
        }
        Intent open = new Intent(this, AiChatActivity.class);
        if (!jobs.isEmpty()) open.putExtra(AiChatActivity.EXTRA_CHAT, jobs.get(0).chatId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 11, open, fl);
        PendingIntent stop = PendingIntent.getService(this, 12, new Intent(this, AiService.class).setAction(ACTION_STOP), fl);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ai)
                .setContentTitle("Buildora AI is working")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Stop", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }
}
