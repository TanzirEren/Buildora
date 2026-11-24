package com.tenix.buildora;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.tenix.buildora.apt.AptConfig;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.FormDialog;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Annotation processors of a project: add (Maven coordinate or local .jar), enable/disable, per-build-type
 * scope, processor options, timeout, isolation and incremental switches, generated sources.
 * Saved straight into buildora.json ("annotationProcessing"); Gradle-style lines can be pasted in.
 */
public class AptActivity extends BaseActivity {

    private Project project;
    private LinearLayout body;

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
        TextView title = UI.label(this, "Annotation processors", 20, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton add = new ImageButton(this, null, 0, R.style.BIconBtn);
        add.setImageResource(R.drawable.ic_add);
        add.setOnClickListener(v -> addDialog());
        top.addView(add, new LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 56)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 56)));

        ScrollView sv = new ScrollView(this);
        body = UI.vbox(this);
        body.setPadding(UI.dp(this, 16), UI.dp(this, 4), UI.dp(this, 16), UI.dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        styleBars();
        render();
    }

    private void save() {
        try { project.save(); } catch (Exception e) { UI.toast(this, e.getMessage()); }
    }

    private void section(String s) {
        TextView h = UI.label(this, s, 11, true);
        h.setTextColor(UI.color(this, R.attr.bAccent));
        h.setPadding(UI.dp(this, 4), UI.dp(this, 18), 0, UI.dp(this, 4));
        body.addView(h);
    }

    private void render() {
        body.removeAllViews();
        final AptConfig c = project.apt;
        body.addView(UI.label(this, "Processors generate code at build time (Room, Glide, Dagger, AutoValue...). "
                + "Buildora downloads them and their dependencies, runs them in a separate process and adds the generated "
                + "sources to the build. The library itself (for example room-runtime) is added in Libraries.", 12, true));

        Switch master = new Switch(this);
        master.setText("Run annotation processing");
        master.setTextColor(UI.color(this, R.attr.bText));
        master.setChecked(c.enabled);
        master.setPadding(UI.dp(this, 4), UI.dp(this, 14), UI.dp(this, 4), UI.dp(this, 6));
        master.setOnCheckedChangeListener((v, on) -> { c.enabled = on; save(); });
        body.addView(master);

        section("PROCESSORS");
        if (c.processors.isEmpty()) body.addView(UI.label(this, "None yet. Tap + to add one, for example\n  androidx.room:room-compiler:2.6.1", 13, true));
        for (final AptConfig.Entry e : c.processors) {
            LinearLayout row = UI.vbox(this);
            row.setPadding(UI.dp(this, 4), UI.dp(this, 8), UI.dp(this, 4), UI.dp(this, 8));
            Switch sw = new Switch(this);
            sw.setText(e.artifactName());
            sw.setTextColor(UI.color(this, R.attr.bText));
            sw.setChecked(e.enabled);
            sw.setOnCheckedChangeListener((v, on) -> { e.enabled = on; save(); });
            row.addView(sw);
            String sub = e.coord + "   (hold to remove)" + (e.variants.isEmpty() ? "" : "\nOnly for: " + join(e.variants))
                    + (e.classes.isEmpty() ? "" : "\nProcessors: " + join(e.classes));
            TextView t = UI.label(this, sub, 12, true);
            t.setPadding(UI.dp(this, 4), 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(v -> editDialog(e));
            row.setOnLongClickListener(v -> {
                UI.dialog(this).setTitle("Remove " + e.artifactName() + "?").setMessage(e.coord)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> { c.remove(e.coord); save(); render(); }).show();
                return true;
            });
            body.addView(row);
        }
        if (!c.unsupported.isEmpty()) {
            section("NOT SUPPORTED (from an imported Gradle file)");
            for (String u : c.unsupported) body.addView(UI.label(this, "\u2022 " + u, 12, true));
        }

        section("OPTIONS (-A arguments)");
        if (c.options.isEmpty()) body.addView(UI.label(this, "No options. Example: room.schemaLocation = /storage/emulated/0/Buildora/MyApp/schemas", 12, true));
        for (final Map.Entry<String, String> o : c.options.entrySet()) {
            TextView t = UI.label(this, o.getKey() + " = " + o.getValue(), 13, false);
            t.setPadding(UI.dp(this, 4), UI.dp(this, 6), 0, UI.dp(this, 6));
            t.setOnClickListener(v -> optionDialog(o.getKey(), o.getValue()));
            body.addView(t);
        }
        TextView addOpt = UI.button(this, "Add option", false);
        addOpt.setOnClickListener(v -> optionDialog(null, ""));
        body.addView(addOpt);

        section("EXECUTION");
        body.addView(toggle("Run in a separate process", "A crashing, hanging or memory-hungry processor cannot take Buildora down.", c.isolate, on -> c.isolate = on));
        body.addView(toggle("Incremental processing", "Only regenerate code of changed files when every processor is declared isolating.", c.incremental, on -> c.incremental = on));
        body.addView(toggle("Raw processing environment", "Compatibility switch: processors get the compiler's own environment. Disables error attribution and incremental processing.", c.rawEnvironment, on -> c.rawEnvironment = on));
        TextView to = UI.button(this, "Timeout: " + c.timeoutSec + " s", false);
        to.setOnClickListener(v -> new FormDialog(this, "Processing timeout").number("t", "Seconds (30 - 3600)", c.timeoutSec)
                .show("Save", values -> {
                    int t = (Integer) values.get("t");
                    if (t < 30 || t > 3600) return "Use 30 to 3600 seconds";
                    c.timeoutSec = t; save(); render(); return null;
                }));
        body.addView(to);

        section("GENERATED SOURCES");
        File aptDir = new File(project.root, ".buildora/cache");
        boolean any = false;
        File[] variants = aptDir.listFiles();
        if (variants != null) for (File v : variants) {
            final File gen = new File(v, "apt/gen");
            List<File> files = FileUtil.listFiles(gen, ".java");
            if (files.isEmpty()) continue;
            any = true;
            TextView h = UI.label(this, v.getName() + "  \u2022  " + files.size() + " file" + (files.size() == 1 ? "" : "s"), 13, false);
            h.setPadding(UI.dp(this, 4), UI.dp(this, 8), 0, UI.dp(this, 2));
            body.addView(h);
            for (final File f : files) {
                TextView t = UI.label(this, FileUtil.relPath(gen, f), 12, true);
                t.setPadding(UI.dp(this, 12), UI.dp(this, 3), 0, UI.dp(this, 3));
                t.setOnClickListener(x -> showSource(f));
                body.addView(t);
            }
        }
        if (!any) body.addView(UI.label(this, "Nothing generated yet. Run the project once.", 12, true));
        TextView clear = UI.button(this, "Clear generated sources and cache", false);
        clear.setOnClickListener(v -> UI.dialog(this).setTitle("Clear generated sources?")
                .setMessage("The next build runs all processors again over all sources.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    File[] vs = aptDir.listFiles();
                    if (vs != null) for (File x : vs) FileUtil.deleteRecursive(new File(x, "apt"));
                    for (File x : new File[]{new File(project.root, ".buildora/cache")}) { File[] ch = x.listFiles(); if (ch != null) for (File y : ch) new File(y, "java-state.txt").delete(); }
                    UI.toast(this, "Cleared");
                    render();
                }).show());
        body.addView(clear);

        section("GRADLE");
        TextView imp = UI.button(this, "Paste Gradle lines", false);
        imp.setOnClickListener(v -> new FormDialog(this, "Import from Gradle")
                .note("Paste annotationProcessor lines and annotationProcessorOptions arguments. kapt/ksp lines are listed as not supported.")
                .multiline("g", "build.gradle text", "")
                .show("Import", values -> {
                    project.apt.parseGradle((String) values.get("g"));
                    save(); render(); return null;
                }));
        body.addView(imp);
        TextView exp = UI.button(this, "Copy as Gradle lines", false);
        exp.setOnClickListener(v -> {
            String g = project.apt.toGradleLines() + project.apt.toGradleOptions();
            if (g.isEmpty()) { UI.toast(this, "Nothing to copy"); return; }
            UI.copy(this, "annotationProcessor", g);
            UI.toast(this, "Copied");
        });
        body.addView(exp);
        TextView hint = UI.label(this, "Honest limits: processors run through ECJ's JSR 269 engine at Java 8 level. Gradle plugins (Hilt's "
                + "class transform, KSP, kapt) are not available. Processors that need javac internals (com.sun.tools.javac, e.g. Lombok) "
                + "do not work.", 12, true);
        hint.setPadding(UI.dp(this, 4), UI.dp(this, 14), UI.dp(this, 4), 0);
        body.addView(hint);
    }

    private interface Flag { void set(boolean on); }

    private android.view.View toggle(String title, String desc, boolean value, final Flag f) {
        LinearLayout row = UI.vbox(this);
        row.setPadding(UI.dp(this, 4), UI.dp(this, 8), UI.dp(this, 4), UI.dp(this, 4));
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(UI.color(this, R.attr.bText));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((v, on) -> { f.set(on); save(); });
        row.addView(sw);
        row.addView(UI.label(this, desc, 12, true));
        return row;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    private static List<String> split(String s) {
        List<String> out = new java.util.ArrayList<>();
        for (String x : s.split("[,\\s]+")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    private void addDialog() {
        new FormDialog(this, "Add annotation processor")
                .note("Maven coordinate group:artifact:version (downloaded from Google Maven, Maven Central or JitPack with its dependencies) "
                        + "or the path of a local .jar (relative to the project).\nExample: androidx.room:room-compiler:2.6.1")
                .text("coord", "Coordinate or .jar path", "")
                .show("Add", values -> {
                    String coord = ((String) values.get("coord")).trim();
                    if (coord.isEmpty()) return "Enter a coordinate or a jar path";
                    boolean local = coord.endsWith(".jar") || coord.contains("/");
                    if (!local && coord.split(":").length < 2) return "Use group:artifact:version";
                    project.apt.add(coord);
                    save(); render();
                    return null;
                });
    }

    private void editDialog(final AptConfig.Entry e) {
        new FormDialog(this, e.artifactName())
                .text("coord", "Coordinate or .jar path", e.coord)
                .text("variants", "Only for build types / variants (comma separated, empty = all)", join(e.variants))
                .text("classes", "Processor classes (empty = discover automatically)", join(e.classes))
                .show("Save", values -> {
                    String coord = ((String) values.get("coord")).trim();
                    if (coord.isEmpty()) return "Enter a coordinate or a jar path";
                    e.coord = coord;
                    e.variants.clear(); e.variants.addAll(split((String) values.get("variants")));
                    e.classes.clear(); e.classes.addAll(split((String) values.get("classes")));
                    save(); render();
                    return null;
                });
    }

    private void optionDialog(final String key, String value) {
        new FormDialog(this, key == null ? "Add option" : "Option")
                .text("k", "Name (passed as -Aname=value)", key == null ? "" : key)
                .text("v", "Value (empty removes the option)", value)
                .show("Save", values -> {
                    String k = ((String) values.get("k")).trim();
                    String v = (String) values.get("v");
                    if (!k.matches("[\\w.\\-]+")) return "Names may contain letters, digits, . _ -";
                    if (key != null && !key.equals(k)) project.apt.options.remove(key);
                    if (v.isEmpty()) project.apt.options.remove(k); else project.apt.options.put(k, v);
                    save(); render();
                    return null;
                });
    }

    private void showSource(File f) {
        String text;
        try { text = FileUtil.readText(f); } catch (Exception e) { text = e.getMessage(); }
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(UI.color(this, R.attr.bText));
        t.setPadding(UI.dp(this, 16), UI.dp(this, 8), UI.dp(this, 16), UI.dp(this, 8));
        t.setTextIsSelectable(true);
        t.setText(text);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        UI.dialog(this).setTitle(f.getName() + " (generated, read-only)").setView(sv).setPositiveButton("Close", null).show();
    }

    @Override protected void onPause() {
        super.onPause();
        save();
    }
}
