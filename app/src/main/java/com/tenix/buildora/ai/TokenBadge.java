package com.tenix.buildora.ai;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.tenix.buildora.R;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.UI;

/** Small token counter shown in the chat header, left of the new-chat pencil. Tap for details. */
public final class TokenBadge {
    private TokenBadge() {}

    public static TextView create(Context c, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setTextSize(11.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(UI.color(c, R.attr.bText));
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_token_box);
        t.setCompoundDrawablesWithIntrinsicBounds(R.drawable.mi_bolt, 0, 0, 0);
        t.setCompoundDrawablePadding(UI.dp(c, 4));
        t.setPadding(UI.dp(c, 8), UI.dp(c, 5), UI.dp(c, 10), UI.dp(c, 5));
        t.setText("0");
        t.setOnClickListener(l);
        t.setVisibility(Prefs.bool(c, Prefs.AI_TOKBOX, true) ? View.VISIBLE : View.GONE);
        t.post(() -> {
            android.graphics.drawable.Drawable[] d = t.getCompoundDrawables();
            if (d[0] != null) {
                d[0] = d[0].mutate();
                d[0].setTint(UI.color(c, R.attr.bAccent));
                d[0].setBounds(0, 0, UI.dp(c, 13), UI.dp(c, 13));
                t.setCompoundDrawables(d[0], null, null, null);
            }
        });
        return t;
    }

    public static void set(TextView t, long tokens) {
        if (t == null) return;
        String s = Ai.compact(tokens);
        if (!s.contentEquals(t.getText())) t.setText(s);
    }
}
