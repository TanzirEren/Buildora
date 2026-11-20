package com.tenix.buildora.ai;

import com.tenix.buildora.core.Project;

import java.io.File;
import java.util.List;

/** Applying AI replies to a project without any UI (used by the chat and by the background engine). */
public final class AiApply {
    private AiApply() {}

    /** Adds "group:artifact:version" lines to the project's dependency list. Returns how many were new. */
    public static int addDeps(File root, List<String> deps) {
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

    public static final class Outcome {
        public String backupId = "";
        public int deps;
        public List<String> touched = new java.util.ArrayList<>();
        public String error;
    }

    /** Plans and applies every valid change of {@code reply} to the project at {@code root}. */
    public static Outcome applyReply(File root, File attachDir, String reply) {
        Outcome o = new Outcome();
        try {
            Protocol.Parsed pr = Protocol.parse(reply);
            List<FileOps.Change> plan = FileOps.plan(root, pr.ops, attachDir);
            o.backupId = FileOps.apply(root, plan, new File(new File(root, ".buildora"), "ai_backup"));
            for (FileOps.Change c : plan) if (c.selected && c.ok()) { o.touched.add(c.path); if (c.to != null && !c.asset) o.touched.add(c.to); }
            o.deps = addDeps(root, pr.deps);
        } catch (Exception e) {
            o.error = String.valueOf(e.getMessage());
        }
        return o;
    }
}
