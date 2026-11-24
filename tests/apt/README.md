# Annotation-processing tests

- `AptTest.java` (99 checks): config/Gradle parsing, processor discovery, offline resolution + version conflicts, D8 conversion,
  state/incremental logic and end-to-end processor semantics (rounds, claiming, options, errors, crashes, missing classes)
  through the real `ProcessorHost` and ECJ.
- `AptBuildHarness.java`: runs the real `BuildEngine` (aapt2, ECJ + processors, D8, apksig) on a project directory.
- `fixtures/`: small real processors compiled by the tests.
- `make_m2.py`: builds an offline Maven repository from real jars (AutoValue, AutoService, Guava, …) for the resolution tests.
- `../run_apt_tests.sh`: runs everything on a JVM **without** the `java.compiler` module, so the JSR 269 API comes from Buildora's
  own sources, like on Android. Needs `TC` (android.jar, lambda-stubs.jar), `ECJ_JAR`, `R8_JAR`, `JSON_JAR` (and `M2` for resolution tests).
