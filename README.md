<p align="center"><img src="docs/logo.png" width="120" alt="Buildora"></p>

# Buildora

**An on-device Android app builder for Java + XML.** Write, build, sign and install real APKs entirely on your phone.
Buildora itself is written in Java + XML and is built with GitHub Actions.

```
ECJ (Java compiler) → aapt2 (resources) → D8 (dex) → zipalign-style packager → apksig (v1/v2/v3 signing)
```

## Build Buildora on GitHub (3 steps)

1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. *(Recommended)* Put an Android-ARM `aapt2` binary at `toolchain/aapt2-arm64-v8a` — see [`toolchain/README.md`](toolchain/README.md).
   If you skip this the Action tries to auto-download one, and if that fails you can import `aapt2` inside the app later.
3. Open the **Actions** tab → *Build Buildora APK* → **Run workflow**. When it finishes download the
   **Buildora-APK** artifact → `Buildora.apk`.

> The APK is ~50 MB because it carries `android.jar`, the Java compiler, D8, JGit and apksig.

## How the app works

| Screen | What you get |
|---|---|
| **Home** | List of your projects (name, package, version, date) · **＋** FAB · **⚙** settings (top right) |
| **＋ menu** | **New project** · **Import project** · **Clone repo** |
| **New project** | project name, app name, package id, app icon (+ corner radius slider, default = Buildora icon), min/target SDK (compile API auto), template, theme, orientation, version, internet permission |
| **Code IDE** | ☰ explorer · ▶ run · undo · redo · save · ⋮ menu (all small icons) · open-file tabs in one line · full-screen editor |
| **Explorer** | glass side panel, tree view, **＋** create file/folder, **⬇** import, long-press menus (file & folder) |
| **Run ▶** | live progress dialog → on success **Download / Install / Share**, on failure **Copy all errors** + **Close** |
| **Settings** | editor, build, signing keys (generate / import / export), toolchain, defaults, Git, storage |

Projects live in `/storage/emulated/0/Buildora/<Project_Name>/` and the built `<Project_Name>.apk` is placed right inside that folder.

## Features (45)

**Projects** 1 New-project wizard · 2 Import any folder (Gradle layouts auto-detected) · 3 Clone Git repo (name auto-filled) · 4 Four buildable templates (Empty, Counter, WebView, AppCompat) · 5 App icon picker + corner radius · 6 Gradle-compatible folder layout + `build.gradle` files · 7 Rename / duplicate / export-ZIP / delete / info · 8 Imports `applicationId`, SDKs, versions and dependencies from an existing `build.gradle`

**Explorer** 9 Tree view · 10 Create file/folder (Java & layout files get a skeleton) · 11 Import file/folder · 12 Rename · 13 Delete · 14 Duplicate · 15 Move / change path · 16 Export copy / ZIP · 17 Properties · 18 Copy path · 19 Install / share any APK in the tree · 20 Glass blur panel (Android 12+)

**Editor** 21 Syntax highlighting (Java, XML, JSON, Gradle, properties, Smali) · 22 Line numbers · 23 Two-finger pinch zoom · 24 Undo / redo · 25 Open-file tabs · 26 Auto-save · 27 Find & replace (regex, case) · 28 Go to line · 29 Auto-indent · 30 Auto-close brackets & XML tags · 31 Live red error lines (XML + brackets) · 32 Compiler errors marked in the editor after a failed build · 33 Code formatter · 34 Symbol bar · 35 Image preview · 36 6 colour schemes · 37 Custom fonts

**Build** 38 Full on-device pipeline · 39 Library manager (Maven resolver + AAR support, 48-library catalog, custom coordinates, local jar/aar) · 40 Manifest merge for libraries · 41 Dex cache for libraries · 42 Debug/release dex, auto version bump, clean · 43 Signing: generate / import / export keys, v1+v2+v3 · 44 Git commit / pull / push / status · 45 Toolchain manager (import / download aapt2, android.jar)

## Project layout of this repo

```
app/src/main/java/com/tenix/buildora/
  MainActivity, IdeActivity, SettingsActivity, KeystoreActivity, LibrariesActivity, BuildRunner
  core/    Project, Templates, FileUtil, FilePicker, IconUtil, Prefs, UI, Installer, Perm, Net
  editor/  CodeEditText, Highlighter, Linter, EditorScheme
  build/   BuildEngine, Toolchain, MavenResolver, ManifestMerger, ApkBuilder, Signer, KeyTool, Keystores, GitHelper, Catalog
.github/workflows/build.yml      CI
scripts/fetch_toolchain.py       bundles android.jar + aapt2 into the APK assets
toolchain/                       (optional) drop your aapt2 binaries here
```

## Design decisions & honest notes

* **`targetSdk 28` on purpose.** Android 10+ forbids executing files from app storage for newer targets, and `aapt2`
  must be executed. 28 also gives plain `java.io.File` access to `/storage/emulated/0/Buildora` (with "All files access").
* **Only Java + XML projects** are compiled (as requested). Kotlin libraries work as *dependencies* (their bytecode is dexed) but Kotlin sources are not compiled.
* **The Java language level is 1.8** (lambdas and method refs are supported via D8 desugaring).
* **Libraries:** AARs are extracted, their resources are linked with the app and their `R` classes generated (`--extra-packages`);
  manifests are merged with a simplified merger (permissions + components + `${applicationId}` placeholders).
  Big stacks such as AppCompat/Material are supported but are the most likely place to hit edge cases.
* Keystore passwords are stored in the app's private preferences.
* Annotation processors (Room, Dagger, …) are not run.

## Verification status — please read

I could not run Gradle, an Android emulator or a phone in the environment this was written in, so:

* ✅ All Java compiles with `javac` against the real API 34 `android.jar` (third-party APIs were checked against stubs of their public API).
* ✅ The self-signed-certificate generator, the aligned APK zip writer and the Maven resolver were executed and tested on a JVM
  (certificate verifies, `resources.arsc` and `.so` alignment correct, version ranges / properties / scopes resolved).
* ✅ All XML resources are well-formed and every `R.*` reference resolves.
* ⚠️ **Not run:** the Gradle/AGP build itself, the aapt2 resource step, ECJ and D8 *on a device*, and the apksig call.
  Those are standard APIs used the standard way, but expect to fix a small thing or two on first run.
  If the Action fails, open the failing step's log and send it to me — it is usually a one-line fix
  (e.g. a dependency version). If a build fails *inside the app*, the error dialog has **Copy all**.
