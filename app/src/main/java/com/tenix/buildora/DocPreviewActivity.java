package com.tenix.buildora;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tenix.buildora.assist.Markdown;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.UI;

import java.io.File;

/** Preview of README.md / Markdown and HTML files. */
public class DocPreviewActivity extends BaseActivity {

    private WebView web;
    private File file;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String p = getIntent().getStringExtra("path");
        if (p == null) { finish(); return; }
        file = new File(p);

        LinearLayout root = UI.vbox(this);
        root.setBackgroundColor(UI.color(this, R.attr.bBg));
        root.setFitsSystemWindows(true);
        LinearLayout top = UI.hbox(this);
        top.setPadding(UI.dp(this, 6), 0, UI.dp(this, 6), 0);
        ImageButton back = new ImageButton(this, null, 0, R.style.BIconBtn);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        TextView title = UI.label(this, file.getName(), 17, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton refresh = new ImageButton(this, null, 0, R.style.BIconBtn);
        refresh.setImageResource(R.drawable.ic_refresh);
        refresh.setOnClickListener(v -> load());
        top.addView(refresh, new LinearLayout.LayoutParams(UI.dp(this, 44), UI.dp(this, 52)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(this, 52)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setBackgroundColor(0);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        styleBars();
        load();
    }

    private void load() {
        try {
            String text = FileUtil.readText(file);
            String n = file.getName().toLowerCase();
            String html = n.endsWith(".html") || n.endsWith(".htm") ? text : Markdown.page(Markdown.toHtml(text), dark);
            web.loadDataWithBaseURL("file://" + file.getParentFile().getAbsolutePath() + "/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            UI.toast(this, "Cannot read file: " + e.getMessage());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) load();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
