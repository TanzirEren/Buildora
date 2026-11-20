package com.tenix.buildora.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.tenix.buildora.R;
import com.tenix.buildora.core.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs AI requests independently of any screen. A request keeps going when the chat is closed, the
 * app is left, or the screen locks (AiService holds the process alive). When nobody is watching, the
 * reply is saved into the chat, optionally applied to the project, and a notification is posted.
 */
public final class AiJobs {
    private AiJobs() {}

    public static final String CHANNEL_DONE = "buildora_ai_done";
    private static final int NOTIFY_BASE = 5000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Job> JOBS = new HashMap<>();

    /** Callbacks always arrive on the main thread. */
    public interface Listener {
        void onDelta(Job j);
        void onKeySwitch(String message);
        void onFinish(Job j);
    }

    public static final class Job {
        public final String chatId;
        public String title = "", projectRoot = "", model = "", providerName = "";
        public final StringBuilder stream = new StringBuilder();
        public volatile boolean done, stopped;
        public String error;
        public String full = "";
        public int usageIn, usageOut;      // final numbers (reported or estimated)
        public int estimatedIn;
        public boolean autoApply, fixMode;
        public final long started = System.currentTimeMillis();
        public int deltas;

        ChatClient client;
        Listener listener;
        boolean visible = true;
        String lastKeyNote = "";

        Job(String chatId) { this.chatId = chatId; }

        public synchronized String snapshot() { synchronized (stream) { return stream.toString(); } }

        public void stop() {
            stopped = true;
            ChatClient c = client;
            if (c != null) c.cancel();
        }

        public synchronized void attach(Listener l) { listener = l; visible = true; }
        public synchronized void detach(Listener l) { if (listener == l) listener = null; }
        public synchronized void setVisible(boolean v) { visible = v; }
        public String keyNote() { return lastKeyNote; }
    }

    public static synchronized Job get(String chatId) { return JOBS.get(chatId); }

    public static synchronized int running() { return JOBS.size(); }

    public static synchronized List<Job> all() { return new ArrayList<>(JOBS.values()); }

    public static void cancelAll() {
        for (Job j : all()) j.stop();
    }

    /** Starts a request. The listener may be null; the job then finishes in the background. */
    public static Job start(final Context ctx, final String chatId, String title, String projectRoot, final AiProvider p, final String model,
                            final List<ChatClient.Msg> req, final ChatClient.Options opt, boolean autoApply, boolean fixMode, Listener l) {
        final Context app = ctx.getApplicationContext();
        final Job job = new Job(chatId);
        job.title = title == null ? "" : title;
        job.projectRoot = projectRoot == null ? "" : projectRoot;
        job.model = model;
        job.providerName = p.name;
        job.autoApply = autoApply;
        job.fixMode = fixMode;
        job.listener = l;
        int est = 0;
        for (ChatClient.Msg m : req) est += ChatClient.estimateTokens(m.text) + m.images.size() * 800;
        job.estimatedIn = est;
        final ChatClient cl = new ChatClient();
        job.client = cl;
        synchronized (AiJobs.class) { JOBS.put(chatId, job); }
        AiService.ensure(app);
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] usage = {0, 0};
                cl.run(p, model, req, opt, new ChatClient.Callback() {
                    @Override public void onDelta(String t) {
                        synchronized (job.stream) { job.stream.append(t); job.deltas++; }
                        MAIN.post(new Runnable() { @Override public void run() { Listener x = listenerOf(job); if (x != null) x.onDelta(job); } });
                    }
                    @Override public void onUsage(int in, int out) { usage[0] = in; usage[1] = out; }
                    @Override public void onKeySwitch(final String message) {
                        job.lastKeyNote = message;
                        MAIN.post(new Runnable() { @Override public void run() { Listener x = listenerOf(job); if (x != null) x.onKeySwitch(message); } });
                    }
                    @Override public void onDone(String full) { end(app, job, full, null, usage); }
                    @Override public void onError(String message, String partial) { end(app, job, partial, message, usage); }
                });
            }
        }, "ai-job").start();
        return job;
    }

    private static Listener listenerOf(Job j) { synchronized (j) { return j.listener; } }

    private static void end(final Context app, final Job job, final String full, final String err, final int[] usage) {
        MAIN.post(new Runnable() {
            @Override public void run() { complete(app, job, full, err, usage); }
        });
    }

    private static void complete(Context app, Job job, String full, String err, int[] usage) {
        job.full = full == null ? "" : full;
        job.error = err;
        job.usageIn = usage[0] > 0 ? usage[0] : job.estimatedIn;
        job.usageOut = usage[1] > 0 ? usage[1] : ChatClient.estimateTokens(job.full);
        job.done = true;
        synchronized (AiJobs.class) { if (JOBS.get(job.chatId) == job) JOBS.remove(job.chatId); }
        Ai.addUsage(app, job.usageIn, job.usageOut);
        Listener l;
        boolean visible;
        synchronized (job) { l = job.listener; visible = job.visible; }
        String note = null;
        if (l != null) l.onFinish(job);
        else note = saveDetached(app, job);
        if ((l == null || !visible) && Prefs.bool(app, Prefs.AI_NOTIFY, true)) notifyDone(app, job, note);
        AiService.check(app);
    }

    /** Nobody is watching: store the reply in the chat file and apply it when the user allowed that. */
    private static String saveDetached(Context app, Job job) {
        String note = null;
        try {
            ChatStore store = Ai.chats(app);
            Chat chat = store.load(job.chatId);
            if (chat == null) return null;
            Chat.Message am = new Chat.Message("assistant", job.full);
            am.model = job.model;
            am.tokIn = job.usageIn;
            am.tokOut = job.usageOut;
            if (job.error != null) {
                am.error = job.full.isEmpty();
                am.text = am.error ? "⚠ " + job.error : am.text + "\n\n⚠ " + job.error;
            } else if (job.stopped && !am.text.isEmpty()) am.text += "\n\n*(stopped)*";
            if (am.text.isEmpty()) am.text = "*(stopped)*";
            chat.messages.add(am);
            chat.tokensIn += job.usageIn;
            chat.tokensOut += job.usageOut;
            if (job.error == null && !job.stopped && job.autoApply && !job.projectRoot.isEmpty()) {
                File root = new File(job.projectRoot);
                if (root.isDirectory() && Protocol.parse(job.full).any()) {
                    AiApply.Outcome o = AiApply.applyReply(root, Ai.attachDir(app, chat.id), job.full);
                    if (o.error == null && !o.backupId.isEmpty()) {
                        am.backupId = o.backupId;
                        note = "Applied " + o.touched.size() + " file" + (o.touched.size() == 1 ? "" : "s") + " to " + root.getName();
                        Chat.Message n = new Chat.Message("assistant", "✓ " + note + " while the app was in the background. Open the project to build.");
                        n.model = "__note";
                        chat.messages.add(n);
                    }
                }
            }
            if (!chat.autoTitled && Prefs.bool(app, Prefs.AI_AUTOTITLE, true)) { chat.title = chat.guessTitle(); chat.autoTitled = true; }
            store.save(chat);
        } catch (Exception ignored) { }
        return note;
    }

    private static void notifyDone(Context app, Job job, String note) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_DONE) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_DONE, "Buildora AI replies", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Tells you when Buildora AI finished a reply in the background");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(app, AiChatActivity.class);
            i.putExtra(AiChatActivity.EXTRA_CHAT, job.chatId);
            if (!job.projectRoot.isEmpty()) i.putExtra(AiChatActivity.EXTRA_PROJECT, job.projectRoot);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, NOTIFY_BASE + Math.abs(job.chatId.hashCode() % 1000), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text;
            if (job.error != null && job.full.isEmpty()) text = "Problem: " + com.tenix.buildora.core.UI.ellipsize(job.error.replace('\n', ' '), 120);
            else if (note != null) text = note;
            else {
                String t = job.full.replaceAll("(?s)```.*?```", " ").replaceAll("[#*`>]", "").replace('\n', ' ').trim();
                text = t.isEmpty() ? "Reply is ready" : com.tenix.buildora.core.UI.ellipsize(t, 120);
            }
            Notification.Builder b = new Notification.Builder(app, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_ai)
                    .setContentTitle((job.error != null && job.full.isEmpty() ? "Buildora AI stopped" : "Buildora AI finished") + (job.title.isEmpty() ? "" : " · " + com.tenix.buildora.core.UI.ellipsize(job.title, 30)))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(NOTIFY_BASE + Math.abs(job.chatId.hashCode() % 1000), b.build());
        } catch (RuntimeException ignored) { }
    }
}
