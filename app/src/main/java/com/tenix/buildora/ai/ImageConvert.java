package com.tenix.buildora.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Resizes / converts pictures for the AI operations import-image and app-icon. */
public final class ImageConvert implements FileOps.ImageConverter {

    @Override public void convert(File src, File dest, int size, String format, boolean round) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        if (o.outWidth <= 0) throw new IOException("cannot read the picture " + src.getName());
        int target = size > 0 ? size : Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap in = BitmapFactory.decodeFile(src.getPath(), o2);
        if (in == null) throw new IOException("cannot read the picture " + src.getName());
        String fmt = format == null || format.isEmpty() ? FileDigest.ext(dest.getName()) : format;
        boolean jpg = fmt.equals("jpg") || fmt.equals("jpeg");
        Bitmap out;
        if (size > 0) {
            // fit inside a square, centred; transparent padding (white for JPEG)
            out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            if (jpg) cv.drawColor(Color.WHITE);
            if (round) {
                Path clip = new Path();
                clip.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
                cv.clipPath(clip);
            }
            float s = Math.min(size / (float) in.getWidth(), size / (float) in.getHeight());
            if (round) s = Math.max(s, size / (float) Math.min(in.getWidth(), in.getHeight()));   // fill the circle
            float w = in.getWidth() * s, h = in.getHeight() * s;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(in, null, new RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), p);
        } else out = in;
        Bitmap.CompressFormat cf = jpg ? Bitmap.CompressFormat.JPEG : fmt.equals("webp") ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.PNG;
        dest.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(dest)) {
            if (!out.compress(cf, 95, os)) throw new IOException("cannot write " + dest.getName());
        }
        if (out != in) out.recycle();
        in.recycle();
    }
}
