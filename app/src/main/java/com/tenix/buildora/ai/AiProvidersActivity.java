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
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, more);
            pm.getMenu().add(0, 1, 0, "Import providers (from clipboard)");
            pm.getMenu().add(0, 2, 1, "Export providers (copy, no keys)");
            pm.getMenu().add(0, 3, 2, "Switch all on");
            pm.getMenu().add(0, 4, 3, "Switch all off");
            pm.setOnMenuItemClickListener(mi -> {
                switch (mi.getItemId()) {
                    case 1: importClipboard(); break;
                    case 2: UI.copy(this, "providers", store.exportJson()); UI.toast(this, "Copied (API keys are not included)"); break;
                    case 3: case 4:
                        for (AiProvider p : store.all()) { p.enabled = mi.getItemId() == 3; store.replace(p); }
                        render();
                        break;
                }
                return true;
            });
            pm.show();
        });
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
        String stat = p.models.size() + " models · " + p.freeCount() + " free · " + p.enabledCount() + " on" + (isDef ? "  ·  default" : "") + (p.apiKey.isEmpty() ? "  ·  no key" : "");
        TextView st = UI.label(this, stat, 12, true);
        st.setPadding(0, UI.dp(this, 6), 0, 0);
        c.addView(st);
        c.setAlpha(p.enabled ? 1f : 0.55f);
        c.setOnClickListener(v -> showEditor(p));
        return c;
    }

    private void menu(final AiProvider p, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Edit / manage models");
        pm.getMenu().add(0, 2, 1, "Refresh models");
        pm.getMenu().add(0, 3, 2, "Test connection");
        pm.getMenu().add(0, 4, 3, "Make default");
        pm.getMenu().add(0, 5, 4, "Duplicate");
        pm.getMenu().add(0, 6, 5, "Delete");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: showEditor(p); break;
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
                    UI.dialog(this).setTitle("Delete " + p.name + "?").setMessage("The provider, its API key and model choices are removed from this phone.")
                            .setPositiveButton("Delete", (d, w) -> { store.remove(p.id); render(); }).setNegativeButton("Cancel", null).show();
                    break;
            }
            return true;
        });
        pm.show();
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
        final EditText key = UI.input(this, "API key", work.apiKey);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText ids = UI.input(this, "Model IDs - one per line", "");
        ids.setSingleLine(false);
        ids.setMinLines(2);
        ids.setMaxLines(5);
        ids.setGravity(Gravity.TOP);
        ids.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(name);
        box.addView(url);
        LinearLayout keyRow = UI.hbox(this);
        keyRow.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView show = UI.label(this, "Show", 12.5f, false);
        show.setTextColor(UI.color(this, R.attr.bAccent));
        show.setPadding(UI.dp(this, 10), UI.dp(this, 14), UI.dp(this, 4), 0);
        final boolean[] shown = {false};
        show.setOnClickListener(v -> {
            shown[0] = !shown[0];
            key.setInputType(InputType.TYPE_CLASS_TEXT | (shown[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            ((TextView) v).setText(shown[0] ? "Hide" : "Show");
            key.setSelection(key.length());
        });
        TextView paste = UI.label(this, "Paste", 12.5f, false);
        paste.setTextColor(UI.color(this, R.attr.bAccent));
        paste.setPadding(UI.dp(this, 10), UI.dp(this, 14), UI.dp(this, 4), 0);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null) key.setText(t.toString().trim());
            }
        });
        keyRow.addView(paste);
        keyRow.addView(show);
        box.addView(keyRow);
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
            key.setHint(ps.keyHint);
        }).show());
        note.setVisibility(preset[0].note.isEmpty() ? View.GONE : View.VISIBLE);

        final Runnable readFields = () -> {
            work.name = name.getText().toString().trim();
            work.baseUrl = url.getText().toString().trim();
            work.apiKey = key.getText().toString().trim();
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
            if (work.apiKey.isEmpty() && !work.baseUrl.contains("localhost") && !work.baseUrl.contains("127.0.0.1") && !work.baseUrl.contains("192.168."))
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
            new Thread(() -> { final String e = ChatClient.test(work, mid); ui(() -> status.setText(e == null ? "Connection OK ✓" : e)); }).start();
        });
    }
}
