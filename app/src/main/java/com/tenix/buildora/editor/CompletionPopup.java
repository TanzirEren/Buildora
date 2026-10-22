package com.tenix.buildora.editor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.assist.Completion;
import com.tenix.buildora.core.UI;

import java.util.ArrayList;
import java.util.List;

/** Suggestion list shown under the caret. Never takes focus, so the keyboard stays open. */
public final class CompletionPopup {

    public interface OnPick { void onPick(Completion c); }

    private final Context ctx;
    private final CodeEditText editor;
    private final OnPick onPick;
    private final PopupWindow window;
    private final ListView list;
    private final List<Completion> items = new ArrayList<>();
    private int rowH;

    public CompletionPopup(Context ctx, CodeEditText editor, OnPick onPick) {
        this.ctx = ctx;
        this.editor = editor;
        this.onPick = onPick;
        rowH = UI.dp(ctx, 38);
        list = new ListView(ctx);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UI.color(ctx, R.attr.bSurface));
        bg.setCornerRadius(UI.dp(ctx, 10));
        bg.setStroke(UI.dp(ctx, 1), UI.color(ctx, R.attr.bDivider));
        list.setBackground(bg);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) { return row(items.get(i)); }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                Completion c = items.get(pos);
                dismiss();
                CompletionPopup.this.onPick.onPick(c);
            }
        });
        window = new PopupWindow(list, UI.dp(ctx, 300), rowH * 3, false);
        window.setFocusable(false);
        window.setTouchable(true);
        window.setOutsideTouchable(false);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setElevation(UI.dp(ctx, 8));
    }

    private int kindColor(char k) {
        switch (k) {
            case 'm': return Color.parseColor("#8250DF");
            case 'f': return Color.parseColor("#0E8A7D");
            case 'c': return Color.parseColor("#D97706");
            case 'v': return Color.parseColor("#2F7EC7");
            case 'k': return Color.parseColor("#C0392B");
            case 'r': return Color.parseColor("#B8860B");
            case 'a': return Color.parseColor("#0B7A5E");
            case 't': return Color.parseColor("#8A5CF5");
            default: return Color.GRAY;
        }
    }

    private View row(Completion c) {
        LinearLayout r = UI.hbox(ctx);
        r.setPadding(UI.dp(ctx, 8), 0, UI.dp(ctx, 8), 0);
        r.setLayoutParams(new AbsListViewLP(rowH));
        TextView badge = new TextView(ctx);
        badge.setText(String.valueOf(Character.toUpperCase(c.kind)));
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable b = new GradientDrawable();
        b.setColor(kindColor(c.kind));
        b.setCornerRadius(UI.dp(ctx, 5));
        badge.setBackground(b);
        r.addView(badge, new LinearLayout.LayoutParams(UI.dp(ctx, 20), UI.dp(ctx, 20)));
        TextView label = new TextView(ctx);
        label.setText(c.label);
        label.setTextColor(UI.color(ctx, R.attr.bText));
        label.setTextSize(13);
        label.setTypeface(Typeface.MONOSPACE);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = UI.dp(ctx, 8);
        r.addView(label, lp);
        TextView detail = new TextView(ctx);
        detail.setText(c.detail);
        detail.setTextColor(UI.color(ctx, R.attr.bTextDim));
        detail.setTextSize(11);
        detail.setSingleLine(true);
        detail.setMaxWidth(UI.dp(ctx, 110));
        detail.setEllipsize(android.text.TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.leftMargin = UI.dp(ctx, 8);
        r.addView(detail, dl);
        return r;
    }

    private static final class AbsListViewLP extends android.widget.AbsListView.LayoutParams {
        AbsListViewLP(int h) { super(ViewGroup.LayoutParams.MATCH_PARENT, h); }
    }

    public boolean isShowing() { return window.isShowing(); }

    public void dismiss() { if (window.isShowing()) window.dismiss(); }

    public Completion first() { return items.isEmpty() ? null : items.get(0); }

    /** Shows (or updates) the list under the caret; if there is no room below it opens above. */
    public void show(List<Completion> result) {
        if (result.isEmpty() || editor.getLayout() == null || !editor.isShown()) { dismiss(); return; }
        items.clear();
        items.addAll(result);
        ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        list.setSelection(0);
        Layout l = editor.getLayout();
        int off = Math.max(0, Math.min(editor.getSelectionStart(), editor.length()));
        int line = l.getLineForOffset(off);
        int[] loc = new int[2];
        editor.getLocationInWindow(loc);
        float x = l.getPrimaryHorizontal(off) + editor.getCompoundPaddingLeft() - editor.getScrollX();
        int lineTop = l.getLineTop(line) + editor.getExtendedPaddingTop() - editor.getScrollY();
        int lineBottom = l.getLineBottom(line) + editor.getExtendedPaddingTop() - editor.getScrollY();
        int rows = Math.min(items.size(), 6);
        int h = rows * rowH;
        int w = Math.min(UI.dp(ctx, 340), editor.getWidth() - UI.dp(ctx, 12));
        int px = loc[0] + (int) x;
        px = Math.max(loc[0] + UI.dp(ctx, 4), Math.min(px, loc[0] + editor.getWidth() - w - UI.dp(ctx, 4)));
        int below = loc[1] + lineBottom + UI.dp(ctx, 2);
        int editorBottom = loc[1] + editor.getHeight();
        int py = below + h <= editorBottom ? below : loc[1] + lineTop - h - UI.dp(ctx, 2);
        if (py < loc[1]) py = below;
        if (window.isShowing()) window.update(px, py, w, h);
        else {
            window.setWidth(w);
            window.setHeight(h);
            window.showAtLocation(editor, Gravity.NO_GRAVITY, px, py);
        }
    }
}
