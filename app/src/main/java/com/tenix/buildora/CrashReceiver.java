package com.tenix.buildora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.tenix.buildora.assist.LogcatParser;
import com.tenix.buildora.core.CrashStore;
import com.tenix.buildora.core.Prefs;

/** Receives crash reports from apps built in debug mode (see CrashHook) and shows a notification. */
public class CrashReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "buildora_crash";

    @Override public void onReceive(Context ctx, Intent i) {
        if (i == null || !"com.tenix.buildora.CRASH".equals(i.getAction())) return;
        String pkg = i.getStringExtra("pkg");
        String trace = i.getStringExtra("trace");
        if (pkg == null || trace == null) return;
        if (trace.length() > 70000) trace = trace.substring(0, 70000);
        long time = i.getLongExtra("time", System.currentTimeMillis());
        CrashStore.add(ctx, pkg, String.valueOf(i.getStringExtra("thread")), time, trace);
        if (!Prefs.bool(ctx, Prefs.NOTIFY, true)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App crashes", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(ctx, LogcatActivity.class).putExtra("tab", "crashes").putExtra("package", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) (time % 100000), open, fl);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_build)
                .setContentTitle(pkg + " crashed")
                .setContentText(LogcatParser.firstException(trace))
                .setStyle(new Notification.BigTextStyle().bigText(LogcatParser.firstException(trace)))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify((int) (time % 100000), n);
    }
}
