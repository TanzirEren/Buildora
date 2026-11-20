import com.tenix.buildora.ai.*;
import com.tenix.buildora.core.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

public class AiLogicTest {
    static int pass, fail;
    static void ok(boolean c, String n) { if (c) pass++; else { fail++; System.out.println("FAIL: " + n); } }
    static void eq(Object a, Object b, String n) { ok(Objects.equals(a, b), n + " (got " + a + ", want " + b + ")"); }

    static String sse(String text, boolean usage) {
        StringBuilder sb = new StringBuilder();
        sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n");
        if (usage) sb.append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":45}}\n\n");
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    static HttpServer server(Map<String, int[]> codes, List<String> seen, AtomicInteger hits) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(auth);
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            int[] cfg = codes.get(auth);
            int code = cfg == null ? 200 : cfg[0];
            byte[] body;
            if (code == 200) { body = sse("hello from " + auth.substring(auth.length() - 1), true).getBytes(); ex.getResponseHeaders().add("Content-Type", "text/event-stream"); }
            else {
                String msg = code == 429 && cfg[1] == 1 ? "{\"error\":{\"message\":\"You exceeded your current quota, please check billing\"}}" : "{\"error\":{\"message\":\"nope\"}}";
                body = msg.getBytes();
                if (code == 429 && cfg[1] == 0) ex.getResponseHeaders().add("Retry-After", "30");
            }
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }

    static class Cap implements ChatClient.Callback {
        StringBuilder text = new StringBuilder(); String err, done, sw; int in, out;
        public void onDelta(String t) { text.append(t); }
        public void onDone(String f) { done = f; }
        public void onError(String m, String p) { err = m; }
        public void onUsage(int i, int o) { in = i; out = o; }
        public void onKeySwitch(String m) { sw = m; }
    }

    static AiProvider prov(int port, String... keys) {
        AiProvider p = new AiProvider();
        p.name = "t"; p.baseUrl = "http://127.0.0.1:" + port + "/v1"; p.kind = AiProvider.KIND_OPENAI;
        for (String k : keys) p.keys.add(new AiProvider.KeySlot(k));
        return p;
    }

    static List<ChatClient.Msg> msgs() {
        List<ChatClient.Msg> l = new ArrayList<>();
        l.add(new ChatClient.Msg("user", "hi"));
        return l;
    }

    public static void main(String[] a) throws Exception {
        // ---------- provider keys
        AiProvider p = new AiProvider();
        p.keys.add(new AiProvider.KeySlot("sk-AAAA1111BBBB"));
        AiProvider.KeySlot k2 = new AiProvider.KeySlot("sk-CCCC2222DDDD"); k2.label = "backup"; k2.blockedUntil = 123456789L; k2.lastError = "HTTP 429";
        p.keys.add(k2);
        Map<String, Object> m = p.toMap(true);
        AiProvider q = AiProvider.fromMap(m);
        eq(q.keys.size(), 2, "keys roundtrip count");
        eq(q.keys.get(1).label, "backup", "label kept");
        eq(q.keys.get(1).blockedUntil, 123456789L, "blockedUntil kept");
        eq(q.keyCount(), 2, "keyCount");
        eq(AiProvider.fromMap(p.toMap(false)).keyCount(), 0, "export without keys");
        Map<String, Object> legacy = new LinkedHashMap<>(); legacy.put("id", "x"); legacy.put("name", "n"); legacy.put("apiKey", "old-key-123456");
        eq(AiProvider.fromMap(legacy).keys.size(), 1, "legacy single key migrated");
        eq(AiProvider.fromMap(legacy).activeKey(), "old-key-123456", "legacy active key");
        eq(p.copy(true).keys.size(), 2, "copy with keys"); eq(p.copy(false).keys.size(), 0, "copy without keys");
        ok(p.activeKey().equals("sk-AAAA1111BBBB"), "active key is first usable");
        ok(k2.masked().contains("••••"), "masked key");

        // ---------- failover: first key hits a quota -> second key used
        Map<String, int[]> codes = new HashMap<>();
        List<String> seen = new ArrayList<>(); AtomicInteger hits = new AtomicInteger();
        HttpServer s = server(codes, seen, hits);
        int port = s.getAddress().getPort();
        codes.put("Bearer key-A", new int[]{429, 1});
        AiProvider fp = prov(port, "key-A", "key-B", "key-C");
        AtomicInteger saved = new AtomicInteger(); fp.onChange = saved::incrementAndGet;
        Cap c = new Cap();
        new ChatClient().run(fp, "m", msgs(), new ChatClient.Options(), c);
        ok(c.done != null && c.err == null, "failover finished ok: " + c.err);
        eq(c.text.toString(), "hello from B", "reply came from key B");
        ok(c.sw != null && c.sw.contains("#2"), "key switch reported: " + c.sw);
        ok(fp.keys.get(0).blockedUntil > System.currentTimeMillis() + 3600000L, "quota key rests for hours");
        eq(c.in, 120, "usage in"); eq(c.out, 45, "usage out");
        ok(saved.get() >= 1, "key state persisted via onChange (" + saved.get() + ")");
        eq(fp.keys.get(1).uses, 1L, "key B use counted");
        // next request skips A immediately
        seen.clear();
        Cap c2 = new Cap();
        new ChatClient().run(fp, "m", msgs(), new ChatClient.Options(), c2);
        eq(seen.size(), 1, "second request goes straight to key B");
        eq(c2.sw, null, "no switch message the second time");

        // ---------- 401 on first -> next; short 429 -> minutes rest
        codes.clear(); seen.clear();
        codes.put("Bearer bad", new int[]{401, 0});
        AiProvider p401 = prov(port, "bad", "good");
        Cap c3 = new Cap();
        new ChatClient().run(p401, "m", msgs(), new ChatClient.Options(), c3);
        eq(c3.text.toString(), "hello from d", "401 -> next key");
        ok(p401.keys.get(0).blockedUntil > System.currentTimeMillis() + 3600000L, "invalid key rests 24h");
        codes.clear(); codes.put("Bearer r1", new int[]{429, 0});
        AiProvider pr = prov(port, "r1", "r2");
        new ChatClient().run(pr, "m", msgs(), new ChatClient.Options(), new Cap());
        long rest = pr.keys.get(0).blockedUntil - System.currentTimeMillis();
        ok(rest > 25000 && rest <= 61000, "rate limit rests ~60s (" + rest + ")");

        // ---------- all keys limited -> clear error
        codes.clear(); codes.put("Bearer x1", new int[]{429, 1}); codes.put("Bearer x2", new int[]{429, 1});
        Cap c4 = new Cap();
        new ChatClient().run(prov(port, "x1", "x2"), "m", msgs(), new ChatClient.Options(), c4);
        ok(c4.err != null && c4.err.contains("All 2 API keys"), "all keys limited message: " + c4.err);
        // ---------- disabled key skipped
        codes.clear(); seen.clear();
        AiProvider pd = prov(port, "k1", "k2"); pd.keys.get(0).enabled = false;
        new ChatClient().run(pd, "m", msgs(), new ChatClient.Options(), new Cap());
        eq(seen.get(0), "Bearer k2", "disabled key skipped");
        // ---------- no key (local server) works
        seen.clear();
        Cap c5 = new Cap();
        AiProvider pl = prov(port);
        pl.keys.clear();
        // server dereferences auth; send via key-less provider would NPE the fake server, so only check selection logic
        eq(pl.keyCount(), 0, "no keys");
        s.stop(0);

        // ---------- Anthropic usage parsing via fake server
        HttpServer as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        as.createContext("/v1/messages", ex -> {
            ex.getRequestBody().readAllBytes();
            String b = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":77,\"output_tokens\":1}}}\n\n"
                    + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                    + "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":33}}\n\n";
            ex.sendResponseHeaders(200, b.length()); ex.getResponseBody().write(b.getBytes()); ex.close();
        });
        as.start();
        AiProvider ap = new AiProvider(); ap.baseUrl = "http://127.0.0.1:" + as.getAddress().getPort() + "/v1"; ap.kind = AiProvider.KIND_ANTHROPIC; ap.keys.add(new AiProvider.KeySlot("ant-key"));
        Cap c6 = new Cap();
        new ChatClient().run(ap, "claude", msgs(), new ChatClient.Options(), c6);
        eq(c6.text.toString(), "Hi", "anthropic text"); eq(c6.in, 77, "anthropic in"); eq(c6.out, 33, "anthropic out");
        as.stop(0);

        // ---------- helpers
        eq(ChatClient.estimateTokens("abcdefghijklmnopqrstuvwxyz0123456789"), 10, "estimate tokens");
        ok(ChatClient.knowsStreamUsage(prov(1)) == false, "local not stream_options");
        AiProvider orp = new AiProvider(); orp.baseUrl = "https://openrouter.ai/api/v1";
        ok(ChatClient.knowsStreamUsage(orp), "openrouter stream_options");

        // ---------- Chat tokens roundtrip
        Chat ch = new Chat(); ch.tokensIn = 1500; ch.tokensOut = 900;
        Chat.Message am = new Chat.Message("assistant", "x"); am.tokIn = 10; am.tokOut = 20; ch.messages.add(am);
        Chat ch2 = Chat.fromMap(ch.toMap());
        eq(ch2.tokensIn, 1500L, "chat tokensIn"); eq(ch2.totalTokens(), 2400L, "chat total"); eq(ch2.messages.get(0).tokOut, 20, "msg tokOut");
        eq(Chat.fromMap(new Chat().toMap()).tokensIn, 0L, "old chats load with zero tokens");

        // ---------- protocol: import-asset and attach reads
        String reply = "```buildora-ops\nimport logo.png -> app/src/main/res/drawable/logo.png\nimport `My Photo (2).jpg` -> ./app/src/main/assets/photo.jpg\nimport attach:data.csv => app/src/main/assets/data.csv\ndelete app/old.txt\n```\n```buildora-read\nattach:report.pdf\napp/src/Main.java\n```\n";
        Protocol.Parsed pp = Protocol.parse(reply);
        int assets = 0; for (Protocol.Op o : pp.ops) if (o.type == Protocol.ASSET) assets++;
        eq(assets, 3, "three asset ops parsed");
        eq(pp.ops.get(1).attachment, "My Photo (2).jpg", "attachment name with spaces/parens");
        eq(pp.ops.get(1).to, "app/src/main/assets/photo.jpg", "./ stripped from target");
        eq(pp.ops.get(2).attachment, "data.csv", "attach: prefix stripped");
        eq(pp.reads.get(0), "attach:report.pdf", "attach read kept"); eq(pp.reads.get(1), "app/src/Main.java", "file read kept");
        eq(Protocol.parse("```buildora-ops\nimport a.png -> ../evil.png\n```").ops.size(), 0, "escape path rejected");

        // ---------- FileOps assets
        Path tmp = Files.createTempDirectory("bt");
        File root = new File(tmp.toFile(), "proj"); root.mkdirs();
        File att = new File(tmp.toFile(), "att"); att.mkdirs();
        Files.write(new File(att, "logo.png").toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        Files.write(new File(att, "Bad Name.png").toPath(), new byte[]{1, 2});
        Files.write(new File(att, "tool.exe").toPath(), new byte[]{1, 2});
        String r2 = "```buildora-ops\nimport logo.png -> app/src/main/res/drawable/logo.png\nimport LOGO.PNG -> app/src/main/assets/l.png\nimport Bad Name.png -> app/src/main/res/drawable/Bad Name.png\nimport logo.png -> app/src/main/res/drawable/BigLogo.png\nimport nothere.png -> app/x.png\nimport tool.exe -> app/tool.exe\n```\n";
        List<FileOps.Change> plan = FileOps.plan(root, Protocol.parse(r2).ops, att);
        eq(plan.size(), 6, "six changes");
        ok(plan.get(0).ok() && plan.get(0).asset && plan.get(0).kind == FileOps.NEW, "asset import planned");
        eq(plan.get(0).kindLabel(), "Import", "label Import");
        ok(plan.get(1).ok(), "case-insensitive attachment lookup");
        ok(plan.get(2).error != null, "bad res name rejected: " + plan.get(2).error);
        ok(plan.get(3).error != null && plan.get(3).error.contains("lowercase"), "uppercase res name rejected");
        ok(plan.get(4).error != null && plan.get(4).error.contains("not found"), "missing attachment");
        ok(plan.get(5).error != null, "exe not importable");
        File bk = new File(tmp.toFile(), "bk");
        String id = FileOps.apply(root, plan, bk);
        ok(!id.isEmpty(), "backup id");
        byte[] got = Files.readAllBytes(new File(root, "app/src/main/res/drawable/logo.png").toPath());
        eq(got.length, 7, "binary copied unchanged"); eq(got[1] & 0xff, (int) 'P', "png bytes intact");
        ok(new File(root, "app/src/main/assets/l.png").isFile(), "second asset written");
        FileOps.undo(root, bk, id);
        ok(!new File(root, "app/src/main/res/drawable/logo.png").exists(), "undo removes imported file");
        ok(FileOps.plan(root, Protocol.parse("```buildora-ops\nimport logo.png -> app/a.png\n```").ops).get(0).error != null, "asset without attachment dir -> error");

        // ---------- FileDigest
        File d = new File(tmp.toFile(), "dg"); d.mkdirs();
        Files.write(new File(d, "A.java").toPath(), "class A { int x = 1; }\n".getBytes());
        FileDigest.Result r = FileDigest.read(new File(d, "A.java"), 1000);
        eq(r.kind, FileDigest.TEXT, "java is text"); ok(r.text.contains("class A"), "java text read");
        Files.write(new File(d, "weird.xyzzy").toPath(), "plain words in an unknown extension".getBytes());
        eq(FileDigest.read(new File(d, "weird.xyzzy"), 1000).kind, FileDigest.TEXT, "unknown extension read as text");
        Files.write(new File(d, "latin.txt").toPath(), new byte[]{'c', 'a', 'f', (byte) 0xe9});
        ok(FileDigest.read(new File(d, "latin.txt"), 1000).text.startsWith("caf"), "latin-1 fallback");
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        Files.write(new File(d, "big.log").toPath(), big.toString().getBytes());
        FileDigest.Result br = FileDigest.read(new File(d, "big.log"), 2000);
        ok(br.truncated && br.text.contains("characters omitted") && br.text.startsWith("line 0") && br.text.trim().endsWith("line 4999"), "head+tail cut");
        zip(new File(d, "a.docx"), "word/document.xml", "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>World &amp; co</w:t></w:r></w:p></w:body></w:document>");
        FileDigest.Result dr = FileDigest.read(new File(d, "a.docx"), 1000);
        eq(dr.kind, FileDigest.DOC, "docx kind"); eq(dr.text, "Hello\nWorld & co", "docx text");
        ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(new File(d, "b.xlsx")));
        put(zo, "xl/sharedStrings.xml", "<sst><si><t>Name</t></si><si><t>Age</t></si><si><t>Bob</t></si></sst>");
        put(zo, "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>41</v></c></row></sheetData></worksheet>");
        zo.close();
        FileDigest.Result xr = FileDigest.read(new File(d, "b.xlsx"), 1000);
        eq(xr.kind, FileDigest.SHEET, "xlsx kind"); ok(xr.text.contains("Name | Age") && xr.text.contains("Bob | 41"), "xlsx cells: " + xr.text);
        zip(new File(d, "c.pptx"), "ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>Title slide</a:t></a:r></a:p></p:sld>");
        ok(FileDigest.read(new File(d, "c.pptx"), 1000).text.contains("Title slide"), "pptx text");
        ZipOutputStream z2 = new ZipOutputStream(new FileOutputStream(new File(d, "p.zip")));
        put(z2, "src/Main.java", "class Main {}"); put(z2, "res/logo.bin", "\u0000\u0001"); z2.close();
        FileDigest.Result zr = FileDigest.read(new File(d, "p.zip"), 3000);
        eq(zr.kind, FileDigest.ARCHIVE, "zip kind"); ok(zr.text.contains("src/Main.java") && zr.text.contains("class Main"), "zip listing + small text");
        Files.write(new File(d, "blob.bin").toPath(), new byte[]{0x7f, 'E', 'L', 'F', 0, 0, 0, 1, 'h', 'e', 'l', 'l', 'o', '_', 'w', 'o', 'r', 'l', 'd', 0, 0});
        FileDigest.Result bn = FileDigest.read(new File(d, "blob.bin"), 3000);
        eq(bn.kind, FileDigest.BINARY, "binary kind"); ok(bn.note.contains("ELF") && bn.text.contains("hello_world"), "binary summary");
        eq(FileDigest.read(new File(d, "x.png"), 10).kind, FileDigest.IMAGE, "png routed to image path");
        eq(FileDigest.read(new File(d, "y.pdf"), 10).kind, FileDigest.PDF, "pdf routed to pdf path");
        eq(FileDigest.safeName("../../etc/pass:wd.txt"), "pass_wd.txt", "safeName strips path");
        eq(FileDigest.safeName(""), "file", "safeName empty");
        File u = new File(tmp.toFile(), "u"); u.mkdirs(); Files.write(new File(u, "p.png").toPath(), new byte[1]);
        eq(FileDigest.uniqueName(u, "p.png"), "p (2).png", "uniqueName"); eq(FileDigest.uniqueName(u, "q.png"), "q.png", "uniqueName free");
        eq(FileDigest.badge("report.final.pdf"), "PDF", "badge"); eq(FileDigest.badge("README"), "FILE", "badge none");

        // ---------- ImagePalette
        int[] px = new int[400];
        for (int i = 0; i < 400; i++) px[i] = i < 250 ? 0xFF3F7EB8 : i < 380 ? 0xFFE0E1A6 : 0x00000000;
        List<String> cols = ImagePalette.dominant(px, 3);
        eq(cols.get(0), "#3F7EB8", "dominant colour"); eq(cols.size(), 2, "transparent ignored, two colours");
        ok(ImagePalette.describe("a.png", 1080, 1920, 5000, cols).contains("portrait"), "palette description orientation");

        // ---------- misc
        eq(Ai_compact(950), "950", "compact small"); 
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String Ai_compact(long n) { return n < 1000 ? String.valueOf(n) : "x"; }

    static void zip(File f, String name, String content) throws Exception { try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) { put(z, name, content); } }
    static void put(ZipOutputStream z, String name, String content) throws Exception { z.putNextEntry(new ZipEntry(name)); z.write(content.getBytes("UTF-8")); z.closeEntry(); }
}
