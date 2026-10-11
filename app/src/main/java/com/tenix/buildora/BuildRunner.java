package com.tenix.buildora;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.build.BuildEngine;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.Installer;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;

import java.io.File;

/** Runs a build and shows what is happening; offers Download/Install on success and copyable errors on failure. */
final class BuildRunner {
    private BuildRunner() {}

    interface Done { void onFinished(BuildEngine.Result r); }

    static void run(final Activity act, final Project p, final Done done) {
        final LinearLayout root = UI.vbox(act);
        root.setPadding(UI.dp(act, 20), UI.dp(act, 8), UI.dp(act, 20), UI.dp(act, 12));

        final TextView stage = UI.label(act, "Starting…", 15, false);
        stage.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(stage);
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 6));
        blp.topMargin = UI.dp(act, 10);
        root.addView(bar, blp);

        final TextView log = new TextView(act);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(10);
        log.setTextColor(UI.color(act, R.attr.bTextDim));
        log.setPadding(UI.dp(act, 10), UI.dp(act, 8), UI.dp(act, 10), UI.dp(act, 8));
        log.setBackgroundResource(R.drawable.bg_input);
        final ScrollView sv = new ScrollView(act);
        sv.addView(log);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 230));
        slp.topMargin = UI.dp(act, 12);
        root.addView(sv, slp);

        final LinearLayout actions = UI.vbox(act);
        root.addView(actions);
        final TextView cancel = UI.button(act, "Cancel build", false);
        actions.addView(cancel);

        final AlertDialog dlg = UI.dialog(act).setTitle("Building " + p.name).setView(root).setCancelable(false).create();
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Prefs.bool(act, Prefs.KEEP_AWAKE, true)) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final StringBuilder shown = new StringBuilder();
        final BuildEngine engine = new BuildEngine(act, p, new BuildEngine.Listener() {
            @Override public void onStage(final String s, final int percent) {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { stage.setText(s + "…"); bar.setProgress(percent); }
                });
            }
            @Override public void onLog(final String line) {
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        shown.append(line).append('\n');
                        if (shown.length() > 24000) shown.delete(0, shown.length() - 16000);
                        log.setText(shown);
                        sv.post(new Runnable() { @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
                    }
                });
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel.setText("Cancelling…"); engine.cancel(); }
        });

        new Thread(new Runnable() {
            @Override public void run() {
                final BuildEngine.Result r = engine.build();
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!Prefs.bool(act, Prefs.KEEP_AWAKE, true)) { /* nothing */ }
                        act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        dlg.dismiss();
                        if (r.success) showSuccess(act, p, r);
                        else if (r.cancelled) UI.toast(act, "Build cancelled");
                        else showErrors(act, p, r);
                        done.onFinished(r);
                    }
                });
            }
        }, "buildora-build").start();
    }

    private static void showSuccess(final Activity act, final Project p, final BuildEngine.Result r) {
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 4), UI.dp(act, 20), UI.dp(act, 8));
        TextView info = UI.label(act, p.apkFile().getName() + "  •  " + FileUtil.human(r.apk.length())
                + "\nBuilt in " + String.format(java.util.Locale.US, "%.1f", r.millis / 1000.0) + " s"
                + "\n\n" + r.apk.getAbsolutePath(), 13, true);
        box.addView(info);
        final AlertDialog[] d = new AlertDialog[1];
        TextView download = UI.button(act, "Download", true);
        TextView install = UI.button(act, "Install", false);
        TextView share = UI.button(act, "Share", false);
        TextView close = UI.button(act, "Close", false);
        box.addView(download);
        box.addView(install);
        box.addView(share);
        box.addView(close);
        download.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    File f = Installer.copyToDownloads(r.apk);
                    UI.toastLong(act, "Saved to " + f.getAbsolutePath());
                } catch (Exception e) {
                    UI.toastLong(act, "Download failed: " + e.getMessage());
                }
            }
        });
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Installer.install(act, r.apk); }
        });
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Installer.share(act, r.apk); }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d[0].dismiss(); }
        });
        d[0] = UI.dialog(act).setTitle("Build successful").setView(box).create();
        d[0].show();
        if (Prefs.bool(act, Prefs.AUTO_INSTALL, false)) Installer.install(act, r.apk);
    }

    static void showErrors(final Activity act, final Project p, final BuildEngine.Result r) {
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 4), UI.dp(act, 20), UI.dp(act, 8));
        final TextView t = new TextView(act);
        t.setText(r.errorText);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(UI.color(act, R.attr.bText));
        t.setPadding(UI.dp(act, 10), UI.dp(act, 8), UI.dp(act, 10), UI.dp(act, 8));
        t.setBackgroundResource(R.drawable.bg_input);
        ScrollView sv = new ScrollView(act);
        sv.addView(t);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 300)));
        LinearLayout row = UI.hbox(act);
        TextView copy = UI.button(act, "Copy all", true);
        TextView close = UI.button(act, "Close", false);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View gap = new View(act);
        row.addView(gap, new LinearLayout.LayoutParams(UI.dp(act, 8), 1));
        row.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setGravity(Gravity.CENTER);
        box.addView(row);
        final AlertDialog[] d = new AlertDialog[1];
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UI.copy(act, "Buildora build errors", r.errorText);
                UI.toast(act, "All errors copied");
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d[0].dismiss(); }
        });
        d[0] = UI.dialog(act).setTitle("Build failed").setView(box).create();
        d[0].show();
    }
}
