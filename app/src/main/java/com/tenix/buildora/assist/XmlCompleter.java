package com.tenix.buildora.assist;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Completion for layout / manifest / drawable / values XML: tags, attributes, values and @resources. */
public final class XmlCompleter {

    private static final String[] COMMON_ATTRS = {"layout_width", "layout_height", "id", "text", "textSize", "textColor", "background",
            "padding", "layout_margin", "orientation", "gravity", "layout_gravity", "visibility", "src", "onClick", "layout_weight",
            "hint", "inputType", "contentDescription", "textStyle", "scaleType", "layout_marginTop", "layout_marginStart", "paddingStart",
            "layout_centerInParent", "layout_alignParentBottom", "layout_below", "layout_toEndOf", "clickable", "enabled", "tint", "name", "label", "theme", "icon", "exported"};

    private static final String[] LIBRARY_TAGS = {"androidx.constraintlayout.widget.ConstraintLayout", "androidx.recyclerview.widget.RecyclerView",
            "androidx.cardview.widget.CardView", "androidx.viewpager2.widget.ViewPager2", "androidx.swiperefreshlayout.widget.SwipeRefreshLayout",
            "androidx.coordinatorlayout.widget.CoordinatorLayout", "androidx.appcompat.widget.Toolbar", "androidx.appcompat.widget.AppCompatButton",
            "androidx.appcompat.widget.AppCompatTextView", "androidx.appcompat.widget.AppCompatImageView", "androidx.fragment.app.FragmentContainerView",
            "com.google.android.material.button.MaterialButton", "com.google.android.material.textfield.TextInputLayout",
            "com.google.android.material.textfield.TextInputEditText", "com.google.android.material.floatingactionbutton.FloatingActionButton",
            "com.google.android.material.appbar.AppBarLayout", "com.google.android.material.card.MaterialCardView",
            "com.google.android.material.bottomnavigation.BottomNavigationView", "com.google.android.material.tabs.TabLayout"};

    private static final String[] MANIFEST_TAGS = {"manifest", "application", "activity", "activity-alias", "service", "receiver", "provider",
            "uses-permission", "uses-feature", "uses-sdk", "intent-filter", "action", "category", "data", "meta-data", "permission", "queries", "package"};

    private static final String[] DRAWABLE_TAGS = {"shape", "selector", "layer-list", "ripple", "vector", "path", "group", "bitmap", "item",
            "solid", "corners", "stroke", "gradient", "padding", "size", "level-list", "transition", "animated-vector", "inset", "clip", "rotate"};

    private static final String[] VALUE_TAGS = {"resources", "string", "color", "dimen", "style", "item", "string-array", "integer-array", "array",
            "integer", "bool", "declare-styleable", "attr", "plurals"};

    private static final String[] MENU_TAGS = {"menu", "item", "group"};

    private final ClassIndex idx;
    private final ResourceIndex res;
    private final List<File> javaDirs;
    private List<String> tags, attrs;

    public XmlCompleter(ClassIndex idx, ResourceIndex res, List<File> javaDirs) {
        this.idx = idx;
        this.res = res;
        this.javaDirs = javaDirs;
    }

    private static boolean has(String[] arr, String s) { for (String a : arr) if (a.equals(s)) return true; return false; }

    private synchronized List<String> viewTags() { if (tags == null) tags = idx.viewTags(); return tags; }
    private synchronized List<String> attrNames() { if (attrs == null) attrs = idx.attrNames(); return attrs; }

    public List<Completion> complete(String text, int caret, File file, boolean force) {
        return JavaCompleter.finish(completeRaw(text, caret, file, force), "");
    }

    private List<Completion> completeRaw(String text, int caret, File file, boolean force) {
        List<Completion> out = new ArrayList<>();
        int lt = text.lastIndexOf('<', Math.max(0, caret - 1));
        int gt = text.lastIndexOf('>', Math.max(0, caret - 1));
        String dir = file != null && file.getParentFile() != null ? file.getParentFile().getName() : "";
        String name = file == null ? "" : file.getName();
        if (lt < 0 || gt > lt && gt < caret) return out;         // not inside a tag
        String inTag = text.substring(lt + 1, caret);
        if (inTag.startsWith("!--") || inTag.startsWith("?")) return out;

        // ---------------- attribute value?
        Matcher vm = Pattern.compile("([\\w:.\\-]+)\\s*=\\s*\"([^\"]*)$").matcher(inTag);
        if (vm.find()) return valueItems(vm.group(1), vm.group(2), text, caret);

        // ---------------- tag name?
        Matcher nm = Pattern.compile("^(/?)([\\w.:\\-]*)$").matcher(inTag);
        if (nm.find()) {
            boolean closing = nm.group(1).equals("/");
            String prefix = nm.group(2);
            if (closing) {
                String open = nearestOpenTag(text.substring(0, lt));
                if (open != null && JavaCompleter.matchScore(open, prefix) > 0) out.add(new Completion(open + ">", "close tag", open + ">", 0, 't', null, 200));
                return out;
            }
            return tagItems(prefix, dir, name);
        }

        // ---------------- attribute name
        Matcher am = Pattern.compile("[\\s\"]([\\w:.\\-]*)$").matcher(inTag);
        if (am.find()) {
            String prefix = am.group(1);
            if (prefix.isEmpty() && !force) return out;
            return attrItems(prefix, name);
        }
        return out;
    }

    // ------------------------------------------------------------------ tags

    private List<Completion> tagItems(String prefix, String dir, String fileName) {
        List<Completion> out = new ArrayList<>();
        List<String> all = new ArrayList<>();
        if (fileName.equals("AndroidManifest.xml")) Collections.addAll(all, MANIFEST_TAGS);
        else if (dir.startsWith("drawable") || dir.startsWith("mipmap")) Collections.addAll(all, DRAWABLE_TAGS);
        else if (dir.startsWith("values")) Collections.addAll(all, VALUE_TAGS);
        else if (dir.startsWith("menu")) Collections.addAll(all, MENU_TAGS);
        else { all.addAll(viewTags()); Collections.addAll(all, LIBRARY_TAGS); all.add("include"); all.add("merge"); all.add("requestFocus"); all.add("fragment"); }
        for (String t : all) {
            int s = JavaCompleter.matchScore(t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t, prefix);
            if (s == 0 && !(t.contains(".") && t.startsWith(prefix) && !prefix.isEmpty())) continue;
            if (s == 0) s = 40;
            String disp = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
            out.add(new Completion(disp, t.contains(".") ? t : "view", t, 0, 't', null, s));
        }
        return out;
    }

    /** Name of the innermost tag that is still open before the given text. */
    static String nearestOpenTag(String before) {
        Matcher m = Pattern.compile("<(/?)([\\w.:\\-]+)([^<>]*?)(/?)>").matcher(before.replaceAll("(?s)<!--.*?-->", ""));
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        while (m.find()) {
            if (m.group(1).equals("/")) { if (!stack.isEmpty()) stack.pop(); }
            else if (!m.group(4).equals("/") && !m.group(3).endsWith("/")) stack.push(m.group(2));
        }
        return stack.isEmpty() ? null : stack.peek();
    }

    // ------------------------------------------------------------------ attributes

    private List<Completion> attrItems(String prefix, String fileName) {
        List<Completion> out = new ArrayList<>();
        String p = prefix.startsWith("android:") ? prefix.substring(8) : prefix;
        boolean ns = prefix.startsWith("app:") || prefix.startsWith("tools:") || prefix.startsWith("xmlns");
        if (prefix.startsWith("app:")) {
            for (String a : new String[]{"layout_constraintTop_toTopOf", "layout_constraintBottom_toBottomOf", "layout_constraintStart_toStartOf",
                    "layout_constraintEnd_toEndOf", "layout_constraintTop_toBottomOf", "layout_constraintStart_toEndOf", "layout_constraintHorizontal_bias",
                    "layout_constraintVertical_bias", "layout_constraintWidth_percent", "layout_constraintDimensionRatio", "cardCornerRadius", "cardElevation",
                    "layoutManager", "spanCount", "backgroundTint", "srcCompat", "title", "navigationIcon"}) {
                String full = "app:" + a;
                int s = JavaCompleter.matchScore(full, prefix);
                if (s > 0) out.add(new Completion(full + "=\"\"", "app", full + "=\"\"", 1, 'a', null, s));
            }
            return out;
        }
        if (prefix.startsWith("tools:")) {
            for (String a : new String[]{"text", "context", "listitem", "src", "visibility", "ignore", "layout"}) {
                String full = "tools:" + a;
                if (JavaCompleter.matchScore(full, prefix) > 0) out.add(new Completion(full + "=\"\"", "tools", full + "=\"\"", 1, 'a', null, 50));
            }
            return out;
        }
        if (ns) {
            for (String x : new String[]{"xmlns:android=\"http://schemas.android.com/apk/res/android\"", "xmlns:app=\"http://schemas.android.com/apk/res-auto\"",
                    "xmlns:tools=\"http://schemas.android.com/tools\""}) if (JavaCompleter.matchScore(x, prefix) > 0) out.add(new Completion(x.substring(0, x.indexOf('=')), "namespace", x, 0, 'a', null, 90));
            return out;
        }
        Set<String> common = new HashSet<>();
        Collections.addAll(common, COMMON_ATTRS);
        for (String a : attrNames()) {
            int s = JavaCompleter.matchScore(a, p);
            if (s == 0) continue;
            if (common.contains(a)) s += 30;
            if (a.equals("layout_width") || a.equals("layout_height") || a.equals("id") || a.equals("text") || a.equals("textSize") || a.equals("textColor") || a.equals("orientation")) s += 25;
            s -= Math.min(12, a.length() / 4);
            out.add(new Completion("android:" + a, "attribute", "android:" + a + "=\"\"", 1, 'a', null, s));
            if (out.size() > 400) break;
        }
        if ("xmlns".startsWith(prefix) && !prefix.isEmpty()) out.add(new Completion("xmlns:android", "namespace", "xmlns:android=\"http://schemas.android.com/apk/res/android\"", 0, 'a', null, 90));
        if ("app".startsWith(prefix) && !prefix.isEmpty()) out.add(new Completion("app:", "attribute", "app:", 0, 'a', null, 40));
        if (fileName.equals("AndroidManifest.xml") && "package".startsWith(prefix) && !prefix.isEmpty()) out.add(new Completion("package", "attribute", "package=\"\"", 1, 'a', null, 80));
        return out;
    }

    // ------------------------------------------------------------------ values

    private static final String[][] ENUMS = {
            {"layout_width", "match_parent", "wrap_content", "0dp"},
            {"layout_height", "match_parent", "wrap_content", "0dp"},
            {"orientation", "vertical", "horizontal"},
            {"gravity", "center", "center_vertical", "center_horizontal", "left", "right", "top", "bottom", "start", "end", "fill"},
            {"layout_gravity", "center", "center_vertical", "center_horizontal", "left", "right", "top", "bottom", "start", "end", "fill"},
            {"visibility", "visible", "invisible", "gone"},
            {"textStyle", "bold", "italic", "normal", "bold|italic"},
            {"scaleType", "centerCrop", "centerInside", "fitCenter", "fitXY", "center", "matrix", "fitStart", "fitEnd"},
            {"inputType", "text", "number", "numberDecimal", "textPassword", "numberPassword", "textEmailAddress", "phone", "textMultiLine", "textPersonName", "date"},
            {"ellipsize", "end", "start", "middle", "marquee", "none"},
            {"textAlignment", "center", "viewStart", "viewEnd", "textStart", "textEnd", "gravity"},
            {"layout_centerInParent", "true", "false"},
            {"clickable", "true", "false"}, {"enabled", "true", "false"}, {"focusable", "true", "false"}, {"checked", "true", "false"},
            {"singleLine", "true", "false"}, {"exported", "true", "false"}, {"allowBackup", "true", "false"},
            {"fontFamily", "sans-serif", "sans-serif-medium", "sans-serif-light", "serif", "monospace"},
            {"screenOrientation", "portrait", "landscape", "unspecified", "sensor", "fullSensor"},
            {"launchMode", "standard", "singleTop", "singleTask", "singleInstance"},
            {"importantForAccessibility", "yes", "no", "auto"},
    };

    private static int preferredType(String attr, String type) {
        if ((attr.equals("text") || attr.equals("hint") || attr.equals("contentDescription") || attr.equals("title")) && type.equals("string")) return 60;
        if ((attr.equals("textColor") || attr.equals("background") || attr.equals("tint")) && (type.equals("color") || type.equals("drawable"))) return 60;
        if ((attr.equals("src") || attr.equals("icon") || attr.equals("srcCompat")) && (type.equals("drawable") || type.equals("mipmap"))) return 60;
        if (attr.equals("style") && type.equals("style")) return 60;
        return 0;
    }

    private List<Completion> valueItems(String attr, String typed, String text, int caret) {
        List<Completion> out = new ArrayList<>();
        String a = attr.contains(":") ? attr.substring(attr.lastIndexOf(':') + 1) : attr;
        if (typed.startsWith("@")) {
            int slash = typed.indexOf('/');
            if (slash < 0) {
                String tp = typed.substring(1);
                int ti = 0;
                for (String t : ResourceIndex.TYPES) {
                    ti++;
                    if (t.equals("styleable")) continue;
                    String disp = (a.equals("id") && t.equals("id")) ? "+id" : t;
                    int s = JavaCompleter.matchScore(disp, tp);
                    if (s > 0) s += 40 - ti * 2 + preferredType(a, t);
                    if (s > 0) out.add(new Completion("@" + disp + "/", "resource", "@" + disp + "/", 0, 'r', null, s));
                }
                if (a.equals("id") && JavaCompleter.matchScore("+id", tp) > 0) out.add(new Completion("@+id/", "new id", "@+id/", 0, 'r', null, 120));
                return out;
            }
            String type = typed.substring(1, slash).replace("+", "");
            String np = typed.substring(slash + 1);
            for (String n : res.names(type)) {
                int s = JavaCompleter.matchScore(n, np);
                if (s > 0) out.add(new Completion(n, "@" + type, "@" + (typed.startsWith("@+") ? "+" : "") + type + "/" + n, 0, 'r', null, s));
            }
            return out;
        }
        for (String[] row : ENUMS) {
            if (!row[0].equals(a)) continue;
            for (int i = 1; i < row.length; i++) { int s = JavaCompleter.matchScore(row[i], typed); if (s > 0) out.add(Completion.simple(row[i], "value", 'a', s)); }
            return out;
        }
        if (a.equals("onClick")) {
            for (File d : javaDirs) for (File f : FileUtil.listFiles(d, ".java")) {
                try {
                    Matcher m = Pattern.compile("public\\s+void\\s+(\\w+)\\s*\\(\\s*(?:android\\.view\\.)?View\\s+\\w+\\s*\\)").matcher(FileUtil.readText(f));
                    while (m.find()) { int s = JavaCompleter.matchScore(m.group(1), typed); if (s > 0) out.add(Completion.simple(m.group(1), f.getName(), 'm', s)); }
                } catch (IOException ignored) { }
            }
            return out;
        }
        String ref = null;
        if (a.equals("text") || a.equals("hint") || a.equals("contentDescription") || a.equals("title") || a.equals("label") || a.equals("description")) ref = "string";
        else if (a.equals("textColor") || a.equals("background") || a.equals("tint") || a.equals("backgroundTint") || a.equals("textColorHint") || a.equals("color")) ref = "color";
        else if (a.equals("src") || a.equals("srcCompat") || a.equals("icon") || a.equals("drawableLeft") || a.equals("drawableStart")) ref = "drawable";
        else if (a.equals("theme") || a.equals("style")) ref = "style";
        else if (a.equals("layout")) ref = "layout";
        if (ref != null && typed.isEmpty()) out.add(new Completion("@" + ref + "/", "resource", "@" + ref + "/", 0, 'r', null, 60));
        return out;
    }
}
