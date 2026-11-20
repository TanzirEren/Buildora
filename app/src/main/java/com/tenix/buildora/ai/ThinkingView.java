package com.tenix.buildora.ai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.UI;

/**
 * The "AI is working" indicator: a pulsing sparkle, a status line that changes while the model is
 * thinking (and shows the file being written during streaming), and three bouncing dots.
 */
public final class ThinkingView extends LinearLayout {
    private static final String[] PHASES = {"Thinking", "Reading your request", "Looking at the project", "Planning the answer", "Working out the details", "Almost there"};

    private final Sparkle spark;
    private final Dots dots;
    private final TextView label;
    private ValueAnimator anim;
    private float t;
    private final long t0 = SystemClock.uptimeMillis();
    private boolean writing;
    private String detail = "";
    private int phase;
    private long phaseAt;

    public ThinkingView(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(UI.dp(c, 2), UI.dp(c, 8), UI.dp(c, 2), UI.dp(c, 4));
        spark = new Sparkle(c);
        addView(spark, new LayoutParams(UI.dp(c, 22), UI.dp(c, 22)));
        label = UI.label(c, PHASES[0], 12.5f, true);
        LayoutParams ll = new LayoutParams(ViewGroup_WRAP, ViewGroup_WRAP);
        ll.leftMargin = UI.dp(c, 8);
        addView(label, ll);
        dots = new Dots(c);
        LayoutParams dl = new LayoutParams(UI.dp(c, 22), UI.dp(c, 14));
        dl.leftMargin = UI.dp(c, 4);
        dl.topMargin = UI.dp(c, 4);
        addView(dots, dl);
    }

    private static final int ViewGroup_WRAP = LayoutParams.WRAP_CONTENT;

    /** Switch from the thinking phrases to a live "writing" line. */
    public void setWriting(String what, int tokens, float tokensPerSec) {
        writing = true;
        StringBuilder sb = new StringBuilder("Writing");
        if (what != null && !what.isEmpty()) sb.append(' ').append(what);
        sb.append("  ·  ").append(Ai.compact(tokens)).append(" tokens");
        if (tokensPerSec >= 1) sb.append("  ·  ").append((int) tokensPerSec).append("/s");
        detail = sb.toString();
        label.setText(detail);
    }

    public void setStopping() { writing = true; label.setText("Stopping…"); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1300);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (Float) a.getAnimatedValue();
            spark.invalidate();
            dots.invalidate();
            long now = SystemClock.uptimeMillis();
            if (writing) label.setAlpha(0.72f + 0.28f * (float) Math.sin(t * 2 * Math.PI));
            else {
                if (now - phaseAt > 2600) {
                    phaseAt = now;
                    phase = (phase + 1) % PHASES.length;
                    label.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                        long sec = (SystemClock.uptimeMillis() - t0) / 1000;
                        label.setText(PHASES[phase] + (sec >= 4 ? "  ·  " + sec + "s" : ""));
                        label.animate().alpha(1f).setDuration(160).start();
                    }).start();
                }
            }
        });
        phaseAt = SystemClock.uptimeMillis();
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    private final class Sparkle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Sparkle(Context c) { super(c); }
        private void star(Canvas cv, float cx, float cy, float r, float rot) {
            path.reset();
            path.moveTo(cx, cy - r);
            path.quadTo(cx, cy, cx + r, cy);
            path.quadTo(cx, cy, cx, cy + r);
            path.quadTo(cx, cy, cx - r, cy);
            path.quadTo(cx, cy, cx, cy - r);
            cv.save();
            cv.rotate(rot, cx, cy);
            cv.drawPath(path, p);
            cv.restore();
        }
        @Override protected void onDraw(Canvas cv) {
            p.setColor(UI.color(getContext(), R.attr.bAccent));
            float w = getWidth(), h = getHeight();
            float pulse = 0.78f + 0.22f * (float) Math.sin(t * 2 * Math.PI);
            star(cv, w * 0.42f, h * 0.58f, w * 0.40f * pulse, t * 90f);
            float p2 = 0.55f + 0.45f * (float) Math.sin((t + 0.5f) * 2 * Math.PI);
            p.setAlpha(190);
            star(cv, w * 0.80f, h * 0.22f, w * 0.17f * Math.max(0.35f, p2), -t * 90f);
            p.setAlpha(255);
        }
    }

    private final class Dots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dots(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            p.setColor(UI.color(getContext(), R.attr.bTextDim));
            float r = getHeight() * 0.17f, base = getHeight() * 0.62f;
            for (int i = 0; i < 3; i++) {
                double ph = (t - i * 0.14) * 2 * Math.PI;
                float up = Math.max(0f, (float) Math.sin(ph)) * getHeight() * 0.38f;
                p.setAlpha(110 + (int) (145 * Math.max(0f, (float) Math.sin(ph))));
                cv.drawCircle(getWidth() * (0.18f + 0.32f * i), base - up, r, p);
            }
        }
    }
}
