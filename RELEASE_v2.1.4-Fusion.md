## Tag

```
v2.1.4
```

## Release title

```
Buildora v2.1.4 "Fusion" — Sign APK, signed AAB export, AI agent with 35 tools
```

## Release notes (paste into the description box)

```md
# Buildora v2.1.4 — Fusion 🔗

Build, sign and ship from your phone, and let Buildora AI work like a real agent.

## ✍️ Sign APK
- New **Sign APK** button under *Download* on the *Build successful* dialog (also in the ⋮ menu)
- Rebuilds the **release** variant and signs it with a real key (v1 + v2 + v3), then verifies it
- The result shows the key name and SHA-256; a debug-key build is clearly marked "not for publishing"

## 📦 Export AAB (signed)
- Builds a **release** bundle signed with a real key, never the debug key
- **No key yet? One is generated automatically** (2048-bit RSA, valid 30 years, random password) and reused for later releases of the project
- Options: choose another key · R8 shrink on/off · raise the version code · back up the key to `Buildora/keystores`
- After the build: **Back up key** and **Copy key details** (alias + passwords) buttons

## 🤖 Buildora AI agent (35 tools)
Agent mode is on by default (Settings, Buildora AI). The AI calls tools, reads the results and keeps going until the job is done.

**Look:** list_files · read_file · search_text · find_files · file_info · find_usages · list_resources · project_info · class_outline · project_stats
**Change (all undoable):** write_file · append_file · replace_lines · insert_lines · replace_all · make_dir · delete_path · move_path · copy_path · undo_last
**Refactor:** rename_class (file, Java references, layout tags, manifest) · rename_resource (layouts, ids, strings, drawables)
**Check & build:** diagnostics (XML syntax, brackets, imports, package/file names, undefined R.* and @resources, manifest components and permissions) · build_project (real build, errors with code lines, debug / release / aab) · read_crash_logs · install_app
**Run:** run_shell · run_python (only if the phone has Python) · search_web · fetch_url
**Project:** add_dependency · remove_dependency · set_version · git_status · git_commit · plan

- Build → read errors → fix → build again, until it succeeds
- Shell and Python ask before running (Settings: ask / always / never); dangerous commands are blocked
- Settings: agent steps per request (4 to 30), web access on/off
- Works on the Buildora AI page and in the IDE AI panel; the Stop button also stops a running tool

## 🔐 Signed release
`Buildora.apk` is now signed with the official Buildora release key (v1 + v2 + v3).

## 📲 Update
- Builds before v2.1.4 were signed with a debug key, so Android will refuse to install this APK over them. **Before updating:** export your signing keys (Settings, Signing keys). Your projects in `Buildora/` stay on the phone. Then uninstall the old Buildora and install v2.1.4.
- From v2.1.4 on, new releases install over this one and keep projects, keys and chats.

## ⚠️ Known limitations
- The agent loop needs the chat open; if you leave the app mid-task the current reply is saved but tools do not continue
- `run_python` needs a Python interpreter on the phone (Buildora does not bundle one)
- `search_web` reads public search pages and may fail if the site blocks the request
- Java 8 + XML only; no annotation processors or Kotlin sources

## 🐞 Found a bug?
Tap **Copy all** in the build error dialog and open an issue with your Android version and device.
```
