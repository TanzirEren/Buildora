package com.tenix.buildora.ai;

import com.tenix.buildora.core.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A saved conversation. */
public final class Chat {

    public static final class Message {
        public String role = "user";        // user | assistant
        public String text = "";
        public String model = "";           // model id that produced it (assistant)
        public long time = System.currentTimeMillis();
        public String quote = "";           // text the user replied to
        public final List<String> attachments = new ArrayList<>();   // file names (content is not stored)
        public boolean error;
        public String backupId = "";        // set after the AI file changes of this message were applied
        public boolean hidden;              // shown only as a small note (auto follow-ups)

        public Message() {}
        public Message(String role, String text) { this.role = role; this.text = text; }
    }

    public String id;
    public String title = "New chat";
    public String projectRoot = "";    // linked Buildora project folder, "" = none
    public String folder = "";         // custom folder name, "" = none
    public boolean pinned;
    public boolean autoTitled;
    public long created = System.currentTimeMillis();
    public long updated = created;
    public String provider = "", model = "";   // last used model of this chat
    public final List<Message> messages = new ArrayList<>();

    public static String newId() { return "c" + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000); }

    public static Chat create() {
        Chat c = new Chat();
        c.id = newId();
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("projectRoot", projectRoot);
        m.put("folder", folder);
        m.put("pinned", pinned);
        m.put("autoTitled", autoTitled);
        m.put("created", (double) created);
        m.put("updated", (double) updated);
        m.put("provider", provider);
        m.put("model", model);
        List<Object> ms = new ArrayList<>();
        for (Message x : messages) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("role", x.role);
            o.put("text", x.text);
            o.put("model", x.model);
            o.put("time", (double) x.time);
            if (!x.quote.isEmpty()) o.put("quote", x.quote);
            if (!x.attachments.isEmpty()) o.put("att", new ArrayList<Object>(x.attachments));
            if (x.error) o.put("error", true);
            if (!x.backupId.isEmpty()) o.put("backup", x.backupId);
            if (x.hidden) o.put("hidden", true);
            ms.add(o);
        }
        m.put("messages", ms);
        return m;
    }

    public static Chat fromMap(Map<String, Object> m) {
        Chat c = new Chat();
        c.id = s(m.get("id"));
        if (c.id.isEmpty()) c.id = newId();
        c.title = s(m.get("title"));
        if (c.title.isEmpty()) c.title = "New chat";
        c.projectRoot = s(m.get("projectRoot"));
        c.folder = s(m.get("folder"));
        c.pinned = Boolean.TRUE.equals(m.get("pinned"));
        c.autoTitled = Boolean.TRUE.equals(m.get("autoTitled"));
        c.created = l(m.get("created"), System.currentTimeMillis());
        c.updated = l(m.get("updated"), c.created);
        c.provider = s(m.get("provider"));
        c.model = s(m.get("model"));
        List<Object> ms = MiniJson.array(m.get("messages"));
        if (ms != null) for (Object o : ms) {
            Map<String, Object> x = MiniJson.object(o);
            if (x == null) continue;
            Message g = new Message();
            g.role = s(x.get("role")).isEmpty() ? "user" : s(x.get("role"));
            g.text = s(x.get("text"));
            g.model = s(x.get("model"));
            g.time = l(x.get("time"), c.created);
            g.quote = s(x.get("quote"));
            List<Object> at = MiniJson.array(x.get("att"));
            if (at != null) for (Object a : at) if (a instanceof String) g.attachments.add((String) a);
            g.error = Boolean.TRUE.equals(x.get("error"));
            g.backupId = s(x.get("backup"));
            g.hidden = Boolean.TRUE.equals(x.get("hidden"));
            c.messages.add(g);
        }
        return c;
    }

    private static String s(Object o) { return o instanceof String ? (String) o : ""; }
    private static long l(Object o, long d) { return o instanceof Number ? ((Number) o).longValue() : d; }

    /** First user line, trimmed - used as an automatic title. */
    public String guessTitle() {
        for (Message m : messages) {
            if (!m.role.equals("user") || m.hidden) continue;
            String t = m.text.replace('\n', ' ').trim();
            if (t.startsWith("Build failed")) return "Fix build errors";
            if (t.length() > 48) t = t.substring(0, 47).trim() + "…";
            if (!t.isEmpty()) return t;
        }
        return "New chat";
    }

    /** Plain Markdown transcript. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        for (Message m : messages) {
            if (m.hidden) continue;
            sb.append(m.role.equals("user") ? "### You" : "### Buildora AI" + (m.model.isEmpty() ? "" : " (" + m.model + ")")).append("\n\n");
            if (!m.quote.isEmpty()) sb.append("> ").append(m.quote.replace("\n", "\n> ")).append("\n\n");
            sb.append(m.text.trim()).append("\n\n");
        }
        return sb.toString();
    }
}
