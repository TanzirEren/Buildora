package com.tenix.buildora.editor;

import java.util.Arrays;

/**
 * Newline table of a text, rebuilt lazily (one fast pass) after the text changed.
 * Gives logical line numbers by binary search instead of scanning from the top of the file.
 * Plain Java so it can be tested on a JVM.
 */
public final class LineIndex {

    /** Read access to the text without copying it into a String. */
    public interface Source {
        int length();
        void getChars(int start, int end, char[] dst, int dstOffset);
    }

    private char[] buf = new char[0];
    private int[] nl = new int[256];
    private int count;
    private boolean dirty = true;
    private int validLen;

    public void markDirty() { dirty = true; }

    /** The characters of the text as of the last {@link #ensure}; only the first length() entries are valid. */
    public char[] chars() { return buf; }

    public void ensure(Source s) {
        if (!dirty) return;
        int len = s.length();
        if (buf.length < len) buf = new char[Math.max(len + len / 4, 4096)];
        s.getChars(0, len, buf, 0);
        int n = 0;
        char[] b = buf;
        int[] t = nl;
        for (int i = 0; i < len; i++) {
            if (b[i] == '\n') {
                if (n == t.length) { t = Arrays.copyOf(t, t.length * 2); nl = t; }
                t[n++] = i;
            }
        }
        count = n;
        validLen = len;
        dirty = false;
    }

    /** 1-based logical line that contains the offset. */
    public int lineOf(Source s, int off) {
        ensure(s);
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (nl[mid] < off) lo = mid + 1; else hi = mid;
        }
        return lo + 1;
    }

    public int lineCount(Source s) { ensure(s); return count + 1; }

    /** Offset of the first character of a 1-based line (clamped to the existing lines). */
    public int lineStart(Source s, int line) {
        ensure(s);
        int l = Math.max(1, Math.min(line, count + 1));
        return l <= 1 ? 0 : nl[l - 2] + 1;
    }

    public int length() { return validLen; }
}
