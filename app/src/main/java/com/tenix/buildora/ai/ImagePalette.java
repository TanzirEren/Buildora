package com.tenix.buildora.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dominant colours of an image, as text, so even models without vision can use an attached picture. Pure Java. */
public final class ImagePalette {
    private ImagePalette() {}

    /** Returns up to {@code k} colours as "#RRGGBB", most common first. Pixels are ARGB ints. */
    public static List<String> dominant(int[] argb, int k) {
        Map<Integer, int[]> bins = new HashMap<>();     // quantised colour -> {count, sumR, sumG, sumB}
        for (int p : argb) {
            if (((p >>> 24) & 0xff) < 128) continue;
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            int[] v = bins.get(key);
            if (v == null) { v = new int[4]; bins.put(key, v); }
            v[0]++; v[1] += r; v[2] += g; v[3] += b;
        }
        List<int[]> all = new ArrayList<>(bins.values());
        Collections.sort(all, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Integer.compare(b[0], a[0]); }
        });
        List<int[]> picked = new ArrayList<>();
        for (int[] v : all) {
            int r = v[1] / v[0], g = v[2] / v[0], b = v[3] / v[0];
            boolean near = false;
            for (int[] q : picked) {
                int qr = q[1] / q[0], qg = q[2] / q[0], qb = q[3] / q[0];
                if (Math.abs(r - qr) + Math.abs(g - qg) + Math.abs(b - qb) < 60) { near = true; break; }
            }
            if (!near) picked.add(v);
            if (picked.size() >= k) break;
        }
        List<String> out = new ArrayList<>();
        for (int[] v : picked) out.add(String.format(Locale.US, "#%02X%02X%02X", v[1] / v[0], v[2] / v[0], v[3] / v[0]));
        return out;
    }

    public static String describe(String name, int w, int h, long bytes, List<String> colors) {
        StringBuilder sb = new StringBuilder("Image ").append(name).append(": ").append(w).append("x").append(h).append(" px, ").append(bytes).append(" bytes");
        if (!colors.isEmpty()) {
            sb.append(". Dominant colours: ");
            for (int i = 0; i < colors.size(); i++) { if (i > 0) sb.append(", "); sb.append(colors.get(i)); }
        }
        if (h > 0) {
            double ratio = (double) w / h;
            sb.append(". Orientation: ").append(ratio > 1.15 ? "landscape" : ratio < 0.87 ? "portrait" : "square-ish");
        }
        return sb.append('.').toString();
    }
}
