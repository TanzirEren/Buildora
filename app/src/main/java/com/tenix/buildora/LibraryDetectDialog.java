package com.tenix.buildora;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.build.Catalog;
import com.tenix.buildora.build.LibraryDetector;
import com.tenix.buildora.core.ModuleCfg;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;
import com.tenix.buildora.core.Variant;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** "Libraries your code needs": reads imports and layout tags, then adds and downloads what is missing. */
final class LibraryDetectDialog {
    private LibraryDetectDialog() {}

    static void show(final Activity act, final Project project, final Runnable after) {
        List<File> java = new ArrayList<>(project.javaDirs), res = new ArrayList<>();
        if (project.resDir != null) res.add(project.resDir);
        for (ModuleCfg m : project.orderedModules()) { java.addAll(m.javaDirs); if (m.resDir != null) res.add(m.resDir); }
        Variant v = project.activeVariant();
        for (File d : new File[]{v.flavorDir, v.typeDir}) {
            if (d == null) continue;
            java.add(new File(d, "java"));
            res.add(new File(d, "res"));
        }
        final List<LibraryDetector.Suggestion> sug = LibraryDetector.detect(java, res, project.dependencies);
        if (sug.isEmpty()) { UI.toast(act, "No missing libraries found - your code is covered."); return; }

        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 4), UI.dp(act, 20), UI.dp(act, 4));
        box.addView(UI.label(act, "These libraries are used by your code but not added yet:", 12, true));
        final List<CheckBox> boxes = new ArrayList<>();
        for (LibraryDetector.Suggestion s : sug) {
            LinearLayout row = UI.vbox(act);
            row.setPadding(0, UI.dp(act, 8), 0, 0);
            CheckBox cb = new CheckBox(act);
            cb.setText(s.lib.name);
            cb.setTextColor(UI.color(act, R.attr.bText));
            cb.setTextSize(15);
            cb.setChecked(s.auto());
            cb.setEnabled(s.auto());
            row.addView(cb);
            TextView why = UI.label(act, s.reason + (s.auto() ? "" : "\n" + s.lib.note), 11, true);
            why.setPadding(UI.dp(act, 32), 0, 0, 0);
            row.addView(why);
            box.addView(row);
            boxes.add(cb);
        }
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        UI.dialog(act).setTitle("Detected libraries").setView(sv).setNegativeButton("Cancel", null)
                .setPositiveButton("Add & download", (d, w) -> {
                    for (int i = 0; i < sug.size(); i++) {
                        if (!boxes.get(i).isChecked()) continue;
                        Catalog.Lib lib = sug.get(i).lib;
                        for (String c : lib.coords) {
                            String[] cp = c.split(":");
                            boolean has = false;
                            for (String dep : project.dependencies) { String[] dp = dep.split(":"); if (dp.length >= 2 && dp[0].equals(cp[0]) && dp[1].equals(cp[1])) has = true; }
                            if (!has) project.dependencies.add(c);
                        }
                    }
                    try { project.save(); } catch (Exception e) { UI.toast(act, e.getMessage()); }
                    if (after != null) after.run();
                    DownloadDialog.run(act, project.dependencies, null);
                }).show();
    }
}
