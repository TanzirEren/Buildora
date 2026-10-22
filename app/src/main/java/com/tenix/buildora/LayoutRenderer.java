package com.tenix.buildora;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Renders a layout of the built app inside Buildora: the compiled resources of the APK are loaded with
 * PackageManager.getResourcesForApplication and the real LayoutInflater inflates your XML. Custom / library
 * views are loaded from the APK's dex when the APK is up to date, otherwise shown as labelled boxes.
 */
final class LayoutRenderer {
    private LayoutRenderer() {}

    static final class Config {
        int widthDp = 360, heightDp = 720;
        boolean night;
    }

    static final class Result {
        View root;
        Resources res;
        String pkg = "";
        String error;
        int themeId;
        final Map<View, Integer> lines = new IdentityHashMap<>();
    }

    static Result render(Context base, File apk, boolean loadClasses, String layoutName, Config cfg) {
        Result out = new Result();
        try {
            PackageManager pm = base.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (pi == null || pi.applicationInfo == null) throw new IllegalStateException("Cannot read " + apk.getName());
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = apk.getPath();
            ai.publicSourceDir = apk.getPath();
            out.pkg = pi.packageName;
            Resources res = pm.getResourcesForApplication(ai);
            DisplayMetrics dm = new DisplayMetrics();
            dm.setTo(base.getResources().getDisplayMetrics());
            Configuration c = new Configuration(res.getConfiguration());
            c.screenWidthDp = cfg.widthDp;
            c.screenHeightDp = cfg.heightDp;
            c.smallestScreenWidthDp = Math.min(cfg.widthDp, cfg.heightDp);
            c.orientation = cfg.widthDp > cfg.heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (cfg.night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            res.updateConfiguration(c, dm);
            out.res = res;

            int layoutId = res.getIdentifier(layoutName, "layout", pi.packageName);
            if (layoutId == 0) throw new IllegalStateException("Layout '" + layoutName + "' is not in the built resources yet.");
            int themeId = ai.theme != 0 ? ai.theme : android.R.style.Theme_Material_Light_NoActionBar;
            out.themeId = themeId;
            ClassLoader dex = null;
            if (loadClasses) {
                try {
                    File cache = new File(base.getCacheDir(), "preview-dex");
                    cache.mkdirs();
                    dex = new dalvik.system.DexClassLoader(apk.getPath(), cache.getPath(), null, LayoutRenderer.class.getClassLoader());
                } catch (Throwable ignored) { }
            }
            ThemedContext ctx = new ThemedContext(base, res, themeId, pi.packageName, dex);
            LayoutInflater inflater = LayoutInflater.from(ctx).cloneInContext(ctx);
            inflater.setFactory2(new PreviewFactory(inflater, out.lines, dex));
            FrameLayout host = new FrameLayout(ctx);
            View v = inflater.inflate(res.getLayout(layoutId), host, false);
            host.addView(v);
            out.root = host;
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            out.error = t + (s.length() > 1200 ? "\n" + s.substring(0, 1200) : "\n" + s);
        }
        return out;
    }

    /** Window background of the app theme (white / dark) for the preview frame. */
    static Drawable windowBackground(Context ctx) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.windowBackground, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
                return new android.graphics.drawable.ColorDrawable(tv.data);
            try { return ctx.getResources().getDrawable(tv.resourceId, ctx.getTheme()); } catch (Throwable ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------------ context that serves the app's resources

    static final class ThemedContext extends ContextThemeWrapper {
        private final Resources res;
        private final int themeId;
        private final String pkg;
        private final ClassLoader loader;
        private Resources.Theme theme;

        ThemedContext(Context base, Resources res, int themeId, String pkg, ClassLoader loader) {
            super(base, 0);
            this.res = res;
            this.themeId = themeId;
            this.pkg = pkg;
            this.loader = loader;
        }

        @Override public Resources getResources() { return res; }
        @Override public AssetManager getAssets() { return res.getAssets(); }
        @Override public String getPackageName() { return pkg; }
        @Override public ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }

        @Override public Resources.Theme getTheme() {
            if (theme == null) {
                theme = res.newTheme();
                theme.applyStyle(themeId, true);
            }
            return theme;
        }
    }

    // ------------------------------------------------------------------ view creation

    static final class PreviewFactory implements LayoutInflater.Factory2 {
        private final LayoutInflater inflater;
        private final Map<View, Integer> lines;
        private final ClassLoader dex;
        private static final String[] PREFIXES = {"android.widget.", "android.view.", "android.webkit."};

        PreviewFactory(LayoutInflater inflater, Map<View, Integer> lines, ClassLoader dex) {
            this.inflater = inflater;
            this.lines = lines;
            this.dex = dex;
        }

        @Override public View onCreateView(String name, Context context, AttributeSet attrs) { return onCreateView(null, name, context, attrs); }

        @Override public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            int line = -1;
            try { line = ((XmlPullParser) attrs).getLineNumber(); } catch (Throwable ignored) { }
            View v = null;
            try {
                if (name.indexOf('.') < 0) {
                    for (String pfx : PREFIXES) {
                        try { v = inflater.createView(name, pfx, attrs); break; } catch (ClassNotFoundException ignored) { }
                    }
                } else if (dex != null) {
                    Class<?> k = dex.loadClass(name);
                    try {
                        Constructor<?> ct = k.getConstructor(Context.class, AttributeSet.class);
                        v = (View) ct.newInstance(context, attrs);
                    } catch (NoSuchMethodException e) {
                        v = (View) k.getConstructor(Context.class).newInstance(context);
                    }
                }
            } catch (Throwable ignored) {
                v = null;
            }
            if (v == null) v = new Placeholder(context, name);
            if (line > 0) lines.put(v, line);
            return v;
        }
    }

    /** Stand-in for a view whose class is not available (custom or library widgets before the first build). */
    static final class Placeholder extends FrameLayout {
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

        Placeholder(Context c, String name) {
            super(c);
            setWillNotDraw(false);
            dash.setStyle(Paint.Style.STROKE);
            dash.setColor(0xFF8A8F98);
            dash.setStrokeWidth(3f);
            dash.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));
            setBackgroundColor(0x1A888888);
            TextView t = new TextView(c);
            t.setText(name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name);
            t.setTextSize(11);
            t.setTextColor(0xFF7A808A);
            t.setPadding(8, 6, 8, 6);
            addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setMinimumHeight(48);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(2, 2, getWidth() - 2, getHeight() - 2, dash);
        }
    }

    /** Frame that swallows touches (so the preview is not interactive) and reports taps for the inspector. */
    static final class PreviewFrame extends FrameLayout {
        interface OnTap { void onTap(float x, float y); }
        private OnTap onTap;
        private android.graphics.Rect highlight;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint();

        PreviewFrame(Context c) {
            super(c);
            setWillNotDraw(false);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(5f);
            stroke.setColor(0xFFE5484D);
            fill.setColor(0x33E5484D);
        }

        void setOnTap(OnTap t) { onTap = t; }
        void setHighlight(android.graphics.Rect r) { highlight = r; invalidate(); }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) { return true; }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && onTap != null) onTap.onTap(ev.getX(), ev.getY());
            return true;
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (highlight != null) { canvas.drawRect(highlight, fill); canvas.drawRect(highlight, stroke); }
        }
    }

    /** Deepest visible view containing the point (coordinates relative to root). */
    static View hit(View root, float x, float y) {
        if (!(root instanceof ViewGroup)) return root;
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX(), cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx >= 0 && cy >= 0 && cx <= c.getWidth() && cy <= c.getHeight()) return hit(c, cx, cy);
        }
        return root;
    }
}
