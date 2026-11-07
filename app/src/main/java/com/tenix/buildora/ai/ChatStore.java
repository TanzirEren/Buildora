package com.tenix.buildora.ai;

import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.MiniJson;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chats are stored one JSON file each; custom folders in folders.json. */
public final class ChatStore {

    public static final class Meta {
        public String id, title, projectRoot, folder, snippet;
        public boolean pinned;
        public long updated;
        public int count;
    }

    private final File dir;

    public ChatStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private File fileOf(String id) { return new File(dir, id + ".json"); }

    public synchronized void save(Chat c) {
        try {
            c.updated = System.currentTimeMillis();
            FileUtil.writeText(fileOf(c.id), Jx.write(c.toMap()));
        } catch (Exception ignored) { }
    }

    public synchronized Chat load(String id) {
        try {
            File f = fileOf(id);
            if (!f.isFile()) return null;
            Map<String, Object> m = MiniJson.object(MiniJson.parse(FileUtil.readText(f)));
            return m == null ? null : Chat.fromMap(m);
        } catch (Exception e) { return null; }
    }

    public synchronized void delete(String id) { fileOf(id).delete(); }

    public synchronized List<Chat> loadAll() {
        List<Chat> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".json") || f.getName().equals("folders.json")) continue;
            Chat c = load(f.getName().substring(0, f.getName().length() - 5));
            if (c != null) out.add(c);
        }
        Collections.sort(out, new Comparator<Chat>() {
            @Override public int compare(Chat a, Chat b) { return Long.compare(b.updated, a.updated); }
        });
        return out;
    }

    public static Meta meta(Chat c) {
        Meta m = new Meta();
        m.id = c.id; m.title = c.title; m.projectRoot = c.projectRoot; m.folder = c.folder;
        m.pinned = c.pinned; m.updated = c.updated; m.count = c.messages.size();
        m.snippet = "";
        for (int i = c.messages.size() - 1; i >= 0; i--) {
            if (!c.messages.get(i).hidden) { m.snippet = c.messages.get(i).text.replace('\n', ' '); break; }
        }
        if (m.snippet.length() > 80) m.snippet = m.snippet.substring(0, 80);
        return m;
    }

    /** Title or message text contains the query (case-insensitive, all words). */
    public static boolean matches(Chat c, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        StringBuilder hay = new StringBuilder(c.title.toLowerCase(Locale.ROOT)).append(' ');
        for (Chat.Message m : c.messages) hay.append(m.text.toLowerCase(Locale.ROOT)).append(' ');
        String h = hay.toString();
        for (String w : words) if (!h.contains(w)) return false;
        return true;
    }

    public synchronized Chat duplicate(String id) {
        Chat c = load(id);
        if (c == null) return null;
        c.id = Chat.newId();
        c.title = c.title + " (copy)";
        c.pinned = false;
        for (Chat.Message m : c.messages) m.backupId = "";
        save(c);
        return c;
    }

    // ----- custom folders

    private File foldersFile() { return new File(dir, "folders.json"); }

    public synchronized List<String> folders() {
        List<String> out = new ArrayList<>();
        try {
            if (!foldersFile().isFile()) return out;
            List<Object> a = MiniJson.array(MiniJson.parse(FileUtil.readText(foldersFile())));
            if (a != null) for (Object o : a) if (o instanceof String) out.add((String) o);
        } catch (Exception ignored) { }
        return out;
    }

    private void saveFolders(List<String> f) {
        try { FileUtil.writeText(foldersFile(), Jx.write(new ArrayList<Object>(f))); } catch (Exception ignored) { }
    }

    public synchronized boolean addFolder(String name) {
        name = name.trim();
        List<String> f = folders();
        if (name.isEmpty() || f.contains(name)) return false;
        f.add(name);
        saveFolders(f);
        return true;
    }

    public synchronized void renameFolder(String from, String to) {
        to = to.trim();
        if (to.isEmpty() || from.equals(to)) return;
        List<String> f = folders();
        int i = f.indexOf(from);
        if (i < 0 || f.contains(to)) return;
        f.set(i, to);
        saveFolders(f);
        for (Chat c : loadAll()) if (c.folder.equals(from)) { c.folder = to; save(c); }
    }

    /** Removes the folder; its chats become unfiled (or are deleted). */
    public synchronized void deleteFolder(String name, boolean deleteChats) {
        List<String> f = folders();
        f.remove(name);
        saveFolders(f);
        for (Chat c : loadAll()) if (c.folder.equals(name)) {
            if (deleteChats) delete(c.id); else { c.folder = ""; save(c); }
        }
    }

    public synchronized Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Chat c : loadAll()) {
            String k = !c.projectRoot.isEmpty() ? "p:" + c.projectRoot : !c.folder.isEmpty() ? "f:" + c.folder : "";
            Integer n = m.get(k);
            m.put(k, n == null ? 1 : n + 1);
        }
        return m;
    }
}
