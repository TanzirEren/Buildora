package com.tenix.buildora.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.FileUtil;
import com.tenix.buildora.core.UI;

import java.io.File;

/** Full view of an attached picture or file. */
public final class AttachViewer {
    private AttachViewer() {}

    public static void show(Activity a, File f) {
        if (f == null || !f.isFile()) { UI.toast(a, "This attachment is no longer available"); return; }
        AlertDialog.Builder b = UI.dialog(a).setTitle(f.getName() + "  ·  " + FileUtil.human(f.length())).setPositiveButton("Close", null);
        String e = FileDigest.ext(f.getName());
        if (FileDigest.isImageExt(e)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (o.outWidth / s > 2000 || o.outHeight / s > 2000) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
            if (bm != null) {
                ImageView iv = new ImageView(a);
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setPadding(UI.dp(a, 12), UI.dp(a, 8), UI.dp(a, 12), UI.dp(a, 4));
                b.setView(iv);
                b.show();
                return;
            }
        }
        FileDigest.Result r = FileDigest.read(f, 20000);
        TextView t = new TextView(a);
        t.setText(r.text.isEmpty() ? r.note : r.text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11.5f);
        t.setTextColor(UI.color(a, R.attr.bText));
        t.setTextIsSelectable(true);
        t.setHorizontallyScrolling(true);
        t.setPadding(UI.dp(a, 16), UI.dp(a, 8), UI.dp(a, 16), UI.dp(a, 8));
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.addView(t);
        ScrollView sv = new ScrollView(a);
        sv.addView(hs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setView(sv).show();
    }
}
