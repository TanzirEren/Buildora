package com.tenix.buildora;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.tenix.buildora.assist.LogcatParser;
import com.tenix.buildora.core.CrashStore;
import com.tenix.buildora.core.UI;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Live logcat of the phone with filters, plus crash reports of apps built in debug mode. */
public class LogcatActivity extends BaseActivity {

    private static final String GRANT = "adb shell pm grant com.tenix.buildora android.permission.READ_LOGS";

    private final List<LogcatParser.Line> all = new ArrayList<>(), shown = new ArrayList<>(), pending = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ListView logList, crashList;
    private BaseAdapter logAdapter, crashAdapter;
    private TextView tvInfo;
    private View banner, filterRow;
    private Process proc;
    private volatile boolean running, paused;
    private volatile int pid;
    private boolean stick = true, flushQueued;
    private String pkg = "", query = "";
    private int minLevel;
    private boolean myAppOnly;
    private List<CrashStore.Crash> crashes = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String p = getIntent().getStringExtra("package");
        pkg = p == null ? "" : p;
        myAppOnly = !pkg.isEmpty();

        LinearLayout root = UI.vbox(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);

        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0);
        ImageButton back = icon(R.drawable.ic_arrow_back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        TextView title = UI.label(this, "Logcat", 19, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final ImageButton pause = icon(R.drawable.ic_play);
        pause.setRotation(0);
        pause.setOnClickListener(v -> { paused = !paused; pause.setAlpha(paused ? 0.4f : 1f); UI.toast(this, paused ? "Paused" : "Live"); if (!paused) rebuild(); });
        top.addView(pause, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        ImageButton clear = icon(R.drawable.ic_delete);
        clear.setOnClickListener(v -> clearAll());
        top.addView(clear, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        ImageButton copy = icon(R.drawable.ic_copy);
        copy.setOnClickListener(v -> copyVisible());
        top.addView(copy, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 52)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));

        LinearLayout tabs = UI.hbox(this);
        tabs.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 6));
        final TextView live = UI.button(this, "Live log", true), crash = UI.button(this, "Crashes", false);
        tabs.addView(live, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tabs.addView(new View(this), new LinearLayout.LayoutParams(UI.dp(this, 8), 1));
        tabs.addView(crash, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (View t : new View[]{live, crash}) ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = 0;
        root.addView(tabs);

        banner = UI.vbox(this);
        ((LinearLayout) banner).setPadding(UI.dp(this, 14), UI.dp(this, 8), UI.dp(this, 14), UI.dp(this, 8));
        banner.setBackgroundResource(R.drawable.bg_card);
        TextView bt = UI.label(this, "Only Buildora's own log is readable right now. To see other apps' logs, grant a one-time permission (Wireless debugging + Termux or a PC):\n" + GRANT, 11, true);
        bt.setTextIsSelectable(true);
        ((LinearLayout) banner).addView(bt);
        TextView cp = UI.button(this, "Copy command", false);
        cp.setTextSize(12);
        ((LinearLayout) banner).addView(cp);
        cp.setOnClickListener(v -> { UI.copy(this, "adb command", GRANT); UI.toast(this, "Copied"); });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 6));
        root.addView(banner, blp);
        banner.setVisibility(checkSelfPermission("android.permission.READ_LOGS") == android.content.pm.PackageManager.PERMISSION_GRANTED ? View.GONE : View.VISIBLE);

        LinearLayout fr = UI.hbox(this);
        filterRow = fr;
        fr.setPadding(UI.dp(this, 12), 0, UI.dp(this, 12), UI.dp(this, 4));
        final Spinner lvl = new Spinner(this);
        lvl.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Verbose", "Debug", "Info", "Warn", "Error"}));
        fr.addView(lvl, new LinearLayout.LayoutParams(UI.dp(this, 112), UI.dp(this, 40)));
        EditText search = UI.input(this, "Filter text or tag", "");
        search.setTextSize(13);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, UI.dp(this, 40), 1);
        slp.leftMargin = UI.dp(this, 6);
        search.setLayoutParams(slp);
        fr.addView(search);
        final Switch mine = new Switch(this);
        mine.setText(pkg.isEmpty() ? "My app" : "My app");
        mine.setTextSize(11);
        mine.setTextColor(UI.color(this, R.attr.bTextDim));
        mine.setChecked(myAppOnly);
        mine.setVisibility(pkg.isEmpty() ? View.GONE : View.VISIBLE);
        fr.addView(mine);
        root.addView(fr);

        tvInfo = UI.label(this, "", 10, true);
        tvInfo.setPadding(UI.dp(this, 14), 0, UI.dp(this, 14), UI.dp(this, 2));
        root.addView(tvInfo);

        FrameLayout2 body = new FrameLayout2(this);
        logList = new ListView(this);
        logList.setDivider(null);
        logList.setStackFromBottom(false);
        logAdapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(LogcatActivity.this);
                LogcatParser.Line l = shown.get(i);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(10.5f);
                t.setPadding(UI.dp(LogcatActivity.this, 10), UI.dp(LogcatActivity.this, 2), UI.dp(LogcatActivity.this, 10), UI.dp(LogcatActivity.this, 2));
                t.setTextColor(levelColor(l.level));
                t.setText(l.tag.isEmpty() ? l.raw : l.time.substring(Math.min(6, l.time.length())) + " " + l.level + "/" + l.tag + ": " + l.msg);
                return t;
            }
        };
        logList.setAdapter(logAdapter);
        logList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { stick = first + visible >= total - 1; }
        });
        logList.setOnItemLongClickListener((a, v, pos, id) -> { UI.copy(this, "log line", shown.get(pos).raw); UI.toast(this, "Line copied"); return true; });
        crashList = new ListView(this);
        crashList.setDivider(null);
        crashList.setPadding(UI.dp(this, 12), UI.dp(this, 4), UI.dp(this, 12), UI.dp(this, 12));
        crashList.setClipToPadding(false);
        crashAdapter = new BaseAdapter() {
            @Override public int getCount() { return crashes.size(); }
            @Override public Object getItem(int i) { return crashes.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                CrashStore.Crash c = crashes.get(i);
                LinearLayout r = UI.vbox(LogcatActivity.this);
                r.setBackgroundResource(R.drawable.bg_card);
                r.setPadding(UI.dp(LogcatActivity.this, 14), UI.dp(LogcatActivity.this, 10), UI.dp(LogcatActivity.this, 14), UI.dp(LogcatActivity.this, 10));
                AbsListView.LayoutParams lp = new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                r.setLayoutParams(lp);
                TextView t = UI.label(LogcatActivity.this, c.pkg, 14, false);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                r.addView(t);
                r.addView(UI.label(LogcatActivity.this, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(c.time)) + "  •  thread " + c.thread, 11, true));
                TextView ex = UI.label(LogcatActivity.this, LogcatParser.firstException(c.trace), 12, false);
                ex.setTextColor(UI.color(LogcatActivity.this, R.attr.bError));
                ex.setPadding(0, UI.dp(LogcatActivity.this, 4), 0, 0);
                r.addView(ex);
                LinearLayout wrap = UI.vbox(LogcatActivity.this);
                wrap.setPadding(0, 0, 0, UI.dp(LogcatActivity.this, 8));
                wrap.addView(r);
                return wrap;
            }
        };
        crashList.setAdapter(crashAdapter);
        crashList.setOnItemClickListener((a, v, pos, id) -> showCrash(crashes.get(pos)));
        body.addView(logList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(crashList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        styleBars();

        final Runnable showLive = () -> {
            live.setBackgroundResource(R.drawable.bg_btn_primary); live.setTextColor(UI.color(this, R.attr.bOnAccent));
            crash.setBackgroundResource(R.drawable.bg_btn_tonal); crash.setTextColor(UI.color(this, R.attr.bText));
            logList.setVisibility(View.VISIBLE); crashList.setVisibility(View.GONE);
            filterRow.setVisibility(View.VISIBLE); tvInfo.setVisibility(View.VISIBLE);
        };
        final Runnable showCrashes = () -> {
            crash.setBackgroundResource(R.drawable.bg_btn_primary); crash.setTextColor(UI.color(this, R.attr.bOnAccent));
            live.setBackgroundResource(R.drawable.bg_btn_tonal); live.setTextColor(UI.color(this, R.attr.bText));
            logList.setVisibility(View.GONE); crashList.setVisibility(View.VISIBLE);
            filterRow.setVisibility(View.GONE); tvInfo.setVisibility(View.GONE);
            loadCrashes();
        };
        live.setOnClickListener(v -> showLive.run());
        crash.setOnClickListener(v -> showCrashes.run());
        lvl.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { minLevel = pos; rebuild(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(); rebuild(); }
        });
        mine.setOnCheckedChangeListener((v, on) -> { myAppOnly = on; rebuild(); });
        if ("crashes".equals(getIntent().getStringExtra("tab"))) showCrashes.run(); else showLive.run();
    }

    private ImageButton icon(int res) {
        ImageButton b = new ImageButton(this, null, 0, R.style.BIconBtn);
        b.setImageResource(res);
        return b;
    }

    /** Plain FrameLayout that exists only to keep the two lists stacked. */
    private static final class FrameLayout2 extends android.widget.FrameLayout {
        FrameLayout2(android.content.Context c) { super(c); }
    }

    private static int levelColor(char l) {
        switch (l) {
            case 'E': case 'F': case 'A': return Color.parseColor("#E5484D");
            case 'W': return Color.parseColor("#D9A400");
            case 'I': return Color.parseColor("#2E9E6B");
            case 'D': return Color.parseColor("#4C8DC9");
            default: return Color.parseColor("#8A8F98");
        }
    }

    // ------------------------------------------------------------------ live reader

    @Override protected void onResume() {
        super.onResume();
        startReader();
    }

    @Override protected void onPause() {
        super.onPause();
        stopReader();
    }

    private void startReader() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                proc = new ProcessBuilder("logcat", "-v", "threadtime", "-T", "400").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("---")) continue;
                    synchronized (pending) { pending.add(LogcatParser.parse(line)); }
                    queueFlush();
                }
            } catch (Exception e) {
                synchronized (pending) { LogcatParser.Line l = new LogcatParser.Line(); l.msg = "Cannot read logcat: " + e; l.raw = l.msg; pending.add(l); }
                queueFlush();
            }
        }, "logcat-reader").start();
        new Thread(() -> {
            while (running) {
                if (!pkg.isEmpty()) pid = findPid(pkg);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }, "logcat-pid").start();
    }

    private void stopReader() {
        running = false;
        Process p = proc;
        if (p != null) p.destroy();
        proc = null;
    }

    private static int findPid(String pkg) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "pidof " + pkg).redirectErrorStream(true).start();
            String out = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            if (out != null) { String f = out.trim().split("\\s+")[0]; if (f.matches("\\d+")) return Integer.parseInt(f); }
        } catch (Exception ignored) { }
        return 0;
    }

    private void queueFlush() {
        synchronized (pending) {
            if (flushQueued) return;
            flushQueued = true;
        }
        main.postDelayed(() -> {
            List<LogcatParser.Line> batch;
            synchronized (pending) { batch = new ArrayList<>(pending); pending.clear(); flushQueued = false; }
            if (paused) return;
            all.addAll(batch);
            if (all.size() > 6000) all.subList(0, all.size() - 5000).clear();
            for (LogcatParser.Line l : batch) if (matches(l)) shown.add(l);
            if (shown.size() > 6000) shown.subList(0, shown.size() - 5000).clear();
            logAdapter.notifyDataSetChanged();
            updateInfo();
            if (stick && !shown.isEmpty()) logList.setSelection(shown.size() - 1);
        }, 200);
    }

    private boolean matches(LogcatParser.Line l) {
        if (LogcatParser.levelRank(l.level) < minLevel) return false;
        if (myAppOnly && !pkg.isEmpty()) {
            int pp = pid;
            if (pp > 0 ? l.pid != pp : !l.raw.contains(pkg)) return false;
        }
        return query.isEmpty() || l.raw.toLowerCase().contains(query);
    }

    private void rebuild() {
        shown.clear();
        for (LogcatParser.Line l : all) if (matches(l)) shown.add(l);
        logAdapter.notifyDataSetChanged();
        updateInfo();
        if (!shown.isEmpty()) logList.setSelection(shown.size() - 1);
    }

    private void updateInfo() {
        tvInfo.setText(shown.size() + " lines" + (myAppOnly && !pkg.isEmpty() ? "  •  " + pkg + (pid > 0 ? " (pid " + pid + ")" : " (not running)") : "") + (paused ? "  •  paused" : ""));
    }

    private void clearAll() {
        all.clear(); shown.clear();
        logAdapter.notifyDataSetChanged();
        try { new ProcessBuilder("logcat", "-c").start(); } catch (Exception ignored) { }
        if (crashList.getVisibility() == View.VISIBLE) {
            UI.dialog(this).setTitle("Delete all crash reports?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> { CrashStore.clear(this); loadCrashes(); }).show();
        }
        updateInfo();
    }

    private void copyVisible() {
        StringBuilder sb = new StringBuilder();
        if (crashList.getVisibility() == View.VISIBLE) { for (CrashStore.Crash c : crashes) sb.append(c.pkg).append('\n').append(c.trace).append("\n\n"); }
        else { int from = Math.max(0, shown.size() - 800); for (int i = from; i < shown.size(); i++) sb.append(shown.get(i).raw).append('\n'); }
        UI.copy(this, "logcat", sb.toString());
        UI.toast(this, "Copied");
    }

    // ------------------------------------------------------------------ crashes

    private void loadCrashes() {
        crashes = CrashStore.list(this);
        crashAdapter.notifyDataSetChanged();
        if (crashes.isEmpty()) UI.toast(this, "No crashes yet. Run a debug build and use your app.");
    }

    private void showCrash(final CrashStore.Crash c) {
        TextView t = new TextView(this);
        t.setText(c.trace);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(10.5f);
        t.setTextColor(UI.color(this, R.attr.bText));
        t.setPadding(UI.dp(this, 16), UI.dp(this, 8), UI.dp(this, 16), UI.dp(this, 8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        UI.dialog(this).setTitle(c.pkg).setView(sv).setPositiveButton("Close", null)
                .setNeutralButton("Copy", (d, w) -> { UI.copy(this, "crash", c.trace); UI.toast(this, "Copied"); })
                .setNegativeButton("Delete", (d, w) -> { c.file.delete(); loadCrashes(); }).show();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
}
