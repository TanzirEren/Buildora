/*
 * Copyright (c) 2005, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package javax.tools;

/**
 * Provides methods for locating tool providers.
 *
 * <p>Android adaptation: the JDK locates tools through the module system, which does not exist on
 * Android. There is no system Java compiler or documentation tool on a device, so (as the API
 * allows) the methods return {@code null}. Buildora runs ECJ directly instead.
 */
public class ToolProvider {

    private ToolProvider() {}

    /** @return {@code null}: no system compiler is provided on Android */
    public static JavaCompiler getSystemJavaCompiler() { return null; }

    /** @return {@code null}: no documentation tool is provided on Android */
    public static DocumentationTool getSystemDocumentationTool() { return null; }

    /** @return the class loader of this class */
    public static ClassLoader getSystemToolClassLoader() { return ToolProvider.class.getClassLoader(); }
}
