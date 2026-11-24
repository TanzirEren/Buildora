<p align="center">
  <img src="docs/logo.png" width="128" alt="Buildora logo">
</p>

<h1 align="center">Buildora</h1>

<p align="center">
  <b>Build real Android apps with Java &amp; XML — entirely on your phone.</b><br>
  Write code, compile, sign and install an APK without a PC.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Language" src="https://img.shields.io/badge/built%20with-Java%20%2B%20XML-3F7EB8">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</p>

---

## What is Buildora?

Buildora is an on-device app builder. It contains a code editor and a complete build pipeline, so you can
create a project, press **▶**, and get a signed, installable APK in seconds — no computer, no cloud build.

```
Java sources ──ECJ──▶ .class ──D8──▶ dex ─┐
Resources / Manifest ──aapt2──▶ resources ─┴─▶ package ──apksig──▶ signed APK
```

Buildora itself is written in Java + XML and is built with GitHub Actions.

## Download

Get the latest `Buildora.apk` (v2.1.5 Radiant) from the **[Releases](../../releases)** page and install it.
Android asks you to allow installing from your browser/file manager the first time.

**Requirements:** Android 8.0 (API 26) or newer, an ARM phone, ~300 MB free storage.
On first launch allow **All files access** — projects are stored in shared storage so you can reach them from any file manager.

## What's new in v2.1.5 "Radiant"

**Java annotation processing, on the device.** Code generators (annotation processors) now run during a build. Tested with the real AutoValue and AutoService processors; Room, Glide, Dagger and Hilt use the same mechanism but are still *experimental* (not verified).

- **Real JSR 269 engine** - Buildora ships the real `javax.annotation.processing` / `javax.lang.model` / `javax.tools` API and runs processors through ECJ's annotation-processing engine (Java 8 level).
- **Annotation processors screen** (IDE ⋮ menu): add a Maven coordinate or a local `.jar`, switch processors on or off, limit them to build types, set options (`-A` arguments), timeout and execution switches, and read the generated sources.
- **Automatic dependencies** - processors and their transitive dependencies come from Google Maven, Maven Central and JitPack, are cached, version conflicts are resolved (highest wins and is logged), and **Offline builds** (Settings) use the cache only.
- **Isolated process** - processors run in a separate Android process with a timeout. A crash, hang or out-of-memory only stops that process; it is killed after every run.
- **Incremental processing** - when every processor is declared *isolating* only the generated files of changed sources are rebuilt; any other processor (or an unknown one) re-runs over all sources, and the log says why.
- **Clear diagnostics** - the build log lists discovered processors, rounds, timings and every generated file with its origin. Errors name the exact processor and source line; errors inside generated code say which processor and source produced it; a crash shows a trimmed stack trace; a timeout names the processor that was running.
- **Gradle interop** - `annotationProcessor '…'` lines and `annotationProcessorOptions` arguments can be pasted in. `kapt`, `ksp` and `project(':x')` lines are listed as **not supported**, never faked.
- The catalog entries for Room, Glide, Dagger, Hilt and Epoxy offer their processor in one tap.

## What's new in v2.1.4 "Fusion"

- **Sign APK** button on the *Build successful* dialog: rebuilds the release variant and signs it with a real key (v1 + v2 + v3, verified).
- **Export AAB** now builds a release bundle signed with a real release key. A 2048-bit RSA key (30 years) is generated automatically when you have none and reused for every later release. Options: choose another key, R8 on/off, version-code bump, key backup to `Buildora/keystores`. The result shows the key name and SHA-256, plus *Back up key* and *Copy key details*.
- **Buildora AI agent mode**: the AI can call 35 tools (read, search, edit, refactor, diagnostics, real builds, shell, Python if present, web search, crash logs, Git) and loops until the build passes. Every file change is backed up and can be undone. See Settings, Buildora AI.

## What's new in v2.1.3 "Catalyst"

**Buildora AI, faster and more capable.**

- **Animated thinking and writing** - a pulsing sparkle with a live status line (it shows the file being written, tokens and tokens/second), smooth message animations.
- **Files shown as paths** - the AI's code appears as a compact file row; tap a path to open the code. Long answers no longer fill the screen.
- **Works in the background** - requests keep running when you leave the app or lock the screen (foreground service). When it is done you get a notification, and the reply is saved in the chat.
- **Token counter** in the chat header (left of the pencil icon): tokens used by this chat, details for the last reply, the next request and all chats.
- **Attachments, reworked** - pick many pictures from the Gallery at once, take a photo, add files of any type, share from other apps into Buildora AI. Pictures and files appear as tiles in the chat box, with preview and remove.
- **The AI can use what you attach** - it sees your pictures (and PDF pages), reads any text or code file, Word / Excel / PowerPoint / OpenDocument text, archive contents and unknown binary files, and can **import an attached file into your project** (icons, fonts, data).
- **New menus** - rounded menus with vector icons for the chat **＋** button, the AI 3-dot menus, the chat history and the IDE 3-dot menu.
- **Several API keys per provider** - add as many keys as you like; when one reaches its limit or is rejected, the next one starts automatically. Per-key status, switch, test and reset.

## What's new in v2.1.2 "Pioneer"

**Buildora AI** - build and fix apps by chatting, with your own AI provider.

- **AI providers** (Settings, before *Build*): 20 presets (OpenRouter, OmniRouter, OpenAI, Gemini, Groq, Anthropic, DeepSeek, Mistral, xAI, Together, Fireworks, Cerebras, NVIDIA NIM, SambaNova, Hyperbolic, SiliconFlow, Moonshot, Perplexity, Ollama, LM Studio) and **custom providers** (name, base URL, API key, model ids line by line). **Fetch models**, a **FREE** tag, *All / Free only / None*, per-model and per-provider switches, test connection, default model, import/export (without keys).
- **Chat screen** (FAB → *Build with Buildora AI*): Markdown replies in a small font, select any text and tap **Reply**, attachments (project files, device files, images, clipboard, selection, build log), model picker, stop, regenerate, edit & resend.
- **Chat history drawer**: search, pinned chats, project folders, custom folders, and a 3-dot menu per chat (rename, pin, move, duplicate, export, open project, clear, delete).
- **Build an app in chat**: the AI writes the files, you review the diff, tap *Create project*, then open it in the IDE. **Undo** any AI change.
- **In the IDE**: the AI icon left of Run opens the same chat from the right, with the project, open files and your selection as context, *Find bugs*, *Write code*, *Insert at cursor*, and more.
- **Buildora AI Fix**: on the *Build failed* dialog, send the errors and a master prompt to the AI; it reads the project, fixes the files, rebuilds and retries (up to the number of rounds you choose).

Your code is sent to the provider **you** choose only when you use Buildora AI. API keys stay in the app's private storage.

## What's new in v2.1.1 "Horizon"

- **Smooth editing on long files** - typing, scrolling and the line numbers no longer slow down as a file grows. Only the part of the file you can see is coloured,
  error checking runs in the background, and the line numbers use an index instead of scanning the whole file on every frame.
- **Edit existing projects** - tap a project, choose **Edit project**, and change the name, app name, package id, icon, SDK levels, version name/code and the internet permission.
  Changing the package id moves your source folders and rewrites the package in your code, manifest and Gradle files.
- **Editor display fix** - the blank band at the bottom of the editor (code was not drawn there) is gone.
- **Settings** - *Write & send issues* opens a new GitHub issue; the About section now credits **Developer Tanzir** and has Telegram, Website, GitHub and Developer GitHub shortcuts.

## What's new in v2.1.0 "Overdrive"

- **Code completion** - Java and XML suggestions while you type: members through call chains (`getResources().getString`), your own classes and fields, `R.id.*` / `R.string.*`,
  XML tags, `android:` attributes, attribute values and `@string/…` resources. Accepting a class adds its import. The **⌁** key opens suggestions on demand.
- **Logcat viewer** - live phone log with level, text and "my app" filters. Debug builds of your apps report **crashes** straight to Buildora (no permission needed)
  with a notification and a Crashes tab. Optional one-time `READ_LOGS` grant shows every app's log.
- **Layout preview** - tap the small eye icon on any layout XML: your real compiled layout is rendered with phone and tablet sizes, portrait/landscape,
  day/night, tap-to-inspect (class, id, size) and **Go to XML** for the selected view. Screenshots are saved to `outputs/preview`.
- **README / HTML preview** - the same eye icon previews `.md` and `.html` files.
- **Refactoring** - rename (symbols, classes with their file/layout tags/manifest entries, resources with their files) with a reviewable match list,
  **extract method**, and **organize imports**.
- **Auto libraries** - Buildora reads your imports and layout tags, adds the libraries you need and downloads them with a live progress dialog
  (file, MB, files done). Missing-library errors get a one-tap **Fix**, and **Detect libraries** is in the menu.

## What's new in v2.0.0

- **Faster builds** - incremental builds reuse compiled resources and only recompile the Java files you changed (plus the files that use them).
  Nothing changed? Run finishes instantly. If a cache ever goes stale, Buildora retries once with a clean cache by itself.
- **Builds survive leaving the app** - they run in a foreground service with a progress notification (Cancel button included) and a wake lock, so a locked screen no longer stops them.
- **Clickable errors with plain-language hints** - tap an error to jump to the file and line. Missing import? One tap adds it. **Copy all** is still there.
- **126 libraries in the catalog** - every library from the community list plus Retrofit + Moshi / Gson, OkHttp interceptors, Room, Material 3, Navigation, Lottie,
  MPAndroidChart, Glide, Coil, Firebase (with `google-services.json` support), WorkManager, DataStore, ZXing, Media3/ExoPlayer, CameraX, Biometric and Play Services.
  Use `group:artifact` (or `+`) for the latest stable version; a missing pinned version falls back to the latest one.
- **Build variants** - debug / release / your own build types and product flavors with their own application id, version suffix, signing key,
  libraries, `BuildConfig` fields and source sets (`app/src/<flavor>/java`, `res`, `AndroidManifest.xml`).
- **Multi-module projects** - add Android or Java library modules, choose what the app and each module use.
- **Release builds** - R8 shrinking, obfuscation and optimisation with a `mapping.txt`, **AAB export**, signature verification and a zipalign / 16 KB check.
- **ProGuard rules editor** with one-tap presets (Gson, Retrofit/OkHttp, Moshi, Glide, Firebase, Room, WebView JS, Kotlin, EventBus, remove logs, ...).
- **App icon from the Gallery** - pick an icon straight from your photos for new projects and existing ones.

## Quick start

1. Open Buildora and tap **＋ → New project**.
2. Choose a name, package id, icon and template, then **Create**.
3. In the IDE tap **▶ Run**. When the build finishes tap **Install** (or **Download**).

Projects live in `/storage/emulated/0/Buildora/<Project_Name>/` and the finished
`<Project_Name>.apk` is placed right inside that folder.

## Features

**Projects**
- New project wizard (name, app name, package id, icon + corner radius, min/target SDK, theme, orientation)
- 4 buildable templates: Empty Activity, Counter, WebView, AppCompat
- App icon from the Gallery, a file, or the default Buildora icon (with corner radius)
- Import any folder (Gradle-style layouts are detected, including dependencies from `build.gradle`)
- Clone a Git repository
- Rename, duplicate, export as ZIP, delete

**Code IDE**
- Syntax highlighting for Java, XML, JSON, Gradle, properties and Smali
- Line numbers, open-file tabs, undo / redo, auto-save
- Two-finger pinch zoom
- Find &amp; replace (regex), go to line, code formatter
- Auto-indent, auto-close brackets and XML tags, symbol bar
- Live red error lines for XML / bracket mistakes, and compiler errors marked in the editor after a failed build
- 6 colour schemes, custom fonts, light / dark app theme

**File explorer**
- Glass side panel with a tree view
- Create / import files and folders
- Long-press menus: rename, duplicate, move, export, delete, properties, copy path, install / share APKs

**Build &amp; signing**
- Fully on-device pipeline: ECJ → aapt2 → D8 / R8 → packager → apksig (v1 + v2 + v3), incremental
- Library manager: Maven resolver (Google Maven, Maven Central, JitPack), AAR support, a 126-entry catalog, custom coordinates, latest-version lookup and local `.jar` / `.aar` files
- Generate, import and export signing keys; choose the active key
- Live build log, copyable error report, install / download / share the APK

**Git** — commit, pull, push and status (with a personal access token).

## Building Buildora from source

Buildora is built by GitHub Actions — no local Android setup needed.

1. Fork or upload this repository to GitHub.
2. Make sure the Android-native `aapt2` binaries are in [`toolchain/`](toolchain/README.md)
   (`aapt2-arm64-v8a`, optionally `aapt2-armeabi-v7a`).
3. Open **Actions → Build Buildora APK → Run workflow**.
4. Download the **Buildora-APK** artifact.

The workflow installs JDK 17 and the Android SDK platform, bundles `android.jar` and `aapt2` into the
app assets (`scripts/fetch_toolchain.py`), and runs `gradle :app:assembleDebug`.

## Project structure

```
app/src/main/java/com/tenix/buildora/
  MainActivity · IdeActivity · SettingsActivity · KeystoreActivity · LibrariesActivity · VariantsActivity · ModulesActivity · ProguardActivity
  BuildRunner · BuildManager · BuildService (foreground service)
  core/    projects, templates, file picker, icons, preferences, installer
  editor/  code editor view, highlighter, linter, colour schemes
  build/   build engine, incremental Java, Maven resolver, manifest merger, APK/AAB writers, R8 shrinker, signer, error hints, catalog, Git helper
app/src/main/java/javax/lang/model/   SourceVersion shim (Android has no javax.lang.model; ECJ needs it)
.github/workflows/build.yml           CI
scripts/fetch_toolchain.py            bundles android.jar + aapt2 into the APK
toolchain/                            Android-native aapt2 binaries
```

## Good to know

- **Java 8 + XML projects.** Buildora compiles Java sources at language level 1.8 (lambdas and method references work).
  Kotlin *libraries* can be used as dependencies, but Kotlin source files are not compiled.
- **Annotation processors** run through ECJ (Java 8 level) from *Annotation processors* in the ⋮ menu. Gradle plugins do not exist on the device: **no KSP, no kapt (Kotlin), no Hilt Gradle plugin class transform**. Processors that need javac internals (`com.sun.tools.javac`, e.g. Lombok) do not work. Room, Glide, Dagger and Hilt are *experimental*: they are not verified on a device yet.
- **Modules are compiled together with the app** (merged sources, resources and manifests), not as separate artifacts.
- **R8 shrinks code only**; resource shrinking is not included. AAB export produces a single `base` module.
- **`targetSdk` is 28 on purpose.** Android 10+ blocks executing binaries from app storage for newer targets, and
  Buildora has to run `aapt2`. It also keeps plain file access to `/storage/emulated/0/Buildora`.
- Large library stacks (AppCompat, Material, …) use a simplified manifest merger and are the most likely place to hit edge cases.
- Signing-key passwords are stored in the app's private preferences.
- Builds run on-device, so heavy projects need RAM; the first build with libraries is slower (dex results are cached).

## Troubleshooting

| Problem | Fix |
|---|---|
| *aapt2 cannot run* | Put the right-architecture binary in `toolchain/`, or use **Settings → Toolchain → Import aapt2** |
| Build error | Tap the error to jump to the line, or **Copy all** and open an issue with the text |
| Odd build behaviour after a crash | **Settings → Build → Clear incremental build cache** |
| Can't see projects / save fails | Grant **All files access** in Android settings |
| Dependency download fails | Check your internet connection, or remove the library in **Libraries** |

## Contributing

Issues and pull requests are welcome. Please include your Android version, device architecture and the copied build
log when reporting a build problem.

## Third-party components

Buildora bundles open-source software, each under its own license: the Eclipse Compiler for Java (ECJ),
R8/D8, apksig, Eclipse JGit, SLF4J, AndroidX, the Android SDK `android.jar` and `aapt2`.
Please see each project for its license terms.

## License

Buildora's own source code is released under the [MIT License](LICENSE).
