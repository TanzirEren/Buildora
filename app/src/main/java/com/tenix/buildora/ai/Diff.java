package com.tenix.buildora.ai;

import java.util.ArrayList;
import java.util.List;

/** Line diff (prefix/suffix trim + LCS on the middle). Each entry starts with ' ', '+' or '-'. */
public final class Diff {
    private Diff() {}

    public static List<String> lines(String oldText, String newText) {
        String[] a = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] b = newText.isEmpty() ? new String[0] : newText.split("\n", -1);
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) suf++;
        int n = a.length - pre - suf, m = b.length - pre - suf;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) out.add(" " + a[i]);
        if ((long) n * m > 4_000_000L) {
            for (int i = 0; i < n; i++) out.add("-" + a[pre + i]);
            for (int j = 0; j < m; j++) out.add("+" + b[pre + j]);
        } else {
            int[][] dp = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--)
                for (int j = m - 1; j >= 0; j--)
                    dp[i][j] = a[pre + i].equals(b[pre + j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            int i = 0, j = 0;
            while (i < n && j < m) {
                if (a[pre + i].equals(b[pre + j])) { out.add(" " + a[pre + i]); i++; j++; }
                else if (dp[i + 1][j] >= dp[i][j + 1]) out.add("-" + a[pre + i++]);
                else out.add("+" + b[pre + j++]);
            }
            while (i < n) out.add("-" + a[pre + i++]);
            while (j < m) out.add("+" + b[pre + j++]);
        }
        for (int i = suf; i > 0; i--) out.add(" " + a[a.length - i]);
        return out;
    }

    public static int[] stats(List<String> d) {
        int add = 0, del = 0;
        for (String s : d) { if (s.startsWith("+")) add++; else if (s.startsWith("-")) del++; }
        return new int[]{add, del};
    }

    /** Diff trimmed to the changed regions with 2 lines of context. */
    public static List<String> compact(List<String> d) {
        int n = d.size();
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) if (d.get(i).charAt(0) != ' ') for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) keep[k] = true;
        List<String> out = new ArrayList<>();
        boolean gap = false;
        for (int i = 0; i < n; i++) {
            if (keep[i]) { if (gap) out.add("@…"); out.add(d.get(i)); gap = false; } else gap = true;
        }
        return out;
    }
}
