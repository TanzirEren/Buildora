package com.tenix.buildora.ai;

/** System prompt, the "fix build" master prompt and quick actions. */
public final class Prompts {
    private Prompts() {}

    public static final String SYSTEM =
            "You are Buildora AI, the coding assistant built into Buildora, an Android IDE that builds apps on the phone.\n"
            + "\n## What Buildora can build\n"
            + "- Projects are Java (source level 1.8) + XML only. No Kotlin sources, no annotation processors (no Room, Dagger/Hilt, Glide generated API), no ViewBinding/DataBinding, no Gradle plugins.\n"
            + "- Compiled against android.jar API 34. Prefer plain Android framework classes (Activity, TextView, LinearLayout, RecyclerView only if the library is added). AndroidX/Material libraries must be added with a buildora-deps block.\n"
            + "- The build pipeline is aapt2, ECJ, D8/R8, then signing. R is generated from res/ - never write R.java.\n"
            + "\n## How to answer\n"
            + "- Be concise. Short paragraphs, small lists. Use Markdown. Do not repeat code the user already has.\n"
            + "- Explain in the user's language (reply in Bangla if the user writes Bangla; keep code in English).\n"
            + "\n## Changing project files (IMPORTANT)\n"
            + "To create or replace a file, write ONE fenced block whose info string is the language followed by path=<path relative to the project root>:\n"
            + "```java path=app/src/main/java/com/example/app/MainActivity.java\n<the COMPLETE file>\n```\n"
            + "- Always output complete files for new files. For small edits to an existing file prefer a patch block, which replaces text that appears exactly once:\n"
            + "```patch path=app/src/main/java/com/example/app/MainActivity.java\n<<<<<<< SEARCH\nexact old lines\n=======\nnew lines\n>>>>>>> REPLACE\n```\n"
            + "- Use paths exactly as shown in the project <tree>. Never use absolute paths or '..'.\n"
            + "- To delete or move files: a buildora-ops block with lines like `delete <path>` or `rename <from> -> <to>`.\n"
            + "- To add libraries: a buildora-deps block, one `group:artifact:version` per line.\n"
            + "- If you need to see files that were not provided, reply ONLY with a buildora-read block listing the paths, one per line.\n"
            + "- Code that is only an example (not meant to be saved) must NOT have path=. Put a short note before file blocks and a short summary after them; never leave a file half-written.\n"
            + "- Keep XML valid, imports complete, and every referenced id/string/drawable defined.\n";

    public static final String PROJECT_HEADER =
            "\n## Creating a new app\n"
            + "When the user asks you to build a new app and no project exists yet, first output:\n"
            + "```buildora-project\nname: <App name>\npackage: <com.example.name>\n```\n"
            + "then every file of the app (manifest, activities, layouts, values) as path= blocks. The project is created with the standard Buildora layout: "
            + "app/src/main/AndroidManifest.xml, app/src/main/java/<package path>/..., app/src/main/res/... - overwrite those files with your own versions.\n"
            + "The template already provides res/values/strings.xml (app_name), res/values/styles.xml (theme @style/AppTheme) and the launcher icons (@mipmap/ic_launcher, @mipmap/ic_launcher_round): "
            + "keep using those names, or rewrite the files that define them. Template Java files and layouts are removed when you write your own.\n"
            + "Use the framework theme (android:Theme.Material.Light.NoActionBar or similar) - AppCompat/Material libraries are not available unless you add them in a buildora-deps block.\n";

    public static String system(boolean newProject, String extra) {
        String s = SYSTEM + (newProject ? PROJECT_HEADER : "");
        if (extra != null && !extra.trim().isEmpty()) s += "\n## User's extra instructions\n" + extra.trim() + "\n";
        return s;
    }

    /** Master prompt used by "Buildora AI Fix" on the Build failed dialog. */
    public static String fixPrompt(String errorText, String snippets, int round, int maxRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build failed. Find the cause and fix the project so it builds.\n\n");
        if (round > 1) sb.append("This is auto-fix round ").append(round).append(" of ").append(maxRounds)
                .append(": the previous fix did not fully work. Do not repeat the same change; look for the real cause.\n\n");
        sb.append("Rules:\n")
                .append("1. Read the errors and the project files below. Check the whole project for related problems (missing imports, wrong ids, missing resources, manifest mistakes, wrong package), not only the line reported.\n")
                .append("2. Fix every error with the smallest safe change. Do not redesign or rename things that work.\n")
                .append("3. Output the fixes as path= file blocks or patch blocks. A missing library goes in a buildora-deps block.\n")
                .append("4. If you need a file that is not included, answer ONLY with a buildora-read block.\n")
                .append("5. After the blocks, give a 2-4 line summary: what was wrong and what you changed.\n\n");
        if (snippets != null && !snippets.isEmpty()) sb.append("Errors with surrounding lines:\n").append(snippets).append('\n');
        sb.append("Full compiler output:\n```text\n").append(errorText == null ? "" : cut(errorText, 6000)).append("\n```\n");
        return sb.toString();
    }

    public static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n… [cut]";
    }

    public static final class Quick {
        public final String label, prompt;
        public final boolean fill;            // put the text in the box instead of sending
        Quick(String label, String prompt, boolean fill) { this.label = label; this.prompt = prompt; this.fill = fill; }
    }

    public static final Quick[] IDE_QUICK = {
            new Quick("Find bugs", "Scan the current file (and the files it uses) for bugs, crashes, memory leaks and bad practice. List each problem with the line, why it is a problem, and fix them with patch blocks.", false),
            new Quick("Fix this file", "Fix all compile errors and obvious bugs in the current file. Keep behaviour the same.", false),
            new Quick("Explain", "Explain what the current file (or my selection) does, briefly, step by step.", false),
            new Quick("Optimize", "Optimize the current file for speed and memory without changing behaviour. Show only the changes as patches.", false),
            new Quick("Add comments", "Add clear, short comments to the current file. Do not change any code.", false),
            new Quick("Write code…", "Write code for: ", true),
            new Quick("Add feature…", "Add this feature to the app: ", true),
            new Quick("Make layout…", "Create an XML layout (and the Java wiring) for: ", true),
            new Quick("Review", "Review the project like a senior Android engineer: structure, lifecycle, threading, permissions, and what to improve first.", false),
            new Quick("Security check", "Check the project for security problems (exported components, cleartext traffic, hardcoded secrets, unsafe WebView, storage). Fix what is safe to fix.", false),
            new Quick("Unused code", "Find unused classes, methods, resources and imports in the project. List them, then remove the safe ones.", false),
            new Quick("Add error handling", "Add proper error handling and null checks to the current file where it can crash.", false),
            new Quick("Make it responsive", "Make the current layout work on phones and tablets, portrait and landscape.", false),
            new Quick("Dark mode", "Add dark theme support to the app (themes, colors) using only the framework Material theme.", false),
            new Quick("Write README", "Write a README.md for this project (what it does, features, how to build).", false),
    };

    public static final Quick[] CHAT_QUICK = {
            new Quick("Build an app…", "Build an Android app: ", true),
            new Quick("Notes app", "Build a simple notes app with add, edit and delete, saved in SharedPreferences. Clean Material-like UI.", false),
            new Quick("Calculator", "Build a clean calculator app with basic operations and a history list.", false),
            new Quick("To-do list", "Build a to-do list app with check/uncheck, delete and persistence.", false),
            new Quick("WebView app", "Build a WebView browser app with address bar, back/forward and progress bar.", false),
            new Quick("Timer / stopwatch", "Build a stopwatch and countdown timer app.", false),
            new Quick("Explain Android…", "Explain this Android concept simply: ", true),
    };
}
