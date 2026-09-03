# Dialer Tweaks 📞

An LSPosed module tailored for **Google Dialer (`com.google.android.dialer`)**, bringing force-enabled call recording and stealth voice announcement muting (including Pixel's AI-powered **Call Notes / Fermat** feature).

---

## ✨ Features

- **Force Enable Call Recording**: Bypasses regional and carrier restrictions in Google Dialer using DexKit pattern matching and Telephony country ISO interception.
- **Disable Voice Announcement**: Mutes standard call recording announcements ("This call is now being recorded").
- **Disable Call Notes Announcement**: Mutes AI Call Notes (`Fermat` / `SODA`) announcements and beeps without breaking the recording state machine.
- **Stealth Muting Engine ("Execute but Silent")**: Uses stack-trace shape analysis and volume/PCM muting rather than blocking control flow, preventing defensive app crashes.
- **Material 3 UI & Granular Master Logging**: Built-in settings activity with Material 3 design and customizable debug logging.

---

## 📱 Requirements & Compatibility

- **Android Version**: Android 14+ (API 34+)
- **Target App**: Google Dialer (`com.google.android.dialer`)
- **Framework**: LSPosed Framework (API 102+)
- **Call Notes Muting Notice**: Standard call recording features work on supported Google Dialer builds. **Call Notes (Fermat AI Summary)** muting specifically targets Android 14+.

---

## 🛠️ Architecture & Technical Highlights

- **LSPosed API 102**: Modern module structure with `META-INF/xposed/` metadata declaration.
- **Anti-Obfuscation via Stack Trace Shape**: Identifies audio callers based on call stack heuristics (e.g., `media` callers in Dialer, `NotificationPlayer` in `SystemUI`) rather than volatile obfuscated class names (`oea.c`, `hsk.b`).
- **Secure Cross-Process IPC**: Inter-process communication using `DeviceProtectedStorageContext`, signature-verified Broadcast Receivers, and UID validation.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

Anyone is free to use, study, modify, and redistribute this codebase under the terms of the GPL-3.0 license. See [LICENSE](./LICENSE) for the full text.

---

## 📚 Credits & Acknowledgments

- **Inspiration**: Early technical insights and Dialer hook patterns inspired by [vvb2060/CallRecording](https://github.com/vvb2060/CallRecording)
- **LSPosed Team**: For the LSPosed framework and API 102 specifications.
- **DexKit**: For native dex pattern matching library.
