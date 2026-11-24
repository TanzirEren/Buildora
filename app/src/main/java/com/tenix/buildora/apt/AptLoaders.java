package com.tenix.buildora.apt;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Creates the class loader that holds the annotation processors and their dependencies.
 * On Android it is a DexClassLoader over dexed jars (ART cannot load Java bytecode); on a desktop JVM
 * (tests, harness) a URLClassLoader over the original jars. Both are "child first" so a processor's own
 * Guava/JavaPoet/... wins over anything inside Buildora, except the platform and the JSR 269 API,
 * which must be shared with the compiler.
 */
public final class AptLoaders {

    private static final String[] PARENT_FIRST = {
            "java.", "javax.annotation.processing.", "javax.lang.model.", "javax.tools.", "android.", "dalvik.",
            "libcore.", "sun.", "jdk.", "org.eclipse.jdt.", "com.tenix.buildora.apt.", "org.w3c.", "org.xml.", "org.json."
    };

    private AptLoaders() { }

    public static boolean parentFirst(String name) {
        for (String p : PARENT_FIRST) if (name.startsWith(p)) return true;
        return false;
    }

    public static ClassLoader create(List<File> jars, ClassLoader parent) throws Exception {
        if (isAndroid()) {
            StringBuilder sb = new StringBuilder();
            for (File f : jars) { if (sb.length() > 0) sb.append(File.pathSeparatorChar); sb.append(f.getPath()); }
            Class<?> c = Class.forName("com.tenix.buildora.apt.AndroidDexLoader");
            return (ClassLoader) c.getConstructor(String.class, ClassLoader.class).newInstance(sb.toString(), parent);
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toURI().toURL();
        return new ChildFirstUrl(urls, parent);
    }

    public static boolean isAndroid() {
        try { Class.forName("dalvik.system.DexClassLoader"); return true; } catch (Throwable t) { return false; }
    }

    private static final class ChildFirstUrl extends URLClassLoader {
        ChildFirstUrl(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (parentFirst(name)) {
                        c = super.loadClass(name, false);
                    } else {
                        try { c = findClass(name); } catch (ClassNotFoundException e) { c = super.loadClass(name, false); }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
