package com.tenix.buildora.build;

import com.tenix.buildora.core.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the libraries a project needs by reading its imports and layout tags. */
public final class LibraryDetector {
    private LibraryDetector() {}

    public static final class Suggestion {
        public Catalog.Lib lib;
        public String reason;
        public boolean auto() { return lib.status == Catalog.OK; }
    }

    /** package prefix -> catalog library name (longest prefix wins). */
    private static final String[][] MAP = {
            {"androidx.appcompat.", "AppCompat"}, {"androidx.core.", "AndroidX Core"}, {"com.google.android.material.", "Material Components"},
            {"androidx.constraintlayout.", "ConstraintLayout"}, {"androidx.recyclerview.", "RecyclerView"}, {"androidx.viewpager2.", "ViewPager2"},
            {"androidx.cardview.", "CardView"}, {"androidx.swiperefreshlayout.", "SwipeRefreshLayout"}, {"androidx.coordinatorlayout.", "CoordinatorLayout"},
            {"androidx.fragment.", "Fragment"}, {"androidx.activity.", "Activity"}, {"androidx.navigation.", "Navigation Component"},
            {"androidx.preference.", "Preference"}, {"androidx.drawerlayout.", "DrawerLayout"}, {"androidx.webkit.", "WebViewAssetLoader (WebKit)"},
            {"androidx.biometric.", "Biometric"}, {"androidx.browser.", "Browser (Custom Tabs)"}, {"androidx.work.", "WorkManager"},
            {"androidx.lifecycle.", "Android Lifecycle"}, {"androidx.room.", "Room"}, {"androidx.camera.", "CameraX"}, {"androidx.media3.", "Media3 ExoPlayer"},
            {"androidx.datastore.", "DataStore (Preferences)"}, {"androidx.exifinterface.", "ExifInterface"},
            {"com.google.gson.", "Gson"}, {"com.squareup.moshi.", "Moshi"}, {"com.fasterxml.jackson.", "Jackson"}, {"org.json.JSONObject", null},
            {"okhttp3.logging.", "OkHttp + Logging Interceptor"}, {"okhttp3.", "OkHttp"}, {"retrofit2.converter.gson.", "Retrofit + Gson"},
            {"retrofit2.converter.moshi.", "Retrofit + Moshi"}, {"retrofit2.", "Retrofit"}, {"com.android.volley.", "Volley"}, {"org.jsoup.", "Jsoup"},
            {"com.bumptech.glide.", "Glide"}, {"com.squareup.picasso.", "Picasso"}, {"coil.", "Coil"}, {"com.facebook.drawee.", "Fresco"},
            {"com.facebook.fresco.", "Fresco"}, {"com.yalantis.ucrop.", "uCrop"}, {"com.github.chrisbanes.photoview.", "PhotoView"},
            {"de.hdodenhof.circleimageview.", "CircleImageView"}, {"com.airbnb.lottie.", "Lottie"}, {"com.facebook.shimmer.", "Shimmer"},
            {"com.github.mikephil.charting.", "MPAndroidChart"}, {"com.google.zxing.", "ZXing"}, {"com.journeyapps.barcodescanner.", "ZXing Android Embedded"},
            {"com.google.mlkit.", "ML Kit Barcode Scanning"}, {"com.google.firebase.auth.", "Firebase Authentication"},
            {"com.google.firebase.database.", "Firebase Realtime Database"}, {"com.google.firebase.firestore.", "Firebase Firestore"},
            {"com.google.firebase.storage.", "Firebase Storage"}, {"com.google.firebase.messaging.", "Firebase Cloud Messaging"},
            {"com.google.firebase.analytics.", "Firebase Analytics"}, {"com.google.firebase.crashlytics.", "Firebase Crashlytics"},
            {"com.google.firebase.remoteconfig.", "Firebase Remote Config"}, {"com.google.android.gms.maps.", "Play Services Maps"},
            {"com.google.android.gms.location.", "Play Services Location"}, {"com.google.android.gms.auth.", "Play Services Auth (Google Sign-In)"},
            {"com.google.android.gms.ads.", "Play Services Ads"}, {"io.reactivex.rxjava3.android.", "RxAndroid"}, {"io.reactivex.rxjava3.", "RxJava"},
            {"kotlinx.coroutines.", "Kotlin Coroutines"}, {"kotlin.", "Kotlin stdlib"}, {"org.greenrobot.eventbus.", "EventBus"}, {"timber.log.", "Timber"},
            {"com.orhanobut.logger.", "Logger"}, {"com.orhanobut.hawk.", "Hawk"}, {"com.tencent.mmkv.", "MMKV"}, {"org.koin.", "Koin"},
            {"org.apache.commons.io.", "Commons IO"}, {"org.apache.commons.lang3.", "Commons Lang3"}, {"org.apache.commons.codec.", "Commons Codec"},
            {"org.apache.commons.compress.", "Apache Commons Compress"}, {"net.lingala.zip4j.", "Zip4j"}, {"org.bouncycastle.", "Bouncy Castle"},
            {"com.google.common.", "Guava (Android)"}, {"com.chuckerteam.", "Chucker"}, {"com.squareup.leakcanary.", "LeakCanary"},
            {"com.facebook.stetho.", "Stetho"}, {"org.litepal.", "LitePal"}, {"com.j256.ormlite.", "ORMLite"}, {"net.zetetic.", "SQLCipher"},
            {"io.paperdb.", "Paper"}, {"com.koushikdutta.async.", "AndroidAsync"}, {"com.google.android.flexbox.", "Flexbox Layout"},
            {"com.airbnb.epoxy.", "Epoxy"}, {"dagger.hilt.", "Hilt"}, {"dagger.", "Dagger"}, {"org.smali.", "Dexlib2"}, {"com.android.tools.r8.", "R8"},
    };

    private static Catalog.Lib byName(String name) {
        for (Catalog.Lib l : Catalog.ALL) if (l.name.equals(name)) return l;
        return null;
    }

    /** The catalog library that provides this package / class, or null. */
    public static Catalog.Lib forPackage(String fq) {
        String best = null;
        String libName = null;
        for (String[] e : MAP) {
            if (e[1] == null) continue;
            if ((fq + ".").startsWith(e[0]) || fq.startsWith(e[0])) {
                if (best == null || e[0].length() > best.length()) { best = e[0]; libName = e[1]; }
            }
        }
        return libName == null ? null : byName(libName);
    }

    private static String ga(String coord) {
        String[] p = coord.trim().split(":");
        return p.length >= 2 ? p[0] + ":" + p[1] : coord;
    }

    private static boolean alreadyHas(Catalog.Lib l, List<String> deps) {
        if (l.coords.length == 0) return false;
        for (String c : l.coords) {
            boolean has = false;
            for (String d : deps) if (ga(d).equals(ga(c))) has = true;
            if (!has) return false;
        }
        return true;
    }

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern TAG = Pattern.compile("</?([a-z][\\w]*(?:\\.[\\w]+)+)");

    public static List<Suggestion> detect(List<File> javaDirs, List<File> xmlDirs, List<String> existingDeps) {
        Map<String, Suggestion> found = new LinkedHashMap<>();
        for (File d : javaDirs) {
            for (File f : FileUtil.listFiles(d, ".java")) {
                try {
                    Matcher m = IMPORT.matcher(FileUtil.readText(f));
                    while (m.find()) add(found, m.group(1), "import " + m.group(1) + " in " + f.getName(), existingDeps);
                } catch (IOException ignored) { }
            }
        }
        for (File d : xmlDirs) {
            for (File f : FileUtil.listFiles(d, ".xml")) {
                try {
                    Matcher m = TAG.matcher(FileUtil.readText(f));
                    while (m.find()) add(found, m.group(1), "<" + m.group(1) + "> in " + f.getName(), existingDeps);
                } catch (IOException ignored) { }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void add(Map<String, Suggestion> found, String fq, String reason, List<String> deps) {
        Catalog.Lib l = forPackage(fq);
        if (l == null || found.containsKey(l.name) || alreadyHas(l, deps) || l.status == Catalog.TOOL) return;
        Suggestion s = new Suggestion();
        s.lib = l;
        s.reason = reason;
        found.put(l.name, s);
    }
}
