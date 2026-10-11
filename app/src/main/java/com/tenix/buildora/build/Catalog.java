package com.tenix.buildora.build;

/** Built-in catalog of popular Android/Java libraries the user can add with one tap. */
public final class Catalog {
    private Catalog() {}

    public static final class Lib {
        public final String name, coord, desc, group;
        Lib(String group, String name, String coord, String desc) {
            this.group = group; this.name = name; this.coord = coord; this.desc = desc;
        }
    }

    public static final Lib[] ALL = {
            // AndroidX
            new Lib("AndroidX", "AppCompat", "androidx.appcompat:appcompat:1.6.1", "Backwards compatible Activity, themes, toolbar"),
            new Lib("AndroidX", "Core", "androidx.core:core:1.12.0", "Core utilities, FileProvider, NotificationCompat"),
            new Lib("AndroidX", "ConstraintLayout", "androidx.constraintlayout:constraintlayout:2.1.4", "Flexible constraint based layout"),
            new Lib("AndroidX", "RecyclerView", "androidx.recyclerview:recyclerview:1.3.2", "Efficient scrolling lists and grids"),
            new Lib("AndroidX", "CardView", "androidx.cardview:cardview:1.0.0", "Rounded card container"),
            new Lib("AndroidX", "ViewPager2", "androidx.viewpager2:viewpager2:1.0.0", "Swipeable pages with tabs"),
            new Lib("AndroidX", "SwipeRefreshLayout", "androidx.swiperefreshlayout:swiperefreshlayout:1.1.0", "Pull to refresh"),
            new Lib("AndroidX", "Fragment", "androidx.fragment:fragment:1.6.2", "Fragments"),
            new Lib("AndroidX", "Activity", "androidx.activity:activity:1.8.2", "Activity result APIs, back handling"),
            new Lib("AndroidX", "Preference", "androidx.preference:preference:1.2.1", "Settings screens"),
            new Lib("AndroidX", "WebKit", "androidx.webkit:webkit:1.9.0", "Modern WebView features"),
            new Lib("AndroidX", "Browser (Custom Tabs)", "androidx.browser:browser:1.7.0", "Chrome Custom Tabs"),
            new Lib("AndroidX", "DrawerLayout", "androidx.drawerlayout:drawerlayout:1.2.0", "Navigation drawer"),
            new Lib("AndroidX", "CoordinatorLayout", "androidx.coordinatorlayout:coordinatorlayout:1.2.0", "Scrolling behaviours"),
            new Lib("AndroidX", "GridLayout", "androidx.gridlayout:gridlayout:1.0.0", "Grid layout"),
            new Lib("AndroidX", "ExifInterface", "androidx.exifinterface:exifinterface:1.3.7", "Read/write image EXIF"),
            new Lib("AndroidX", "DocumentFile", "androidx.documentfile:documentfile:1.0.1", "Storage Access Framework helper"),
            new Lib("AndroidX", "Material Components", "com.google.android.material:material:1.11.0", "Material Design widgets and themes"),
            // networking
            new Lib("Network", "OkHttp 4", "com.squareup.okhttp3:okhttp:4.12.0", "HTTP client"),
            new Lib("Network", "OkHttp 3 (Java only)", "com.squareup.okhttp3:okhttp:3.12.13", "HTTP client, pure Java"),
            new Lib("Network", "Retrofit", "com.squareup.retrofit2:retrofit:2.9.0", "Type-safe REST client"),
            new Lib("Network", "Retrofit Gson converter", "com.squareup.retrofit2:converter-gson:2.9.0", "Gson converter for Retrofit"),
            new Lib("Network", "Volley (Google)", "com.android.volley:volley:1.2.1", "HTTP library by Google"),
            new Lib("Network", "Jsoup", "org.jsoup:jsoup:1.17.2", "HTML parser / scraper"),
            // images
            new Lib("Images", "Glide", "com.github.bumptech.glide:glide:4.16.0", "Image loading and caching"),
            new Lib("Images", "Picasso", "com.squareup.picasso:picasso:2.71828", "Image loading"),
            new Lib("Images", "CircleImageView", "de.hdodenhof:circleimageview:3.1.0", "Circular ImageView"),
            new Lib("Images", "PhotoView", "com.github.chrisbanes:PhotoView:2.3.0", "Zoomable ImageView (JitPack)"),
            new Lib("Images", "Lottie", "com.airbnb.android:lottie:6.3.0", "Lottie animations"),
            new Lib("Images", "uCrop", "com.github.yalantis:ucrop:2.2.8", "Image cropping (JitPack)"),
            // UI helpers
            new Lib("UI", "MPAndroidChart", "com.github.PhilJay:MPAndroidChart:v3.1.0", "Charts (JitPack)"),
            new Lib("UI", "Shimmer", "com.facebook.shimmer:shimmer:0.5.0", "Shimmer loading effect"),
            new Lib("UI", "Android-SpinKit", "com.github.ybq:Android-SpinKit:1.4.0", "Loading animations (JitPack)"),
            new Lib("UI", "Markwon", "io.noties.markwon:core:4.6.2", "Markdown renderer"),
            // data
            new Lib("Data", "Gson", "com.google.code.gson:gson:2.10.1", "JSON serialization"),
            new Lib("Data", "org.json", "org.json:json:20231013", "JSON reference implementation"),
            new Lib("Data", "Jackson Databind", "com.fasterxml.jackson.core:jackson-databind:2.15.3", "JSON data binding"),
            new Lib("Data", "Guava (Android)", "com.google.guava:guava:33.0.0-android", "Google core libraries"),
            // utils
            new Lib("Utils", "Commons IO", "commons-io:commons-io:2.15.1", "File and stream utilities"),
            new Lib("Utils", "Commons Lang3", "org.apache.commons:commons-lang3:3.14.0", "String/Object helpers"),
            new Lib("Utils", "Commons Codec", "commons-codec:commons-codec:1.16.0", "Base64, hex, digests"),
            new Lib("Utils", "Commons Compress", "org.apache.commons:commons-compress:1.25.0", "zip/tar/7z"),
            new Lib("Utils", "ZXing core", "com.google.zxing:core:3.5.2", "QR / barcode encode & decode"),
            new Lib("Utils", "EventBus", "org.greenrobot:eventbus:3.3.1", "Publish/subscribe events"),
            new Lib("Utils", "Timber", "com.jakewharton.timber:timber:5.0.1", "Logging"),
            new Lib("Utils", "RxJava 3", "io.reactivex.rxjava3:rxjava:3.1.8", "Reactive streams"),
            new Lib("Utils", "RxAndroid 3", "io.reactivex.rxjava3:rxandroid:3.0.2", "Android main thread scheduler"),
            new Lib("Utils", "Kotlin stdlib", "org.jetbrains.kotlin:kotlin-stdlib:1.9.22", "Kotlin runtime (for Kotlin libraries)"),
    };
}
