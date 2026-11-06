package com.tenix.buildora.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import com.tenix.buildora.assist.Completion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code editor: line numbers, syntax highlighting, red error lines, undo/redo,
 * auto indent / auto close, pinch-to-zoom (two fingers) like MT Manager.
 */
public class CodeEditText extends EditText {

    public interface Listener {
        void onContentChanged();
        void onCaretMoved(int line, int col);
        void onLint(List<Linter.Problem> problems);
    }

    // ---- undo stack (one per open file)
    public static final class UndoStack {
        static final class Edit {
            final int start; final String removed; final String added; final long time;
            Edit(int s, String r, String a, long t) { start = s; removed = r; added = a; time = t; }
        }
        final ArrayList<Edit> undo = new ArrayList<>();
        final ArrayList<Edit> redo = new ArrayList<>();
        public void clear() { undo.clear(); redo.clear(); }
    }

    /** Asks the host for suggestions at the caret; the host answers on the UI thread. */
    public interface Completer {
        void request(String text, int caret, boolean force, CompletionResult cb);
    }
    public interface CompletionResult { void onResult(java.util.List<Completion> items); }

    private Completer completer;
    private CompletionPopup popup;
    private boolean completionEnabled = true;
    private final Runnable completionRunnable = new Runnable() { @Override public void run() { triggerCompletion(false); } };
    private long lastEditAt;

    private Listener listener;
    private UndoStack stack = new UndoStack();
    private EditorScheme scheme = EditorScheme.get(0);
    private String lang = "text";
    private boolean recording = true;
    private boolean internalEdit = false;

    private final Paint gutterBgPaint = new Paint();
    private final Paint gutterTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineBgPaint = new Paint();
    private final Paint errPaint = new Paint();
    private final Paint divPaint = new Paint();
    private final Map<Integer, String> errorLines = new HashMap<>();

    private boolean showLineNumbers = true;
    private boolean highlightLine = true;
    private boolean autoIndent = true;
    private boolean autoClose = true;
    private int tabSize = 4;
    private boolean useSpaces = true;
    private int gutterWidth;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hlRunnable = new Runnable() { @Override public void run() { rehighlight(); } };
    private final Runnable lintRunnable = new Runnable() { @Override public void run() { runLint(); } };
    private boolean liveLint = true;

    // pending-edit capture for undo + auto features
    private String capRemoved = "";
    private int capStart;
    private int lastInsertStart = -1, lastInsertCount = 0;
    private String lastInserted = "";

    // ---- line index (newline offsets), rebuilt lazily once per text change
    private final LineIndex lines = new LineIndex();
    private final LineIndex.Source textSource = new LineIndex.Source() {
        @Override public int length() { return CodeEditText.this.length(); }
        @Override public void getChars(int start, int end, char[] dst, int off) { CodeEditText.this.getText().getChars(start, end, dst, off); }
    };
    private int lastDigits = -1;
    private float lastGutterTextSize = -1f;
    private int lastPadLeft = Integer.MIN_VALUE;

    // ---- windowed highlighting for big files
    private static final int FULL_HL_LIMIT = 30000;     // chars; below this the whole file is highlighted
    private int hlFrom = 0, hlTo = 0;
    private boolean scrollHlPending = false;
    private final Runnable scrollHlRunnable = new Runnable() {
        @Override public void run() { scrollHlPending = false; rehighlight(); }
    };

    // ---- background lint
    private int editVersion = 0;
    private static final java.util.concurrent.ExecutorService LINT_POOL = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Paint squigglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path squigglePath = new android.graphics.Path();

    private final ScaleGestureDetector scale;
    private float zoomSize;
    private boolean ignoreUntilUp = false;
    private ZoomListener zoomListener;

    public interface ZoomListener { void onZoomEnd(float sp); }

    public CodeEditText(Context c) { this(c, null); }

    public CodeEditText(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(0);
        setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        setHorizontallyScrolling(true);
        setHorizontalScrollBarEnabled(true);
        setVerticalScrollBarEnabled(true);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        setTypeface(Typeface.MONOSPACE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        setLineSpacing(0, 1.12f);
        setFilters(new android.text.InputFilter[0]);
        // never put the whole file into the saved-instance Bundle, and keep autofill out of every keystroke
        setFreezesText(false);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        squigglePaint.setStyle(Paint.Style.STROKE);
        errPaint.setStyle(Paint.Style.FILL);
        divPaint.setStrokeWidth(1f);
        zoomSize = 12;

        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomSize = Math.max(8f, Math.min(28f, zoomSize * d.getScaleFactor()));
                setTextSize(TypedValue.COMPLEX_UNIT_SP, zoomSize);
                updateGutter();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                if (zoomListener != null) zoomListener.onZoomEnd(zoomSize);
            }
        });

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (internalEdit) return;
                capStart = start;
                lastEditAt = android.os.SystemClock.uptimeMillis();
                capRemoved = s.subSequence(start, start + count).toString();
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                lines.markDirty();
                editVersion++;
                shiftHlWindow(start, before, count);
                if (internalEdit) return;
                String added = s.subSequence(start, start + count).toString();
                lastInsertStart = start;
                lastInsertCount = count;
                lastInserted = added;
                if (recording && (!capRemoved.isEmpty() || !added.isEmpty())) pushEdit(capStart, capRemoved, added);
            }
            @Override public void afterTextChanged(Editable e) {
                if (internalEdit) return;
                if (autoIndent || autoClose) smartEdit(e);
                lastEditAt = android.os.SystemClock.uptimeMillis();
                scheduleCompletion();
                scheduleHighlight();
                updateGutter();
                if (listener != null) listener.onContentChanged();
            }
        });
    }

    // ------------------------------------------------------------------ configuration

    public void setListener(Listener l) { listener = l; }
    public void setZoomListener(ZoomListener z) { zoomListener = z; }
    public void setUndoStack(UndoStack s) { stack = s; }
    public UndoStack getUndoStack() { return stack; }
    public String getLang() { return lang; }

    public void configure(boolean lineNumbers, boolean hlLine, boolean indent, boolean close,
                          int tab, boolean spaces, boolean wrap, boolean lint, float sp) {
        showLineNumbers = lineNumbers;
        lastDigits = -1;
        lastPadLeft = Integer.MIN_VALUE;
        highlightLine = hlLine;
        autoIndent = indent;
        autoClose = close;
        tabSize = tab;
        useSpaces = spaces;
        liveLint = lint;
        setHorizontallyScrolling(!wrap);
        zoomSize = sp;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        updateGutter();
        invalidate();
    }

    public void setFontSizeSp(float sp) {
        zoomSize = sp;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        updateGutter();
    }

    public void applyScheme(EditorScheme s) {
        scheme = s;
        setTextColor(s.fg);
        setHighlightColor(s.selection);
        gutterBgPaint.setColor(s.gutterBg);
        gutterTextPaint.setColor(s.gutterFg);
        lineBgPaint.setColor(s.curLine);
        divPaint.setColor(s.gutterFg & 0x00FFFFFF | 0x33000000);
        errPaint.setColor(s.error & 0x00FFFFFF | 0x2A000000);
        scheduleHighlight();
        invalidate();
    }

    @Override public void setTypeface(Typeface tf) {
        super.setTypeface(tf);
        // TextView's constructor calls this before our fields exist
        if (gutterTextPaint != null) gutterTextPaint.setTypeface(tf);
    }

    /** Replace all text (file open). Clears undo history. */
    public void load(String text, String language) {
        lang = language;
        internalEdit = true;
        recording = false;
        setText(text);
        recording = true;
        internalEdit = false;
        errorLines.clear();
        hlFrom = hlTo = 0;
        updateGutter();
        rehighlight();
        if (liveLint) scheduleLint();
    }

    // ------------------------------------------------------------------ code completion

    public void setCompleter(Completer c, CompletionPopup p) { completer = c; popup = p; }
    public void setCompletionEnabled(boolean on) { completionEnabled = on; if (!on) dismissCompletion(); }
    public void dismissCompletion() { handler.removeCallbacks(completionRunnable); if (popup != null) popup.dismiss(); }
    public boolean completionShowing() { return popup != null && popup.isShowing(); }

    private void scheduleCompletion() {
        if (completer == null || !completionEnabled || internalEdit) return;
        handler.removeCallbacks(completionRunnable);
        String ins = lastInserted == null ? "" : lastInserted;
        boolean trigger = ins.length() == 1 && (Character.isJavaIdentifierPart(ins.charAt(0)) || ".<:@/+_-\"".indexOf(ins.charAt(0)) >= 0);
        boolean keepOpen = completionShowing() && capRemoved.length() == 1 && ins.isEmpty();   // backspace inside a word
        if (trigger || keepOpen) handler.postDelayed(completionRunnable, 110);
        else dismissCompletion();
    }

    /** Opens the suggestions now (also bound to the symbol-bar button). */
    public void triggerCompletion(final boolean force) {
        if (completer == null || (!completionEnabled && !force) || getLayout() == null) return;
        if (getSelectionStart() != getSelectionEnd() || length() > 400000) { dismissCompletion(); return; }
        final int caret = getSelectionStart();
        final String text = getText().toString();
        completer.request(text, caret, force, new CompletionResult() {
            @Override public void onResult(java.util.List<Completion> items) {
                if (getSelectionStart() != caret || popup == null) return;
                popup.show(items);
            }
        });
    }

    /** Start of the word being completed (what an accepted suggestion replaces). */
    public int completionStart() {
        int caret = getSelectionStart();
        Editable e = getText();
        int p = caret;
        boolean xml = lang.equals("xml");
        while (p > 0) {
            char c = e.charAt(p - 1);
            if (Character.isJavaIdentifierPart(c) || (xml && ":@+/.-".indexOf(c) >= 0)) p--; else break;
        }
        if (xml && p < caret && e.charAt(p) == '/' && p > 0 && e.charAt(p - 1) == '<') p++;
        return p;
    }

    /** Inserts a suggestion, moves the caret, and adds its import when needed. */
    public void acceptCompletion(Completion c) {
        int caret = getSelectionStart();
        int start = completionStart();
        Editable e = getText();
        String insert = c.insert;
        e.replace(start, caret, insert);
        int pos = start + insert.length() - c.caretBack;
        if (c.importFqcn != null && lang.equals("java")) {
            String old = e.toString();
            String neu = com.tenix.buildora.build.ImportIndex.addImport(old, c.importFqcn);
            if (!neu.equals(old)) {
                int d = neu.length() - old.length(), i = 0;
                while (i < old.length() && old.charAt(i) == neu.charAt(i)) i++;
                e.insert(i, neu.substring(i, i + d));
                if (i <= pos) pos += d;
            }
        }
        setSelection(Math.max(0, Math.min(pos, e.length())));
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent ev) {
        if (completionShowing() && (keyCode == android.view.KeyEvent.KEYCODE_TAB || keyCode == android.view.KeyEvent.KEYCODE_ENTER) && popup.first() != null) {
            Completion c = popup.first();
            dismissCompletion();
            acceptCompletion(c);
            return true;
        }
        if (completionShowing() && keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) { dismissCompletion(); return true; }
        return super.onKeyDown(keyCode, ev);
    }

    // ------------------------------------------------------------------ errors

    public void setErrors(Map<Integer, String> lines) {
        errorLines.clear();
        if (lines != null) errorLines.putAll(lines);
        invalidate();
    }

    public String errorAtCaret() {
        return errorLines.get(currentLine());
    }

    public void gotoFirstError() {
        int min = Integer.MAX_VALUE;
        for (int l : errorLines.keySet()) min = Math.min(min, l);
        if (min != Integer.MAX_VALUE) gotoLine(min);
    }

    // ------------------------------------------------------------------ undo / redo

    private void pushEdit(int start, String removed, String added) {
        long now = System.currentTimeMillis();
        if (!stack.undo.isEmpty() && removed.isEmpty() && added.length() == 1 && !added.equals("\n")) {
            UndoStack.Edit last = stack.undo.get(stack.undo.size() - 1);
            if (last.removed.isEmpty() && now - last.time < 900 && last.start + last.added.length() == start
                    && !last.added.endsWith("\n") && last.added.length() < 60) {
                stack.undo.set(stack.undo.size() - 1, new UndoStack.Edit(last.start, "", last.added + added, now));
                stack.redo.clear();
                return;
            }
        }
        stack.undo.add(new UndoStack.Edit(start, removed, added, now));
        if (stack.undo.size() > 800) stack.undo.remove(0);
        stack.redo.clear();
    }

    public boolean canUndo() { return !stack.undo.isEmpty(); }
    public boolean canRedo() { return !stack.redo.isEmpty(); }

    public void undo() {
        if (stack.undo.isEmpty()) return;
        UndoStack.Edit e = stack.undo.remove(stack.undo.size() - 1);
        applyRaw(e.start, e.added.length(), e.removed);
        stack.redo.add(e);
        setSelection(Math.min(length(), e.start + e.removed.length()));
        afterRaw();
    }

    public void redo() {
        if (stack.redo.isEmpty()) return;
        UndoStack.Edit e = stack.redo.remove(stack.redo.size() - 1);
        applyRaw(e.start, e.removed.length(), e.added);
        stack.undo.add(e);
        setSelection(Math.min(length(), e.start + e.added.length()));
        afterRaw();
    }

    private void applyRaw(int start, int len, String with) {
        internalEdit = true;
        Editable ed = getText();
        int s = Math.max(0, Math.min(start, ed.length()));
        int e = Math.max(s, Math.min(s + len, ed.length()));
        ed.replace(s, e, with);
        internalEdit = false;
    }

    private void afterRaw() {
        rehighlight();
        updateGutter();
        if (liveLint) scheduleLint();
        if (listener != null) listener.onContentChanged();
    }

    // ------------------------------------------------------------------ editing helpers

    public void insertAtCursor(String s) {
        Editable e = getText();
        int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        int b = Math.max(0, Math.max(getSelectionStart(), getSelectionEnd()));
        e.replace(a, b, s);
    }

    public void insertTab() {
        if (useSpaces) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tabSize; i++) sb.append(' ');
            insertAtCursor(sb.toString());
        } else insertAtCursor("\t");
    }

    public void replaceAll(String text) {
        Editable e = getText();
        e.replace(0, e.length(), text);
    }

    public void gotoLine(int line) {
        Layout l = getLayout();
        int idx = lines.lineStart(textSource, line);
        setSelection(idx);
        requestFocus();
        if (l != null) {
            int vl = l.getLineForOffset(idx);
            int y = l.getLineTop(vl) - getHeight() / 3;
            scrollTo(0, Math.max(0, y));
        }
    }

    public int currentLine() {
        return lineOfOffset(Math.max(0, getSelectionStart()));
    }

    public int currentCol() {
        int sel = Math.max(0, Math.min(getSelectionStart(), length()));
        int start = lines.lineStart(textSource, lines.lineOf(textSource, sel));
        return sel - start + 1;
    }

    // ------------------------------------------------------------------ line index

    private void ensureLines() { lines.ensure(textSource); }
    private int lineOfOffset(int off) { return lines.lineOf(textSource, off); }
    private int logicalLineCount() { return lines.lineCount(textSource); }

    // ------------------------------------------------------------------ find / replace

    public int find(String query, boolean forward, boolean matchCase, boolean regex) {
        if (query.isEmpty()) return 0;
        String text = getText().toString();
        Pattern p;
        try {
            int flags = (matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) | (regex ? 0 : Pattern.LITERAL);
            p = Pattern.compile(query, flags);
        } catch (Exception ex) {
            return -1;
        }
        Matcher m = p.matcher(text);
        int total = 0;
        int selS = Math.min(getSelectionStart(), getSelectionEnd());
        int selE = Math.max(getSelectionStart(), getSelectionEnd());
        int[] next = null, prev = null, first = null, last = null;
        while (m.find()) {
            total++;
            int[] cur = {m.start(), m.end()};
            if (first == null) first = cur;
            last = cur;
            if (cur[0] >= selE && next == null) next = cur;
            if (cur[1] <= selS) prev = cur;
        }
        int[] target = forward ? (next != null ? next : first) : (prev != null ? prev : last);
        if (target != null) {
            setSelection(target[0], target[1]);
            requestFocus();
            ensureVisible(target[0]);
        }
        return total;
    }

    public int replaceCurrent(String query, String with, boolean matchCase, boolean regex) {
        int a = Math.min(getSelectionStart(), getSelectionEnd());
        int b = Math.max(getSelectionStart(), getSelectionEnd());
        if (a == b) return find(query, true, matchCase, regex);
        String sel = getText().subSequence(a, b).toString();
        boolean ok;
        try {
            int flags = (matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) | (regex ? 0 : Pattern.LITERAL);
            ok = Pattern.compile(query, flags).matcher(sel).matches();
        } catch (Exception e) { return -1; }
        if (ok) {
            String rep = with;
            if (regex) {
                try {
                    int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    rep = Pattern.compile(query, flags).matcher(sel).replaceFirst(with);
                } catch (Exception ignored) { }
            }
            getText().replace(a, b, rep);
        }
        return find(query, true, matchCase, regex);
    }

    public int replaceAllMatches(String query, String with, boolean matchCase, boolean regex) {
        if (query.isEmpty()) return 0;
        String text = getText().toString();
        try {
            int flags = (matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) | (regex ? 0 : Pattern.LITERAL);
            Matcher m = Pattern.compile(query, flags).matcher(text);
            int n = 0;
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                n++;
                m.appendReplacement(sb, regex ? with : Matcher.quoteReplacement(with));
            }
            m.appendTail(sb);
            if (n > 0) replaceAll(sb.toString());
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    private void ensureVisible(int offset) {
        Layout l = getLayout();
        if (l == null) return;
        int line = l.getLineForOffset(offset);
        int top = l.getLineTop(line);
        if (top < getScrollY() || top > getScrollY() + getHeight() - getLineHeight() * 2) {
            scrollTo(getScrollX(), Math.max(0, top - getHeight() / 3));
        }
    }

    /** Simple re-indent based on bracket depth (Java/JSON) or tag depth (XML). */
    public void formatDocument() {
        String t = getText().toString();
        String out = lang.equals("xml") ? Formatter.xml(t, indentUnit()) : Formatter.braces(t, indentUnit());
        if (!out.equals(t)) {
            int caret = Math.min(getSelectionStart(), out.length());
            replaceAll(out);
            setSelection(Math.max(0, Math.min(caret, out.length())));
        }
    }

    private String indentUnit() {
        if (!useSpaces) return "\t";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tabSize; i++) sb.append(' ');
        return sb.toString();
    }

    // ------------------------------------------------------------------ smart typing

    private void smartEdit(Editable e) {
        if (lastInsertCount != 1 || lastInsertStart < 0 || lastInsertStart >= e.length()) return;
        char c = lastInserted.isEmpty() ? 0 : lastInserted.charAt(0);
        int pos = lastInsertStart + 1;
        lastInsertCount = 0;
        internalEdit = false;
        if (c == '\n' && autoIndent) {
            int ls = lastInsertStart;
            int prevStart = ls;
            while (prevStart > 0 && e.charAt(prevStart - 1) != '\n') prevStart--;
            StringBuilder ind = new StringBuilder();
            for (int i = prevStart; i < ls && (e.charAt(i) == ' ' || e.charAt(i) == '\t'); i++) ind.append(e.charAt(i));
            char before = 0;
            for (int i = ls - 1; i >= prevStart; i--) {
                char ch = e.charAt(i);
                if (ch != ' ' && ch != '\t') { before = ch; break; }
            }
            boolean opensBrace = before == '{' || before == '(' || before == '[';
            boolean nextCloses = pos < e.length() && (e.charAt(pos) == '}' || e.charAt(pos) == ')' || e.charAt(pos) == ']');
            String extra = opensBrace ? indentUnit() : "";
            if (lang.equals("xml") && before == '>' && !lang.isEmpty()) {
                int lt = ls - 1;
                while (lt >= prevStart && e.charAt(lt) != '<') lt--;
                if (lt < prevStart) lt = -1;
                if (lt >= prevStart && lt + 1 < e.length() && e.charAt(lt + 1) != '/' && e.charAt(lt + 1) != '?'
                        && e.charAt(lt + 1) != '!' && !(ls >= 2 && e.charAt(ls - 2) == '/')) {
                    extra = indentUnit();
                    nextCloses = pos < e.length() && e.charAt(pos) == '<' && pos + 1 < e.length() && e.charAt(pos + 1) == '/';
                }
            }
            String ins = ind + extra;
            if (opensBrace && nextCloses || (lang.equals("xml") && !extra.isEmpty() && nextCloses)) {
                String full = ins + "\n" + ind;
                e.insert(pos, full);
                setSelection(pos + ins.length());
            } else if (!ins.isEmpty()) {
                e.insert(pos, ins);
                setSelection(pos + ins.length());
            }
            return;
        }
        if (!autoClose) return;
        char next = pos < e.length() ? e.charAt(pos) : 0;
        boolean nextFree = next == 0 || Character.isWhitespace(next) || next == ')' || next == ']' || next == '}' || next == ';' || next == ',' || next == '<';
        if (c == '(' && nextFree) { e.insert(pos, ")"); setSelection(pos); }
        else if (c == '[' && nextFree) { e.insert(pos, "]"); setSelection(pos); }
        else if (c == '{' && nextFree) { e.insert(pos, "}"); setSelection(pos); }
        else if (c == '"' && nextFree && !lang.equals("text")) {
            char prev = lastInsertStart > 0 ? e.charAt(lastInsertStart - 1) : 0;
            if (prev != '"' && prev != '\\' && !Character.isLetterOrDigit(prev)) { e.insert(pos, "\""); setSelection(pos); }
        } else if ((c == ')' || c == ']' || c == '}') && next == c) {
            // typed a closing char right before an identical one: skip over it
            e.delete(pos, pos + 1);
        } else if (c == '>' && lang.equals("xml")) {
            autoCloseTag(e, pos);
        }
    }

    private void autoCloseTag(Editable e, int pos) {
        int lt = pos - 2;
        int floor = Math.max(0, pos - 4000);
        while (lt >= floor && e.charAt(lt) != '<') lt--;
        if (lt < floor) return;
        String inner = e.subSequence(lt + 1, pos - 1).toString();
        if (inner.isEmpty() || inner.startsWith("/") || inner.startsWith("?") || inner.startsWith("!") || inner.endsWith("/")) return;
        if (inner.indexOf('<') >= 0) return;
        int end = 0;
        while (end < inner.length() && !Character.isWhitespace(inner.charAt(end))) end++;
        String tag = inner.substring(0, end);
        if (tag.isEmpty()) return;
        String closing = "</" + tag;
        int lim = Math.min(e.length(), pos + closing.length());
        if (e.subSequence(pos, lim).toString().equals(closing)) return;
        e.insert(pos, closing + ">");
        setSelection(pos);
    }

    // ------------------------------------------------------------------ highlight / lint scheduling

    private void scheduleHighlight() {
        handler.removeCallbacks(hlRunnable);
        handler.postDelayed(hlRunnable, length() > 60000 ? 260 : 160);
        if (liveLint) scheduleLint();
    }

    private void scheduleLint() {
        handler.removeCallbacks(lintRunnable);
        handler.postDelayed(lintRunnable, length() > 100000 ? 1400 : 700);
    }

    /** Keeps the highlighted window glued to its text when characters are inserted or deleted. */
    private void shiftHlWindow(int start, int before, int count) {
        if (hlTo <= hlFrom) return;
        int delta = count - before;
        if (start + before <= hlFrom) { hlFrom = Math.max(0, hlFrom + delta); hlTo = Math.max(hlFrom, hlTo + delta); }
        else if (start <= hlTo) hlTo = Math.max(hlFrom, hlTo + delta);
        int len = length() ;
        if (hlTo > len) hlTo = len;
        if (hlFrom > hlTo) hlFrom = hlTo;
    }

    /** Big files are only coloured around the viewport; this asks for a refresh when the viewport nears the window edge. */
    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (length() <= FULL_HL_LIMIT || lang.equals("text") || scrollHlPending) return;
        Layout lay = getLayout();
        if (lay == null || lay.getLineCount() == 0) return;
        int vf = lay.getLineForVertical(getScrollY());
        int vl = lay.getLineForVertical(getScrollY() + getHeight());
        boolean needTop = hlFrom > 0 && vf - lay.getLineForOffset(Math.min(hlFrom, length())) < 40;
        boolean needBottom = hlTo < length() && lay.getLineForOffset(Math.min(hlTo, length())) - vl < 40;
        if (needTop || needBottom || hlTo <= hlFrom) {
            scrollHlPending = true;
            handler.postDelayed(scrollHlRunnable, 70);
        }
    }

    /** Start of the window must not fall inside a block comment, otherwise its colours would be wrong. */
    private int adjustForBlockComment(int from) {
        String open, close;
        if (lang.equals("java")) { open = "/*"; close = "*/"; }
        else if (lang.equals("xml")) { open = "<!--"; close = "-->"; }
        else return from;
        ensureLines();
        char[] b = lines.chars();
        int floor = Math.max(0, from - 40000);
        for (int i = Math.min(from, length()) - 1; i >= floor; i--) {
            if (b[i] == close.charAt(close.length() - 1) && i - close.length() + 1 >= 0
                    && regionIs(b, i - close.length() + 1, close)) return from;       // closed comment before us
            if (b[i] == open.charAt(open.length() - 1) && i - open.length() + 1 >= 0
                    && regionIs(b, i - open.length() + 1, open)) return i - open.length() + 1;   // we are inside a comment
        }
        return from;
    }

    private static boolean regionIs(char[] b, int at, String what) {
        for (int k = 0; k < what.length(); k++) if (b[at + k] != what.charAt(k)) return false;
        return true;
    }

    public void rehighlight() {
        Editable e = getText();
        int len = e.length();
        if (lang.equals("text")) { Highlighter.clear(e, 0, len); hlFrom = hlTo = 0; return; }
        int from = 0, to = len;
        Layout l = getLayout();
        if (len > FULL_HL_LIMIT && l != null && l.getLineCount() > 0) {
            int fl = Math.max(0, l.getLineForVertical(getScrollY()) - 160);
            int ll = Math.min(l.getLineCount() - 1, l.getLineForVertical(getScrollY() + getHeight()) + 160);
            from = l.getLineStart(fl);
            to = l.getLineEnd(ll);
            ensureLines();
            from = adjustForBlockComment(from);
        } else if (len > FULL_HL_LIMIT) {
            to = FULL_HL_LIMIT;
        }
        // drop colours of the previous window as well, so nothing stale is left behind
        int clearFrom = Math.min(from, hlTo > hlFrom ? hlFrom : from);
        int clearTo = Math.max(to, hlTo > hlFrom ? hlTo : to);
        internalEdit = true;
        try {
            Highlighter.clear(e, Math.max(0, clearFrom), Math.min(len, clearTo));
            Highlighter.apply(e, lang, scheme, from, to, false);
        } finally { internalEdit = false; }
        hlFrom = from;
        hlTo = to;
    }

    private void runLint() {
        if (!liveLint || listener == null) return;
        final String text = getText().toString();
        final String language = lang;
        final int version = editVersion;
        LINT_POOL.execute(new Runnable() {
            @Override public void run() {
                final List<Linter.Problem> probs;
                try { probs = Linter.check(text, language); } catch (Throwable t) { return; }
                final Map<Integer, String> m = new HashMap<>();
                for (Linter.Problem p : probs) m.put(p.line, p.message);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (version != editVersion || listener == null) return;   // text changed meanwhile; a newer lint is on its way
                        setErrors(m);
                        listener.onLint(probs);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ gutter & drawing

    /**
     * Bottom padding must stay tiny: TextView clips the text at the bottom padding, so a big value
     * (it used to be 80dp) left a blank band at the bottom of the editor where no code was drawn.
     */
    private static final int PAD_BOTTOM_DP = 6;

    private void updateGutter() {
        if (!showLineNumbers) {
            gutterWidth = 0;
            applyPadLeft(UIDp(8));
            return;
        }
        int digits = Math.max(2, String.valueOf(logicalLineCount()).length());
        float ts = getTextSize();
        if (digits != lastDigits || ts != lastGutterTextSize) {
            lastDigits = digits;
            lastGutterTextSize = ts;
            gutterTextPaint.setTextSize(ts * 0.85f);
            gutterTextPaint.setTypeface(getTypeface());
            gutterWidth = Math.round(gutterTextPaint.measureText("0") * digits + UIDp(14));
        }
        applyPadLeft(gutterWidth + UIDp(6));
    }

    /** setPadding forces a re-layout, so it is only called when the value really changes. */
    private void applyPadLeft(int left) {
        if (left == lastPadLeft) return;
        lastPadLeft = left;
        setPadding(left, UIDp(4), UIDp(24), UIDp(PAD_BOTTOM_DP));
    }

    private int UIDp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (completionShowing() && android.os.SystemClock.uptimeMillis() - lastEditAt > 250) dismissCompletion();
        if (listener != null && getLayout() != null) listener.onCaretMoved(currentLine(), currentCol());
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        Layout l = getLayout();
        if (l == null) { super.onDraw(canvas); return; }
        int scrollX = getScrollX(), scrollY = getScrollY();
        int w = getWidth(), h = getHeight();
        int top = getExtendedPaddingTop();
        int firstVis = l.getLineForVertical(scrollY);
        int lastVis = l.getLineForVertical(scrollY + h);
        CharSequence text = getText();

        int caretVisual = -1;
        if (highlightLine && getSelectionStart() == getSelectionEnd() && getSelectionStart() >= 0) {
            caretVisual = l.getLineForOffset(getSelectionStart());
        }

        // logical line number of every visible visual line: binary search, no scan from the top of the file
        int count = Math.max(0, lastVis - firstVis + 1);
        int[] logicalNums = new int[count];
        boolean[] starts = new boolean[count];
        for (int i = firstVis; i <= lastVis; i++) {
            int ls = l.getLineStart(i);
            starts[i - firstVis] = ls == 0 || text.charAt(ls - 1) == '\n';
            logicalNums[i - firstVis] = lineOfOffset(ls);
        }

        // backgrounds
        canvas.drawColor(scheme.bg);
        for (int i = firstVis; i <= lastVis; i++) {
            int y1 = top + l.getLineTop(i), y2 = top + l.getLineBottom(i);
            if (i == caretVisual) canvas.drawRect(scrollX, y1, scrollX + w, y2, lineBgPaint);
            if (!errorLines.isEmpty() && errorLines.containsKey(logicalNums[i - firstVis])) canvas.drawRect(scrollX, y1, scrollX + w, y2, errPaint);
        }

        super.onDraw(canvas);

        // squiggle under error lines (only the visible part of each line)
        if (!errorLines.isEmpty()) {
            squigglePaint.setColor(scheme.error);
            squigglePaint.setStrokeWidth(UIDp(1.2f));
            float step = UIDp(3), amp = UIDp(2);
            for (int i = firstVis; i <= lastVis; i++) {
                if (!errorLines.containsKey(logicalNums[i - firstVis])) continue;
                int y = top + l.getLineBottom(i) - UIDp(2);
                float x1 = l.getLineLeft(i) + getCompoundPaddingLeft();
                float x2 = Math.max(x1 + UIDp(12), l.getLineRight(i) + getCompoundPaddingLeft());
                x1 = Math.max(x1, scrollX - step);
                x2 = Math.min(x2, scrollX + w + step);
                if (x2 <= x1) continue;
                squigglePath.rewind();
                squigglePath.moveTo(x1, y);
                boolean up = true;
                for (float x = x1; x < x2; x += step) {
                    squigglePath.lineTo(x + step, up ? y - amp : y);
                    up = !up;
                }
                canvas.drawPath(squigglePath, squigglePaint);
            }
        }

        if (showLineNumbers) {
            canvas.drawRect(scrollX, scrollY, scrollX + gutterWidth, scrollY + h, gutterBgPaint);
            canvas.drawLine(scrollX + gutterWidth, scrollY, scrollX + gutterWidth, scrollY + h, divPaint);
            gutterTextPaint.setTextAlign(Paint.Align.RIGHT);
            for (int i = firstVis; i <= lastVis; i++) {
                if (!starts[i - firstVis]) continue;
                int num = logicalNums[i - firstVis];
                boolean err = errorLines.containsKey(num);
                gutterTextPaint.setColor(err ? scheme.error : (i == caretVisual ? scheme.fg : scheme.gutterFg));
                canvas.drawText(String.valueOf(num), scrollX + gutterWidth - UIDp(7),
                        top + l.getLineBaseline(i), gutterTextPaint);
            }
        }
    }

    // ------------------------------------------------------------------ two finger zoom

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        scale.onTouchEvent(ev);
        if (a == MotionEvent.ACTION_POINTER_DOWN && !ignoreUntilUp) {
            MotionEvent c = MotionEvent.obtain(ev);
            c.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(c);
            c.recycle();
            ignoreUntilUp = true;
        }
        if (ignoreUntilUp) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false;
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // ------------------------------------------------------------------ formatter

    static final class Formatter {
        static String braces(String t, String unit) {
            StringBuilder out = new StringBuilder();
            int depth = 0;
            boolean inBlock = false;
            for (String raw : t.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) { out.append('\n'); continue; }
                if (inBlock) {
                    out.append(repeat(unit, depth)).append(' ').append(line).append('\n');
                    if (line.contains("*/")) inBlock = false;
                    continue;
                }
                int lead = 0;
                while (lead < line.length() && (line.charAt(lead) == '}' || line.charAt(lead) == ')' || line.charAt(lead) == ']')) lead++;
                int d = Math.max(0, depth - lead);
                out.append(repeat(unit, d)).append(line).append('\n');
                if (line.startsWith("/*") && !line.contains("*/")) inBlock = true;
                depth = Math.max(0, depth + count(line));
            }
            if (out.length() > 0 && !t.endsWith("\n")) out.setLength(out.length() - 1);
            return out.toString();
        }

        private static int count(String line) {
            int n = 0;
            boolean str = false;
            char q = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (str) { if (c == '\\') i++; else if (c == q) str = false; continue; }
                if (c == '"' || c == '\'') { str = true; q = c; continue; }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                if (c == '{' || c == '(' || c == '[') n++;
                else if (c == '}' || c == ')' || c == ']') n--;
            }
            return n;
        }

        static String xml(String t, String unit) {
            StringBuilder out = new StringBuilder();
            int depth = 0;
            boolean inTag = false, inComment = false, closing = false, pi = false;
            char quote = 0;
            for (String raw : t.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) { out.append('\n'); continue; }
                int lineDepth;
                if (inComment) lineDepth = depth;
                else if (inTag) lineDepth = depth + 1;
                else if (line.startsWith("</")) lineDepth = Math.max(0, depth - 1);
                else lineDepth = depth;
                out.append(repeat(unit, lineDepth)).append(line).append('\n');
                char last = 0;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (inComment) {
                        if (line.startsWith("-->", i)) { inComment = false; i += 2; }
                        continue;
                    }
                    if (inTag) {
                        if (quote != 0) { if (c == quote) quote = 0; continue; }
                        if (c == '"' || c == '\'') { quote = c; continue; }
                        if (c == '>') {
                            inTag = false;
                            if (closing) depth = Math.max(0, depth - 1);
                            else if (!pi && last != '/') depth++;
                            continue;
                        }
                        if (!Character.isWhitespace(c)) last = c;
                        continue;
                    }
                    if (c == '<') {
                        if (line.startsWith("<!--", i)) { inComment = true; i += 3; continue; }
                        inTag = true;
                        closing = line.startsWith("</", i);
                        pi = line.startsWith("<?", i) || line.startsWith("<!", i);
                        last = 0;
                    }
                }
            }
            if (out.length() > 0 && !t.endsWith("\n")) out.setLength(out.length() - 1);
            return out.toString();
        }

        private static String repeat(String s, int n) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) b.append(s);
            return b.toString();
        }
    }
}
