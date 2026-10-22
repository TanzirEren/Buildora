package com.tenix.buildora;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.tenix.buildora.build.AndroidBuildEnv;
import com.tenix.buildora.build.BuildEngine;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.ModuleCfg;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;
import com.tenix.buildora.core.Variant;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Visual preview of an XML layout with device sizes, day/night, tap-to-inspect and jump to the XML line. */
public class PreviewActivity extends BaseActivity {

    private static final String[] DEVICES = {"Small phone 320×568", "Phone 360×720", "Large phone 412×892", "Tablet 600×960", "Tablet 10\" 800×1280"};
    private static final int[][] SIZES = {{320, 568}, {360, 720}, {412, 892}, {600, 960}, {800, 1280}};

    private Project project;
    private File apk;
    private boolean useClasses;
    private String layoutName = "";
    private int device = 1;
    private boolean landscape, night, preparing;
    private final List<String> layouts = new ArrayList<>();
    private FrameLayout stage;
    private TextView tvInfo, tvStatus;
    private View goTo;
    private ProgressBar progress;
    private LayoutRenderer.Result current;
    private int selectedLine = -1;
    private Spinner layoutSpinner;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String r = getIntent().getStringExtra("root");
        if (r == null) { finish(); return; }
        project = Project.load(new File(r));
        layoutName = getIntent().getStringExtra("layout") == null ? "" : getIntent().getStringExtra("layout");
        collectLayouts();
        if (layoutName.isEmpty() && !layouts.isEmpty()) layoutName = layouts.get(0);

        LinearLayout root = UI.vbox(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);
        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0);
        ImageButton back = icon(R.drawable.ic_arrow_back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        TextView title = UI.label(this, "Layout preview", 19, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton rot = icon(R.drawable.ic_refresh);
        rot.setRotation(90);
        rot.setOnClickListener(v -> { landscape = !landscape; draw(); });
        top.addView(rot, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        final TextView mode = UI.label(this, "☀", 20, false);
        mode.setGravity(android.view.Gravity.CENTER);
        mode.setOnClickListener(v -> { night = !night; ((TextView) v).setText(night ? "☾" : "☀"); draw(); });
        top.addView(mode, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        ImageButton shot = icon(R.drawable.ic_image);
        shot.setOnClickListener(v -> screenshot());
        top.addView(shot, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        ImageButton refresh = icon(R.drawable.ic_refresh);
        refresh.setOnClickListener(v -> prepare(true));
        top.addView(refresh, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));

        LinearLayout row = UI.hbox(this);
        row.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 6));
        layoutSpinner = new Spinner(this);
        layoutSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, layouts));
        layoutSpinner.setSelection(Math.max(0, layouts.indexOf(layoutName)));
        layoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String n = layouts.get(pos);
                if (!n.equals(layoutName)) { layoutName = n; draw(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        row.addView(layoutSpinner, new LinearLayout.LayoutParams(0, UI.dp(this, 40), 1));
        Spinner dev = new Spinner(this);
        dev.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, DEVICES));
        dev.setSelection(device);
        dev.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (pos != device) { device = pos; draw(); } }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, UI.dp(this, 40), 1);
        dlp.leftMargin = UI.dp(this, 6);
        row.addView(dev, dlp);
        root.addView(row);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 3)));
        tvStatus = UI.label(this, "", 11, true);
        tvStatus.setPadding(UI.dp(this, 14), UI.dp(this, 2), UI.dp(this, 14), UI.dp(this, 2));
        root.addView(tvStatus);

        ScrollView sv = new ScrollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        stage = new FrameLayout(this);
        stage.setPadding(UI.dp(this, 12), UI.dp(this, 6), UI.dp(this, 12), UI.dp(this, 20));
        hs.addView(stage);
        sv.addView(hs);
        sv.setBackgroundColor(UI.color(this, R.attr.bSurface2));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = UI.hbox(this);
        bottom.setPadding(UI.dp(this, 14), UI.dp(this, 6), UI.dp(this, 8), UI.dp(this, 6));
        bottom.setBackgroundColor(UI.color(this, R.attr.bSurface));
        tvInfo = UI.label(this, "Tap a view to inspect it", 12, true);
        bottom.addView(tvInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView go = UI.button(this, "Go to XML", true);
        go.setTextSize(12);
        go.setPadding(UI.dp(this, 12), UI.dp(this, 7), UI.dp(this, 12), UI.dp(this, 7));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        go.setLayoutParams(glp);
        go.setVisibility(View.GONE);
        go.setOnClickListener(v -> {
            Intent res = new Intent().putExtra("line", selectedLine).putExtra("layout", layoutName);
            setResult(RESULT_OK, res);
            finish();
        });
        goTo = go;
        bottom.addView(go);
        root.addView(bottom);
        setContentView(root);
        styleBars();
        prepare(false);
    }

    private ImageButton icon(int res) {
        ImageButton b = new ImageButton(this, null, 0, R.style.BIconBtn);
        b.setImageResource(res);
        return b;
    }

    private List<File> resDirs() {
        List<File> out = new ArrayList<>();
        if (project.resDir != null) out.add(project.resDir);
        for (ModuleCfg m : project.orderedModules()) if (m.resDir != null) out.add(m.resDir);
        Variant v = project.activeVariant();
        for (File d : new File[]{v.flavorDir, v.typeDir}) if (d != null) out.add(new File(d, "res"));
        return out;
    }

    private void collectLayouts() {
        TreeSet<String> set = new TreeSet<>();
        for (File res : resDirs()) {
            File[] dirs = res.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (!d.getName().equals("layout") && !d.getName().startsWith("layout-")) continue;
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.getName().endsWith(".xml")) set.add(f.getName().substring(0, f.getName().length() - 4));
            }
        }
        layouts.addAll(set);
    }

    private long newestResource() {
        long t = project.manifestFile.isFile() ? project.manifestFile.lastModified() : 0;
        for (File res : resDirs()) for (File f : FileUtil.listFiles(res, null)) t = Math.max(t, f.lastModified());
        return t;
    }

    /** Uses the last full build when it is newer than every resource, otherwise builds only the resources (fast). */
    private void prepare(final boolean force) {
        if (preparing) return;
        if (layouts.isEmpty()) { tvStatus.setText("This project has no layout files (res/layout)."); return; }
        final Variant v = project.activeVariant();
        File full = v.apkFile(project);
        final long newest = newestResource();
        if (!force && full.isFile() && full.lastModified() >= newest) {
            apk = full; useClasses = true;
            tvStatus.setText("Using your last build" );
            draw();
            return;
        }
        preparing = true;
        progress.setVisibility(View.VISIBLE);
        tvStatus.setText("Preparing resources…");
        new Thread(new Runnable() {
            @Override public void run() {
                BuildEngine.Options o = new BuildEngine.Options();
                o.variant = v;
                o.previewOnly = true;
                final BuildEngine.Result r = new BuildEngine(new AndroidBuildEnv(PreviewActivity.this), project, o, new BuildEngine.Listener() {
                    @Override public void onStage(String s, int p) { }
                    @Override public void onLog(String l) { }
                }).build();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        preparing = false;
                        progress.setVisibility(View.GONE);
                        if (!r.success) {
                            tvStatus.setText("Resources have errors - fix them and refresh.");
                            BuildRunnerErrors.show(PreviewActivity.this, r);
                            return;
                        }
                        apk = r.apk;
                        useClasses = false;
                        tvStatus.setText("Custom / library views appear as boxes until you run a build");
                        draw();
                    }
                });
            }
        }, "buildora-preview").start();
    }

    private void draw() {
        if (apk == null || layoutName.isEmpty()) return;
        stage.removeAllViews();
        selectedLine = -1;
        goTo.setVisibility(View.GONE);
        tvInfo.setText("Tap a view to inspect it");
        LayoutRenderer.Config cfg = new LayoutRenderer.Config();
        int w = SIZES[device][0], h = SIZES[device][1];
        cfg.widthDp = landscape ? h : w;
        cfg.heightDp = landscape ? w : h;
        cfg.night = night;
        current = LayoutRenderer.render(this, apk, useClasses, layoutName, cfg);
        if (current.error != null) {
            TextView t = UI.label(this, "Could not render this layout:\n\n" + current.error, 12, false);
            t.setTextIsSelectable(true);
            t.setTextColor(UI.color(this, R.attr.bError));
            stage.addView(t);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int wpx = Math.round(cfg.widthDp * density), hpx = Math.round(cfg.heightDp * density);
        final LayoutRenderer.PreviewFrame frame = new LayoutRenderer.PreviewFrame(this);
        android.graphics.drawable.Drawable bg = LayoutRenderer.windowBackground(current.root.getContext());
        if (bg != null) frame.setBackground(bg); else frame.setBackgroundColor(night ? 0xFF121212 : 0xFFFFFFFF);
        frame.addView(current.root, new FrameLayout.LayoutParams(wpx, hpx));
        int avail = getResources().getDisplayMetrics().widthPixels - UI.dp(this, 24);
        float scale = Math.min(1f, avail / (float) wpx);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(wpx, hpx);
        frame.setPivotX(0);
        frame.setPivotY(0);
        frame.setScaleX(scale);
        frame.setScaleY(scale);
        wrap.addView(frame, flp);
        stage.addView(wrap, new FrameLayout.LayoutParams(Math.round(wpx * scale), Math.round(hpx * scale)));
        // force layout at the exact size
        frame.measure(View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hpx, View.MeasureSpec.EXACTLY));
        frame.setOnTap((x, y) -> inspect(frame, x, y, density));
        tvStatus.setText(DEVICES[device] + (landscape ? " (landscape)" : "") + (night ? " • night" : " • day"));
    }

    private void inspect(LayoutRenderer.PreviewFrame frame, float x, float y, float density) {
        View root = current.root;
        View v = LayoutRenderer.hit(root, x, y);
        if (v == null || v == root) { frame.setHighlight(null); tvInfo.setText("Tap a view to inspect it"); goTo.setVisibility(View.GONE); return; }
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(b);
        float scale = frame.getScaleX();
        int l = (int) ((a[0] - b[0]) / scale), t = (int) ((a[1] - b[1]) / scale);
        frame.setHighlight(new Rect(l, t, l + v.getWidth(), t + v.getHeight()));
        String id = "";
        try { if (v.getId() != View.NO_ID) id = "  @id/" + current.res.getResourceEntryName(v.getId()); } catch (Throwable ignored) { }
        Integer line = current.lines.get(v);
        selectedLine = line == null ? -1 : line;
        String cls = v instanceof LayoutRenderer.Placeholder ? "custom view" : v.getClass().getSimpleName();
        tvInfo.setText(cls + id + "\n" + Math.round(v.getWidth() / density) + " × " + Math.round(v.getHeight() / density) + " dp" + (selectedLine > 0 ? "  •  line " + selectedLine : ""));
        goTo.setVisibility(selectedLine > 0 ? View.VISIBLE : View.GONE);
    }

    private void screenshot() {
        if (stage.getChildCount() == 0 || current == null || current.root == null) return;
        try {
            View frame = ((ViewGroup) stage.getChildAt(0)).getChildAt(0);
            Bitmap bmp = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
            float sx = frame.getScaleX();
            frame.setScaleX(1f); frame.setScaleY(1f);
            frame.draw(new Canvas(bmp));
            frame.setScaleX(sx); frame.setScaleY(sx);
            File out = new File(project.root, "outputs/preview/" + layoutName + "-" + SIZES[device][0] + "x" + SIZES[device][1] + (night ? "-night" : "") + ".png");
            out.getParentFile().mkdirs();
            com.tenix.buildora.core.IconUtil.save(bmp, out);
            UI.toastLong(this, "Saved " + out.getAbsolutePath());
        } catch (Exception e) {
            UI.toast(this, "Screenshot failed: " + e.getMessage());
        }
    }

    /** Minimal error dialog for the resource-only build. */
    static final class BuildRunnerErrors {
        static void show(PreviewActivity a, BuildEngine.Result r) {
            TextView t = new TextView(a);
            t.setText(r.errorText);
            t.setTextIsSelectable(true);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(11);
            t.setTextColor(UI.color(a, R.attr.bText));
            t.setPadding(UI.dp(a, 16), UI.dp(a, 8), UI.dp(a, 16), UI.dp(a, 8));
            ScrollView sv = new ScrollView(a);
            sv.addView(t);
            UI.dialog(a).setTitle("Resource errors").setView(sv).setPositiveButton("Close", null).show();
        }
    }
}
