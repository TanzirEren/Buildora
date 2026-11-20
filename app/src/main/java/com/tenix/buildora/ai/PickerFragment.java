package com.tenix.buildora.ai;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Intent;

import com.tenix.buildora.core.UI;

/** Invisible fragment that lets a custom view start an Android picker and get the result back. */
public final class PickerFragment extends Fragment {
    public interface Result { void onResult(int resultCode, Intent data); }

    private static final int REQ = 4711;
    private Intent intent;
    private Result callback;

    public static void launch(Activity a, Intent intent, Result r) {
        PickerFragment f = new PickerFragment();
        f.intent = intent;
        f.callback = r;
        try {
            a.getFragmentManager().beginTransaction().add(f, "buildora_picker_" + System.nanoTime()).commitAllowingStateLoss();
        } catch (RuntimeException e) {
            UI.toast(a, "Cannot open the picker right now");
        }
    }

    @Override public void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        setRetainInstance(true);
        if (intent == null) { remove(); return; }
        try {
            startActivityForResult(intent, REQ);
        } catch (ActivityNotFoundException e) {
            UI.toast(getActivity(), "No app found to pick this");
            remove();
        }
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Result r = callback;
        callback = null;
        remove();
        if (requestCode == REQ && r != null) r.onResult(resultCode, data);
    }

    private void remove() {
        try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (RuntimeException ignored) { }
    }
}
