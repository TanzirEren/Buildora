package com.tenix.buildora.core;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Rounded floating menu with a vector icon on every row, used instead of the plain system popup.
 * It opens above the anchor when there is more room there (for example the chat "+" button).
 */
public final class MenuSheet {

    public interface Pick { void on(int id); }

    private static final class Item {
        int id, icon;
        String label = "", sub = "";
        boolean danger, divider, header;
    }

    private final Context c;
    private final List<Item> items = new ArrayList<>();

    public MenuSheet(Context c) { this.c = c; }

    public MenuSheet add(int id, int icon, String label) { return add(id, icon, label, ""); }

    public MenuSheet add(int id, int icon, String label, String sub) {
        Item i = new Item();
        i.id = id; i.icon = icon; i.label = label; i.sub = sub == null ? "" : sub;
        items.add(i);
        return this;
    }

    public MenuSheet danger(int id, int icon, String label) {
        add(id, icon, label);
        items.get(items.size() - 1).danger = true;
        return this;
    }

    public MenuSheet divider() {
        if (items.isEmpty() || items.get(items.size() - 1).divider) return this;
        Item i = new Item();
        i.divider = true;
        items.add(i);
        return this;
    }

    public MenuSheet header(String text) {
        Item i = new Item();
        i.header = true; i.label = text;
        items.add(i);
        return this;
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public void show(final View anchor, final Pick listener) {
        final PopupWindow[] pw = new PopupWindow[1];
        LinearLayout col = UI.vbox(c);
        col.setPadding(UI.dp(c, 6), UI.dp(c, 8), UI.dp(c, 6), UI.dp(c, 8));
        for (final Item it : items) {
            if (it.divider) {
                View d = new View(c);
                d.setBackgroundColor(UI.color(c, R.attr.bDivider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 1));
                lp.setMargins(UI.dp(c, 14), UI.dp(c, 6), UI.dp(c, 14), UI.dp(c, 6));
                col.addView(d, lp);
                continue;
            }
            if (it.header) {
                TextView h = UI.label(c, it.label.toUpperCase(), 10.5f, true);
                h.setLetterSpacing(0.08f);
                h.setPadding(UI.dp(c, 14), UI.dp(c, 8), UI.dp(c, 14), UI.dp(c, 4));
                col.addView(h);
                continue;
            }
            LinearLayout row = UI.hbox(c);
            row.setMinimumHeight(UI.dp(c, 48));
            row.setPadding(UI.dp(c, 8), UI.dp(c, 4), UI.dp(c, 16), UI.dp(c, 4));
            row.setBackgroundResource(R.drawable.bg_ripple_rect);
            if (it.icon != 0) {
                FrameLayout box = new FrameLayout(c);
                box.setBackgroundResource(it.danger ? R.drawable.bg_menu_icon_danger : R.drawable.bg_menu_icon);
                ImageView iv = new ImageView(c);
                iv.setImageResource(it.icon);
                iv.setColorFilter(it.danger ? UI.color(c, R.attr.bError) : UI.color(c, R.attr.bAccent));
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(UI.dp(c, 19), UI.dp(c, 19), Gravity.CENTER);
                box.addView(iv, ilp);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(UI.dp(c, 36), UI.dp(c, 36));
                blp.rightMargin = UI.dp(c, 12);
                row.addView(box, blp);
            } else row.setPadding(UI.dp(c, 16), UI.dp(c, 4), UI.dp(c, 16), UI.dp(c, 4));
            LinearLayout txt = UI.vbox(c);
            TextView l = UI.label(c, it.label, 14.5f, false);
            if (it.danger) l.setTextColor(UI.color(c, R.attr.bError));
            l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            txt.addView(l);
            if (!it.sub.isEmpty()) txt.addView(UI.label(c, it.sub, 11, true));
            row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                if (pw[0] != null) pw[0].dismiss();
                if (listener != null) listener.on(it.id);
            });
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int margin = UI.dp(c, 10);
        int rootW = anchor.getRootView().getWidth(), rootH = anchor.getRootView().getHeight();
        int minW = UI.dp(c, 230), maxW = Math.min(UI.dp(c, 300), rootW - 2 * margin);
        col.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.max(minW, Math.min(maxW, col.getMeasuredWidth()));
        int h = col.getMeasuredHeight();

        int[] loc = new int[2], sloc = new int[2];
        anchor.getLocationInWindow(loc);
        anchor.getLocationOnScreen(sloc);
        int dx = sloc[0] - loc[0], dy = sloc[1] - loc[1];     // window origin on the screen (status bar etc.)
        int aTop = loc[1], aBottom = loc[1] + anchor.getHeight();
        int below = rootH - aBottom - margin, above = aTop - margin;
        boolean openUp = h > below && above > below;
        int maxH = Math.max(UI.dp(c, 160), (openUp ? above : below) - UI.dp(c, 4));
        int hh = Math.min(h, maxH);
        int x = loc[0] + anchor.getWidth() / 2 > rootW / 2 ? loc[0] + anchor.getWidth() - w : loc[0];
        x = Math.max(margin, Math.min(x, rootW - w - margin));
        int y = openUp ? aTop - hh - UI.dp(c, 6) : aBottom + UI.dp(c, 2);
        y = Math.max(margin, y);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout card = new FrameLayout(c);
        card.setBackgroundResource(R.drawable.bg_menu);
        card.setClipToOutline(true);
        card.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout wrap = new FrameLayout(c);
        int sh = UI.dp(c, 10);
        wrap.setPadding(sh, sh, sh, sh);
        wrap.setClipToPadding(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, hh));
        card.setElevation(UI.dp(c, 8));

        final PopupWindow p = new PopupWindow(wrap, w + 2 * sh, hh + 2 * sh, true);
        pw[0] = p;
        p.setBackgroundDrawable(new ColorDrawable(0));
        p.setOutsideTouchable(true);
        p.setClippingEnabled(true);
        card.setPivotX(x + w / 2 > rootW / 2 ? w : 0);
        card.setPivotY(openUp ? hh : 0);
        card.setScaleX(0.9f); card.setScaleY(0.9f); card.setAlpha(0f);
        p.showAtLocation(anchor, Gravity.NO_GRAVITY, x - sh + dx, y - sh + dy);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
    }
}
