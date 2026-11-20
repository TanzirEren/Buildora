## Tag

```
v2.1.3
```

## Release title

```
Buildora v2.1.3 "Catalyst" — background AI, attachments, token counter, multi-key failover
```

## Release notes (paste into the description box)

```md
# Buildora v2.1.3 — Catalyst ⚗️

Buildora AI gets faster to read, smarter with your files, and keeps working when you leave the app.

## ✨ What's new

### 🎞 Thinking & typing animations
- Pulsing sparkle, bouncing dots and a status line that changes while the AI thinks
- While writing: the **file being written**, tokens so far and tokens per second
- Messages slide in; the send button breathes while the AI works; the AI icon in the IDE does too

### 🗂 File paths instead of code walls
- Code for your project shows as a compact **file row** (path · lines · new/edit)
- **Tap a path** to open its code; nothing is drawn until you open it, so long answers stay smooth
- Applies to the Buildora AI page and to the AI panel inside the IDE

### 🌙 Works in the background
- Requests keep running when you leave the app, switch apps or lock the screen (foreground service with a *Stop* button)
- When nobody is watching, the reply is **saved into the chat** and a notification tells you it is ready (tap to open)
- Optional: changes are applied to the project too when *Auto-apply* is on, with Undo
- Switches in Settings → Buildora AI: *Keep AI working in the background*, *Notify when AI finishes*

### ⚡ Token counter
- Small box left of the pencil icon (full screen and IDE panel), live while a reply is written
- Tap for: sent / written / last reply / next request estimate / all chats / API key in use; reset totals

### 🖼 Attachments, reworked
- **Photos & gallery**: pick many pictures at once · **Take a photo** · **Files** (any type) · Browse phone storage
- Project file · current file · selected code · last build log · **paste text or picture**
- Tiles above the message box (like the Claude app): picture thumbnails, file cards with type badge and size, PDF page count, remove (×), tap to preview
- **Share to Buildora AI** from other apps (pictures, PDFs, text, Word, Excel, zip, JSON)
- Attachments are shown in the sent message and stay available for the whole chat
- Up to 10 attachments per message; picture size and PDF pages are adjustable in Settings

### 👁 The AI sees and uses your files
- Pictures go to vision models, plus size and **dominant colours** as text for models without vision
- **PDF pages** are rendered and sent as pictures
- Reads **any text or code file** whatever its extension, **Word / Excel / PowerPoint / OpenDocument** text, **archive** contents (zip, jar, apk, aar) and a safe summary of unknown binary files; long files keep their start and end
- **Import into your project**: `import logo.png -> app/src/main/res/drawable/logo.png` (checked for safe paths and valid resource names, with Undo)
- The AI can look at an older attachment again; every chat keeps its attachments
- Rebuild a design from a screenshot, use your CSV/JSON as data, add fonts and sounds

### 🎨 New menus & dialogs
- Rounded menus with a vector icon on every row, grouped with dividers, opening above or below the button
- Used for the chat **＋** menu, the AI 3-dot menus, the chat history menus, providers, and the IDE 3-dot menu
- Dialogs have rounder corners

### 🔑 Several API keys per provider
- Add as many keys as you want per provider (paste several at once)
- When a key hits its limit, runs out of credit or is rejected, the **next key starts automatically**, even mid-conversation; you see a short note
- Per key: state (*in use / ready / limit reached – back in …*), on/off switch, delete; **Test** checks every key one by one; *Reset limits*
- Rest times: rate limit ≈ 1 min (or the server's *Retry-After*), quota/credit ≈ 6–12 h, rejected key 24 h
- Old single-key providers are upgraded automatically

## 📲 Update

Install `Buildora.apk` over your existing version — projects, chats, providers and keys are kept.

## ⚠️ Known limitations

- Pictures need a vision model; PDFs are sent as page pictures
- Imported attachments are copied as they are (no resizing / conversion)
- Token numbers are estimates when the provider does not report usage
- Background work needs the notification permission on Android 13+; battery savers may still stop apps
- The auto-fix + rebuild loop needs the IDE open; in the background the AI writes and applies, you build when you return
- API keys are stored unencrypted in the app's private storage

## 🐞 Found a bug?

Tap **Copy all** in the build error dialog (or copy from Logcat) and open an issue with your Android version and device.
```
