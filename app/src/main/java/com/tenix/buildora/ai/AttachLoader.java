package com.tenix.buildora.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings anything the user attaches into a form the AI can use: a stored copy inside the chat's
 * attachment folder, text for documents and code, downscaled pictures for vision models, and PDF
 * pages rendered as pictures. Call from a worker thread.
 */
public final class AttachLoader {
    private AttachLoader() {}

    public static final long MAX_BYTES = 60L * 1024 * 1024;

    public static final class Item {
        public String name = "";             // stored name (also what the AI calls it)
        public File stored;
        public String kind = FileDigest.TEXT;
        public String text;                  // text the AI receives (file content or image description)
        public final List<ChatClient.Img> imgs = new ArrayList<>();
        public Bitmap thumb;
        public long size;
        public String note = "";
        public int pages;
        public String error;
        public int tokens() {
            int t = text == null ? 0 : ChatClient.estimateTokens(text);
            return t + imgs.size() * 800;
        }
    }

    public static String displayName(Context c, Uri uri) {
        String n = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (q != null && q.moveToFirst()) n = q.getString(0);
            } catch (RuntimeException ignored) { }
        }
        if (n == null || n.isEmpty()) { n = uri.getLastPathSegment(); }
        if (n == null || n.isEmpty()) n = "file";
        String mime = c.getContentResolver().getType(uri);
        if (FileDigest.ext(n).isEmpty() && mime != null) {
            if (mime.equals("image/png")) n += ".png";
            else if (mime.startsWith("image/")) n += ".jpg";
            else if (mime.equals("application/pdf")) n += ".pdf";
            else if (mime.startsWith("text/")) n += ".txt";
        }
        return FileDigest.safeName(n);
    }

    /** Copies the content behind a Uri into the chat's attachment folder and reads it. */
    public static Item fromUri(Context c, Uri uri, File dir, int maxPx, int pdfPages) {
        Item it = new Item();
        try {
            dir.mkdirs();
            String name = FileDigest.uniqueName(dir, displayName(c, uri));
            File dst = new File(dir, name);
            long total = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
                if (in == null) throw new IOException("cannot open the file");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) { out.close(); dst.delete(); throw new IOException("larger than 60 MB"); }
                    out.write(buf, 0, n);
                }
            }
            return fromFile(c, dst, maxPx, pdfPages);
        } catch (IOException | RuntimeException e) {
            it.error = String.valueOf(e.getMessage());
            return it;
        }
    }

    /** Copies a file from the phone's storage into the chat's attachment folder and reads it. */
    public static Item fromDeviceFile(Context c, File src, File dir, int maxPx, int pdfPages) {
        Item it = new Item();
        try {
            if (src.length() > MAX_BYTES) { it.error = "larger than 60 MB"; return it; }
            dir.mkdirs();
            File dst = new File(dir, FileDigest.uniqueName(dir, FileDigest.safeName(src.getName())));
            copy(src, dst);
            return fromFile(c, dst, maxPx, pdfPages);
        } catch (IOException | RuntimeException e) {
            it.error = String.valueOf(e.getMessage());
            return it;
        }
    }

    /** Stores plain text (selection, log, clipboard) as a small attachment. */
    public static Item fromText(File dir, String name, String text) {
        Item it = new Item();
        try {
            dir.mkdirs();
            File dst = new File(dir, FileDigest.uniqueName(dir, FileDigest.safeName(name)));
            try (OutputStream o = new FileOutputStream(dst)) { o.write(text.getBytes("UTF-8")); }
            it.stored = dst;
            it.name = dst.getName();
            it.kind = FileDigest.TEXT;
            it.size = dst.length();
            it.text = text;
            it.note = "text";
        } catch (IOException e) { it.error = String.valueOf(e.getMessage()); }
        return it;
    }

    /** Reads an already stored attachment again (used for "attach:name" requests and after restart). */
    public static Item fromFile(Context c, File f, int maxPx, int pdfPages) {
        Item it = new Item();
        it.stored = f;
        it.name = f.getName();
        it.size = f.length();
        try {
            FileDigest.Result r = FileDigest.read(f, 60000);
            it.kind = r.kind;
            it.note = r.note;
            if (r.kind.equals(FileDigest.IMAGE)) readImage(f, it, maxPx);
            else if (r.kind.equals(FileDigest.PDF)) readPdf(f, it, pdfPages);
            else it.text = r.text + (r.truncated ? "\n[file shortened to fit]" : "");
        } catch (RuntimeException | IOException e) {
            it.error = String.valueOf(e.getMessage());
        }
        return it;
    }

    private static void copy(File a, File b) throws IOException {
        try (InputStream in = new java.io.FileInputStream(a); OutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------------ images

    static void readImage(File f, Item it, int maxPx) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) throw new IOException("cannot read this image");
        int w = o.outWidth, h = o.outHeight;
        int sample = 1;
        while (w / sample > maxPx * 2 || h / sample > maxPx * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
        if (bm == null) throw new IOException("cannot read this image");
        Bitmap sc = scaleTo(bm, maxPx);
        if (sc != bm) bm.recycle();
        boolean alpha = sc.hasAlpha() && FileDigest.ext(f.getName()).equals("png");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sc.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 84, bo);
        it.imgs.add(new ChatClient.Img(alpha ? "image/png" : "image/jpeg", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)));
        it.thumb = scaleTo(sc, 220);
        int[] px = new int[48 * 48];
        Bitmap small = Bitmap.createScaledBitmap(sc, 48, 48, true);
        small.getPixels(px, 0, 48, 0, 0, 48, 48);
        if (small != sc && small != it.thumb) small.recycle();
        it.text = ImagePalette.describe(f.getName(), w, h, f.length(), ImagePalette.dominant(px, 5));
        it.kind = FileDigest.IMAGE;
        it.note = w + "×" + h;
        if (sc != it.thumb) sc.recycle();
    }

    static Bitmap scaleTo(Bitmap b, int maxSide) {
        int w = b.getWidth(), h = b.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return b;
        float s = maxSide / (float) m;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    // ------------------------------------------------------------------ PDF

    static void readPdf(File f, Item it, int maxPages) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = null;
        try {
            r = new PdfRenderer(fd);
            int total = r.getPageCount();
            int n = Math.min(total, Math.max(1, maxPages));
            for (int i = 0; i < n; i++) {
                PdfRenderer.Page p = r.openPage(i);
                try {
                    int w = 1100, h = Math.max(1, Math.round(1100f * p.getHeight() / p.getWidth()));
                    Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    new Canvas(bm).drawColor(Color.WHITE);
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                    it.imgs.add(new ChatClient.Img("image/jpeg", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)));
                    if (i == 0) it.thumb = scaleTo(bm, 220);
                    if (bm != it.thumb) bm.recycle();
                } finally { p.close(); }
            }
            it.pages = total;
            it.kind = FileDigest.PDF;
            it.note = total + " page" + (total == 1 ? "" : "s");
            it.text = "PDF document " + f.getName() + " with " + total + " page" + (total == 1 ? "" : "s") + ". "
                    + (n < total ? "The first " + n + " pages are attached as pictures." : "All pages are attached as pictures.");
        } catch (SecurityException e) {
            throw new IOException("this PDF is protected or damaged");
        } finally {
            if (r != null) r.close();
            try { fd.close(); } catch (IOException ignored) { }
        }
    }
}
