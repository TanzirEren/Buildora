package com.tenix.buildora.ai;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Checkable model list with search and All / Free / None shortcuts. A "free" tag shows on the right. */
public class ModelListView extends LinearLayout {
    private final List<AiModel> all = new ArrayList<>();
    private final List<AiModel> shown = new ArrayList<>();
    private final BaseAdapter adapter;
    private final TextView summary;
    private String filter = "";

    public ModelListView(Context c, int heightDp) {
        super(c);
        setOrientation(VERTICAL);
        final TextView[] none = {null};

        LinearLayout chips = UI.hbox(c);
        chips.addView(chip(c, "All", () -> setAll(false)));
        chips.addView(chip(c, "Free only", () -> setAll(true)));
        chips.addView(chip(c, "None", () -> { for (AiModel m : all) m.enabled = false; refresh(); }));
        summary = UI.label(c, "", 12, true);
        summary.setGravity(Gravity.END);
        chips.addView(summary, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(chips);

        android.widget.EditText search = UI.input(c, "Search models", "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { filter = s.toString().trim().toLowerCase(Locale.ROOT); applyFilter(); }
        });
        addView(search);

        ListView lv = new ListView(c);
        lv.setDivider(null);
        lv.setNestedScrollingEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                Row r = v instanceof Row ? (Row) v : new Row(parent.getContext());
                r.bind(shown.get(i));
                return r;
            }
        };
        lv.setAdapter(adapter);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, heightDp));
        lp.topMargin = UI.dp(c, 6);
        addView(lv, lp);
    }

    private TextView chip(Context c, String s, final Runnable r) {
        TextView t = UI.label(c, s, 12.5f, false);
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(UI.dp(c, 12), UI.dp(c, 6), UI.dp(c, 12), UI.dp(c, 6));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(c, 6);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void setAll(boolean freeOnly) {
        for (AiModel m : all) m.enabled = !freeOnly || m.free;
        refresh();
    }

    public void setModels(List<AiModel> models) {
        all.clear();
        all.addAll(models);
        applyFilter();
    }

    public List<AiModel> getModels() { return all; }

    private void applyFilter() {
        shown.clear();
        for (AiModel m : all) if (filter.isEmpty() || m.id.toLowerCase(Locale.ROOT).contains(filter) || m.label().toLowerCase(Locale.ROOT).contains(filter)) shown.add(m);
        refresh();
    }

    private void refresh() {
        int on = 0, free = 0;
        for (AiModel m : all) { if (m.enabled) on++; if (m.free) free++; }
        summary.setText(on + "/" + all.size() + " selected · " + free + " free");
        adapter.notifyDataSetChanged();
    }

    private final class Row extends LinearLayout {
        final CheckBox cb;
        final TextView id, tag, sub;
        AiModel model;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, UI.dp(c, 2), 0, UI.dp(c, 2));
            cb = new CheckBox(c);
            cb.setFocusable(false);
            cb.setClickable(false);
            addView(cb);
            LinearLayout col = UI.vbox(c);
            id = UI.label(c, "", 13.5f, false);
            id.setSingleLine(true);
            id.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            sub = UI.label(c, "", 11, true);
            sub.setSingleLine(true);
            col.addView(id);
            col.addView(sub);
            addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tag = UI.label(c, "FREE", 10.5f, false);
            tag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tag.setBackgroundResource(R.drawable.bg_tag_free);
            tag.setPadding(UI.dp(c, 7), UI.dp(c, 2), UI.dp(c, 7), UI.dp(c, 2));
            LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = UI.dp(c, 8);
            addView(tag, tl);
            setOnClickListener(v -> { model.enabled = !model.enabled; cb.setChecked(model.enabled); refreshSummary(); });
        }

        void bind(AiModel m) {
            model = m;
            cb.setChecked(m.enabled);
            id.setText(m.id);
            String s = m.name != null && !m.name.equals(m.id) ? m.name : "";
            if (m.context > 0) s += (s.isEmpty() ? "" : " · ") + (m.context >= 1000 ? (m.context / 1000) + "k" : m.context) + " ctx";
            sub.setText(s);
            sub.setVisibility(s.isEmpty() ? GONE : VISIBLE);
            tag.setVisibility(m.free ? VISIBLE : GONE);
        }
    }

    private void refreshSummary() {
        int on = 0, free = 0;
        for (AiModel m : all) { if (m.enabled) on++; if (m.free) free++; }
        summary.setText(on + "/" + all.size() + " selected · " + free + " free");
    }
}
