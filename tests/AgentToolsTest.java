import com.tenix.buildora.ai.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** JVM tests for the agent tool protocol and file/search/refactor/diagnostics/shell tools. */
public class AgentToolsTest {
    static int pass, fail;
    static void ok(boolean c, String n) { if (c) pass++; else { fail++; System.out.println("FAIL: " + n); } }
    static void eq(Object a, Object b, String n) { ok(Objects.equals(a, b), n + " (got " + a + ", want " + b + ")"); }
    static void w(File root, String p, String t) throws Exception { File f = new File(root, p); f.getParentFile().mkdirs(); Files.write(f.toPath(), t.getBytes("UTF-8")); }
    static String r(File root, String p) throws Exception { return new String(Files.readAllBytes(new File(root, p).toPath()), "UTF-8"); }

    static AgentTools.Result run(AgentTools a, String name, String arg, String body) { return a.run(new Protocol.Tool(name, arg, body)); }

    public static void main(String[] x) throws Exception {
        // ---- protocol
        Protocol.Parsed p = Protocol.parse("```buildora-tool\nlist_files app\nread_file A.java 1-20\nsearch_text foo in app\n```\n");
        eq(p.tools.size(), 3, "three line tools in one block");
        eq(p.tools.get(1).arg, "A.java 1-20", "arg kept");
        p = Protocol.parse("text\n```buildora-tool\nwrite_file app/X.java\nclass X {\n}\n```\n");
        eq(p.tools.size(), 1, "body tool is one call"); eq(p.tools.get(0).body, "class X {\n}", "body kept");
        p = Protocol.parse("```buildora-tool\ntool: build_project release aab\n```");
        eq(p.tools.get(0).name, "build_project", "tool: prefix"); eq(p.tools.get(0).arg, "release aab", "build args");
        p = Protocol.parse("```buildora-tool\nread_file(app/A.java)\n```");
        eq(p.tools.get(0).arg, "app/A.java", "function-call style");
        ok(Protocol.parse("```buildora-tool\nhack_the_planet x\n```").tools.isEmpty(), "unknown tool ignored");
        ok(!Protocol.parse("```buildora-tool\nlist_files\n```").any(), "tools do not create a changes card");
        eq(AgentTools.NAMES.length, 36, "36 names (35 tools + plan)");

        Path tmp = Files.createTempDirectory("agent");
        File root = new File(tmp.toFile(), "proj"); root.mkdirs();
        File bk = new File(tmp.toFile(), "bk");
        AgentTools.Config cfg = new AgentTools.Config(); cfg.shellMode = 1; cfg.web = false; cfg.home = new File(tmp.toFile(), "home");
        AgentTools a = new AgentTools(root, bk, null, cfg);

        w(root, "app/src/main/AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.x\">\n<application android:label=\"@string/app_name\">\n<activity android:name=\".Main\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/></intent-filter></activity>\n</application></manifest>\n");
        w(root, "app/src/main/res/values/strings.xml", "<resources><string name=\"app_name\">X</string></resources>\n");
        w(root, "app/src/main/res/layout/activity_main.xml", "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"><TextView android:id=\"@+id/title\" android:text=\"@string/app_name\"/></LinearLayout>\n");
        w(root, "app/src/main/java/com/x/Main.java", "package com.x;\n\nimport android.app.Activity;\nimport android.os.Bundle;\nimport java.util.List;\n\npublic class Main extends Activity {\n    private int count;\n    @Override protected void onCreate(Bundle b) {\n        super.onCreate(b);\n        setContentView(R.layout.activity_main);\n        findViewById(R.id.title);\n    }\n    public int next() { return ++count; }\n}\n");

        // ---- read tools
        AgentTools.Result rr = run(a, "list_files", "", "");
        ok(rr.ok && rr.output.contains("Main.java") && rr.output.contains("strings.xml"), "list_files tree");
        rr = run(a, "read_file", "app/src/main/java/com/x/Main.java 7-9", "");
        ok(rr.output.contains("7| public class Main") && !rr.output.contains("1| package"), "read_file range with numbers");
        rr = run(a, "read_file", "nope.txt", "");
        ok(!rr.ok, "read_file missing is an error");
        rr = run(a, "read_file", "../etc/passwd", "");
        ok(!rr.ok, "path escape blocked");
        rr = run(a, "search_text", "count in app", "");
        ok(rr.output.contains("Main.java:8") && rr.output.contains("match"), "search_text");
        rr = run(a, "find_files", "Main*", "");
        ok(rr.output.contains("Main.java"), "find_files glob");
        rr = run(a, "file_info", "app/src/main/java/com/x/Main.java", "");
        ok(rr.output.contains("package: com.x") && rr.output.contains("class: Main"), "file_info");
        rr = run(a, "find_usages", "count", "");
        ok(rr.output.contains("usage"), "find_usages");
        rr = run(a, "list_resources", "id", "");
        ok(rr.output.contains("title"), "list_resources id");
        rr = run(a, "class_outline", "app/src/main/java/com/x/Main.java", "");
        ok(rr.output.contains("class Main") && rr.output.contains("onCreate") && rr.output.contains("next()") && rr.output.contains("count"), "class_outline: " + rr.output);
        rr = run(a, "project_stats", "", "");
        ok(rr.output.contains("java: 1 files"), "project_stats");

        // ---- write tools + undo
        rr = run(a, "write_file", "app/src/main/java/com/x/Util.java", "package com.x;\npublic class Util { }");
        ok(rr.ok && r(root, "app/src/main/java/com/x/Util.java").endsWith("}\n"), "write_file creates");
        rr = run(a, "append_file", "app/src/main/java/com/x/Util.java", "// end");
        ok(r(root, "app/src/main/java/com/x/Util.java").endsWith("// end\n"), "append_file");
        rr = run(a, "replace_lines", "app/src/main/java/com/x/Util.java 1-1", "package com.y;");
        ok(r(root, "app/src/main/java/com/x/Util.java").startsWith("package com.y;\npublic class"), "replace_lines");
        rr = run(a, "insert_lines", "app/src/main/java/com/x/Util.java 0", "// top");
        ok(r(root, "app/src/main/java/com/x/Util.java").startsWith("// top\npackage com.y;"), "insert_lines at top");
        rr = run(a, "replace_all", "app/src/main/java/com/x/Util.java com.y => com.x", "");
        ok(rr.ok && r(root, "app/src/main/java/com/x/Util.java").contains("package com.x;"), "replace_all");
        rr = run(a, "replace_all", "app/src/main/java/com/x/Util.java zzz => q", "");
        ok(!rr.ok, "replace_all not found is an error");
        rr = run(a, "copy_path", "app/src/main/java/com/x/Util.java -> app/Copy.txt", "");
        ok(rr.ok && new File(root, "app/Copy.txt").isFile(), "copy_path");
        rr = run(a, "move_path", "app/Copy.txt -> app/Moved.txt", "");
        ok(rr.ok && !new File(root, "app/Copy.txt").exists() && new File(root, "app/Moved.txt").isFile(), "move_path");
        rr = run(a, "delete_path", "app/Moved.txt", "");
        ok(rr.ok && !new File(root, "app/Moved.txt").exists(), "delete_path");
        rr = run(a, "undo_last", "", "");
        ok(rr.ok && new File(root, "app/Moved.txt").isFile(), "undo_last restores the deleted file: " + rr.output);
        rr = run(a, "make_dir", "app/newdir/sub", "");
        ok(new File(root, "app/newdir/sub").isDirectory(), "make_dir");
        rr = run(a, "plan", "", "- [ ] one\n- [ ] two");
        ok(rr.ok && r(root, ".buildora/ai_plan.md").contains("two"), "plan saved");

        // ---- refactoring
        rr = run(a, "rename_class", "Main -> HomeActivity", "");
        ok(rr.ok, "rename_class ok: " + rr.output);
        ok(new File(root, "app/src/main/java/com/x/HomeActivity.java").isFile() && !new File(root, "app/src/main/java/com/x/Main.java").exists(), "class file renamed");
        ok(r(root, "app/src/main/java/com/x/HomeActivity.java").contains("public class HomeActivity extends Activity"), "declaration renamed");
        ok(r(root, "app/src/main/AndroidManifest.xml").contains("android:name=\".HomeActivity\""), "manifest entry renamed: " + r(root, "app/src/main/AndroidManifest.xml"));
        ok(!run(a, "rename_class", "Nothing -> Other", "").ok, "rename unknown class fails");
        rr = run(a, "rename_resource", "layout/activity_main -> activity_home", "");
        ok(rr.ok && new File(root, "app/src/main/res/layout/activity_home.xml").isFile(), "layout file renamed: " + rr.output);
        ok(r(root, "app/src/main/java/com/x/HomeActivity.java").contains("R.layout.activity_home"), "layout reference renamed");
        rr = run(a, "rename_resource", "id/title -> headline", "");
        ok(rr.ok && r(root, "app/src/main/res/layout/activity_home.xml").contains("@+id/headline") && r(root, "app/src/main/java/com/x/HomeActivity.java").contains("R.id.headline"), "id renamed in xml and java");
        rr = run(a, "undo_last", "", ""); rr = run(a, "undo_last", "", "");
        ok(new File(root, "app/src/main/java/com/x/Main.java").isFile() == false && new File(root, "app/src/main/res/layout/activity_main.xml").isFile(), "undo of resource renames");

        // ---- diagnostics
        File d2 = new File(tmp.toFile(), "p2"); d2.mkdirs();
        AgentTools b = new AgentTools(d2, new File(tmp.toFile(), "bk2"), null, cfg);
        w(d2, "app/src/main/AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.q\"><application><activity android:name=\".Ghost\"/></application></manifest>");
        w(d2, "app/src/main/res/values/strings.xml", "<resources><string name=\"a\">A</string></resources>");
        w(d2, "app/src/main/res/layout/bad.xml", "<LinearLayout><TextView android:text=\"@string/missing\"></LinearLayout>");
        w(d2, "app/src/main/java/com/q/Foo.java", "package com.q;\nimport java.util.List;\nimport android.app.Activity;\npublic class Foo extends Activity {\n  void f() { int x = R.id.nothing; new java.net.URL(\"http://a\"); \n}\n");
        rr = run(b, "diagnostics", "", "");
        ok(rr.output.contains("XML error") && rr.output.contains("bad.xml"), "diag: bad xml");
        ok(rr.output.contains("unbalanced braces"), "diag: unbalanced braces");
        ok(rr.output.contains("R.id.nothing is not defined"), "diag: undefined R.id");
        ok(rr.output.contains("unused import java.util.List"), "diag: unused import");
        ok(rr.output.contains("INTERNET permission is missing"), "diag: INTERNET");
        ok(rr.output.contains("Ghost has no Java class"), "diag: manifest class missing");
        ok(rr.output.contains("not declared as <activity>"), "diag: Foo not in manifest");
        ok(rr.output.contains("no launcher activity"), "diag: no launcher");

        // ---- shell
        rr = run(a, "run_shell", "echo hello && pwd", "");
        ok(rr.ok && rr.output.contains("hello") && rr.output.contains("[exit code 0]") && rr.output.contains("proj"), "run_shell runs in the project: " + rr.output);
        rr = run(a, "run_shell", "timeout=5 sleep 20", "");
        ok(rr.output.contains("timed out"), "run_shell timeout");
        rr = run(a, "run_shell", "rm -rf /", "");
        ok(!rr.ok && rr.output.contains("blocked"), "dangerous command blocked");
        rr = run(a, "run_shell", "rm -rf ~", "");
        ok(!rr.ok, "rm -rf ~ blocked");
        rr = run(a, "run_shell", "rm -rf build_tmp", "");
        ok(!rr.output.contains("blocked"), "ordinary rm inside the project is not blocked");
        AgentTools.Config never = new AgentTools.Config(); never.shellMode = 2;
        ok(!new AgentTools(root, bk, null, never).run(new Protocol.Tool("run_shell", "echo hi", "")).ok, "shell mode never");
        AgentTools.Config ask = new AgentTools.Config(); ask.shellMode = 0;
        final boolean[] asked = {false};
        AgentTools.Host denyHost = new AgentTools.Host() {
            public String build(boolean r, boolean aab) { return "x"; } public String crashReports(String p) { return ""; } public String git(String o, String a2) { return ""; } public String install() { return ""; }
            public boolean approve(String t, String d) { asked[0] = true; return false; }
        };
        rr = new AgentTools(root, bk, denyHost, ask).run(new Protocol.Tool("run_shell", "echo hi", ""));
        ok(asked[0] && !rr.ok && rr.output.contains("did not allow"), "ask mode consults the user and respects Deny");
        rr = run(a, "run_python", "", "print(6*7)");
        ok(rr.output.contains("42") || rr.output.contains("no Python interpreter"), "python runs or fails clearly: " + rr.output);
        rr = run(a, "search_web", "kotlin", "");
        ok(!rr.ok && rr.output.contains("turned off"), "web off respected");
        rr = run(a, "build_project", "", "");
        ok(!rr.ok, "build without host is an error");
        rr = run(a, "git_status", "", "");
        ok(!rr.ok || rr.output.length() > 0, "git without host handled");

        // ---- model text
        List<AgentTools.Result> l = new ArrayList<>(); l.add(run(a, "list_files", "", ""));
        String mt = AgentTools.toModelText(l);
        ok(mt.startsWith("<tool_results>") && mt.contains("[1] list_files") && mt.trim().endsWith("</tool_results>"), "tool results text");
        ok(l.get(0).summary().startsWith("✓ list_files"), "summary line");
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 3000; i++) big.append("line " + i + "\n");
        w(root, "big.txt", big.toString());
        rr = run(a, "read_file", "big.txt", "");
        ok(rr.output.contains("more lines") && rr.output.length() < 15000, "big file read is capped with a continue hint");

        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
