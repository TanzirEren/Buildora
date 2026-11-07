package com.tenix.buildora.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.FilePicker;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.Templates;
import com.tenix.buildora.core.UI;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Buildora AI chat: message list, chat box, attachments, model picker, streaming,
 * and applying AI file changes to a project. Used full screen and inside the IDE drawer.
 */
public class ChatPanel extends LinearLayout {

    /** What the surrounding screen offers to the chat. All methods have harmless defaults. */
    public static abstract class Host {
        public File projectRoot() { return null; }
        public boolean ideMode() { return false; }
        public String currentFile() { return null; }          // relative path of the open file
        public String selection() { return ""; }
        public List<String> openFiles() { return new ArrayList<>(); }
        public String lintInfo() { return ""; }
        public void insertAtCaret(String text) { }
        public void saveAll() { }
        public void filesChanged(List<String> relPaths) { }
        public void runBuild() { }
        public void fixFinished(boolean applied) { }
        public void chatChanged(Chat c) { }
        public void openIde(File root) { }
        public void projectCreated(File root, Chat c) { }
    }

    private static final class Att {
        String name, rel, text;
        ChatClient.Img img;
    }

    private final Activity act;
    private final Host host;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ChatStore store;
    private final ProviderStore ps;

    private Chat chat;
    private String providerId = "", modelId = "";
    private final List<Att> atts = new ArrayList<>();
    private String quote = "";
    private ChatClient client;
    private boolean busy, stopped, fixMode;
    private int fixRound, readRounds;
    private final List<String> forcePriority = new ArrayList<>();
    private final Map<Chat.Message, List<FileOps.Change>> plans = new IdentityHashMap<>();

    // views
    private final ScrollView scroll;
    private final LinearLayout msgBox;
    private final LinearLayout quoteBar;
    private final TextView quoteText;
    private final LinearLayout attachRow;
    private final HorizontalScrollView attachScroll;
    private final HorizontalScrollView quickScroll;
    private final LinearLayout quickRow;
    private final EditText input;
    private final TextView modelChip, tokens;
    private final ImageButton btnSend, btnPlus;
    private LinearLayout liveBody;
    private TextView liveHint;
    private final StringBuilder stream = new StringBuilder();
    private boolean tickPending, autoScroll = true;

    private final MdRender.Listener mdListener = new MdRender.Listener() {
        @Override public void onQuote(String text) { setQuote(text); }
        @Override public void onCode(String lang, String code, String path) { host.insertAtCaret(code); UI.toast(act, "Inserted at cursor"); }
    };

    public ChatPanel(Activity a, Host host) {
        super(a);
        this.act = a;
        this.host = host;
        this.store = Ai.chats(a);
        this.ps = Ai.providers(a);
        setOrientation(VERTICAL);
        setBackgroundColor(UI.color(a, R.attr.bBg));

        // ---- messages
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        msgBox = UI.vbox(a);
        msgBox.setPadding(UI.dp(a, 14), UI.dp(a, 10), UI.dp(a, 14), UI.dp(a, 10));
        scroll.addView(msgBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_MOVE) autoScroll = false;
            if (e.getAction() == MotionEvent.ACTION_UP) h.postDelayed(() -> autoScroll = nearBottom(), 250);
            return false;
        });
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- quote bar
        quoteBar = UI.hbox(a);
        quoteBar.setPadding(UI.dp(a, 16), UI.dp(a, 6), UI.dp(a, 8), UI.dp(a, 2));
        quoteBar.setVisibility(GONE);
        View bar = new View(a);
        bar.setBackgroundColor(UI.color(a, R.attr.bAccent));
        quoteBar.addView(bar, new LayoutParams(UI.dp(a, 3), UI.dp(a, 28)));
        quoteText = UI.label(a, "", 12, true);
        quoteText.setSingleLine(true);
        quoteText.setEllipsize(TextUtils.TruncateAt.END);
        quoteText.setPadding(UI.dp(a, 8), 0, UI.dp(a, 8), 0);
        quoteBar.addView(quoteText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton qx = icon(R.drawable.ic_close, 30, 14);
        qx.setOnClickListener(v -> setQuote(""));
        quoteBar.addView(qx);
        addView(quoteBar);

        // ---- attachments
        attachScroll = new HorizontalScrollView(a);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachRow = UI.hbox(a);
        attachRow.setPadding(UI.dp(a, 14), UI.dp(a, 4), UI.dp(a, 14), UI.dp(a, 2));
        attachScroll.addView(attachRow);
        attachScroll.setVisibility(GONE);
        addView(attachScroll);

        // ---- quick prompts
        quickScroll = new HorizontalScrollView(a);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickRow = UI.hbox(a);
        quickRow.setPadding(UI.dp(a, 14), UI.dp(a, 4), UI.dp(a, 14), UI.dp(a, 2));
        quickScroll.addView(quickRow);
        addView(quickScroll);

        // ---- chat box
        LinearLayout box = UI.vbox(a);
        box.setBackgroundResource(R.drawable.bg_chatbox);
        box.setElevation(UI.dp(a, 3));
        box.setPadding(UI.dp(a, 14), UI.dp(a, 6), UI.dp(a, 8), UI.dp(a, 8));
        input = new EditText(a);
        input.setHint("Ask Buildora AI…");
        input.setHintTextColor(UI.color(a, R.attr.bTextDim));
        input.setTextColor(UI.color(a, R.attr.bText));
        input.setTextSize(14.5f);
        input.setBackground(null);
        input.setMinLines(1);
        input.setMaxLines(7);
        input.setGravity(Gravity.TOP);
        input.setPadding(0, UI.dp(a, 6), UI.dp(a, 6), UI.dp(a, 4));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(input, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = UI.hbox(a);
        btnPlus = circle(R.drawable.ic_add, R.drawable.bg_circle_tonal, R.attr.bText);
        btnPlus.setOnClickListener(v -> attachMenu(v));
        row.addView(btnPlus, new LayoutParams(UI.dp(a, 34), UI.dp(a, 34)));
        modelChip = UI.label(a, "Select model", 12.5f, false);
        modelChip.setBackgroundResource(R.drawable.bg_chip);
        modelChip.setSingleLine(true);
        modelChip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        modelChip.setPadding(UI.dp(a, 12), UI.dp(a, 7), UI.dp(a, 12), UI.dp(a, 7));
        modelChip.setMaxWidth(UI.dp(a, 190));
        modelChip.setOnClickListener(v -> pickModel());
        LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = UI.dp(a, 8);
        row.addView(modelChip, mlp);
        View spacer = new View(a);
        row.addView(spacer, new LayoutParams(0, 1, 1f));
        tokens = UI.label(a, "", 11, true);
        tokens.setPadding(0, 0, UI.dp(a, 8), 0);
        row.addView(tokens);
        btnSend = circle(R.drawable.ic_arrow_up, R.drawable.bg_circle_accent, R.attr.bOnAccent);
        btnSend.setOnClickListener(v -> onSendClicked());
        row.addView(btnSend, new LayoutParams(UI.dp(a, 34), UI.dp(a, 34)));
        box.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(UI.dp(a, 10), UI.dp(a, 4), UI.dp(a, 10), UI.dp(a, 10));
        addView(box, blp);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateMeta(); }
        });
        newChat(host.projectRoot() == null ? "" : host.projectRoot().getPath());
    }

    // ------------------------------------------------------------------ small view helpers

    private ImageButton icon(int res, int sizeDp, int padDp) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(res);
        b.setBackground(null);
        b.setColorFilter(UI.color(act, R.attr.bTextDim));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int p = UI.dp(act, padDp / 2f);
        b.setPadding(p, p, p, p);
        b.setLayoutParams(new LayoutParams(UI.dp(act, sizeDp), UI.dp(act, sizeDp)));
        return b;
    }

    private ImageButton circle(int res, int bg, int tintAttr) {
        ImageButton b = new ImageButton(act);
        b.setImageResource(res);
        b.setBackgroundResource(bg);
        b.setColorFilter(UI.color(act, tintAttr));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int p = UI.dp(act, 7);
        b.setPadding(p, p, p, p);
        return b;
    }

    private TextView chip(String s, boolean accent) {
        TextView t = UI.label(act, s, 12.5f, false);
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(UI.dp(act, 12), UI.dp(act, 7), UI.dp(act, 12), UI.dp(act, 7));
        if (accent) t.setTextColor(UI.color(act, R.attr.bAccent));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(act, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private boolean nearBottom() {
        View c = scroll.getChildAt(0);
        return c == null || scroll.getScrollY() + scroll.getHeight() >= c.getHeight() - UI.dp(act, 60);
    }

    private void scrollDown(final boolean force) {
        if (!force && !autoScroll) return;
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private float sp() { return Ai.fontSp(act); }

    // ------------------------------------------------------------------ chat lifecycle

    public Chat chat() { return chat; }

    public void newChat(String projectRoot) {
        saveDraft();
        chat = Chat.create();
        chat.projectRoot = projectRoot == null ? "" : projectRoot;
        afterChatSwitch();
    }

    public void openChat(Chat c) {
        if (c == null) return;
        saveDraft();
        chat = c;
        afterChatSwitch();
    }

    private void afterChatSwitch() {
        plans.clear();
        atts.clear();
        quote = "";
        quoteBar.setVisibility(GONE);
        busy = false; stopped = false; fixMode = false; readRounds = 0;
        forcePriority.clear();
        String[] d = ps.resolveDefault();
        if (!chat.provider.isEmpty() && ps.get(chat.provider) != null && ps.get(chat.provider).find(chat.model) != null && ps.get(chat.provider).enabled) {
            providerId = chat.provider; modelId = chat.model;
        } else if (d != null) { providerId = d[0]; modelId = d[1]; }
        else { providerId = ""; modelId = ""; }
        input.setText(Prefs.str(act, "ai_draft_" + chat.id, ""));
        refreshAttachRow();
        updateModelChip();
        renderAll();
        updateSendIcon();
        host.chatChanged(chat);
    }

    public void saveDraft() {
        if (chat == null) return;
        String t = input.getText().toString();
        Prefs.put(act, "ai_draft_" + chat.id, t);
    }

    public void onPause() { saveDraft(); }

    public File root() {
        File r = host.projectRoot();
        if (r != null) return r;
        if (chat != null && !chat.projectRoot.isEmpty()) {
            File f = new File(chat.projectRoot);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    private void persist() {
        if (chat.messages.isEmpty()) return;
        store.save(chat);
        host.chatChanged(chat);
    }

    // ------------------------------------------------------------------ rendering

    private List<Chat.Message> visible() {
        List<Chat.Message> out = new ArrayList<>();
        for (Chat.Message m : chat.messages) out.add(m);
        return out;
    }

    private void renderAll() {
        msgBox.removeAllViews();
        liveBody = null; liveHint = null;
        if (chat.messages.isEmpty()) msgBox.addView(emptyState());
        else for (int i = 0; i < chat.messages.size(); i++) msgBox.addView(messageView(i));
        refreshQuick();
        scrollDown(true);
    }

    private View emptyState() {
        LinearLayout v = UI.vbox(act);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(UI.dp(act, 12), UI.dp(act, 48), UI.dp(act, 12), UI.dp(act, 12));
        ImageButton logo = circle(R.drawable.ic_ai, R.drawable.bg_circle_tonal, R.attr.bAccent);
        logo.setEnabled(false);
        int p = UI.dp(act, 14);
        logo.setPadding(p, p, p, p);
        v.addView(logo, new LayoutParams(UI.dp(act, 64), UI.dp(act, 64)));
        TextView t = UI.label(act, "Buildora AI", 20, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, UI.dp(act, 14), 0, UI.dp(act, 4));
        v.addView(t);
        boolean ide = host.ideMode();
        TextView s = UI.label(act, root() != null
                ? "Ask about this project, write code, find bugs, or let me fix build errors."
                : "Describe an app and I'll write the whole project. Then open it in the IDE.", 13, true);
        s.setGravity(Gravity.CENTER);
        v.addView(s);
        if (!ps.active().isEmpty()) return v;
        TextView add = UI.button(act, "Add an AI provider", true);
        add.setOnClickListener(x -> act.startActivity(new Intent(act, AiProvidersActivity.class)));
        v.addView(add);
        return v;
    }

    private void refreshQuick() {
        quickRow.removeAllViews();
        boolean show = !busy && input.getText().length() == 0 && (host.ideMode() || chat.messages.isEmpty());
        quickScroll.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        Prompts.Quick[] qs = host.ideMode() || root() != null ? Prompts.IDE_QUICK : Prompts.CHAT_QUICK;
        for (final Prompts.Quick q : qs) {
            TextView c = chip(q.label, false);
            c.setOnClickListener(v -> {
                if (q.fill) { input.setText(q.prompt); input.setSelection(input.length()); focusInput(); }
                else {
                    String text = q.prompt;
                    String lint = host.lintInfo();
                    if (!lint.isEmpty() && (q.label.equals("Find bugs") || q.label.equals("Fix this file"))) text += "\n\nLive lint results for the open file: " + lint;
                    send(text, false);
                }
            });
            quickRow.addView(c);
        }
    }

    private void focusInput() {
        input.requestFocus();
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, 0);
    }

    private View messageView(final int idx) {
        final Chat.Message m = chat.messages.get(idx);
        if (m.model.equals("__note")) return noteView(m.text);
        if (m.role.equals("user")) return userView(m, idx);
        return assistantView(m, idx);
    }

    private View noteView(String text) {
        TextView t = UI.label(act, text, 12, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(UI.dp(act, 8), UI.dp(act, 8), UI.dp(act, 8), UI.dp(act, 8));
        return t;
    }

    private View userView(final Chat.Message m, final int idx) {
        LinearLayout wrap = UI.vbox(act);
        wrap.setGravity(Gravity.END);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = UI.dp(act, 10);
        wrap.setLayoutParams(wl);
        LinearLayout bub = UI.vbox(act);
        bub.setBackgroundResource(R.drawable.bg_bubble_user);
        bub.setPadding(UI.dp(act, 12), UI.dp(act, 8), UI.dp(act, 12), UI.dp(act, 8));
        if (!m.quote.isEmpty()) {
            TextView q = UI.label(act, "↩ " + UI.ellipsize(m.quote.replace('\n', ' '), 140), 11.5f, true);
            q.setPadding(0, 0, 0, UI.dp(act, 4));
            bub.addView(q);
        }
        if (m.hidden) {
            final TextView t = UI.label(act, "🛠 " + UI.ellipsize(m.text.split("\n")[0], 90), 12.5f, false);
            bub.addView(t);
            t.setOnClickListener(v -> UI.message(act, "Message sent to the AI", m.text));
        } else {
            TextView t = UI.label(act, m.text, 14, false);
            bub.addView(t);
        }
        if (!m.attachments.isEmpty()) {
            TextView a = UI.label(act, "📎 " + TextUtils.join(", ", m.attachments), 11.5f, true);
            a.setPadding(0, UI.dp(act, 4), 0, 0);
            bub.addView(a);
        }
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bub.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bub.setOnLongClickListener(v -> {
            PopupMenu pm = new PopupMenu(act, v);
            pm.getMenu().add(0, 1, 0, "Copy");
            pm.getMenu().add(0, 2, 1, "Reply to this");
            if (!busy) pm.getMenu().add(0, 3, 2, "Edit & resend");
            if (!busy) pm.getMenu().add(0, 4, 3, "Delete from here");
            pm.setOnMenuItemClickListener(mi -> {
                switch (mi.getItemId()) {
                    case 1: UI.copy(act, "message", m.text); UI.toast(act, "Copied"); break;
                    case 2: setQuote(m.text); break;
                    case 3:
                        while (chat.messages.size() > idx) chat.messages.remove(chat.messages.size() - 1);
                        persist(); renderAll();
                        input.setText(m.text); input.setSelection(input.length()); focusInput();
                        break;
                    case 4:
                        while (chat.messages.size() > idx) chat.messages.remove(chat.messages.size() - 1);
                        persist(); renderAll();
                        break;
                }
                return true;
            });
            pm.show();
            return true;
        });
        wrap.addView(bub);
        return wrap;
    }

    private View assistantView(final Chat.Message m, final int idx) {
        LinearLayout wrap = UI.vbox(act);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = UI.dp(act, 12);
        wrap.setLayoutParams(wl);
        LinearLayout body = UI.vbox(act);
        MdRender.render(act, body, m.text, sp(), true, mdListener, host.ideMode());
        if (m.error) for (int i = 0; i < body.getChildCount(); i++) if (body.getChildAt(i) instanceof TextView) ((TextView) body.getChildAt(i)).setTextColor(UI.color(act, R.attr.bError));
        wrap.addView(body);
        View card = changesCard(m);
        if (card != null) wrap.addView(card);
        // actions
        LinearLayout acts = UI.hbox(act);
        acts.setPadding(0, UI.dp(act, 2), 0, 0);
        ImageButton cp = icon(R.drawable.ic_copy, 32, 16);
        cp.setOnClickListener(v -> { UI.copy(act, "reply", m.text); UI.toast(act, "Copied"); });
        acts.addView(cp);
        boolean last = idx == chat.messages.size() - 1 || (idx == chat.messages.size() - 2 && chat.messages.get(idx + 1).model.equals("__note"));
        if (last && !busy) {
            ImageButton rg = icon(R.drawable.ic_refresh, 32, 16);
            rg.setOnClickListener(v -> regenerate());
            acts.addView(rg);
        }
        ImageButton sh = icon(R.drawable.ic_share, 32, 16);
        sh.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, m.text);
            act.startActivity(Intent.createChooser(i, "Share reply"));
        });
        acts.addView(sh);
        if (!busy) {
            ImageButton dl = icon(R.drawable.ic_delete, 32, 16);
            dl.setOnClickListener(v -> { chat.messages.remove(m); persist(); renderAll(); });
            acts.addView(dl);
        }
        if (!m.model.isEmpty() && !m.model.equals("__note")) {
            TextView mt = UI.label(act, UI.ellipsize(m.model, 28), 10.5f, true);
            mt.setPadding(UI.dp(act, 8), 0, 0, 0);
            acts.addView(mt);
        }
        wrap.addView(acts);
        return wrap;
    }

    // ------------------------------------------------------------------ model + input state

    private void pickModel() {
        ModelPicker.show(act, providerId, modelId, (p, m) -> {
            providerId = p.id; modelId = m.id;
            chat.provider = p.id; chat.model = m.id;
            ps.setDefault(p.id, m.id);
            updateModelChip();
            persist();
        });
    }

    private void updateModelChip() {
        AiProvider p = ps.get(providerId);
        AiModel m = p == null ? null : p.find(modelId);
        modelChip.setText(m == null ? "Select model" : modelId);
        modelChip.setAlpha(m == null ? 0.7f : 1f);
    }

    private void updateMeta() {
        int chars = input.length();
        for (Att a : atts) if (a.text != null) chars += a.text.length();
        tokens.setText(chars > 300 ? "~" + ProjectContext.estimateTokens(input.getText().toString()) + " tok" : "");
        if (!busy) refreshQuickVisibility();
    }

    private void refreshQuickVisibility() {
        boolean show = input.getText().length() == 0 && (host.ideMode() || chat.messages.isEmpty());
        quickScroll.setVisibility(show ? VISIBLE : GONE);
    }

    private void updateSendIcon() {
        btnSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_arrow_up);
        refreshQuick();
    }

    private void setQuote(String text) {
        quote = text == null ? "" : text.trim();
        quoteBar.setVisibility(quote.isEmpty() ? GONE : VISIBLE);
        quoteText.setText("Replying to: " + quote.replace('\n', ' '));
        if (!quote.isEmpty()) focusInput();
    }

    private void refreshAttachRow() {
        attachRow.removeAllViews();
        attachScroll.setVisibility(atts.isEmpty() ? GONE : VISIBLE);
        for (final Att a : atts) {
            TextView c = chip((a.img != null ? "🖼 " : "📎 ") + UI.ellipsize(a.name, 24) + "  ✕", false);
            c.setOnClickListener(v -> { atts.remove(a); refreshAttachRow(); updateMeta(); });
            attachRow.addView(c);
        }
    }

    // ------------------------------------------------------------------ attachments

    private void attachMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        final File root = root();
        int n = 0;
        if (root != null) pm.getMenu().add(0, 1, n++, "Project file…");
        pm.getMenu().add(0, 2, n++, "File from device…");
        pm.getMenu().add(0, 3, n++, "Image…");
        if (host.currentFile() != null) pm.getMenu().add(0, 4, n++, "Current file");
        if (!host.selection().isEmpty()) pm.getMenu().add(0, 5, n++, "Selected code");
        if (root != null) pm.getMenu().add(0, 6, n++, "Last build log");
        pm.getMenu().add(0, 7, n++, "Paste from clipboard");
        if (!atts.isEmpty()) pm.getMenu().add(0, 8, n++, "Remove all attachments");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: pickProjectFile(root); break;
                case 2: pickDeviceFile(false); break;
                case 3: pickDeviceFile(true); break;
                case 4: { Att a = new Att(); a.name = host.currentFile(); a.rel = host.currentFile(); addAtt(a); break; }
                case 5: { Att a = new Att(); a.name = "selection"; a.text = host.selection(); addAtt(a); break; }
                case 6: {
                    try {
                        File lf = Project.load(root).buildLog();
                        if (!lf.isFile()) { UI.toast(act, "No build log yet"); break; }
                        String t = FileUtil.readText(lf);
                        Att a = new Att(); a.name = "last-build.log"; a.text = t.length() > 8000 ? t.substring(t.length() - 8000) : t; addAtt(a);
                    } catch (Exception e) { UI.toast(act, "Cannot read the build log"); }
                    break;
                }
                case 7: {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    CharSequence t = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).getText() : null;
                    if (t == null || t.length() == 0) UI.toast(act, "Clipboard is empty");
                    else { Att a = new Att(); a.name = "clipboard"; a.text = t.toString(); addAtt(a); }
                    break;
                }
                case 8: atts.clear(); refreshAttachRow(); updateMeta(); break;
            }
            return true;
        });
        pm.show();
    }

    private void addAtt(Att a) {
        for (Att x : atts) if (x.name.equals(a.name) && x.text == null && a.text == null) return;
        if (atts.size() >= 8) { UI.toast(act, "Up to 8 attachments"); return; }
        atts.add(a);
        refreshAttachRow();
        updateMeta();
    }

    private void pickProjectFile(final File root) {
        final List<String> rels = new ArrayList<>();
        for (File f : ProjectContext.listFiles(root, 600)) rels.add(FileUtil.relPath(root, f));
        final List<String> shown = new ArrayList<>(rels);
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 18), UI.dp(act, 6), UI.dp(act, 18), 0);
        EditText q = UI.input(act, "Search files", "");
        box.addView(q);
        final android.widget.ListView lv = new android.widget.ListView(act);
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(act, android.R.layout.simple_list_item_1, shown);
        lv.setAdapter(ad);
        box.addView(lv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(act, 360)));
        final AlertDialog d = UI.dialog(act).setTitle("Attach project file").setView(box).setNegativeButton("Cancel", null).create();
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String f = s.toString().toLowerCase();
                shown.clear();
                for (String r : rels) if (f.isEmpty() || r.toLowerCase().contains(f)) shown.add(r);
                ad.notifyDataSetChanged();
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String r = shown.get(pos);
            File f = new File(root, r);
            if (!ProjectContext.isText(f)) { UI.toast(act, "Only text files can be attached"); return; }
            Att a = new Att(); a.name = r; a.rel = r; addAtt(a);
            d.dismiss();
        });
        d.show();
    }

    private void pickDeviceFile(final boolean image) {
        File start = Environment.getExternalStorageDirectory();
        FilePicker.Callback cb = f -> {
            try {
                Att a = new Att();
                a.name = f.getName();
                if (image || FileUtil.isImage(f)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    int sample = 1;
                    while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
                    if (bm == null) { UI.toast(act, "Cannot read this image"); return; }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 82, bo);
                    bm.recycle();
                    a.img = new ChatClient.Img("image/jpeg", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                } else {
                    if (FileUtil.looksBinary(f)) { UI.toast(act, "Binary files cannot be attached"); return; }
                    if (f.length() > 200 * 1024) { UI.toast(act, "File is larger than 200 KB"); return; }
                    a.text = FileUtil.readText(f);
                }
                addAtt(a);
            } catch (Exception e) { UI.toast(act, "Could not attach: " + e.getMessage()); }
        };
        if (image) FilePicker.pickFile(act, "Choose image", start, new String[]{"png", "jpg", "jpeg", "webp"}, cb);
        else FilePicker.pickAny(act, "Choose file", start, cb);
    }

    // ------------------------------------------------------------------ sending

    private void onSendClicked() {
        if (busy) { stopped = true; if (client != null) client.cancel(); return; }
        String t = input.getText().toString().trim();
        if (t.isEmpty() && atts.isEmpty()) return;
        if (t.isEmpty()) t = "Please look at the attached file(s).";
        send(t, false);
    }

    /** Sends a prompt. {@code hidden} shows the user message as a small note (used for build-fix prompts). */
    public void send(String text, boolean hidden) {
        if (busy) { UI.toast(act, "Wait for the reply or tap stop"); return; }
        AiProvider p = ps.get(providerId);
        AiModel m = p == null ? null : p.find(modelId);
        if (p == null || m == null || !p.enabled) {
            String[] d = ps.resolveDefault();
            if (d == null) {
                UI.dialog(act).setTitle("Add an AI provider").setMessage("Buildora AI needs a provider and at least one model (OpenRouter, Gemini, Groq, OpenAI, or your own).")
                        .setPositiveButton("Add provider", (x, w) -> act.startActivity(new Intent(act, AiProvidersActivity.class))).setNegativeButton("Cancel", null).show();
                return;
            }
            providerId = d[0]; modelId = d[1];
            p = ps.get(providerId);
            updateModelChip();
        }
        if (host.ideMode()) host.saveAll();
        Chat.Message um = new Chat.Message("user", text);
        um.quote = quote;
        um.hidden = hidden;
        final List<Att> sendAtts = new ArrayList<>(atts);
        for (Att a : sendAtts) um.attachments.add(a.name);
        chat.messages.add(um);
        chat.provider = providerId; chat.model = modelId;
        atts.clear();
        quote = "";
        quoteBar.setVisibility(GONE);
        input.setText("");
        refreshAttachRow();
        persist();
        renderAll();
        generate(um, sendAtts);
    }

    private void regenerate() {
        if (busy) return;
        while (!chat.messages.isEmpty() && !chat.messages.get(chat.messages.size() - 1).role.equals("user")) {
            Chat.Message last = chat.messages.get(chat.messages.size() - 1);
            chat.messages.remove(chat.messages.size() - 1);
        }
        if (chat.messages.isEmpty()) return;
        persist();
        renderAll();
        generate(chat.messages.get(chat.messages.size() - 1), new ArrayList<Att>());
    }

    private static final Pattern FILE_BLOCK = Pattern.compile("(?s)```[^\\n`]*(?:path|file)=([^\\s`]+)[^\\n]*\\n.*?\\n```");

    private List<ChatClient.Msg> buildRequest(Chat.Message lastUser, List<Att> sendAtts) {
        final File root = root();
        List<ChatClient.Msg> out = new ArrayList<>();
        out.add(new ChatClient.Msg("system", Prompts.system(root == null, Prefs.str(act, Prefs.AI_EXTRA, ""))));

        // history (newest last), trimmed to a budget
        List<ChatClient.Msg> hist = new ArrayList<>();
        int budget = 36000, used = 0;
        boolean seenAssistant = false;
        for (int i = chat.messages.size() - 2; i >= 0; i--) {
            Chat.Message m = chat.messages.get(i);
            if (m.model.equals("__note") || m.error) continue;
            String t = m.text;
            if (m.role.equals("assistant")) {
                if (seenAssistant) t = FILE_BLOCK.matcher(t).replaceAll("[wrote file $1]");
                seenAssistant = true;
            }
            if (used + t.length() > budget && !hist.isEmpty()) break;
            used += t.length();
            hist.add(0, new ChatClient.Msg(m.role, t));
        }
        // the API needs the conversation to start with a user turn
        while (!hist.isEmpty() && !hist.get(0).role.equals("user")) hist.remove(0);
        out.addAll(hist);

        StringBuilder sb = new StringBuilder();
        List<String> priority = new ArrayList<>();
        if (root != null && Prefs.bool(act, Prefs.AI_SEND_CTX, true)) {
            String cur = host.currentFile();
            if (cur != null) priority.add(cur);
            for (String o : host.openFiles()) if (!priority.contains(o)) priority.add(o);
            for (Att a : sendAtts) if (a.rel != null && !priority.contains(a.rel)) priority.add(a.rel);
            for (String f : forcePriority) if (!priority.contains(f)) priority.add(f);
            String name = root.getName(), pkg = "";
            int min = 21, tgt = 34;
            try { Project p = Project.load(root); name = p.name; pkg = p.packageName; min = p.minSdk; tgt = p.targetSdk; } catch (Exception ignored) { }
            sb.append("Current project snapshot (files are up to date):\n")
                    .append(ProjectContext.build(root, name, pkg, min, tgt, priority, Ai.contextBudget(act))).append("\n\n");
            if (cur != null) sb.append("The user has this file open in the editor: ").append(cur).append("\n");
            String sel = host.selection();
            if (!sel.isEmpty()) sb.append("Selected code:\n```\n").append(Prompts.cut(sel, 6000)).append("\n```\n");
        }
        ChatClient.Msg um = null;
        for (Att a : sendAtts) {
            if (a.text != null) sb.append("<attachment name=\"").append(a.name).append("\">\n").append(Prompts.cut(a.text, 60000)).append("\n</attachment>\n");
        }
        if (!lastUser.quote.isEmpty()) sb.append("The user is replying to this part of the conversation:\n> ").append(lastUser.quote.replace("\n", "\n> ")).append("\n\n");
        if (sb.length() > 0) sb.append("User request:\n");
        sb.append(lastUser.text);
        um = new ChatClient.Msg("user", sb.toString());
        for (Att a : sendAtts) if (a.img != null) um.images.add(a.img);
        out.add(um);
        return out;
    }

    private void generate(final Chat.Message userMsg, final List<Att> sendAtts) {
        final AiProvider p = ps.get(providerId);
        if (p == null) return;
        final String model = modelId;
        busy = true; stopped = false;
        stream.setLength(0);
        updateSendIcon();
        // live placeholder
        LinearLayout wrap = UI.vbox(act);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = UI.dp(act, 12);
        wrap.setLayoutParams(wl);
        liveBody = UI.vbox(act);
        wrap.addView(liveBody);
        liveHint = UI.label(act, "Thinking…", 12, true);
        liveHint.setPadding(0, UI.dp(act, 6), 0, 0);
        wrap.addView(liveHint);
        msgBox.addView(wrap);
        autoScroll = true;
        scrollDown(true);

        final List<ChatClient.Msg> req = buildRequest(userMsg, sendAtts);
        final ChatClient cl = new ChatClient();
        client = cl;
        final ChatClient.Options opt = Ai.options(act);
        new Thread(() -> cl.run(p, model, req, opt, new ChatClient.Callback() {
            @Override public void onDelta(String t) {
                synchronized (stream) { stream.append(t); }
                h.post(() -> scheduleTick());
            }
            @Override public void onDone(String full) { h.post(() -> finish(full, null, model)); }
            @Override public void onError(String message, String partial) { h.post(() -> finish(partial, message, model)); }
        }), "ai-chat").start();
    }

    private void scheduleTick() {
        if (tickPending) return;
        tickPending = true;
        h.postDelayed(() -> {
            tickPending = false;
            if (!busy || liveBody == null) return;
            String s;
            synchronized (stream) { s = stream.toString(); }
            MdRender.render(act, liveBody, s, sp(), false, null, false);
            if (liveHint != null) liveHint.setText(stopped ? "Stopping…" : "Writing… " + s.length() + " chars");
            scrollDown(false);
        }, 110);
    }

    private void finish(String full, String err, String model) {
        if (!busy) return;
        busy = false;
        client = null;
        Chat.Message am = new Chat.Message("assistant", full == null ? "" : full);
        am.model = model;
        if (err != null) {
            am.error = full == null || full.isEmpty();
            am.text = am.error ? "⚠ " + err : am.text + "\n\n⚠ " + err;
        } else if (stopped && !am.text.isEmpty()) am.text += "\n\n*(stopped)*";
        if (am.text.isEmpty()) { am.text = "*(stopped)*"; }
        chat.messages.add(am);
        if (!chat.autoTitled && Prefs.bool(act, Prefs.AI_AUTOTITLE, true)) { chat.title = chat.guessTitle(); chat.autoTitled = true; }
        persist();
        updateSendIcon();
        renderAll();
        if (err != null || stopped) { if (fixMode) { fixMode = false; host.fixFinished(false); } return; }
        handleReply(am);
    }

    /** Auto-apply (fix mode or setting), answer file requests, or leave the changes card for the user. */
    private void handleReply(Chat.Message am) {
        Protocol.Parsed pr = Protocol.parse(am.text);
        File root = root();
        if (root != null && pr.ops.isEmpty() && !pr.reads.isEmpty() && readRounds < 3) {
            readRounds++;
            for (String r : pr.reads) { String rr = FileOps.resolve(root, r); if (!forcePriority.contains(rr)) forcePriority.add(rr); }
            Chat.Message n = new Chat.Message("user", "Here are the files you asked for (they are in the project snapshot below).");
            n.hidden = true;
            for (String r : pr.reads) n.attachments.add(r);
            chat.messages.add(n);
            persist();
            renderAll();
            generate(n, new ArrayList<Att>());
            return;
        }
        readRounds = 0;
        forcePriority.clear();
        boolean auto = fixMode || Prefs.bool(act, Prefs.AI_AUTOAPPLY, false);
        if (auto && root != null && pr.any()) {
            boolean wasFix = fixMode;
            applyMessage(am, wasFix);
        } else if (fixMode && pr.ops.isEmpty()) {
            fixMode = false;
            host.fixFinished(false);
        }
    }

    // ------------------------------------------------------------------ public helpers for hosts

    public boolean isBusy() { return busy; }

    public void sendPrompt(String text) { send(text, false); }

    public void note(String text) {
        Chat.Message n = new Chat.Message("assistant", text);
        n.model = "__note";
        chat.messages.add(n);
        persist();
        renderAll();
    }

    /** "Buildora AI Fix": sends the build errors with the master prompt and applies the answer automatically. */
    public void startFix(String errorText, List<String[]> diags, int round) {
        File root = root();
        if (root == null) { UI.toast(act, "Open a project first"); return; }
        if (busy) { UI.toast(act, "Buildora AI is still answering"); return; }
        fixMode = true;
        fixRound = round;
        forcePriority.clear();
        for (String[] d : diags) {
            if (d[0] == null || d[0].isEmpty()) continue;
            File f = new File(d[0]);
            if (f.isAbsolute() && FileUtil.isInside(root, f)) { String r = FileUtil.relPath(root, f); if (!forcePriority.contains(r)) forcePriority.add(r); }
        }
        int max = Prefs.num(act, Prefs.AI_FIXLOOPS, 3);
        String prompt = Prompts.fixPrompt(errorText, ProjectContext.errorSnippets(root, diags), round, max);
        send(prompt, true);
        if (!busy) { fixMode = false; host.fixFinished(false); }
    }

    // ------------------------------------------------------------------ changes card

    private View changesCard(final Chat.Message m) {
        if (!m.role.equals("assistant") || m.error || m.model.equals("__note")) return null;
        final Protocol.Parsed pr = Protocol.parse(m.text);
        if (!pr.any()) return null;
        final File root = root();
        LinearLayout card = UI.vbox(act);
        card.setBackgroundResource(R.drawable.bg_code);
        card.setPadding(UI.dp(act, 12), UI.dp(act, 10), UI.dp(act, 12), UI.dp(act, 10));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = UI.dp(act, 6);
        card.setLayoutParams(cl);

        if (root == null) {
            int files = 0;
            for (Protocol.Op o : pr.ops) if (o.type == Protocol.WRITE) files++;
            card.addView(title("New project" + (pr.projectName.isEmpty() ? "" : ": " + pr.projectName) + " · " + files + " file" + (files == 1 ? "" : "s")));
            card.addView(UI.label(act, "Creates the project in Buildora/ and writes every file from this reply.", 12, true));
            TextView b = UI.button(act, "Create project", true);
            b.setOnClickListener(v -> createProjectDialog(m, pr));
            card.addView(b);
            return card;
        }
        if (!m.backupId.isEmpty()) {
            final boolean canUndo = new File(Ai.backups(root), m.backupId).isDirectory();
            card.addView(title("Changes applied ✓"));
            LinearLayout r = UI.hbox(act);
            if (canUndo) {
                TextView u = UI.button(act, "Undo", false);
                u.setOnClickListener(v -> undo(m));
                r.addView(u, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (!host.ideMode()) {
                TextView o = UI.button(act, "Open in IDE", true);
                o.setOnClickListener(v -> host.openIde(root));
                LayoutParams ol = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ol.leftMargin = UI.dp(act, 8);
                r.addView(o, ol);
            }
            if (r.getChildCount() > 0) card.addView(r);
            return card;
        }
        List<FileOps.Change> plan = plans.get(m);
        if (plan == null) { plan = FileOps.plan(root, pr.ops); plans.put(m, plan); }
        final List<FileOps.Change> fplan = plan;
        int ok = 0;
        for (FileOps.Change c : plan) if (c.ok()) ok++;
        card.addView(title("AI changes · " + ok + " file" + (ok == 1 ? "" : "s") + (pr.deps.isEmpty() ? "" : " + " + pr.deps.size() + " librar" + (pr.deps.size() == 1 ? "y" : "ies"))));
        for (final FileOps.Change c : plan) {
            LinearLayout row = UI.hbox(act);
            row.setPadding(0, UI.dp(act, 4), 0, UI.dp(act, 4));
            android.widget.CheckBox cb = new android.widget.CheckBox(act);
            cb.setChecked(c.selected && c.ok());
            cb.setEnabled(c.ok());
            cb.setOnCheckedChangeListener((bv, on) -> c.selected = on);
            row.addView(cb);
            LinearLayout col = UI.vbox(act);
            TextView path = UI.label(act, c.kind == FileOps.RENAME ? c.path + " → " + c.to : c.path, 12, false);
            path.setTypeface(Typeface.MONOSPACE);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            col.addView(path);
            String sub = c.error != null ? "⚠ " + c.error : c.kindLabel() + (c.kind == FileOps.NEW || c.kind == FileOps.MODIFY ? "  +" + c.added + " −" + c.removed : "");
            TextView st = UI.label(act, sub, 11, true);
            if (c.error != null) st.setTextColor(UI.color(act, R.attr.bError));
            col.addView(st);
            row.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (c.ok() && (c.kind == FileOps.NEW || c.kind == FileOps.MODIFY || c.kind == FileOps.DELETE)) {
                TextView d = UI.label(act, "View", 12, false);
                d.setTextColor(UI.color(act, R.attr.bAccent));
                d.setPadding(UI.dp(act, 8), UI.dp(act, 6), UI.dp(act, 4), UI.dp(act, 6));
                d.setOnClickListener(v -> showDiff(c));
                row.addView(d);
            }
            card.addView(row);
        }
        if (!pr.deps.isEmpty()) {
            TextView dl = UI.label(act, "Libraries: " + TextUtils.join(", ", pr.deps), 11.5f, true);
            dl.setPadding(0, UI.dp(act, 4), 0, 0);
            card.addView(dl);
        }
        TextView apply = UI.button(act, "Apply changes", true);
        apply.setOnClickListener(v -> applyMessage(m, false));
        card.addView(apply);
        return card;
    }

    private TextView title(String s) {
        TextView t = UI.label(act, s, 13, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, 0, 0, UI.dp(act, 4));
        return t;
    }

    private void showDiff(FileOps.Change c) {
        List<String> d;
        if (c.kind == FileOps.NEW) d = Diff.lines("", c.newText);
        else if (c.kind == FileOps.DELETE) d = Diff.lines(c.oldText, "");
        else d = Diff.compact(Diff.lines(c.oldText, c.newText));
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int n = 0;
        for (String l : d) {
            if (n++ > 600) { sb.append("… (more lines not shown)\n"); break; }
            int a = sb.length();
            sb.append(l.startsWith("@") ? "  ⋯" : l.charAt(0) + " " + l.substring(1)).append('\n');
            if (l.startsWith("+")) sb.setSpan(new android.text.style.BackgroundColorSpan(0x3340C057), a, sb.length(), 0);
            else if (l.startsWith("-")) sb.setSpan(new android.text.style.BackgroundColorSpan(0x33E5484D), a, sb.length(), 0);
        }
        TextView t = new TextView(act);
        t.setText(sb);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(UI.color(act, R.attr.bText));
        t.setHorizontallyScrolling(true);
        t.setPadding(UI.dp(act, 12), UI.dp(act, 8), UI.dp(act, 12), UI.dp(act, 8));
        t.setTextIsSelectable(true);
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.addView(t);
        ScrollView sv = new ScrollView(act);
        sv.addView(hs);
        UI.dialog(act).setTitle(c.path + "  +" + c.added + " −" + c.removed).setView(sv).setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------------ apply / undo / create

    private void applyMessage(final Chat.Message m, final boolean fromFix) {
        final File root = root();
        if (root == null) { if (fromFix) { fixMode = false; host.fixFinished(false); } return; }
        final Protocol.Parsed pr = Protocol.parse(m.text);
        host.saveAll();
        List<FileOps.Change> plan = plans.get(m);
        if (plan == null || fromFix) plan = FileOps.plan(root, pr.ops);
        final List<FileOps.Change> fplan = plan;
        new Thread(() -> {
            String id = "";
            int deps = 0;
            String err = null;
            try {
                id = FileOps.apply(root, fplan, Ai.backups(root));
                deps = addDeps(root, pr.deps);
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final String fid = id, ferr = err;
            final int fdeps = deps;
            h.post(() -> afterApply(m, fplan, fid, fdeps, ferr, fromFix));
        }).start();
    }

    private void afterApply(Chat.Message m, List<FileOps.Change> plan, String id, int deps, String err, boolean fromFix) {
        plans.remove(m);
        List<String> touched = new ArrayList<>();
        int bad = 0;
        StringBuilder badTxt = new StringBuilder();
        for (FileOps.Change c : plan) {
            if (c.selected && c.ok()) { touched.add(c.path); if (c.to != null) touched.add(c.to); }
            else if (c.error != null) { bad++; if (badTxt.length() < 300) badTxt.append("\n• ").append(c.path).append(" - ").append(c.error); }
        }
        if (err != null) { UI.toast(act, "Could not apply: " + err); if (fromFix) { fixMode = false; host.fixFinished(false); } return; }
        if (!id.isEmpty()) { m.backupId = id; }
        persist();
        if (!touched.isEmpty()) host.filesChanged(touched);
        renderAll();
        if (bad > 0) note("⚠ " + bad + " change(s) skipped:" + badTxt);
        if (id.isEmpty() && deps == 0) UI.toast(act, "Nothing to change");
        else UI.toast(act, "Applied " + touched.size() + " file(s)" + (deps > 0 ? " + " + deps + " librar" + (deps == 1 ? "y" : "ies") : ""));
        if (fromFix) { fixMode = false; host.fixFinished(!id.isEmpty() || deps > 0); }
    }

    /** Adds "group:artifact:version" lines to the project's dependency list. Returns how many were new. */
    private int addDeps(File root, List<String> deps) {
        if (deps.isEmpty()) return 0;
        try {
            Project p = Project.load(root);
            int n = 0;
            for (String d : deps) {
                String[] parts = d.split(":");
                String ga = parts[0] + ":" + parts[1];
                boolean has = false;
                for (String e : p.dependencies) if (e.equals(d) || e.startsWith(ga + ":") || e.equals(ga)) { has = true; break; }
                if (!has) { p.dependencies.add(d); n++; }
            }
            if (n > 0) p.save();
            return n;
        } catch (Exception e) { return 0; }
    }

    private void undo(final Chat.Message m) {
        final File root = root();
        if (root == null) return;
        host.saveAll();
        final String id = m.backupId;
        new Thread(() -> {
            String err = null;
            List<String> t = new ArrayList<>();
            try { t = FileOps.undo(root, Ai.backups(root), id); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final String ferr = err;
            final List<String> ft = t;
            h.post(() -> {
                if (ferr != null) { UI.toast(act, ferr); return; }
                m.backupId = "";
                plans.remove(m);
                persist();
                host.filesChanged(ft);
                renderAll();
                UI.toast(act, "Changes undone");
            });
        }).start();
    }

    private static final Pattern PKG = Pattern.compile("package\\s*=\\s*\"([A-Za-z_][\\w.]*)\"");

    private void createProjectDialog(final Chat.Message m, final Protocol.Parsed pr) {
        String pkg = pr.projectPackage;
        if (pkg.isEmpty()) {
            for (Protocol.Op o : pr.ops) if (o.path.endsWith("AndroidManifest.xml")) { Matcher mm = PKG.matcher(o.content); if (mm.find()) pkg = mm.group(1); }
        }
        if (pkg.isEmpty()) pkg = Prefs.str(act, Prefs.PKG_PREFIX, "com.example") + ".app";
        String nm = pr.projectName.isEmpty() ? "AiApp" : pr.projectName;
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 8), UI.dp(act, 20), 0);
        final EditText name = UI.input(act, "App name", nm);
        final EditText pk = UI.input(act, "Package id", pkg);
        box.addView(name);
        box.addView(pk);
        final AlertDialog d = UI.dialog(act).setTitle("Create project").setView(box)
                .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n1 = name.getText().toString().trim(), p1 = pk.getText().toString().trim();
            if (n1.isEmpty()) { UI.toast(act, "Enter an app name"); return; }
            if (!p1.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) { UI.toast(act, "Package must look like com.example.app (lowercase)"); return; }
            d.dismiss();
            createProject(m, pr, n1, p1);
        });
    }

    private void createProject(final Chat.Message m, final Protocol.Parsed pr, final String appName, final String pkg) {
        UI.toast(act, "Creating project…");
        new Thread(() -> {
            Project proj = null;
            String err = null, id = "";
            int deps = 0;
            try {
                Templates.Options o = new Templates.Options();
                o.projectName = appName.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
                if (o.projectName.isEmpty()) o.projectName = "AiApp";
                o.appName = appName;
                o.packageName = pkg;
                o.minSdk = Prefs.num(act, Prefs.DEF_MIN_SDK, 21);
                o.targetSdk = Prefs.num(act, Prefs.DEF_TARGET_SDK, 34);
                proj = Templates.create(act, o);
                // The AI writes its own activities and layouts: drop the template ones it replaces.
                boolean ownJava = false;
                java.util.Set<String> mine = new java.util.HashSet<>();
                for (Protocol.Op op : pr.ops) { mine.add(op.path); if (op.path.endsWith(".java")) ownJava = true; }
                if (ownJava) {
                    for (File f : FileUtil.listFiles(proj.javaRoot(), "java")) if (!mine.contains(FileUtil.relPath(proj.root, f))) f.delete();
                    File lay = new File(proj.resDir, "layout");
                    File[] ls = lay.listFiles();
                    if (ls != null) for (File f : ls) if (!mine.contains(FileUtil.relPath(proj.root, f))) f.delete();
                }
                List<FileOps.Change> plan = FileOps.plan(proj.root, pr.ops);
                id = FileOps.apply(proj.root, plan, Ai.backups(proj.root));
                deps = addDeps(proj.root, pr.deps);
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final Project fp = proj;
            final String ferr = err, fid = id;
            final int fdeps = deps;
            h.post(() -> {
                if (ferr != null || fp == null) { UI.message(act, "Could not create the project", String.valueOf(ferr)); return; }
                chat.projectRoot = fp.root.getPath();
                m.backupId = fid;
                persist();
                host.projectCreated(fp.root, chat);
                renderAll();
                UI.toast(act, "Project created: " + fp.root.getName());
            });
        }).start();
    }
}
