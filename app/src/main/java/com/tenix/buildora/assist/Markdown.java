package com.tenix.buildora.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small Markdown to HTML converter (headings, lists, code, tables, quotes, links, images, inline HTML). */
public final class Markdown {
    private Markdown() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern HR = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern HTML_BLOCK = Pattern.compile("^\\s*</?(p|div|img|table|tr|td|th|thead|tbody|h[1-6]|ul|ol|li|details|summary|br|hr|a|center|picture|source|pre|blockquote|span|kbd|sup|sub|!--)\\b.*", Pattern.CASE_INSENSITIVE);

    public static String toHtml(String md) {
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().isEmpty()) { flush(out, para); i++; continue; }
            // fenced code
            if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                flush(out, para);
                String fence = line.trim().substring(0, 3);
                String lang = line.trim().substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++; }
                i++;
                out.append("<pre><code").append(lang.isEmpty() ? "" : " class=\"language-" + esc(lang) + "\"").append('>').append(esc(code.toString())).append("</code></pre>\n");
                continue;
            }
            Matcher hm = HEADING.matcher(line);
            if (hm.matches()) {
                flush(out, para);
                int n = hm.group(1).length();
                out.append("<h").append(n).append(" id=\"").append(slug(hm.group(2))).append("\">").append(inline(hm.group(2))).append("</h").append(n).append(">\n");
                i++;
                continue;
            }
            if (HR.matcher(line).matches() && para.isEmpty() || HR.matcher(line).matches() && !line.trim().startsWith("-") ) { flush(out, para); out.append("<hr>\n"); i++; continue; }
            // setext heading
            if (!para.isEmpty() && line.matches("^=+\\s*$")) { String t = String.join(" ", para); para.clear(); out.append("<h1>").append(inline(t)).append("</h1>\n"); i++; continue; }
            if (!para.isEmpty() && line.matches("^-{2,}\\s*$")) { String t = String.join(" ", para); para.clear(); out.append("<h2>").append(inline(t)).append("</h2>\n"); i++; continue; }
            // block quote
            if (line.startsWith(">")) {
                flush(out, para);
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].startsWith(">")) { q.append(lines[i].replaceFirst("^>\\s?", "")).append('\n'); i++; }
                out.append("<blockquote>").append(toHtml(q.toString())).append("</blockquote>\n");
                continue;
            }
            // table
            if (line.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches() && lines[i + 1].contains("-")) {
                flush(out, para);
                List<String> head = cells(line);
                List<String> aligns = cells(lines[i + 1]);
                out.append("<table><thead><tr>");
                for (int c = 0; c < head.size(); c++) out.append("<th").append(align(aligns, c)).append('>').append(inline(head.get(c))).append("</th>");
                out.append("</tr></thead><tbody>\n");
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    List<String> row = cells(lines[i]);
                    out.append("<tr>");
                    for (int c = 0; c < head.size(); c++) out.append("<td").append(align(aligns, c)).append('>').append(inline(c < row.size() ? row.get(c) : "")).append("</td>");
                    out.append("</tr>\n");
                    i++;
                }
                out.append("</tbody></table>\n");
                continue;
            }
            // list
            if (LIST.matcher(line).matches() && !HR.matcher(line).matches()) {
                flush(out, para);
                i = list(lines, i, out);
                continue;
            }
            // raw HTML block (README headers like <p align="center">)
            if (para.isEmpty() && HTML_BLOCK.matcher(line).matches()) {
                StringBuilder h = new StringBuilder();
                while (i < lines.length && !lines[i].trim().isEmpty()) { h.append(lines[i]).append('\n'); i++; }
                out.append(h);
                continue;
            }
            para.add(line);
            i++;
        }
        flush(out, para);
        return out.toString();
    }

    private static String align(List<String> aligns, int c) {
        if (c >= aligns.size()) return "";
        String a = aligns.get(c).trim();
        if (a.startsWith(":") && a.endsWith(":")) return " style=\"text-align:center\"";
        if (a.endsWith(":")) return " style=\"text-align:right\"";
        return "";
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '\\' && i + 1 < r.length() && r.charAt(i + 1) == '|') { cur.append('|'); i++; }
            else if (c == '|') { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static int list(String[] lines, int i, StringBuilder out) {
        List<String> stack = new ArrayList<>();      // "ul"/"ol"
        List<Integer> indents = new ArrayList<>();
        boolean open = false;
        while (i < lines.length) {
            String line = lines[i];
            Matcher m = LIST.matcher(line);
            if (!m.matches() || HR.matcher(line).matches()) {
                if (line.trim().isEmpty() && i + 1 < lines.length && LIST.matcher(lines[i + 1]).matches()) { i++; continue; }
                if (!line.trim().isEmpty() && line.startsWith("  ") && open) {   // continuation line
                    out.setLength(out.length() - 6 > 0 && out.toString().endsWith("</li>\n") ? out.length() - 6 : out.length());
                    out.append(' ').append(inline(line.trim())).append("</li>\n");
                    i++;
                    continue;
                }
                break;
            }
            int indent = m.group(1).replace("\t", "    ").length();
            String kind = Character.isDigit(m.group(2).charAt(0)) ? "ol" : "ul";
            String body = m.group(3);
            while (!indents.isEmpty() && indent < indents.get(indents.size() - 1)) { out.append("</").append(stack.remove(stack.size() - 1)).append(">\n"); indents.remove(indents.size() - 1); }
            if (!indents.isEmpty() && indent == indents.get(indents.size() - 1) && !stack.get(stack.size() - 1).equals(kind)) {
                out.append("</").append(stack.remove(stack.size() - 1)).append(">\n");
                indents.remove(indents.size() - 1);
            }
            if (indents.isEmpty() || indent > indents.get(indents.size() - 1)) { out.append('<').append(kind).append(">\n"); stack.add(kind); indents.add(indent); }
            String cls = "";
            Matcher tm = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$").matcher(body);
            if (tm.matches()) { body = "<input type=\"checkbox\" disabled" + (tm.group(1).trim().isEmpty() ? "" : " checked") + "> " + inline(tm.group(2)); cls = " class=\"task\""; }
            else body = inline(body);
            out.append("<li").append(cls).append('>').append(body).append("</li>\n");
            open = true;
            i++;
        }
        while (!stack.isEmpty()) { out.append("</").append(stack.remove(stack.size() - 1)).append(">\n"); }
        return i;
    }

    private static void flush(StringBuilder out, List<String> para) {
        if (para.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < para.size(); k++) {
            String l = para.get(k);
            boolean br = l.endsWith("  ") || l.endsWith("\\");
            sb.append(inline(l.trim()));
            if (k < para.size() - 1) sb.append(br ? "<br>\n" : "\n");
        }
        out.append("<p>").append(sb).append("</p>\n");
        para.clear();
    }

    // ------------------------------------------------------------------ inline

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String slug(String s) { return s.toLowerCase().replaceAll("<[^>]*>", "").replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-"); }

    public static String inline(String text) {
        List<String> holds = new ArrayList<>();
        // code spans
        Matcher cm = Pattern.compile("(`+)(.+?)\\1").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (cm.find()) {
            holds.add("<code>" + esc(cm.group(2).trim()) + "</code>");
            cm.appendReplacement(sb, Matcher.quoteReplacement("\u0000" + (holds.size() - 1) + "\u0000"));
        }
        cm.appendTail(sb);
        String t = sb.toString();
        // keep real HTML tags, escape everything else
        StringBuilder e = new StringBuilder();
        Matcher tag = Pattern.compile("</?[A-Za-z][^<>]*>|<!--.*?-->|<https?://[^>\\s]+>").matcher(t);
        int last = 0;
        while (tag.find()) {
            e.append(escText(t.substring(last, tag.start())));
            String g = tag.group();
            if (g.startsWith("<http")) { String u = g.substring(1, g.length() - 1); e.append("<a href=\"").append(u).append("\">").append(esc(u)).append("</a>"); }
            else e.append(g);
            last = tag.end();
        }
        e.append(escText(t.substring(last)));
        t = e.toString();
        // images then links (links may contain images)
        t = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)").matcher(t).replaceAll("<img src=\"$2\" alt=\"$1\">");
        t = Pattern.compile("\\[((?:[^\\[\\]]|\\[[^\\]]*\\])+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)").matcher(t).replaceAll("<a href=\"$2\">$1</a>");
        t = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__").matcher(t).replaceAll(r -> "<strong>" + (r.group(1) != null ? r.group(1) : r.group(2)) + "</strong>");
        t = Pattern.compile("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])").matcher(t).replaceAll(r -> "<em>" + (r.group(1) != null ? r.group(1) : r.group(2)) + "</em>");
        t = Pattern.compile("~~(.+?)~~").matcher(t).replaceAll("<del>$1</del>");
        for (int k = 0; k < holds.size(); k++) t = t.replace("\u0000" + k + "\u0000", holds.get(k));
        return t;
    }

    private static String escText(String s) {
        return s.replaceAll("&(?!#?\\w+;)", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Full HTML document with theme-aware CSS. */
    public static String page(String bodyHtml, boolean dark) {
        String bg = dark ? "#111215" : "#FFFFFF", fg = dark ? "#ECEAE4" : "#1B1C1F", dim = dark ? "#9B9AA1" : "#6C6B70",
                code = dark ? "#1B1C21" : "#F4F2EE", line = dark ? "#2E2F36" : "#E3E0D9", link = dark ? "#7DB1E0" : "#3F7EB8";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
                "body{background:" + bg + ";color:" + fg + ";font:15px/1.6 -apple-system,Roboto,sans-serif;margin:0;padding:16px;word-wrap:break-word}" +
                "a{color:" + link + "}img{max-width:100%}h1,h2{border-bottom:1px solid " + line + ";padding-bottom:.3em}h1{font-size:1.7em}h2{font-size:1.4em}" +
                "code{background:" + code + ";padding:2px 5px;border-radius:4px;font:13px monospace}pre{background:" + code + ";padding:12px;border-radius:8px;overflow:auto}" +
                "pre code{padding:0;background:none}blockquote{margin:0;padding:0 14px;color:" + dim + ";border-left:4px solid " + line + "}" +
                "table{border-collapse:collapse;display:block;overflow:auto}th,td{border:1px solid " + line + ";padding:6px 10px}th{background:" + code + "}" +
                "hr{border:0;border-top:1px solid " + line + "}li.task{list-style:none;margin-left:-1.2em}</style></head><body>" + bodyHtml + "</body></html>";
    }
}
