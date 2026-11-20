package com.tenix.buildora.ai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders Markdown (the subset chat models use) into native, selectable views. */
public final class MdRender {
    private MdRender() {}

    public interface Listener {
        void onQuote(String text);                          // "Reply" on selected text
        void onCode(String lang, String code, String path); // "Insert/Use" button on a code block, path may be ""
    }

    private static final int ID_REPLY = 0x7f0f0001;
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*(.*)$");
    private static final Pattern HEAD = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern NUM = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile(
            "`([^`\\n]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)|(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])");

    /** Clears {@code out} and fills it with the rendered message. */
    public static void render(Context c, LinearLayout out, String md, float sp, boolean selectable, Listener l, boolean showCodeActions) {
        out.removeAllViews();
        if (md == null) md = "";
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        StringBuilder para = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            Matcher fm = FENCE.matcher(ln);
            if (fm.matches()) {
                flush(c, out, para, sp, selectable, l);
                String fence = fm.group(1), info = fm.group(2).trim();
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    String t = lines[j].trim();
                    if (t.length() >= fence.length() && t.charAt(0) == fence.charAt(0) && t.replace(String.valueOf(fence.charAt(0)), "").isEmpty()) { closed = true; break; }
                    code.append(lines[j]).append('\n');
                    j++;
                }
                String body = code.toString();
                if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
                addCode(c, out, info, body, sp, selectable, l, showCodeActions && closed, closed);
                i = closed ? j + 1 : j;
                continue;
            }
            if (ln.trim().isEmpty()) { flush(c, out, para, sp, selectable, l); i++; continue; }
            Matcher hm = HEAD.matcher(ln);
            if (hm.matches()) {
                flush(c, out, para, sp, selectable, l);
                int lvl = hm.group(1).length();
                TextView t = text(c, inline(c, hm.group(2), sp), sp * (lvl <= 1 ? 1.35f : lvl == 2 ? 1.2f : 1.07f), selectable, l);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                pad(t, 8, 2);
                out.addView(t);
                i++;
                continue;
            }
            if (ln.trim().matches("^([-*_])(\\s*\\1){2,}$")) {
                flush(c, out, para, sp, selectable, l);
                View v = new View(c);
                v.setBackgroundColor(UI.color(c, R.attr.bDivider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 1));
                lp.topMargin = UI.dp(c, 8); lp.bottomMargin = UI.dp(c, 8);
                out.addView(v, lp);
                i++;
                continue;
            }
            if (ln.trim().startsWith(">")) {
                flush(c, out, para, sp, selectable, l);
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().replaceFirst("^>\\s?", "")).append('\n'); i++; }
                addQuote(c, out, q.toString().trim(), sp, selectable, l);
                continue;
            }
            if (ln.contains("|") && i + 1 < lines.length && lines[i + 1].matches("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")) {
                flush(c, out, para, sp, selectable, l);
                List<String[]> rows = new ArrayList<>();
                rows.add(cells(ln));
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) { rows.add(cells(lines[i])); i++; }
                addTable(c, out, rows, sp);
                continue;
            }
            Matcher bm = BULLET.matcher(ln), nm = NUM.matcher(ln);
            if (bm.matches() || nm.matches()) {
                flush(c, out, para, sp, selectable, l);
                boolean num = !bm.matches();
                Matcher m = num ? nm : bm;
                int indent = m.group(1).replace("\t", "    ").length() / 2;
                String marker = num ? m.group(2) + "." : "•";
                TextView t = text(c, inline(c, marker + "  " + m.group(3), sp), sp, selectable, l);
                t.setPadding(UI.dp(c, 6 + indent * 14), UI.dp(c, 1), 0, UI.dp(c, 1));
                out.addView(t);
                i++;
                continue;
            }
            if (para.length() > 0) para.append('\n');
            para.append(ln);
            i++;
        }
        flush(c, out, para, sp, selectable, l);
    }

    private static void pad(TextView t, int top, int bottom) {
        Context c = t.getContext();
        t.setPadding(t.getPaddingLeft(), UI.dp(c, top), t.getPaddingRight(), UI.dp(c, bottom));
    }

    private static void flush(Context c, LinearLayout out, StringBuilder para, float sp, boolean sel, Listener l) {
        if (para.length() == 0) return;
        TextView t = text(c, inline(c, para.toString(), sp), sp, sel, l);
        pad(t, 3, 3);
        out.addView(t);
        para.setLength(0);
    }

    static TextView text(Context c, CharSequence s, float sp, boolean selectable, final Listener l) {
        final TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setLineSpacing(0, 1.12f);
        t.setTextColor(UI.color(c, R.attr.bText));
        t.setLinkTextColor(UI.color(c, R.attr.bAccent));
        t.setMovementMethod(LinkMovementMethod.getInstance());
        if (selectable) makeSelectable(t, l);
        return t;
    }

    /** Selectable text with an extra "Reply" action in the selection menu. */
    public static void makeSelectable(final TextView t, final Listener l) {
        t.setTextIsSelectable(true);
        if (l == null) return;
        t.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, ID_REPLY, 0, "Reply");
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == ID_REPLY) {
                    int a = Math.min(t.getSelectionStart(), t.getSelectionEnd());
                    int b = Math.max(t.getSelectionStart(), t.getSelectionEnd());
                    if (a >= 0 && b > a) l.onQuote(t.getText().subSequence(a, b).toString());
                    mode.finish();
                    return true;
                }
                return false;
            }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        });
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        String[] p = r.split("\\|", -1);
        for (int i = 0; i < p.length; i++) p[i] = p[i].trim().replace("**", "").replace("`", "");
        return p;
    }

    private static void addTable(Context c, LinearLayout out, List<String[]> rows, float sp) {
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        int[] w = new int[cols];
        for (String[] r : rows) for (int k = 0; k < r.length; k++) w[k] = Math.min(40, Math.max(w[k], r[k].length()));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            int start = sb.length();
            for (int k = 0; k < cols; k++) {
                String cell = k < r.length ? r[k] : "";
                if (cell.length() > 40) cell = cell.substring(0, 39) + "…";
                sb.append(cell);
                for (int s = cell.length(); s < w[k]; s++) sb.append(' ');
                if (k < cols - 1) sb.append("  │  ");
            }
            if (ri == 0) sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            if (ri == 0) {
                for (int k = 0; k < cols; k++) { for (int s = 0; s < w[k]; s++) sb.append('─'); if (k < cols - 1) sb.append("──┼──"); }
                sb.append('\n');
            }
        }
        TextView t = new TextView(c);
        t.setText(sb);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp - 1.5f);
        t.setTextColor(UI.color(c, R.attr.bText));
        t.setTextIsSelectable(true);
        t.setPadding(UI.dp(c, 10), UI.dp(c, 8), UI.dp(c, 10), UI.dp(c, 8));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setBackgroundResource(R.drawable.bg_code);
        hs.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(c, 4); lp.bottomMargin = UI.dp(c, 4);
        out.addView(hs, lp);
    }

    private static void addQuote(Context c, LinearLayout out, String text, float sp, boolean sel, Listener l) {
        LinearLayout row = UI.hbox(c);
        View bar = new View(c);
        bar.setBackgroundColor(UI.color(c, R.attr.bAccent));
        row.addView(bar, new LinearLayout.LayoutParams(UI.dp(c, 3), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView t = text(c, inline(c, text, sp), sp, sel, l);
        t.setTextColor(UI.color(c, R.attr.bTextDim));
        t.setPadding(UI.dp(c, 10), UI.dp(c, 2), 0, UI.dp(c, 2));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(c, 4); lp.bottomMargin = UI.dp(c, 4);
        out.addView(row, lp);
    }

    /** Blocks the user has opened (kept across re-renders of the same chat). */
    private static final java.util.Set<String> OPEN = new java.util.HashSet<>();

    private static void addCode(final Context c, LinearLayout out, String info, final String code, float sp, boolean sel,
                                final Listener l, boolean actions, boolean closed) {
        String lang = info.isEmpty() ? "" : info.split("\\s+")[0];
        String path = "";
        if (info.contains("path=") || info.contains("file=") || info.contains(":")) {
            Protocol.Parsed pp = Protocol.parse("```" + info + "\n" + code + "\n```");
            if (!pp.ops.isEmpty()) path = pp.ops.get(0).path;
        }
        boolean special = lang.startsWith("buildora-");
        final String fpath = path, flang = lang;
        boolean fileBlock = !path.isEmpty() || special;
        if (fileBlock) { addFileRow(c, out, path, lang, special, code, sp, sel, l, closed); return; }
        LinearLayout card = UI.vbox(c);
        card.setBackgroundResource(R.drawable.bg_code);
        LinearLayout head = UI.hbox(c);
        head.setPadding(UI.dp(c, 10), UI.dp(c, 4), UI.dp(c, 4), UI.dp(c, 4));
        TextView title = UI.label(c, lang.isEmpty() ? "code" : lang, 11.5f, true);
        title.setTypeface(Typeface.MONOSPACE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (actions && l != null) {
            TextView ins = chip(c, "Insert");
            ins.setOnClickListener(v -> l.onCode(flang, code, ""));
            head.addView(ins);
        }
        TextView copy = chip(c, "Copy");
        copy.setOnClickListener(v -> { UI.copy(c, "code", code); UI.toast(c, "Copied"); });
        head.addView(copy);
        card.addView(head);
        View div = new View(c);
        div.setBackgroundColor(UI.color(c, R.attr.bDivider));
        card.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 1)));
        card.addView(codeBody(c, code, sp, sel, l));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(c, 6); lp.bottomMargin = UI.dp(c, 6);
        out.addView(card, lp);
    }

    private static View codeBody(Context c, String code, float sp, boolean sel, Listener l) {
        TextView body = new TextView(c);
        body.setText(code.isEmpty() ? " " : code);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(Math.max(10.5f, sp - 1.5f));
        body.setTextColor(UI.color(c, R.attr.bText));
        body.setPadding(UI.dp(c, 10), UI.dp(c, 8), UI.dp(c, 10), UI.dp(c, 8));
        if (sel) makeSelectable(body, l);
        body.setHorizontallyScrolling(true);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(body);
        return hs;
    }

    private static int lineCount(String code) {
        if (code.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < code.length(); i++) if (code.charAt(i) == '\n') n++;
        return n;
    }

    /** A file the AI writes shows only its path; tap to open the code. Nothing is inflated until then. */
    private static void addFileRow(final Context c, LinearLayout out, String path, String lang, boolean special, final String code,
                                   final float sp, final boolean sel, final Listener l, boolean closed) {
        final String key = (path.isEmpty() ? lang : path) + "|" + code.length() + "|" + code.hashCode();
        String title = !path.isEmpty() ? path : lang.substring(9);
        if (special) {
            switch (lang.substring(9)) {
                case "ops": title = "file operations"; break;
                case "deps": title = "libraries"; break;
                case "read": title = "files the AI wants to read"; break;
                case "project": title = "new project"; break;
                default: break;
            }
        }
        final LinearLayout card = UI.vbox(c);
        card.setBackgroundResource(R.drawable.bg_code);
        LinearLayout head = UI.hbox(c);
        head.setPadding(UI.dp(c, 12), UI.dp(c, 4), UI.dp(c, 6), UI.dp(c, 4));
        head.setMinimumHeight(UI.dp(c, 44));
        head.setBackgroundResource(R.drawable.bg_ripple_rect);
        android.widget.ImageView ic = new android.widget.ImageView(c);
        ic.setImageResource(special ? R.drawable.mi_list : lang.equals("patch") ? R.drawable.mi_edit : R.drawable.mi_file_text);
        ic.setColorFilter(UI.color(c, R.attr.bAccent));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(UI.dp(c, 18), UI.dp(c, 18));
        ilp.rightMargin = UI.dp(c, 10);
        head.addView(ic, ilp);
        TextView t = UI.label(c, title, 12.5f, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int lines = lineCount(code);
        String meta = !closed ? "writing…" : special ? lines + (lines == 1 ? " line" : " lines") : (lang.equals("patch") ? "edit · " : "") + lines + (lines == 1 ? " line" : " lines");
        TextView m = UI.label(c, meta, 11, true);
        m.setPadding(UI.dp(c, 8), 0, UI.dp(c, 4), 0);
        head.addView(m);
        final android.widget.ImageView chev = new android.widget.ImageView(c);
        chev.setImageResource(R.drawable.mi_chevron_down);
        chev.setColorFilter(UI.color(c, R.attr.bTextDim));
        if (closed) head.addView(chev, new LinearLayout.LayoutParams(UI.dp(c, 20), UI.dp(c, 20)));
        card.addView(head);
        final LinearLayout bodyBox = UI.vbox(c);
        card.addView(bodyBox);
        final boolean[] built = {false};
        final Runnable show = () -> {
            if (!built[0]) {
                View div = new View(c);
                div.setBackgroundColor(UI.color(c, R.attr.bDivider));
                bodyBox.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 1)));
                LinearLayout bar = UI.hbox(c);
                bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                TextView copy = chip(c, "Copy");
                copy.setOnClickListener(v -> { UI.copy(c, "code", code); UI.toast(c, "Copied"); });
                bar.addView(copy);
                bodyBox.addView(bar);
                bodyBox.addView(codeBody(c, code.length() > 60000 ? code.substring(0, 60000) + "\n… (shortened)" : code, sp, sel, l));
                built[0] = true;
            }
            bodyBox.setVisibility(View.VISIBLE);
            chev.setRotation(180f);
        };
        bodyBox.setVisibility(View.GONE);
        if (closed) {
            if (OPEN.contains(key)) show.run();
            head.setOnClickListener(v -> {
                if (bodyBox.getVisibility() == View.VISIBLE) { bodyBox.setVisibility(View.GONE); chev.setRotation(0f); OPEN.remove(key); }
                else { show.run(); if (OPEN.size() > 200) OPEN.clear(); OPEN.add(key); }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UI.dp(c, 4); lp.bottomMargin = UI.dp(c, 4);
        out.addView(card, lp);
    }

    private static TextView chip(Context c, String s) {
        TextView t = UI.label(c, s, 11.5f, false);
        t.setTextColor(UI.color(c, R.attr.bAccent));
        t.setPadding(UI.dp(c, 10), UI.dp(c, 6), UI.dp(c, 10), UI.dp(c, 6));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Inline Markdown: code, bold, italic, strike, links. */
    public static CharSequence inline(Context c, String s, float sp) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = INLINE.matcher(s);
        int last = 0;
        int codeBg = UI.color(c, R.attr.bSurface2);
        while (m.find()) {
            sb.append(s, last, m.start());
            int a = sb.length();
            if (m.group(1) != null) {
                sb.append(m.group(1));
                sb.setSpan(new TypefaceSpan("monospace"), a, sb.length(), 0);
                sb.setSpan(new BackgroundColorSpan(codeBg), a, sb.length(), 0);
                sb.setSpan(new RelativeSizeSpan(0.92f), a, sb.length(), 0);
            } else if (m.group(2) != null || m.group(3) != null) {
                sb.append(m.group(2) != null ? m.group(2) : m.group(3));
                sb.setSpan(new StyleSpan(Typeface.BOLD), a, sb.length(), 0);
            } else if (m.group(4) != null) {
                sb.append(m.group(4));
                sb.setSpan(new StrikethroughSpan(), a, sb.length(), 0);
            } else if (m.group(5) != null) {
                sb.append(m.group(5));
                sb.setSpan(new URLSpan(m.group(6)), a, sb.length(), 0);
            } else {
                sb.append(m.group(7));
                sb.setSpan(new StyleSpan(Typeface.ITALIC), a, sb.length(), 0);
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
        return sb;
    }
}
