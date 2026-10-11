package com.tenix.buildora.build;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Signer {
    private Signer() {}

    public static String sign(Context c, File unsigned, File out, int minSdk) throws Exception {
        Keystores.Entry k = Keystores.active(c);
        KeyStore ks = KeyTool.load(k.path(c), k.storePass.toCharArray(), null);
        PrivateKey pk = KeyTool.privateKey(ks, k.alias, k.keyPass.toCharArray());
        if (pk == null) throw new Exception("Key alias '" + k.alias + "' not found in " + k.name);
        Certificate[] chain = ks.getCertificateChain(k.alias);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate cert : chain) certs.add((X509Certificate) cert);

        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder("CERT", pk, certs).build();
        if (out.exists()) out.delete();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(Math.max(1, minSdk))
                .setV1SigningEnabled(minSdk < 24)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        return k.name;
    }
}
