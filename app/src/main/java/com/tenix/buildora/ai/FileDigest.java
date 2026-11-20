package com.tenix.buildora.ai;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns any file into text the AI can read: source and text files (any extension), Office and
 * OpenDocument files, archives (entry list), and a safe summary for unknown binary files.
 * Images and PDF pages are handled by the Android side. Pure Java.
 */
public final class FileDigest {
    private FileDigest() {}

    public static final String TEXT = "text", DOC = "document", SHEET = "spreadsheet", SLIDES = "presentation",
            ARCHIVE = "archive", BINARY = "binary", IMAGE = "image", PDF = "pdf", AUDIO = "audio", VIDEO = "video";

    public static final class Result {
        public String kind = TEXT;
        public String text = "";
        public String note = "";        // one-line description shown to the user and the AI
        public long size;
        public boolean truncated;
    }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 || d == name.length() - 1 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImageExt(String e) {
        return e.equals("png") || e.equals("jpg") || e.equals("jpeg") || e.equals("webp") || e.equals("gif") || e.equals("bmp") || e.equals("heic");
    }

    public static Result read(File f, int maxChars) {
        Result r = new Result();
        r.size = f.length();
        String e = ext(f.getName());
        try {
            if (isImageExt(e)) { r.kind = IMAGE; r.note = "image"; return r; }
            if (e.equals("pdf")) { r.kind = PDF; r.note = "PDF document"; return r; }
            if (e.equals("docx") || e.equals("dotx") || e.equals("docm")) return office(f, r, DOC, maxChars);
            if (e.equals("xlsx") || e.equals("xlsm")) return office(f, r, SHEET, maxChars);
            if (e.equals("pptx") || e.equals("ppsx")) return office(f, r, SLIDES, maxChars);
            if (e.equals("odt") || e.equals("ods") || e.equals("odp")) return odf(f, r, maxChars);
            if (e.equals("mp3") || e.equals("wav") || e.equals("ogg") || e.equals("m4a") || e.equals("flac") || e.equals("aac")) {
                r.kind = AUDIO; r.note = "audio file (" + e + ")"; r.text = "Audio file, " + r.size + " bytes. The content cannot be read as text."; return r;
            }
            if (e.equals("mp4") || e.equals("mkv") || e.equals("webm") || e.equals("avi") || e.equals("mov") || e.equals("3gp")) {
                r.kind = VIDEO; r.note = "video file (" + e + ")"; r.text = "Video file, " + r.size + " bytes. The content cannot be read as text."; return r;
            }
            byte[] head = readHead(f, 8192);
            String magic = magic(head);
            if (isZipMagic(head)) return archive(f, r, maxChars);
            if (looksBinary(head)) return binary(f, r, head, magic, maxChars);
            r.kind = TEXT;
            String t = decode(readAll(f, 4 * 1024 * 1024));
            r.note = e.isEmpty() ? "text file" : e + " text file";
            if (e.equals("html") || e.equals("htm")) r.note = "HTML file";
            r.text = smartCut(t, maxChars);
            r.truncated = r.text.length() < t.length();
            return r;
        } catch (IOException | RuntimeException ex) {
            r.kind = BINARY;
            r.note = "unreadable (" + ex.getMessage() + ")";
            return r;
        }
    }

    // ------------------------------------------------------------------ helpers

    public static boolean looksBinary(byte[] b) {
        if (b.length == 0) return false;
        int bad = 0;
        for (byte x : b) {
            int v = x & 0xff;
            if (v == 0) return true;
            if (v < 9 || (v > 13 && v < 32)) bad++;
        }
        return bad * 100 / b.length > 8;
    }

    static boolean isZipMagic(byte[] h) { return h.length > 3 && h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5); }

    public static String magic(byte[] h) {
        if (h.length < 4) return "";
        int a = h[0] & 0xff, b = h[1] & 0xff, c = h[2] & 0xff, d = h[3] & 0xff;
        if (a == 0x89 && b == 'P' && c == 'N' && d == 'G') return "PNG image";
        if (a == 0xff && b == 0xd8) return "JPEG image";
        if (a == 'G' && b == 'I' && c == 'F') return "GIF image";
        if (a == '%' && b == 'P' && c == 'D' && d == 'F') return "PDF document";
        if (a == 'P' && b == 'K') return "ZIP container";
        if (a == 0x7f && b == 'E' && c == 'L' && d == 'F') return "ELF binary (native library)";
        if (a == 'd' && b == 'e' && c == 'x' && d == '\n') return "Dalvik DEX";
        if (a == 0xca && b == 0xfe && c == 0xba && d == 0xbe) return "Java class file";
        if (a == 'S' && b == 'Q' && c == 'L' && d == 'i') return "SQLite database";
        if (a == 'I' && b == 'D' && c == '3') return "MP3 audio";
        if (a == 0x1f && b == 0x8b) return "GZIP data";
        if (a == 0x03 && b == 0x00 && c == 0x08 && d == 0x00) return "Android binary XML / resources";
        if (a == 'R' && b == 'I' && c == 'F' && d == 'F') return "RIFF media (WAV/WEBP/AVI)";
        return "";
    }

    static byte[] readHead(File f, int n) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n];
            int got = 0, r;
            while (got < n && (r = in.read(b, got, n - got)) > 0) got += r;
            byte[] o = new byte[got];
            System.arraycopy(b, 0, o, 0, got);
            return o;
        }
    }

    static byte[] readAll(File f, int max) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in, max); }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bo.size() + n > max) { bo.write(buf, 0, max - bo.size()); break; }
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    static String decode(byte[] b) {
        try {
            java.nio.charset.CharsetDecoder d = java.nio.charset.Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String s = d.decode(java.nio.ByteBuffer.wrap(b)).toString();
            return s.length() > 0 && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
        } catch (java.nio.charset.CharacterCodingException e) {
            try { return new String(b, "ISO-8859-1"); } catch (java.io.UnsupportedEncodingException x) { return new String(b); }
        }
    }

    /** Keeps the start and the end of a long text (most useful parts) and marks what was left out. */
    public static String smartCut(String s, int max) {
        if (s.length() <= max) return s;
        int head = (int) (max * 0.7), tail = max - head;
        int omitted = s.length() - head - tail;
        return s.substring(0, head) + "\n… [" + omitted + " characters omitted] …\n" + s.substring(s.length() - tail);
    }

    // ------------------------------------------------------------------ archives

    private static Result archive(File f, Result r, int maxChars) throws IOException {
        r.kind = ARCHIVE;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        long total = 0;
        List<String> textEntries = new ArrayList<>();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                n++;
                total += Math.max(0, e.getSize());
                if (n <= 300) sb.append(e.getName()).append("  (").append(e.getSize()).append(" B)\n");
                String x = ext(e.getName());
                if (textEntries.size() < 4 && e.getSize() > 0 && e.getSize() < 24000
                        && (x.equals("txt") || x.equals("md") || x.equals("json") || x.equals("xml") || x.equals("java") || x.equals("properties") || x.equals("gradle")
                        || x.equals("html") || x.equals("css") || x.equals("js") || x.equals("csv") || x.equals("yml") || x.equals("yaml"))
                        && !e.getName().startsWith("res/") && !e.getName().equals("AndroidManifest.xml")) textEntries.add(e.getName());
            }
            if (n > 300) sb.append("… and ").append(n - 300).append(" more entries\n");
            for (String name : textEntries) {
                ZipEntry e = z.getEntry(name);
                try (InputStream in = z.getInputStream(e)) {
                    byte[] b = readAll(in, 24000);
                    if (!looksBinary(b)) sb.append("\n--- ").append(name).append(" ---\n").append(decode(b)).append('\n');
                }
            }
        }
        r.note = "archive with " + n + " file" + (n == 1 ? "" : "s");
        String head = "Archive " + f.getName() + ": " + n + " files, about " + total + " bytes uncompressed.\nEntries:\n";
        r.text = smartCut(head + sb, maxChars);
        r.truncated = r.text.length() < head.length() + sb.length();
        return r;
    }

    // ------------------------------------------------------------------ Office / OpenDocument

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replaceAll("&#(\\d+);", " ").replace("&amp;", "&");
    }

    private static String entry(ZipFile z, String name, int max) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) { return decode(readAll(in, max)); }
    }

    private static Result office(File f, Result r, String kind, int maxChars) throws IOException {
        r.kind = kind;
        StringBuilder sb = new StringBuilder();
        try (ZipFile z = new ZipFile(f)) {
            if (kind.equals(DOC)) {
                String x = entry(z, "word/document.xml", 6 * 1024 * 1024);
                if (x == null) throw new IOException("not a Word document");
                x = x.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<w:br[^>]*/>", "\n");
                sb.append(unxml(TAG.matcher(x).replaceAll("")).replaceAll("\n{3,}", "\n\n").trim());
                r.note = "Word document";
            } else if (kind.equals(SLIDES)) {
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) { String n = en.nextElement().getName(); if (n.matches("ppt/slides/slide\\d+\\.xml")) names.add(n); }
                Collections.sort(names, new Comparator<String>() {
                    @Override public int compare(String a, String b) { return Integer.compare(numOf(a), numOf(b)); }
                });
                int i = 0;
                for (String n : names) {
                    String x = entry(z, n, 2 * 1024 * 1024);
                    if (x == null) continue;
                    x = x.replaceAll("</a:p>", "\n");
                    sb.append("--- Slide ").append(++i).append(" ---\n").append(unxml(TAG.matcher(x).replaceAll("")).replaceAll("\n{2,}", "\n").trim()).append("\n\n");
                }
                r.note = "presentation, " + names.size() + " slides";
            } else {
                String ss = entry(z, "xl/sharedStrings.xml", 6 * 1024 * 1024);
                List<String> strings = new ArrayList<>();
                if (ss != null) {
                    Matcher m = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(ss);
                    while (m.find()) strings.add(unxml(TAG.matcher(m.group(1)).replaceAll("")));
                }
                List<String> sheets = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) { String n = en.nextElement().getName(); if (n.matches("xl/worksheets/sheet\\d+\\.xml")) sheets.add(n); }
                Collections.sort(sheets, new Comparator<String>() {
                    @Override public int compare(String a, String b) { return Integer.compare(numOf(a), numOf(b)); }
                });
                int si = 0;
                for (String n : sheets) {
                    if (++si > 6) break;
                    String x = entry(z, n, 6 * 1024 * 1024);
                    if (x == null) continue;
                    sb.append("--- Sheet ").append(si).append(" ---\n");
                    Matcher rows = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(x);
                    int rc = 0;
                    while (rows.find() && rc++ < 400) {
                        Matcher cells = Pattern.compile("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL).matcher(rows.group(1));
                        List<String> vals = new ArrayList<>();
                        while (cells.find()) {
                            String attrs = cells.group(1), inner = cells.group(2) == null ? "" : cells.group(2);
                            Matcher v = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL).matcher(inner);
                            String val = v.find() ? v.group(1) : unxml(TAG.matcher(inner).replaceAll(""));
                            if (attrs.contains("t=\"s\"")) {
                                try { int idx = Integer.parseInt(val.trim()); val = idx >= 0 && idx < strings.size() ? strings.get(idx) : val; } catch (NumberFormatException ignored) { }
                            }
                            vals.add(unxml(val));
                        }
                        sb.append(join(vals, " | ")).append('\n');
                    }
                    sb.append('\n');
                }
                r.note = "spreadsheet, " + sheets.size() + " sheet" + (sheets.size() == 1 ? "" : "s");
            }
        }
        r.text = smartCut(sb.toString(), maxChars);
        r.truncated = r.text.length() < sb.length();
        return r;
    }

    private static Result odf(File f, Result r, int maxChars) throws IOException {
        r.kind = ext(f.getName()).equals("ods") ? SHEET : ext(f.getName()).equals("odp") ? SLIDES : DOC;
        try (ZipFile z = new ZipFile(f)) {
            String x = entry(z, "content.xml", 6 * 1024 * 1024);
            if (x == null) throw new IOException("no content.xml");
            x = x.replaceAll("</text:p>|</text:h>|</table:table-row>", "\n").replaceAll("</table:table-cell>", " | ");
            String t = unxml(TAG.matcher(x).replaceAll("")).replaceAll("\n{3,}", "\n\n").trim();
            r.note = "OpenDocument file";
            r.text = smartCut(t, maxChars);
            r.truncated = r.text.length() < t.length();
        }
        return r;
    }

    private static int numOf(String s) {
        Matcher m = Pattern.compile("(\\d+)\\.xml$").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(sep); sb.append(l.get(i)); }
        return sb.toString();
    }

    // ------------------------------------------------------------------ unknown binary

    private static Result binary(File f, Result r, byte[] head, String magic, int maxChars) throws IOException {
        r.kind = BINARY;
        r.note = magic.isEmpty() ? "binary file" : magic;
        StringBuilder sb = new StringBuilder();
        sb.append("Binary file ").append(f.getName()).append(", ").append(f.length()).append(" bytes");
        if (!magic.isEmpty()) sb.append(", detected as ").append(magic);
        sb.append(".\nFirst bytes (hex): ");
        for (int i = 0; i < Math.min(48, head.length); i++) sb.append(String.format(Locale.US, "%02x ", head[i] & 0xff));
        sb.append("\nReadable strings:\n");
        byte[] all = readAll(f, 512 * 1024);
        StringBuilder cur = new StringBuilder();
        int found = 0;
        for (byte x : all) {
            int v = x & 0xff;
            if (v >= 32 && v < 127) cur.append((char) v);
            else {
                if (cur.length() >= 6 && found < 80) { sb.append(cur).append('\n'); found++; }
                cur.setLength(0);
            }
        }
        r.text = smartCut(sb.toString(), maxChars);
        return r;
    }

    /** Maps a file name to a short label for tiles: "PDF", "ZIP", "JAVA". */
    public static String badge(String name) {
        String e = ext(name);
        if (e.isEmpty()) return "FILE";
        return e.length() > 4 ? e.substring(0, 4).toUpperCase(Locale.ROOT) : e.toUpperCase(Locale.ROOT);
    }

    /** Safe file name for storing an attachment (keeps the extension, no path parts). */
    public static String safeName(String raw) {
        String n = raw == null ? "file" : raw;
        int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (s >= 0) n = n.substring(s + 1);
        n = n.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "file";
        if (n.length() > 80) { String e = ext(n); n = n.substring(0, 70) + (e.isEmpty() ? "" : "." + e); }
        return n;
    }

    /** A name that does not exist in {@code dir} yet: photo.png, photo (2).png, ... */
    public static String uniqueName(File dir, String name) {
        if (!new File(dir, name).exists()) return name;
        String e = ext(name);
        String base = e.isEmpty() ? name : name.substring(0, name.length() - e.length() - 1);
        for (int i = 2; i < 1000; i++) {
            String c = base + " (" + i + ")" + (e.isEmpty() ? "" : "." + e);
            if (!new File(dir, c).exists()) return c;
        }
        return System.currentTimeMillis() + "_" + name;
    }
}
