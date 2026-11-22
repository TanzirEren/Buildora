package com.tenix.buildora;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tenix.buildora.build.BuildEngine;
import com.tenix.buildora.build.KeyTool;
import com.tenix.buildora.build.Keystores;
import com.tenix.buildora.core.Prefs;
import com.tenix.buildora.core.Project;
import com.tenix.buildora.core.UI;
import com.tenix.buildora.core.Variant;

import java.io.File;
import java.util.List;

/**
 * "Sign APK" and "Export AAB": rebuilds the release variant and signs it with a real release key.
 * A key is generated automatically when the project has none.
 */
final class SignDialog {
    private SignDialog() {}

    private static String shortFp(String sha256) {
        if (sha256 == null || sha256.length() < 17) return sha256 == null ? "" : sha256;
        return sha256.substring(0, 17) + "…";
    }

    static void show(final IdeActivity act, final Project p0, final boolean aab) {
        final Project p = Project.load(p0.root);
        final Variant v = p.releaseVariant();
        final String[] keyId = {""};             // "" = automatic (project key, active key, else a new one)

        LinearLayout box = UI.vbox(act);
        box.setPadding(UI.dp(act, 20), UI.dp(act, 4), UI.dp(act, 20), UI.dp(act, 8));

        TextView info = UI.label(act, (aab ? "Creates a signed App Bundle (.aab) for Google Play." : "Rebuilds the app as a release and signs it with a real key (v1 + v2 + v3).")
                + "\n\nVariant: " + v.name + "   •   " + v.applicationId
                + "\nVersion: " + v.versionName + " (" + v.versionCode + ")", 13, true);
        box.addView(info);

        final TextView keyLine = UI.label(act, "", 13, false);
        keyLine.setPadding(0, UI.dp(act, 12), 0, UI.dp(act, 4));
        keyLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(keyLine);
        final TextView keySub = UI.label(act, "", 12, true);
        box.addView(keySub);
        Runnable refreshKey = new Runnable() {
            @Override public void run() {
                Keystores.Entry e = null;
                if (!keyId[0].isEmpty()) e = Keystores.byId(act, keyId[0]);
                else {
                    if (v.signingKey != null && !v.signingKey.isEmpty() && !v.signingKey.equals(Keystores.DEBUG_ID)) e = Keystores.byId(act, v.signingKey);
                    if (e == null && !Keystores.activeId(act).equals(Keystores.DEBUG_ID)) e = Keystores.byId(act, Keystores.activeId(act));
                    if (e == null) e = Keystores.findAuto(act, p.name);
                }
                if (e == null) {
                    keyLine.setText("Key: new release key");
                    keySub.setText("No signing key yet. A 2048-bit RSA key (valid 30 years) with a random password is generated automatically and reused for every later release of this project.");
                } else {
                    keyLine.setText("Key: " + e.name);
                    String d = "alias " + e.alias;
                    try { KeyTool.Info i = KeyTool.describe(e.path(act), e.storePass.toCharArray(), e.alias); d += "  •  valid to " + i.validTo + "\nSHA-256 " + shortFp(i.sha256); } catch (Exception ignored) { }
                    keySub.setText(d);
                }
            }
        };
        refreshKey.run();

        TextView choose = UI.button(act, "Choose another key", false);
        box.addView(choose);

        final CheckBox r8 = new CheckBox(act);
        r8.setText("Shrink and obfuscate with R8");
        r8.setChecked(v.minify);
        r8.setTextSize(13.5f);
        r8.setTextColor(UI.color(act, R.attr.bText));
        final CheckBox bump = new CheckBox(act);
        bump.setText("Increase version code by 1");
        bump.setChecked(Prefs.bool(act, Prefs.AUTO_VERSION, false));
        bump.setTextSize(13.5f);
        bump.setTextColor(UI.color(act, R.attr.bText));
        final CheckBox backup = new CheckBox(act);
        backup.setText("Back up the key to /Buildora/keystores");
        backup.setChecked(true);
        backup.setTextSize(13.5f);
        backup.setTextColor(UI.color(act, R.attr.bText));
        box.addView(r8);
        box.addView(bump);
        box.addView(backup);

        TextView go = UI.button(act, aab ? "Sign and export AAB" : "Sign and build APK", true);
        TextView cancel = UI.button(act, "Cancel", false);
        box.addView(go);
        box.addView(cancel);
        TextView warn = UI.label(act, "Keep a backup of your key. An app can only be updated with the key it was first signed with.", 11, true);
        warn.setPadding(0, UI.dp(act, 6), 0, 0);
        box.addView(warn);

        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        final AlertDialog d = UI.dialog(act).setTitle(aab ? "Export AAB (signed)" : "Sign APK").setView(sv).create();
        d.show();

        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                final List<Keystores.Entry> all = Keystores.list(act);
                final String[] names = new String[all.size() + 1];
                names[0] = "Automatic (project key / new key)";
                for (int i = 0; i < all.size(); i++) names[i + 1] = all.get(i).name;
                UI.dialog(act).setTitle("Signing key").setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        keyId[0] = w == 0 ? "" : all.get(w - 1).id;
                        refreshKey.run();
                    }
                }).show();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { d.dismiss(); } });
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                d.dismiss();
                act.runSignedBuild(aab, keyId[0], r8.isChecked(), bump.isChecked(), backup.isChecked());
            }
        });
    }

    /** Resolves the key (generating one when needed). Runs off the main thread. */
    static Keystores.Entry resolveKey(IdeActivity act, Project p, Variant v, String chosenId, boolean backup) throws Exception {
        String pick = chosenId;
        if (pick == null || pick.isEmpty()) pick = v.signingKey;
        boolean existed = Keystores.findAuto(act, p.name) != null;
        Keystores.Entry e = Keystores.forRelease(act, p.name, pick);
        if (!existed && e.name.equals(Keystores.autoKeyName(p.name))) {
            // remember the new key for this project's release build type
            Project q = Project.load(p.root);
            com.tenix.buildora.core.BuildTypeCfg bt = q.findBuildType(v.buildType);
            if (bt != null && !"debug".equals(bt.name) && (bt.signingKey == null || bt.signingKey.isEmpty())) { bt.signingKey = e.id; try { q.save(); } catch (Throwable ignored) { } }
        }
        if (backup) { try { Keystores.backup(act, e); } catch (Throwable ignored) { } }
        return e;
    }

    /** Key details block for the result dialog. */
    static String resultText(BuildEngine.Result r) {
        if (r.keyName == null || r.keyName.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (r.keyDebug) sb.append("\n⚠ Signed with the debug key. Not for publishing: use Sign APK.");
        else sb.append("\n✓ Signed with ").append(r.keyName).append(" (release)");
        if (r.keySha256 != null && !r.keySha256.isEmpty()) sb.append("\nSHA-256 ").append(shortFp(r.keySha256));
        return sb.toString();
    }

    static File backupFile(IdeActivity act, String keyName) {
        for (Keystores.Entry e : Keystores.list(act)) if (e.name.equals(keyName)) { try { return Keystores.backup(act, e); } catch (Exception ignored) { } }
        return null;
    }

    static void copyDetails(IdeActivity act, String keyName) {
        for (Keystores.Entry e : Keystores.list(act)) if (e.name.equals(keyName)) {
            KeyTool.Info i = null;
            try { i = KeyTool.describe(e.path(act), e.storePass.toCharArray(), e.alias); } catch (Exception ignored) { }
            UI.copy(act, "Buildora key details", Keystores.details(e, i));
            UI.toast(act, "Key details copied. Store them safely.");
            return;
        }
        UI.toast(act, "Key not found");
    }
}
