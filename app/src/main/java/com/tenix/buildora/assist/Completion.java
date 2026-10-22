package com.tenix.buildora.assist;

/** One suggestion in the code-completion popup. */
public final class Completion {
    public final String label;       // shown, e.g. setText(CharSequence)
    public final String detail;      // right side, e.g. void
    public final String insert;      // text that replaces the typed prefix
    public final int caretBack;      // move the caret this many chars left after inserting (e.g. 1 to sit inside "()")
    public final char kind;          // m method, f field, c class, v variable, k keyword, r resource, a attribute, t tag
    public final String importFqcn;  // add this import when accepted (or null)
    public int score;

    public Completion(String label, String detail, String insert, int caretBack, char kind, String importFqcn, int score) {
        this.label = label; this.detail = detail == null ? "" : detail; this.insert = insert;
        this.caretBack = caretBack; this.kind = kind; this.importFqcn = importFqcn; this.score = score;
    }

    public static Completion simple(String text, String detail, char kind, int score) {
        return new Completion(text, detail, text, 0, kind, null, score);
    }
}
