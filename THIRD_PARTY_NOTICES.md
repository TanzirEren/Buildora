# Third-party notices

## Java Compiler API (javax.annotation.processing, javax.lang.model, javax.tools)

Located in `app/src/main/java/javax/`. These are the real OpenJDK 17 `java.compiler` module sources
(GPL v2 with the Classpath Exception), shipped because Android does not provide the JSR 269 API
that annotation processors and ECJ's annotation-processing engine are written against.

Changes for Android (everything else is unmodified):
- `javax.tools.ToolProvider`: no module system / system compiler on Android; the methods return `null`.
- `javax.lang.model.SourceVersion`: `latestSupported()` is `RELEASE_8` (Buildora compiles at Java 8 level).
- `javax.tools.StandardJavaFileManager`: private interface helpers became package-visible statics
  (Java 8 source level); `var` / diamond-on-anonymous-class removed in `ElementScanner14`.
- `module-info.java` removed.

Licensed under GPLv2 + Classpath Exception: linking these classes into Buildora does not
change the MIT license of Buildora's own code.

## Others
ECJ (EPL-2.0), R8/D8 (BSD-3), apksig (Apache-2.0), JGit (EDL), SLF4J (MIT), AndroidX (Apache-2.0).
