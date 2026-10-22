package com.tenix.buildora.build;

import java.io.File;
import java.io.IOException;

/** Everything the build engine needs from its surroundings (Android app or a desktop test harness). */
public interface BuildEnv {
    File aapt2();
    File androidJar();
    File lambdaStubs();
    File m2Dir();
    File dexCacheDir();
    /** Extract bundled tools if needed. */
    void prepare() throws IOException;
    String aapt2Version();

    boolean incremental();
    boolean cleanIntermediates();
    boolean autoVersion();
    boolean dexRelease();
    boolean verifySignature();
    /** Debug builds report crashes to Buildora. */
    default boolean crashReports() { return true; }
    /** Detect libraries from imports / layout tags and add them automatically. */
    default boolean autoLibraries() { return true; }
    /** Alignment for native libraries: 4096 or 16384 (16 KB page size devices). */
    int nativeAlign();

    /** keyId: "" = the key selected in Settings, "debug" = Buildora debug key, otherwise a keystore id. */
    Signer.Key signingKey(String keyId) throws Exception;
}
