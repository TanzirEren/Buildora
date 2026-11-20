package com.tenix.buildora.ai;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.tenix.buildora.BaseActivity;
import com.tenix.buildora.R;
import com.tenix.buildora.core.MenuSheet;
import com.tenix.buildora.core.UI;

import java.util.ArrayList;
import java.util.List;

/** Lists the configured AI providers; add / edit / switch them on and off. */
public class AiProvidersActivity extends BaseActivity {

    private LinearLayout content;
    private ProviderStore store;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Ai.providers(this);
        LinearLayout root = UI.vbox(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);

        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0);
        ImageButton back = new ImageButton(this, null, 0, R.style.BIconBtn);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 56)));
        TextView title = UI.label(this, "AI providers", 20, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final ImageButton more = new ImageButton(this, null, 0, R.style.BIconBtn);
        more.setImageResource(R.drawable.ic_more);
        more.setOnClickListener(v -> new MenuSheet(this)
                .add(1, R.drawable.mi_download, "Import providers", "From the clipboard")
                .add(2, R.drawable.mi_export, "Export providers", "Copied without API keys")
                .divider()
                .add(3, R.drawable.mi_toggle_on, "Switch all on")
                .add(4, R.drawable.mi_toggle_off, "Switch all off")
                .divider()
                .add(5, R.drawable.mi_refresh, "Reset key limits", "Make every API key usable again")
                .show(v, id -> {
                    switch (id) {
                        case 1: importClipboard(); break;
                        case 2: UI.copy(this, "providers", store.exportJson()); UI.toast(this, "Copied (API keys are not included)"); break;
                        case 3: case 4:
                            for (AiProvider p : store.all()) { p.enabled = id == 3; store.replace(p); }
                            render();
                            break;
                        case 5:
                            for (AiProvider p : store.all()) p.resetLimits();
                            render();
                            UI.toast(this, "All API keys are ready again");
                            break;
                    }
                }));
        top.addView(more, new LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 56)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 56)));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        content = UI.vbox(this);
        content.setPadding(UI.dp(this, 16), UI.dp(this, 4), UI.dp(this, 16), UI.dp(this, 40));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        styleBars();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        content.removeAllViews();
        List<AiProvider> list = store.all();
        int models = 0, free = 0;
        for (AiProvider p : list) if (p.enabled) { models += p.enabledCount(); for (AiModel m : p.models) if (m.enabled && m.free) free++; }
        TextView sum = UI.label(this, list.isEmpty()
                ? "No providers yet. Add one to chat with Buildora AI."
                : list.size() + " providers · " + models + " models on · " + free + " free", 13, true);
        sum.setPadding(UI.dp(this, 4), UI.dp(this, 4), 0, UI.dp(this, 8));
        content.addView(sum);

        String[] d = store.resolveDefault();
        if (d != null) {
            AiProvider dp = store.get(d[0]);
            LinearLayout r = card();
            r.addView(UI.label(this, "Default model", 12, true));
            TextView t = UI.label(this, dp == null ? d[1] : dp.name + " · " + d[1], 14.5f, false);
            t.setPadding(0, UI.dp(this, 3), 0, 0);
            r.addView(t);
            r.setOnClickListener(v -> ModelPicker.show(this, d[0], d[1], (p, m) -> { store.setDefault(p.id, m.id); render(); }));
            content.addView(r);
        }

        for (final AiProvider p : list) content.addView(providerCard(p));

        TextView add = UI.button(this, "＋  Add provider", true);
        ((LinearLayout.LayoutParams) add.getLayoutParams()).topMargin = UI.dp(this, 16);
        add.setOnClickListener(v -> showEditor(null));
        content.addView(add);

        TextView note = UI.label(this, "API keys are stored only inside Buildora's private app storage on this phone and are sent only to the provider you set. Free tags come from the provider's pricing or free tier and can change.", 11.5f, true);
        note.setPadding(UI.dp(this, 4), UI.dp(this, 14), UI.dp(this, 4), 0);
        content.addView(note);
    }

    private LinearLayout card() {
        LinearLayout r = UI.vbox(this);
        r.setBackgroundResource(R.drawable.bg_chip);
        r.setPadding(UI.dp(this, 14), UI.dp(this, 12), UI.dp(this, 14), UI.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(this, 8);
        r.setLayoutParams(lp);
        return r;
    }

    private View providerCard(final AiProvider p) {
        LinearLayout c = card();
        LinearLayout head = UI.hbox(this);
        LinearLayout col = UI.vbox(this);
        TextView name = UI.label(this, p.name.isEmpty() ? "Provider" : p.name, 15.5f, false);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(name);
        String host = p.baseUrl.replaceFirst("^https?://", "");
        if (host.length() > 44) host = host.substring(0, 43) + "…";
        TextView sub = UI.label(this, host + (p.kind == AiProvider.KIND_ANTHROPIC ? "  ·  Anthropic API" : ""), 11.5f, true);
        sub.setSingleLine(true);
        col.addView(sub);
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw = new Switch(this);
        sw.setChecked(p.enabled);
        sw.setOnCheckedChangeListener((bv, on) -> { p.enabled = on; store.replace(p); render(); });
        head.addView(sw);
        final ImageButton more = new ImageButton(this, null, 0, R.style.BIconBtn);
        more.setImageResource(R.drawable.ic_more);
        more.setOnClickListener(v -> menu(p, more));
        head.addView(more, new LinearLayout.LayoutParams(UI.dp(this, 40), UI.dp(this, 40)));
        c.addView(head);
        boolean isDef = p.id.equals(store.defaultProviderId());
        String stat = p.models.size() + " models · " + p.freeCount() + " free · " + p.enabledCount() + " on" + (isDef ? "  ·  default" : "") + (p.keyCount() == 0 ? "  ·  no key" : p.keyCount() > 1 ? "  ·  " + p.keyCount() + " keys" + (p.usableKeyCount(System.currentTimeMillis()) < p.keyCount() ? " (" + (p.keyCount() - p.usableKeyCount(System.currentTimeMillis())) + " limited)" : "") : "");
        TextView st = UI.label(this, stat, 12, true);
        st.setPadding(0, UI.dp(this, 6), 0, 0);
        c.addView(st);
        c.setAlpha(p.enabled ? 1f : 0.55f);
        c.setOnClickListener(v -> showEditor(p));
        return c;
    }

    private void menu(final AiProvider p, View anchor) {
        new MenuSheet(this)
                .add(1, R.drawable.mi_sliders, "Edit & manage models")
                .add(7, R.drawable.mi_key, "API keys", p.keyCount() + (p.keyCount() == 1 ? " key" : " keys"))
                .add(2, R.drawable.mi_refresh, "Refresh models")
                .add(3, R.drawable.mi_plug, "Test connection")
                .divider()
                .add(4, R.drawable.mi_star, "Make default")
                .add(5, R.drawable.mi_duplicate, "Duplicate")
                .divider()
                .danger(6, R.drawable.mi_trash, "Delete")
                .show(anchor, id -> {
                    switch (id) {
                        case 1: case 7: showEditor(p); break;
                        case 2: refreshModels(p); break;
                        case 3: testProvider(p); break;
                        case 4: {
                            AiModel first = null;
                            for (AiModel m : p.models) if (m.enabled) { first = m; break; }
                            if (first == null) UI.toast(this, "Switch on a model first"); else { store.setDefault(p.id, first.id); render(); }
                            break;
                        }
                        case 5: { AiProvider cpy = p.copy(true); cpy.name = p.name + " (copy)"; store.add(cpy); render(); break; }
                        case 6:
                            UI.dialog(this).setTitle("Delete " + p.name + "?").setMessage("The provider, its API keys and model choices are removed from this phone.")
                                    .setPositiveButton("Delete", (d, w) -> { store.remove(p.id); render(); }).setNegativeButton("Cancel", null).show();
                            break;
                    }
                });
    }

    private void importClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence t = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) t = cm.getPrimaryClip().getItemAt(0).getText();
        int n = t == null ? 0 : store.importJson(t.toString());
        UI.toast(this, n == 0 ? "Nothing to import - copy an exported providers text first" : "Imported " + n + " provider(s). Add the API keys.");
        render();
    }

    private void testProvider(final AiProvider p) {
        AiModel m = null;
        for (AiModel x : p.models) if (x.enabled) { m = x; break; }
        if (m == null) { UI.toast(this, "Switch on a model first"); return; }
        final String mid = m.id;
        final AlertDialog d = UI.dialog(this).setTitle("Testing " + p.name).setMessage("Model: " + mid + "\nPlease wait…").setCancelable(true).show();
        new Thread(() -> {
            final String err = ChatClient.test(p, mid);
            ui(() -> { d.dismiss(); UI.message(this, err == null ? "Connection OK" : "Failed", err == null ? "The provider answered correctly." : err); });
        }).start();
    }

    private void refreshModels(final AiProvider p) {
        final AlertDialog d = UI.dialog(this).setMessage("Fetching models…").show();
        new Thread(() -> {
            String err = null;
            try {
                List<AiModel> f = ModelFetcher.fetch(p);
                ModelFetcher.merge(p.models, f, false);
                store.replace(p);
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final String ferr = err;
            ui(() -> { d.dismiss(); if (ferr != null) UI.message(this, "Could not fetch models", ferr); else UI.toast(this, "Models updated"); render(); });
        }).start();
    }

    // ------------------------------------------------------------------ add / edit dialog

    private void showEditor(final AiProvider existing) {
        final AiProvider work = existing == null ? new AiProvider() : existing.copy(true);
        if (existing != null) work.id = existing.id;
        else {
            work.id = AiProvider.newId();
            work.preset = "custom";
        }
        final boolean adding = existing == null;
        final Context c = this;
        final ProviderPresets.Preset[] preset = {ProviderPresets.byId(work.preset)};

        ScrollView sv = new ScrollView(this);
        LinearLayout box = UI.vbox(this);
        box.setPadding(UI.dp(this, 20), UI.dp(this, 8), UI.dp(this, 20), UI.dp(this, 4));
        sv.addView(box);

        final TextView presetBtn = UI.button(this, "Provider: " + preset[0].name + "  ▾", false);
        box.addView(presetBtn);
        final TextView note = UI.label(this, preset[0].note, 11.5f, true);
        box.addView(note);
        final EditText name = UI.input(this, "Name (e.g. My OpenRouter)", work.name);
        final EditText url = UI.input(this, "Base URL (https://…/v1)", work.baseUrl);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final KeysEditor keys = new KeysEditor(work, preset[0].keyHint);
        final EditText ids = UI.input(this, "Model IDs - one per line", "");
        ids.setSingleLine(false);
        ids.setMinLines(2);
        ids.setMaxLines(5);
        ids.setGravity(Gravity.TOP);
        ids.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(name);
        box.addView(url);
        box.addView(keys.root);
        box.addView(ids);

        final TextView fetch = UI.button(this, "Fetch models", false);
        box.addView(fetch);
        final TextView status = UI.label(this, "", 12, true);
        box.addView(status);
        final ModelListView list = new ModelListView(this, 300);
        list.setModels(work.models);
        list.setVisibility(work.models.isEmpty() ? View.GONE : View.VISIBLE);
        box.addView(list);

        presetBtn.setOnClickListener(v -> UI.dialog(c).setTitle("Provider type").setItems(ProviderPresets.names(), (d, which) -> {
            ProviderPresets.Preset ps = ProviderPresets.ALL[which];
            preset[0] = ps;
            work.preset = ps.id;
            work.kind = ps.kind;
            presetBtn.setText("Provider: " + ps.name + "  ▾");
            note.setText(ps.note);
            note.setVisibility(ps.note.isEmpty() ? View.GONE : View.VISIBLE);
            if (!ps.id.equals("custom")) { name.setText(ps.name); url.setText(ps.baseUrl); }
            else if (adding) { name.setText(""); url.setText(""); }
            keys.setHint(ps.keyHint);
        }).show());
        note.setVisibility(preset[0].note.isEmpty() ? View.GONE : View.VISIBLE);

        final Runnable readFields = () -> {
            work.name = name.getText().toString().trim();
            work.baseUrl = url.getText().toString().trim();
            work.setKeys(keys.collect());
            if (work.name.isEmpty()) work.name = preset[0].name;
            for (String line : ids.getText().toString().split("\n")) {
                String id = line.trim();
                if (id.isEmpty()) continue;
                AiModel ex = work.find(id);
                if (ex == null) {
                    AiModel m = new AiModel(id);
                    m.free = ProviderPresets.isFree(work.preset, id, false);
                    work.models.add(m);
                }
            }
            ids.setText("");
            list.setModels(work.models);
            list.setVisibility(work.models.isEmpty() ? View.GONE : View.VISIBLE);
        };

        fetch.setOnClickListener(v -> {
            readFields.run();
            if (work.base().isEmpty() || !work.baseUrl.startsWith("http")) { status.setText("Enter a base URL that starts with https://"); return; }
            status.setText("Fetching…");
            fetch.setEnabled(false);
            new Thread(() -> {
                String err = null;
                List<AiModel> fetched = null;
                try { fetched = ModelFetcher.fetch(work); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                final String ferr = err;
                final List<AiModel> ff = fetched;
                ui(() -> {
                    fetch.setEnabled(true);
                    if (ferr != null) { status.setText(ferr + (preset[0].id.equals("perplexity") ? "" : "\nYou can still type model ids above.")); return; }
                    boolean anyFree = false;
                    for (AiModel m : ff) if (m.free) anyFree = true;
                    boolean firstTime = work.models.isEmpty();
                    ModelFetcher.merge(work.models, ff, !anyFree || !firstTime);
                    if (firstTime && anyFree) for (AiModel m : work.models) m.enabled = m.free;
                    list.setModels(work.models);
                    list.setVisibility(View.VISIBLE);
                    int fr = 0; for (AiModel m : ff) if (m.free) fr++;
                    status.setText("Found " + ff.size() + " models, " + fr + " free. Tap All, Free only, or pick your own.");
                });
            }).start();
        });

        AlertDialog.Builder db = UI.dialog(this).setTitle(adding ? "Add provider" : "Edit provider").setView(sv)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null);
        if (!adding) db.setNeutralButton("Test", null);
        final AlertDialog dlg = db.create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            readFields.run();
            if (work.baseUrl.isEmpty() || !work.baseUrl.startsWith("http")) { UI.toast(c, "Base URL must start with http:// or https://"); return; }
            if (work.keyCount() == 0 && !work.baseUrl.contains("localhost") && !work.baseUrl.contains("127.0.0.1") && !work.baseUrl.contains("192.168."))
                UI.toast(c, "No API key set - most providers need one");
            if (work.models.isEmpty()) UI.toast(c, "No models yet - use Fetch models or type ids");
            if (adding) store.add(work); else store.replace(work);
            dlg.dismiss();
            render();
        });
        if (!adding) dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            readFields.run();
            AiModel m = null;
            for (AiModel x : work.models) if (x.enabled) { m = x; break; }
            if (m == null) { UI.toast(c, "Switch on a model first"); return; }
            final String mid = m.id;
            status.setText("Testing " + mid + "…");
            new Thread(() -> {
                final StringBuilder res = new StringBuilder();
                if (work.keyCount() <= 1) {
                    String e = ChatClient.test(work, mid);
                    res.append(e == null ? "Connection OK ✓" : e);
                } else {
                    int i = 0;
                    for (AiProvider.KeySlot k : new ArrayList<>(work.keys)) {
                        i++;
                        AiProvider one = work.copy(true);
                        one.keys.clear();
                        one.keys.add(new AiProvider.KeySlot(k.key));
                        String e = ChatClient.test(one, mid);
                        res.append("Key #").append(i).append(": ").append(e == null ? "works ✓" : "✗ " + firstLine(e)).append('\n');
                    }
                }
                ui(() -> status.setText(res.toString().trim()));
            }).start();
        });
    }

    private static String firstLine(String s) { int n = s.indexOf('\n'); return n < 0 ? s : s.substring(0, n); }

    /** The list of API keys of one provider. When a key hits its limit the next one is used automatically. */
    private final class KeysEditor {
        final LinearLayout root;
        private final LinearLayout list;
        private final List<EditText> fields = new ArrayList<>();
        private final List<AiProvider.KeySlot> slots = new ArrayList<>();
        private final List<TextView> statuses = new ArrayList<>();
        private String hint;
        private boolean shown;
        private TextView showBtn, resetBtn;

        KeysEditor(AiProvider work, String hint) {
            this.hint = hint;
            final Context c = AiProvidersActivity.this;
            root = UI.vbox(c);
            LinearLayout head = UI.hbox(c);
            head.setPadding(0, UI.dp(c, 12), 0, 0);
            TextView t = UI.label(c, "API keys", 13, false);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView paste = UI.label(c, "Paste", 12.5f, false);
            paste.setTextColor(UI.color(c, R.attr.bAccent));
            paste.setPadding(UI.dp(c, 10), UI.dp(c, 4), UI.dp(c, 10), UI.dp(c, 4));
            paste.setOnClickListener(v -> pasteInto());
            head.addView(paste);
            showBtn = UI.label(c, "Show", 12.5f, false);
            showBtn.setTextColor(UI.color(c, R.attr.bAccent));
            showBtn.setPadding(UI.dp(c, 10), UI.dp(c, 4), 0, UI.dp(c, 4));
            showBtn.setOnClickListener(v -> {
                shown = !shown;
                showBtn.setText(shown ? "Hide" : "Show");
                for (EditText e : fields) { e.setInputType(InputType.TYPE_CLASS_TEXT | (shown ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)); }
            });
            head.addView(showBtn);
            root.addView(head);
            TextView sub = UI.label(c, "Add more than one key. When a key reaches its limit (or is rejected), the next key starts automatically.", 11, true);
            root.addView(sub);
            list = UI.vbox(c);
            root.addView(list);
            for (AiProvider.KeySlot k : work.keys) addRow(k);
            if (slots.isEmpty()) addRow(new AiProvider.KeySlot(""));
            LinearLayout foot = UI.hbox(c);
            foot.setPadding(0, UI.dp(c, 6), 0, 0);
            TextView add = UI.button(c, "＋ Add another key", false);
            add.setOnClickListener(v -> { addRow(new AiProvider.KeySlot("")); });
            foot.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            resetBtn = UI.label(c, "Reset limits", 12.5f, false);
            resetBtn.setTextColor(UI.color(c, R.attr.bAccent));
            resetBtn.setPadding(UI.dp(c, 12), UI.dp(c, 12), UI.dp(c, 4), UI.dp(c, 12));
            resetBtn.setOnClickListener(v -> { for (AiProvider.KeySlot k : slots) { k.blockedUntil = 0; k.lastError = ""; } refreshStatus(); });
            foot.addView(resetBtn);
            root.addView(foot);
            refreshStatus();
        }

        void setHint(String h) { hint = h; if (!fields.isEmpty() && fields.get(0).getText().length() == 0) fields.get(0).setHint(h); }

        private void pasteInto() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            if (t == null) return;
            // several keys (one per line) can be pasted at once
            List<String> lines = new ArrayList<>();
            for (String l : t.toString().split("[\\r\\n,; ]+")) if (l.trim().length() >= 8) lines.add(l.trim());
            if (lines.isEmpty()) { UI.toast(AiProvidersActivity.this, "Clipboard has no key"); return; }
            int next = 0;
            for (EditText e : fields) if (e.getText().length() == 0 && next < lines.size()) e.setText(lines.get(next++));
            while (next < lines.size()) { addRow(new AiProvider.KeySlot(lines.get(next++))); }
        }

        private void addRow(final AiProvider.KeySlot slot) {
            final Context c = AiProvidersActivity.this;
            LinearLayout row = UI.hbox(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final LinearLayout wrap = UI.vbox(c);
            final int idx = slots.size() + 1;
            TextView num = UI.label(c, String.valueOf(idx), 12, true);
            num.setGravity(Gravity.CENTER);
            num.setBackgroundResource(R.drawable.bg_tag_ext);
            row.addView(num, new LinearLayout.LayoutParams(UI.dp(c, 24), UI.dp(c, 24)));
            final EditText e = UI.input(c, idx == 1 ? hint : "API key " + idx, slot.key);
            e.setInputType(InputType.TYPE_CLASS_TEXT | (shown ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            el.leftMargin = UI.dp(c, 8);
            row.addView(e, el);
            Switch sw = new Switch(c);
            sw.setChecked(slot.enabled);
            sw.setOnCheckedChangeListener((b, on) -> { slot.enabled = on; refreshStatus(); });
            row.addView(sw);
            ImageButton del = new ImageButton(c, null, 0, R.style.BIconBtn);
            del.setImageResource(R.drawable.mi_trash);
            del.setColorFilter(UI.color(c, R.attr.bTextDim));
            del.setOnClickListener(v -> {
                int at = fields.indexOf(e);
                if (slots.size() <= 1) { e.setText(""); return; }
                fields.remove(at); slots.remove(at); statuses.remove(at);
                list.removeView(wrap);
                refreshStatus();
            });
            row.addView(del, new LinearLayout.LayoutParams(UI.dp(c, 38), UI.dp(c, 42)));
            wrap.addView(row);
            TextView st = UI.label(c, "", 11, true);
            st.setPadding(UI.dp(c, 32), 0, 0, UI.dp(c, 2));
            wrap.addView(st);
            list.addView(wrap);
            fields.add(e); slots.add(slot); statuses.add(st);
            refreshStatus();
        }

        private void refreshStatus() {
            long now = System.currentTimeMillis();
            boolean inUse = false, anyLimited = false;
            for (int i = 0; i < slots.size(); i++) {
                AiProvider.KeySlot k = slots.get(i);
                boolean empty = fields.get(i).getText().length() == 0;
                String t;
                if (empty) t = "";
                else if (!k.enabled) t = "Off - skipped";
                else if (k.blockedUntil > now) {
                    long min = Math.max(1, (k.blockedUntil - now) / 60000);
                    t = "⛔ Limit reached - available again in " + (min >= 120 ? (min / 60) + " h" : min + " min") + (k.lastError.isEmpty() ? "" : "  (" + k.lastError + ")");
                    anyLimited = true;
                } else if (!inUse) { t = "● In use now" + (k.uses > 0 ? "  ·  " + k.uses + " requests" : ""); inUse = true; }
                else t = "Ready - backup";
                statuses.get(i).setText(t);
            }
            if (resetBtn != null) resetBtn.setVisibility(anyLimited ? View.VISIBLE : View.GONE);
        }

        List<AiProvider.KeySlot> collect() {
            List<AiProvider.KeySlot> out = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                AiProvider.KeySlot k = slots.get(i);
                k.key = fields.get(i).getText().toString().trim();
                if (!k.key.isEmpty()) out.add(k);
            }
            return out;
        }
    }
}
