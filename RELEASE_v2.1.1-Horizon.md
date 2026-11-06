## Tag

```
v2.1.1
```

## Release title

```
Buildora v2.1.1 "Horizon" — smooth long-file editing, edit projects, editor fix
```

## Release notes (paste into the description box)

```md
# Buildora v2.1.1 — Horizon 🌅

Smoother editing, projects you can edit after creating them, and a few fixes.

## ✨ What's new

### ⚡ Smooth on long files
- Syntax colours are applied only around the part of the file you see (and follow you as you scroll), instead of re-colouring the whole file after every pause
- Error checking (red lines) runs in the background, so typing is never blocked
- Line numbers and the status bar use a line index instead of scanning the whole file on every frame and every caret move
- Code completion drops outdated requests and reuses the resource index for a few seconds
- The editor no longer stores the whole file when Android saves the screen state

### ✏️ Edit existing projects
- Tap a project → **Edit project** opens the same form as *New project*, filled with the project's values
- Change **project name**, **app name**, **package id**, **icon** (and corner radius), **min / target SDK**, **version name / code** and the **internet permission**
- A new package id moves your source folders and rewrites the package in Java files, imports, the manifest and Gradle files
- Tapping a project now shows the project menu (Open, Edit project, Rename, Duplicate, Export as ZIP, Project info, Delete)

### 🛠 Fixes
- **Blank band at the bottom of the editor** — code was not drawn in the lowest part of the editor; fixed

### ⚙️ Settings
- **Git → Write & send issues** opens a new issue on GitHub
- **About** now reads *Developer Tanzir*
- Four contact icons under About: Telegram, Website, GitHub and Developer GitHub

## 📲 Update

Install `Buildora.apk` over your existing version — projects and keys are kept.

## ⚠️ Known limitations

- Edit project can't change the template, theme or orientation of an existing project
- After a package or folder change the next build starts from a clean cache (it can take longer once)
- Java 8 + XML only; no annotation processors yet
- Completion understands Java types from `android.jar` and your own sources; library classes are not indexed yet
- Layout preview: complex library views need a first build; no fragments or data binding

## 🐞 Found a bug?

Use **Settings → Git → Write & send issues**, or tap **Copy all** in the build error dialog and open an issue with your Android version and device.
```
