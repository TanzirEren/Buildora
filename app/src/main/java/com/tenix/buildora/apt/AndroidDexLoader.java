package com.tenix.buildora.apt;

import dalvik.system.DexClassLoader;

/** Child-first DexClassLoader for processor jars (instantiated reflectively by {@link AptLoaders}). */
public final class AndroidDexLoader extends DexClassLoader {

    public AndroidDexLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (AptLoaders.parentFirst(name)) {
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
