package com.tenix.buildora;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.build.Catalog;
import com.tenix.buildora.build.MavenResolver;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.FilePicker;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-project library manager: Maven coordinates, built-in catalog, local .jar/.aar files. */
public class LibrariesActivity extends BaseActivity {

    private Project project;
    private LinearLayout depList, localList;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String r = getIntent().getStringExtra("root");
        if (r == null) { finish(); return; }
        project = Project.load(new File(r));

        LinearLayout root = UI.vbox(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);
        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 6), 0, UI.dp(this, 12), 0);
        ImageButton back = new ImageButton(this, null, 0, R.style.BIconBtn);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 56)));
        TextView title = UI.label(this, "Libraries", 20, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 56)));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = UI.vbox(this);
        body.setPadding(UI.dp(this, 16), UI.dp(this, 4), UI.dp(this, 16), UI.dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        body.addView(UI.label(this, project.name + " • libraries are downloaded from Google Maven, Maven Central and JitPack, and compiled into your app on every build.", 12, true));
        TextView cat = UI.button(this, "Add from catalog (" + Catalog.ALL.length + " libraries)", true);
        TextView custom = UI.button(this, "Add Maven coordinate", false);
        TextView local = UI.button(this, "Import .jar / .aar file", false);
        TextView dl = UI.button(this, "Download all now", false);
        body.addView(cat);
        body.addView(custom);
        body.addView(local);
        body.addView(dl);
        body.addView(sectionLabel("Dependencies"));
        depList = UI.vbox(this);
        body.addView(depList);
        body.addView(sectionLabel("Local libraries (libs folder)"));
        localList = UI.vbox(this);
        body.addView(localList);
        setContentView(root);
        styleBars();

        cat.setOnClickListener(v -> catalogDialog());
        custom.setOnClickListener(v -> customDialog());
        local.setOnClickListener(v -> importLocal());
        dl.setOnClickListener(v -> downloadNow());
        render();
    }

    private TextView sectionLabel(String t) {
        TextView s = UI.label(this, t.toUpperCase(Locale.ROOT), 11, true);
        s.setTextColor(UI.color(this, R.attr.bAccent));
        s.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        s.setPadding(UI.dp(this, 4), UI.dp(this, 22), 0, UI.dp(this, 6));
        return s;
    }

    private File libDir() {
        if (!project.libDirs.isEmpty()) return project.libDirs.get(0);
        File app = new File(project.root, "app");
        File d = app.isDirectory() ? new File(app, "libs") : new File(project.root, "libs");
        d.mkdirs();
        return d;
    }

    private void save() {
        try { project.save(); } catch (Exception e) { UI.toast(this, e.getMessage()); }
    }

    private void render() {
        depList.removeAllViews();
        if (project.dependencies.isEmpty()) depList.addView(UI.label(this, "No dependencies yet.", 13, true));
        for (final String d : new ArrayList<>(project.dependencies)) {
            LinearLayout r = UI.hbox(this);
            r.setBackgroundResource(R.drawable.bg_card);
            r.setPadding(UI.dp(this, 14), UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UI.dp(this, 6);
            r.setLayoutParams(lp);
            TextView t = UI.label(this, d, 13, false);
            t.setTypeface(Typeface.MONOSPACE);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton x = new ImageButton(this, null, 0, R.style.BIconBtn);
            x.setImageResource(R.drawable.ic_close);
            x.setOnClickListener(v -> { project.dependencies.remove(d); save(); render(); });
            r.addView(x, new LinearLayout.LayoutParams(UI.dp(this, 36), UI.dp(this, 36)));
            depList.addView(r);
        }
        localList.removeAllViews();
        boolean any = false;
        for (File dir : project.libDirs) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (final File f : fs) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".jar") && !n.endsWith(".aar")) continue;
                any = true;
                LinearLayout r = UI.hbox(this);
                r.setBackgroundResource(R.drawable.bg_card);
                r.setPadding(UI.dp(this, 14), UI.dp(this, 10), UI.dp(this, 6), UI.dp(this, 10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = UI.dp(this, 6);
                r.setLayoutParams(lp);
                r.addView(UI.label(this, f.getName() + "  •  " + FileUtil.human(f.length()), 13, false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                ImageButton x = new ImageButton(this, null, 0, R.style.BIconBtn);
                x.setImageResource(R.drawable.ic_delete);
                x.setOnClickListener(v -> { f.delete(); render(); });
                r.addView(x, new LinearLayout.LayoutParams(UI.dp(this, 36), UI.dp(this, 36)));
                localList.addView(r);
            }
        }
        if (!any) localList.addView(UI.label(this, "No local .jar / .aar files.", 13, true));
    }

    private boolean addDependency(String coord) {
        String[] p = coord.trim().split(":");
        if (p.length < 3) { UI.toast(this, "Use group:artifact:version"); return false; }
        String ga = p[0] + ":" + p[1];
        for (int i = 0; i < project.dependencies.size(); i++) {
            String[] q = project.dependencies.get(i).split(":");
            if (q.length >= 2 && (q[0] + ":" + q[1]).equals(ga)) {
                project.dependencies.set(i, coord.trim());
                save();
                render();
                return true;
            }
        }
        project.dependencies.add(coord.trim());
        save();
        render();
        return true;
    }

    // ------------------------------------------------------------------ dialogs

    private void customDialog() {
        final EditText e = UI.input(this, "group:artifact:version", "");
        LinearLayout box = UI.vbox(this);
        box.setPadding(UI.dp(this, 20), UI.dp(this, 4), UI.dp(this, 20), 0);
        box.addView(e);
        box.addView(UI.label(this, "Example: com.google.code.gson:gson:2.10.1", 11, true));
        UI.dialog(this).setTitle("Add dependency").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> addDependency(e.getText().toString())).show();
    }

    private void catalogDialog() {
        LinearLayout box = UI.vbox(this);
        box.setPadding(UI.dp(this, 16), UI.dp(this, 4), UI.dp(this, 16), 0);
        final EditText search = UI.input(this, "Search libraries…", "");
        box.addView(search);
        final ListView lv = new ListView(this);
        lv.setDivider(null);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 380)));
        final List<Catalog.Lib> shown = new ArrayList<>();
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                Catalog.Lib l = shown.get(i);
                LinearLayout r = UI.vbox(LibrariesActivity.this);
                r.setPadding(UI.dp(LibrariesActivity.this, 6), UI.dp(LibrariesActivity.this, 9), UI.dp(LibrariesActivity.this, 6), UI.dp(LibrariesActivity.this, 9));
                r.addView(UI.label(LibrariesActivity.this, l.name + "   · " + l.group, 15, false));
                r.addView(UI.label(LibrariesActivity.this, l.desc, 12, true));
                TextView c = UI.label(LibrariesActivity.this, l.coord, 11, true);
                c.setTypeface(Typeface.MONOSPACE);
                r.addView(c);
                return r;
            }
        };
        lv.setAdapter(ad);
        final Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Catalog.Lib l : Catalog.ALL) {
                if (q.isEmpty() || l.name.toLowerCase(Locale.ROOT).contains(q) || l.coord.toLowerCase(Locale.ROOT).contains(q)
                        || l.group.toLowerCase(Locale.ROOT).contains(q) || l.desc.toLowerCase(Locale.ROOT).contains(q)) shown.add(l);
            }
            ad.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { filter.run(); }
        });
        filter.run();
        final AlertDialog dlg = UI.dialog(this).setTitle("Library catalog").setView(box).setNegativeButton("Close", null).create();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            Catalog.Lib l = shown.get(pos);
            if (addDependency(l.coord)) UI.toast(this, "Added " + l.name);
        });
        dlg.show();
    }

    private void importLocal() {
        FilePicker.pickFile(this, "Select .jar or .aar", android.os.Environment.getExternalStorageDirectory(),
                new String[]{"jar", "aar"}, f -> {
                    try {
                        FileUtil.copyFile(f, FileUtil.unique(new File(libDir(), f.getName())));
                        project.detectLayout();
                        render();
                        UI.toast(this, "Imported " + f.getName());
                    } catch (Exception e) { UI.toast(this, "Failed: " + e.getMessage()); }
                });
    }

    private void downloadNow() {
        if (project.dependencies.isEmpty()) { UI.toast(this, "Nothing to download"); return; }
        LinearLayout box = UI.vbox(this);
        box.setPadding(UI.dp(this, 20), UI.dp(this, 8), UI.dp(this, 20), 0);
        box.addView(new ProgressBar(this));
        final TextView log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(10);
        log.setTextColor(UI.color(this, R.attr.bTextDim));
        final ScrollView sv = new ScrollView(this);
        sv.addView(log);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 240)));
        final AlertDialog dlg = UI.dialog(this).setTitle("Downloading libraries").setView(box).setPositiveButton("Close", null).create();
        dlg.show();
        final StringBuilder sb = new StringBuilder();
        new Thread(() -> {
            String result;
            try {
                MavenResolver r = new MavenResolver(new File(getFilesDir(), "m2"), s -> runOnUiThread(() -> {
                    sb.append(s).append('\n');
                    log.setText(sb);
                    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                }));
                int n = r.resolve(project.dependencies).size();
                result = "Done: " + n + " artifacts ready";
            } catch (Exception e) { result = "Failed: " + e.getMessage(); }
            final String res = result;
            runOnUiThread(() -> { sb.append('\n').append(res); log.setText(sb); dlg.setTitle(res); });
        }).start();
    }
}
