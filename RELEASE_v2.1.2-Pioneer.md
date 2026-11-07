## Tag

```
v2.1.2
```

## Release title

```
Buildora v2.1.2 "Pioneer" — Buildora AI: chat, build and auto-fix apps
```

## Release notes (paste into the description box)

```md
# Buildora v2.1.2 — Pioneer 🧭

Build and fix apps by chatting with your own AI provider — right inside Buildora.

## ✨ What's new

### 🤖 Buildora AI chat
- New entry in the **＋** menu: **Build with Buildora AI**
- Markdown answers in a small, readable font; code blocks with Copy
- **Reply** to any selected text of an answer
- Chat box: grows with your text, **＋** to attach (project file, device file, image, current file, selection, last build log, clipboard), model picker with the model id, send / stop
- Regenerate, edit & resend, copy, share, delete from here
- Token estimate for long messages, drafts are kept per chat

### 🗂 Chat history drawer (3-line icon, top left)
- Search all chats
- Pinned chats, **project folders**, your own folders, Today / Yesterday / Earlier
- 3-dot menu per chat: rename · pin · move to project or folder · duplicate · export (share, .md, copy) · open project in IDE · clear · delete
- Folders: new, rename, delete (keep or delete chats), collapse

### 🔌 AI providers (Settings → Buildora AI, before Build)
- 20 presets: OpenRouter, OmniRouter, OpenAI (GPT), Google Gemini, Groq, Anthropic, DeepSeek, Mistral, xAI, Together, Fireworks, Cerebras, NVIDIA NIM, SambaNova, Hyperbolic, SiliconFlow, Moonshot, Perplexity, Ollama, LM Studio
- **Custom provider**: name, base URL, API key, model ids (one per line)
- **Fetch models** — free models get a **FREE** tag; select **All**, **Free only**, **None** or pick your own; search
- Switch every provider and every model on or off; several providers at once
- Test connection, refresh models, make default, duplicate, delete
- Show/hide/paste API key; export and import providers (keys are never exported)

### 🛠 Build and fix apps in chat
- The AI writes whole files or small patches; you see every change as a **diff** and choose what to apply
- **Create project** from a chat, then open it in the IDE
- **Undo** any applied AI change
- Libraries the AI asks for are added to the project
- Optional **auto-apply** for AI changes

### 🧩 AI inside the IDE
- **AI icon** left of ▶ Run opens the chat from the right side
- Sends the project tree, open files, the current file and your selection
- Quick actions: Find bugs (with live lint results), Fix this file, Explain, Optimize, Add comments, Write code, Add feature, Make layout, Review, Security check, Unused code, Error handling, Responsive layout, Dark mode, README
- **Insert** a code block at the cursor; the editor reloads files the AI changed

### 🔧 Buildora AI Fix
- **✦ Buildora AI Fix** button in the top-right of the **Build failed** dialog
- Sends the errors, code around each error and a master prompt to the AI chat; the AI reads the project, may ask for more files, and fixes it
- Optionally **rebuilds and retries** (1–5 rounds, Settings → Buildora AI)

## 🔐 Privacy
Buildora AI sends your messages, attachments and the project files it needs **to the provider you configured** — nothing else, and only when you use it. API keys are stored in the app's private storage.

## 📲 Update

Install `Buildora.apk` over your existing version — projects and keys are kept.

## ⚠️ Known limitations

- Needs your own API key and internet; usage may cost money depending on the provider. *FREE* tags come from the provider's pricing or free tier.
- AI answers can be wrong: review the diff, use Undo.
- The AI works on Java + XML projects (no Kotlin, no annotation processors)
- Image attachments need a model that supports images
- OmniRouter: check the base URL in your dashboard and edit it if it differs

## 🐞 Found a bug?

Tap **Copy all** in the build error dialog (or copy from Logcat) and open an issue with your Android version and device.
```
