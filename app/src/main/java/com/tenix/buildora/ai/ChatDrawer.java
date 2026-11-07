package com.tenix.buildora.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.ProjectStore;
import com.tenix.buildora.core.UI;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Side page with all chats: search, pinned, project folders, custom folders and per-chat actions. */
public class ChatDrawer extends LinearLayout {

    public interface Listener {
        void onOpenChat(Chat c);
        void onNewChat(String projectRoot, String folder);
        void onChatDeleted(String id);
        void onChatRenamed(Chat c);
        void onOpenProject(File root);
        void onClose();
    }

    private final Activity act;
    private final ChatStore store;
    private final Listener l;
    private final LinearLayout list;
    private String query = "";
    private String currentId = "";

    public ChatDrawer(Activity a, Listener l) {
        super(a);
        this.act = a;
        this.l = l;
        this.store = Ai.chats(a);
        setOrientation(VERTICAL);
        setBackgroundColor(UI.color(a, R.attr.bBg));
        setPadding(0, 0, 0, 0);

        LinearLayout head = UI.hbox(a);
        head.setPadding(UI.dp(a, 16), UI.dp(a, 10), UI.dp(a, 6), UI.dp(a, 4));
        TextView t = UI.label(a, "Chats", 19, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton nf = btn(R.drawable.ic_folder);
        nf.setOnClickListener(v -> newFolderDialog());
        head.addView(nf, new LayoutParams(UI.dp(a, 42), UI.dp(a, 42)));
        ImageButton nc = btn(R.drawable.ic_add);
        nc.setOnClickListener(v -> { l.onNewChat("", ""); l.onClose(); });
        head.addView(nc, new LayoutParams(UI.dp(a, 42), UI.dp(a, 42)));
        addView(head);

        EditText search = UI.input(a, "Search chats", "");
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) search.getLayoutParams();
        sl.setMargins(UI.dp(a, 14), UI.dp(a, 2), UI.dp(a, 14), UI.dp(a, 6));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable s) { query = s.toString(); refresh(); }
        });
        addView(search);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        list = UI.vbox(a);
        list.setPadding(UI.dp(a, 8), 0, UI.dp(a, 8), UI.dp(a, 24));
        sv.addView(list);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    private ImageButton btn(int res) {
        ImageButton b = new ImageButton(act, null, 0, R.style.BIconBtn);
        b.setImageResource(res);
        return b;
    }

    public void setCurrent(String id) { currentId = id == null ? "" : id; }

    // ------------------------------------------------------------------ list

    public void refresh() {
        list.removeAllViews();
        List<Chat> all = new ArrayList<>();
        for (Chat c : store.loadAll()) if (ChatStore.matches(c, query)) all.add(c);
        boolean searching = !query.trim().isEmpty();

        List<Chat> pinned = new ArrayList<>();
        Map<String, List<Chat>> projects = new LinkedHashMap<>();
        Map<String, List<Chat>> folders = new LinkedHashMap<>();
        for (String f : store.folders()) folders.put(f, new ArrayList<Chat>());
        List<Chat> recent = new ArrayList<>();
        for (Chat c : all) {
            if (c.pinned) pinned.add(c);
            else if (!c.projectRoot.isEmpty()) {
                List<Chat> g = projects.get(c.projectRoot);
                if (g == null) { g = new ArrayList<>(); projects.put(c.projectRoot, g); }
                g.add(c);
            } else if (!c.folder.isEmpty() && folders.containsKey(c.folder)) folders.get(c.folder).add(c);
            else recent.add(c);
        }
        if (!pinned.isEmpty()) {
            list.addView(section("Pinned"));
            for (Chat c : pinned) list.addView(chatRow(c));
        }
        if (!projects.isEmpty()) {
            list.addView(section("Project folders"));
            for (Map.Entry<String, List<Chat>> e : projects.entrySet()) projectFolder(e.getKey(), e.getValue(), searching);
        }
        if (!folders.isEmpty()) {
            list.addView(section("Folders"));
            for (Map.Entry<String, List<Chat>> e : folders.entrySet()) {
                if (searching && e.getValue().isEmpty()) continue;
                customFolder(e.getKey(), e.getValue(), searching);
            }
        }
        if (!recent.isEmpty()) {
            String lastLabel = "";
            for (Chat c : recent) {
                String lb = dayLabel(c.updated);
                if (!lb.equals(lastLabel)) { list.addView(section(lb)); lastLabel = lb; }
                list.addView(chatRow(c));
            }
        }
        if (all.isEmpty()) {
            TextView e = UI.label(act, searching ? "No chats match your search." : "No chats yet. Start one with the + button.", 13, true);
            e.setPadding(UI.dp(act, 12), UI.dp(act, 24), UI.dp(act, 12), 0);
            list.addView(e);
        }
    }

    private String dayLabel(long t) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        return "Earlier";
    }

    private TextView section(String s) {
        TextView t = UI.label(act, s.toUpperCase(Locale.ROOT), 10.5f, true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.08f);
        t.setPadding(UI.dp(act, 10), UI.dp(act, 14), 0, UI.dp(act, 4));
        return t;
    }

    private boolean collapsed(String key, boolean searching) { return !searching && Prefs.bool(act, "ai_fold_" + key, false); }

    private void projectFolder(final String root, List<Chat> chats, boolean searching) {
        final File rf = new File(root);
        String name = rf.getName() + (rf.isDirectory() ? "" : " (missing)");
        final String key = "p" + root.hashCode();
        header(name, chats.size(), R.drawable.ic_android, key, searching, v -> {
            PopupMenu pm = new PopupMenu(act, v);
            pm.getMenu().add(0, 1, 0, "New chat in this project");
            if (rf.isDirectory()) pm.getMenu().add(0, 2, 1, "Open project in IDE");
            pm.getMenu().add(0, 3, 2, "Delete all chats here");
            pm.setOnMenuItemClickListener(mi -> {
                if (mi.getItemId() == 1) { l.onNewChat(root, ""); l.onClose(); }
                else if (mi.getItemId() == 2) l.onOpenProject(rf);
                else confirmDeleteMany(chats, "all chats of " + rf.getName());
                return true;
            });
            pm.show();
        });
        if (!collapsed(key, searching)) for (Chat c : chats) list.addView(chatRow(c));
    }

    private void customFolder(final String name, final List<Chat> chats, boolean searching) {
        final String key = "f" + name.hashCode();
        header(name, chats.size(), R.drawable.ic_folder, key, searching, v -> {
            PopupMenu pm = new PopupMenu(act, v);
            pm.getMenu().add(0, 1, 0, "New chat in this folder");
            pm.getMenu().add(0, 2, 1, "Rename folder");
            pm.getMenu().add(0, 3, 2, "Delete folder (keep chats)");
            pm.getMenu().add(0, 4, 3, "Delete folder and chats");
            pm.setOnMenuItemClickListener(mi -> {
                switch (mi.getItemId()) {
                    case 1: l.onNewChat("", name); l.onClose(); break;
                    case 2: promptText("Rename folder", name, s -> { store.renameFolder(name, s); refresh(); }); break;
                    case 3: store.deleteFolder(name, false); refresh(); break;
                    case 4: UI.dialog(act).setTitle("Delete folder and its chats?").setMessage(chats.size() + " chat(s) will be deleted.")
                            .setPositiveButton("Delete", (d, w) -> { for (Chat c : chats) l.onChatDeleted(c.id); store.deleteFolder(name, true); refresh(); })
                            .setNegativeButton("Cancel", null).show(); break;
                }
                return true;
            });
            pm.show();
        });
        if (!collapsed(key, searching)) for (Chat c : chats) list.addView(chatRow(c));
    }

    private void header(String name, int count, int icon, final String key, final boolean searching, final java.util.function.Consumer<View> menu) {
        LinearLayout r = UI.hbox(act);
        r.setBackgroundResource(R.drawable.bg_ripple_rect);
        r.setPadding(UI.dp(act, 10), UI.dp(act, 4), UI.dp(act, 2), UI.dp(act, 4));
        boolean col = collapsed(key, searching);
        TextView t = UI.label(act, name + "  ·  " + count, 13.5f, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        UI.tintIcon(act, t, icon, R.attr.bAccent, 18);
        r.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = UI.label(act, col ? "▸" : "▾", 14, true);
        arrow.setPadding(UI.dp(act, 6), 0, UI.dp(act, 2), 0);
        r.addView(arrow);
        final ImageButton more = btn(R.drawable.ic_more);
        more.setOnClickListener(v -> menu.accept(v));
        r.addView(more, new LayoutParams(UI.dp(act, 38), UI.dp(act, 38)));
        r.setOnClickListener(v -> { Prefs.put(act, "ai_fold_" + key, !Prefs.bool(act, "ai_fold_" + key, false)); refresh(); });
        list.addView(r);
    }

    private View chatRow(final Chat c) {
        LinearLayout r = UI.hbox(act);
        boolean cur = c.id.equals(currentId);
        r.setBackgroundResource(cur ? R.drawable.bg_chip : R.drawable.bg_ripple_rect);
        r.setPadding(UI.dp(act, 12), UI.dp(act, 7), UI.dp(act, 2), UI.dp(act, 7));
        LinearLayout col = UI.vbox(act);
        TextView t = UI.label(act, (c.pinned ? "📌 " : "") + c.title, 14, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (cur) t.setTextColor(UI.color(act, R.attr.bAccent));
        col.addView(t);
        ChatStore.Meta m = ChatStore.meta(c);
        TextView s = UI.label(act, new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(c.updated)) + "  ·  " + m.count + " msg", 11, true);
        col.addView(s);
        r.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final ImageButton more = btn(R.drawable.ic_more);
        more.setOnClickListener(v -> chatMenu(c, v));
        r.addView(more, new LayoutParams(UI.dp(act, 38), UI.dp(act, 38)));
        r.setOnClickListener(v -> { l.onOpenChat(store.load(c.id)); l.onClose(); });
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(act, 2);
        r.setLayoutParams(lp);
        return r;
    }

    // ------------------------------------------------------------------ chat menu

    private void chatMenu(final Chat c, View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Rename");
        pm.getMenu().add(0, 2, 1, c.pinned ? "Unpin" : "Pin to top");
        pm.getMenu().add(0, 3, 2, "Move to project / folder…");
        pm.getMenu().add(0, 4, 3, "Duplicate");
        pm.getMenu().add(0, 5, 4, "Export…");
        File pr = c.projectRoot.isEmpty() ? null : new File(c.projectRoot);
        if (pr != null && pr.isDirectory()) pm.getMenu().add(0, 6, 5, "Open project in IDE");
        pm.getMenu().add(0, 7, 6, "Clear messages");
        pm.getMenu().add(0, 8, 7, "Delete");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: promptText("Rename chat", c.title, s -> { c.title = s; c.autoTitled = true; store.save(c); l.onChatRenamed(c); refresh(); }); break;
                case 2: c.pinned = !c.pinned; store.save(c); refresh(); break;
                case 3: moveDialog(c); break;
                case 4: store.duplicate(c.id); refresh(); UI.toast(act, "Chat duplicated"); break;
                case 5: exportDialog(c); break;
                case 6: l.onOpenProject(new File(c.projectRoot)); break;
                case 7: UI.dialog(act).setTitle("Clear messages?").setMessage("The chat stays, all messages are removed.")
                        .setPositiveButton("Clear", (d, w) -> { c.messages.clear(); store.save(c); l.onChatRenamed(c); refresh(); }).setNegativeButton("Cancel", null).show(); break;
                case 8: confirmDeleteMany(java.util.Collections.singletonList(c), "this chat"); break;
            }
            return true;
        });
        pm.show();
    }

    private void confirmDeleteMany(final List<Chat> chats, String what) {
        UI.dialog(act).setTitle("Delete " + what + "?").setMessage(chats.size() + " chat(s) will be removed from this phone.")
                .setPositiveButton("Delete", (d, w) -> { for (Chat c : chats) { store.delete(c.id); l.onChatDeleted(c.id); } refresh(); })
                .setNegativeButton("Cancel", null).show();
    }

    private interface TextCb { void on(String s); }

    private void promptText(String title, String init, final TextCb cb) {
        final EditText e = UI.input(act, title, init);
        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 6), UI.dp(act, 20), 0);
        box.addView(e);
        UI.dialog(act).setTitle(title).setView(box).setPositiveButton("OK", (d, w) -> {
            String s = e.getText().toString().trim();
            if (!s.isEmpty()) cb.on(s);
        }).setNegativeButton("Cancel", null).show();
    }

    private void newFolderDialog() {
        promptText("New folder", "", s -> { if (!store.addFolder(s)) UI.toast(act, "Folder already exists"); refresh(); });
    }

    private void moveDialog(final Chat c) {
        final List<String> labels = new ArrayList<>();
        final List<String[]> targets = new ArrayList<>();      // {projectRoot, folder}
        labels.add("No folder (recent)"); targets.add(new String[]{"", ""});
        for (Project p : ProjectStore.list()) { labels.add("Project: " + p.root.getName()); targets.add(new String[]{p.root.getPath(), ""}); }
        for (String f : store.folders()) { labels.add("Folder: " + f); targets.add(new String[]{"", f}); }
        labels.add("＋ New folder…"); targets.add(null);
        UI.dialog(act).setTitle("Move chat to").setItems(labels.toArray(new String[0]), (d, which) -> {
            String[] t = targets.get(which);
            if (t == null) {
                promptText("New folder", "", s -> { store.addFolder(s); c.projectRoot = ""; c.folder = s; store.save(c); l.onChatRenamed(c); refresh(); });
                return;
            }
            c.projectRoot = t[0]; c.folder = t[1];
            store.save(c);
            l.onChatRenamed(c);
            refresh();
        }).show();
    }

    private void exportDialog(final Chat c) {
        UI.dialog(act).setTitle("Export chat").setItems(new String[]{"Share as text", "Save as .md file", "Copy to clipboard"}, (d, which) -> {
            String md = c.toMarkdown();
            if (which == 0) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, c.title);
                i.putExtra(Intent.EXTRA_TEXT, md);
                act.startActivity(Intent.createChooser(i, "Share chat"));
            } else if (which == 1) {
                try {
                    File dir = new File(Project.baseDir(), "AI_Exports");
                    dir.mkdirs();
                    File f = FileUtil.unique(new File(dir, c.title.replaceAll("[^A-Za-z0-9_ -]+", "").trim().replace(' ', '_') + ".md"));
                    FileUtil.writeText(f, md);
                    UI.toastLong(act, "Saved: " + f.getPath());
                } catch (Exception e) { UI.toast(act, "Could not save: " + e.getMessage()); }
            } else { UI.copy(act, "chat", md); UI.toast(act, "Copied"); }
        }).show();
    }
}
