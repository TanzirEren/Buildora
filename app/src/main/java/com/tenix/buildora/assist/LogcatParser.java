package com.tenix.buildora.assist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses `logcat -v threadtime` lines. */
public final class LogcatParser {
    private LogcatParser() {}

    public static final class Line {
        public String time = "", tag = "", msg = "", raw = "";
        public char level = 'I';
        public int pid, tid;
    }

    private static final Pattern P = Pattern.compile("^(\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:\\s(.*)$");

    public static Line parse(String raw) {
        Line l = new Line();
        l.raw = raw;
        Matcher m = P.matcher(raw);
        if (m.matches()) {
            l.time = m.group(1);
            l.pid = Integer.parseInt(m.group(2));
            l.tid = Integer.parseInt(m.group(3));
            l.level = m.group(4).charAt(0);
            l.tag = m.group(5);
            l.msg = m.group(6);
        } else {
            l.msg = raw;
        }
        return l;
    }

    public static int levelRank(char c) {
        switch (c) { case 'V': return 0; case 'D': return 1; case 'I': return 2; case 'W': return 3; case 'E': return 4; default: return 5; }
    }

    /** First line of a stack trace, for notifications: "java.lang.NullPointerException: ..." */
    public static String firstException(String trace) {
        for (String l : trace.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("at ")) return t.length() > 140 ? t.substring(0, 140) : t;
        }
        return trace.length() > 140 ? trace.substring(0, 140) : trace;
    }
}
