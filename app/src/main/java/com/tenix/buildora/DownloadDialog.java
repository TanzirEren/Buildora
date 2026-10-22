package com.tenix.buildora;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.build.MavenResolver;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.UI;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A dialog that shows libraries being downloaded: current file, bytes, files done, and a log. */
final class DownloadDialog {
    private DownloadDialog() {}

    static void run(final Activity act, final List<String> coords, final Runnable onSuccess) {
        if (coords.isEmpty()) { UI.toast(act, "Nothing to download"); return; }
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 8), UI.dp(act, 20), UI.dp(act, 4));
        final TextView head = UI.label(act, "Preparing…", 15, false);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(head);
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 6));
        blp.topMargin = UI.dp(act, 10);
        box.addView(bar, blp);
        final TextView file = UI.label(act, " ", 12, true);
        file.setPadding(0, UI.dp(act, 6), 0, 0);
        box.addView(file);
        final TextView count = UI.label(act, " ", 12, true);
        box.addView(count);
        final TextView log = new TextView(act);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(10);
        log.setTextColor(UI.color(act, R.attr.bTextDim));
        final ScrollView sv = new ScrollView(act);
        sv.addView(log);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 170));
        slp.topMargin = UI.dp(act, 10);
        box.addView(sv, slp);
        final AlertDialog dlg = UI.dialog(act).setTitle("Downloading libraries").setView(box).setPositiveButton("Close", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        final StringBuilder sb = new StringBuilder();
        final List<String> list = new ArrayList<>(coords);
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                boolean ok = false;
                try {
                    MavenResolver r = new MavenResolver(new File(act.getFilesDir(), "m2"), new MavenResolver.Log() {
                        @Override public void log(final String s) {
                            act.runOnUiThread(new Runnable() { @Override public void run() {
                                sb.append(s).append('\n'); log.setText(sb);
                                sv.post(new Runnable() { @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
                            } });
                        }
                    });
                    r.setProgress(new MavenResolver.Progress() {
                        @Override public void onProgress(final String name, final long done, final long total, final int filesDone) {
                            act.runOnUiThread(new Runnable() { @Override public void run() {
                                head.setText("Downloading…");
                                file.setText(name + (total > 0 ? "   " + FileUtil.human(done) + " / " + FileUtil.human(total) : "   " + FileUtil.human(done)));
                                bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                                count.setText(filesDone + (filesDone == 1 ? " file" : " files") + " downloaded");
                            } });
                        }
                    });
                    int n = r.resolve(list).size();
                    result = "Done - " + n + " libraries ready";
                    ok = true;
                } catch (Exception e) {
                    result = "Failed: " + e.getMessage();
                }
                final String res = result;
                final boolean success = ok;
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    head.setText(res);
                    bar.setProgress(success ? 1000 : 0);
                    dlg.setTitle(success ? "Libraries ready" : "Download problem");
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (success && onSuccess != null) onSuccess.run();
                } });
            }
        }, "buildora-download").start();
    }
}
