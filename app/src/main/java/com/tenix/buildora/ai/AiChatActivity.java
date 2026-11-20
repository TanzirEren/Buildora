package com.tenix.buildora.ai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.tenix.buildora.BaseActivity;
import com.tenix.buildora.IdeActivity;
import com.tenix.buildora.R;
import com.tenix.buildora.core.MenuSheet;
import com.tenix.buildora.core.UI;

import java.io.File;

/** Full-screen Buildora AI: chat on the main area, chat history in a left drawer. */
public class AiChatActivity extends BaseActivity {

    public static final String EXTRA_CHAT = "chat", EXTRA_PROJECT = "project", EXTRA_PROMPT = "prompt";

    private ChatPanel panel;
    private ChatDrawer drawer;
    private View scrim;
    private TextView title;
    private boolean drawerOpen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);

        LinearLayout main = UI.vbox(this);
        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 4), 0, UI.dp(this, 4), 0);
        ImageButton menu = new ImageButton(this, null, 0, R.style.BIconBtn);
        menu.setImageResource(R.drawable.ic_menu_ai);
        menu.setOnClickListener(v -> toggleDrawer(true));
        top.addView(menu, new LinearLayout.LayoutParams(UI.dp(this, 48), UI.dp(this, 52)));
        title = UI.label(this, "Buildora AI", 16.5f, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tokenBox = TokenBadge.create(this, v -> { if (panel != null) panel.showUsage(); });
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbl.rightMargin = UI.dp(this, 2);
        top.addView(tokenBox, tbl);
        final ImageButton neu = new ImageButton(this, null, 0, R.style.BIconBtn);
        neu.setImageResource(R.drawable.ic_edit);
        neu.setOnClickListener(v -> newChat(""));
        top.addView(neu, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        final ImageButton more = new ImageButton(this, null, 0, R.style.BIconBtn);
        more.setImageResource(R.drawable.ic_more);
        more.setOnClickListener(v -> moreMenu(more));
        top.addView(more, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        main.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));

        panel = new ChatPanel(this, new ChatPanel.Host() {
            @Override public void usageChanged() { if (panel != null) TokenBadge.set(tokenBox, panel.totalTokens()); }
            @Override public void chatChanged(Chat c) { updateTitle(c); if (panel != null) TokenBadge.set(tokenBox, panel.totalTokens()); if (drawer != null) { drawer.setCurrent(c.id); drawer.refresh(); } }
            @Override public void openIde(File r) { openProject(r); }
            @Override public void projectCreated(File r, Chat c) { if (drawer != null) drawer.refresh(); }
        });
        main.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> toggleDrawer(false));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new ChatDrawer(this, new ChatDrawer.Listener() {
            @Override public void onOpenChat(Chat c) { if (c != null) panel.openChat(c); }
            @Override public void onNewChat(String projectRoot, String folder) { newChat(projectRoot, folder); }
            @Override public void onChatDeleted(String id) { if (panel.chat() != null && panel.chat().id.equals(id)) panel.newChat(""); }
            @Override public void onChatRenamed(Chat c) {
                if (panel.chat() != null && panel.chat().id.equals(c.id)) {
                    Chat reloaded = Ai.chats(AiChatActivity.this).load(c.id);
                    if (reloaded != null) panel.openChat(reloaded); else panel.newChat("");
                }
            }
            @Override public void onOpenProject(File r) { openProject(r); }
            @Override public void onClose() { toggleDrawer(false); }
        });
        int w = Math.min(UI.dp(this, 320), getResources().getDisplayMetrics().widthPixels - UI.dp(this, 48));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
        drawer.setElevation(UI.dp(this, 12));
        root.addView(drawer, dl);
        drawer.post(() -> drawer.setTranslationX(-drawer.getWidth() - UI.dp(this, 8)));
        setContentView(root);
        styleBars();

        Intent in = getIntent();
        boolean shared = Intent.ACTION_SEND.equals(in.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(in.getAction());
        String cid = shared ? null : in.getStringExtra(EXTRA_CHAT);       // other apps may only share content, not pick chats
        String proj = shared ? null : in.getStringExtra(EXTRA_PROJECT);
        if (cid != null && Ai.chats(this).load(cid) != null) panel.openChat(Ai.chats(this).load(cid));
        else if (proj != null) panel.newChat(proj);
        String prompt = shared ? null : in.getStringExtra(EXTRA_PROMPT);
        if (prompt != null && !prompt.isEmpty()) panel.sendPrompt(prompt);
        drawer.setCurrent(panel.chat().id);
        handleShare(in);
        TokenBadge.set(tokenBox, panel.totalTokens());
    }

    /** Files or text shared to Buildora AI from another app. */
    private void handleShare(Intent in) {
        String a = in.getAction();
        if (!Intent.ACTION_SEND.equals(a) && !Intent.ACTION_SEND_MULTIPLE.equals(a)) return;
        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            android.net.Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            java.util.ArrayList<android.net.Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.isEmpty() && uris.isEmpty()) panel.setInputText(text);
        if (!uris.isEmpty()) panel.attachUris(uris);
    }

    private void newChat(String projectRoot) { newChat(projectRoot, ""); }

    private void newChat(String projectRoot, String folder) {
        panel.newChat(projectRoot);
        if (!folder.isEmpty()) { panel.chat().folder = folder; }
        drawer.setCurrent(panel.chat().id);
        updateTitle(panel.chat());
    }

    private TextView tokenBox;

    private void updateTitle(Chat c) {
        if (title == null) return;
        String t = c.title;
        if (!c.projectRoot.isEmpty()) t += "  ·  " + new File(c.projectRoot).getName();
        title.setText(t);
    }

    private void toggleDrawer(boolean open) {
        if (open == drawerOpen) return;
        drawerOpen = open;
        if (open) {
            drawer.refresh();
            scrim.setVisibility(View.VISIBLE);
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(170).start();
            drawer.animate().translationX(0).setDuration(200).start();
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null && getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        } else {
            scrim.animate().alpha(0f).setDuration(150).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
            drawer.animate().translationX(-drawer.getWidth() - UI.dp(this, 8)).setDuration(190).start();
        }
    }

    private void openProject(File r) {
        Intent i = new Intent(this, IdeActivity.class);
        i.putExtra("root", r.getAbsolutePath());
        startActivity(i);
    }

    private void moreMenu(View anchor) {
        final Chat c = panel.chat();
        File r = panel.root();
        MenuSheet ms = new MenuSheet(this)
                .add(1, R.drawable.mi_rename, "Rename chat")
                .add(2, R.drawable.mi_copy, "Copy whole chat")
                .add(7, R.drawable.mi_stats, "Token usage")
                .divider();
        if (r != null) ms.add(4, R.drawable.mi_code, "Open project in IDE");
        ms.add(5, R.drawable.mi_server, "AI providers & keys")
          .divider()
          .add(3, R.drawable.mi_eraser, "Clear messages")
          .danger(6, R.drawable.mi_trash, "Delete chat");
        ms.show(anchor, id -> {
            switch (id) {
                case 1: {
                    final EditText e = UI.input(this, "Chat title", c.title);
                    LinearLayout box = UI.vbox(this);
                    box.setPadding(UI.dp(this, 20), UI.dp(this, 6), UI.dp(this, 20), 0);
                    box.addView(e);
                    UI.dialog(this).setTitle("Rename chat").setView(box).setPositiveButton("OK", (d, w) -> {
                        String s = e.getText().toString().trim();
                        if (!s.isEmpty()) { c.title = s; c.autoTitled = true; if (!c.messages.isEmpty()) Ai.chats(this).save(c); updateTitle(c); drawer.refresh(); }
                    }).setNegativeButton("Cancel", null).show();
                    break;
                }
                case 2: UI.copy(this, "chat", c.toMarkdown()); UI.toast(this, "Copied"); break;
                case 3: c.messages.clear(); c.tokensIn = 0; c.tokensOut = 0; Ai.chats(this).save(c); panel.openChat(c); drawer.refresh(); break;
                case 4: openProject(panel.root()); break;
                case 5: startActivity(new Intent(this, AiProvidersActivity.class)); break;
                case 7: panel.showUsage(); break;
                case 6:
                    UI.dialog(this).setTitle("Delete this chat?").setPositiveButton("Delete", (d, w) -> {
                        Ai.chats(this).delete(c.id); panel.newChat(""); drawer.refresh();
                    }).setNegativeButton("Cancel", null).show();
                    break;
            }
        });
    }

    @Override protected void onPause() { super.onPause(); panel.onPause(); }

    @Override protected void onResume() { super.onResume(); panel.onResume(); TokenBadge.set(tokenBox, panel.totalTokens()); }

    @Override protected void onDestroy() { if (panel != null) panel.detach(); super.onDestroy(); }

    @Override public void onBackPressed() {
        if (drawerOpen) { toggleDrawer(false); return; }
        super.onBackPressed();
    }
}
