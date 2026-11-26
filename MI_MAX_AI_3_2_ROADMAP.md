# Mi_Max Ai 3.2 — Master implementation checklist

This file is the persistent scope register for the 3.2 upgrade. A feature is **not complete** until implemented, built, and tested. Never mark a feature complete because a screen, button, stub, or placeholder exists.

## Non-negotiable product rules
- Preserve the current Mi_Max Ai 3.0.3 visual identity, default theme, navigation order, colors, typography, and existing screens. No redesign without explicit user approval.
- Preserve existing AI, chat, browser, history, downloads, widgets, ROM tools, project tools, and extension functionality.
- Keep the original main branch and the 3.1 development branch untouched. Work only on `feature/mimaxai-3.2-full-upgrade`.
- Do not claim a feature works until its behavior is implemented and tests pass. Record platform limitations honestly.
- Target Android 14 Go / ARM32 first, with ARM64 support where practical.

## Scope and status

### A. AI core — NOT STARTED
- [ ] Preserve existing AI provider, conversations, chat history, local memory, prompts, settings, and widgets.
- [ ] Discover compatible OpenRouter models dynamically; separate text and image/audio model slots where supported.
- [ ] Temperature and generation settings; image/file input; coding and Android/ROM assistance.
- [ ] Integrate AI assistance into tools without silently changing user files.
- [ ] Protect API credentials; include them only in password-encrypted full backups.

### B. Android ROM Lab — PARTIAL / AUDIT REQUIRED
- [ ] Real extraction/edit/rebuild flows for boot, vendor_boot, init_boot, recovery, system, vendor, product, system_ext, super, logo.bin, logo.img, vbmeta, dtbo, odm, userdata.
- [ ] Sparse/raw conversion; EXT4 and EROFS/F2FS support; CPIO/ramdisk compression; LP metadata (lpunpack/lpdump/lpmake); AVB inspection; hex editor.
- [ ] Extract to folders, edit supported files, rebuild supported formats, validate outputs, preserve originals.
- [ ] Explicitly report unsupported compression/filesystem/partition variants; never fake support.

### C. MT1 extension platform — PARTIAL / AUDIT REQUIRED
- [ ] Install/update/remove extensions; local store; tool/engine/language/theme/UI extension types.
- [ ] Per-extension permissions/capabilities, compatibility/dependency checks, signed or integrity-checked packages.
- [ ] Auto-disable failing extensions, last-known-good recovery, independent maintenance/recovery mode.
- [ ] Per-extension logs, developer kit, extension updates independent from APK where safe.
- [ ] Sandboxed declarative/script APIs; no implicit root or unrestricted OS access.

### D. Standalone terminal and runtimes — NOT STARTED
- [ ] Remove Termux app dependency, RUN_COMMAND permission, Termux receiver/query/bridge paths.
- [ ] Embed an Android-compatible shell/tool runtime and package manager with actual ARM32 binaries; ARM64 binaries when available.
- [ ] Python, Git and common Android/Linux utilities where build/runtime compatibility allows.
- [ ] Sessions, tabs, history, environment, workspace, offline installed tools.
- [ ] No root assumption; clearly distinguish Android shell from a full Linux distribution.

### E. Internal file manager — PARTIAL / AUDIT REQUIRED
- [ ] Browse internal storage, app workspace, downloads, USB OTG and SD card when present/authorized.
- [ ] List/grid, sorting, search, details, multi-select, create/copy/move/rename/delete, favorites/recent.
- [ ] Open files directly in installed internal tools; persistent SAF access for removable storage where supported.
- [ ] Explain Android restrictions on protected paths instead of promising unrestricted access.

### F. Smart download manager — PARTIAL / AUDIT REQUIRED
- [ ] 1/2/4/8/16 segments, adaptive concurrency, verify HTTP Range before splitting.
- [ ] Automatic single-stream fallback when Range is unsupported; safe merge and size/hash checks.
- [ ] Pause/resume, persisted queue, retries, notifications, background work, per-part progress.
- [ ] Never display fake segment counts or progress.

### G. Developer Studio and builders — PARTIAL / AUDIT REQUIRED
- [ ] Kotlin/Java/XML/HTML/CSS/JS/Python editors and project manager.
- [ ] Offline HTML preview, real local Python runtime, dependency manager, logs and diagnostics.
- [ ] Real HTML-to-APK builder and Android build pipeline; explain which builds need GitHub Actions/toolchain.
- [ ] Templates, project import/export, backups and restore.

### H. APK and device tools — PARTIAL / AUDIT REQUIRED
- [ ] APK manifest/resources/DEX/signature inspection; APK/APKS/XAPK handling.
- [ ] ADB/Fastboot and Logcat where the connection and Android permissions allow.
- [ ] Device/ABI/partition/boot image compatibility checks and safe operation previews.

### I. Browser — PARTIAL / AUDIT REQUIRED
- [ ] Preserve existing browser and three-dot rail.
- [ ] Tabs, desktop mode, fullscreen, bookmarks, history, downloads, find-in-page, sharing, permissions, popup handling.
- [ ] Integrate browser downloads with the download manager.

### J. Widgets and weather — PARTIAL / AUDIT REQUIRED
- [ ] Real Android home-screen widgets and in-app widgets: AI/search, clock/date, weather, battery, downloads, storage/device, extension widgets.
- [ ] Weather location permission, automatic location, city/country/region, current/hourly/7-day forecast, manual fallback, offline cache timestamp.
- [ ] Widget size/shape/transparency/colors/gradients/glass/fonts/icons and layout customization.
- [ ] Respect Android widget refresh/interaction limits.

### K. Developer customization and themes — PARTIAL / AUDIT REQUIRED
- [ ] Keep current base theme and appearance unchanged by default.
- [ ] Developer options can be enabled and disabled with an explicit control; disabling hides and locks developer-only controls without deleting their saved data.
- [ ] UI layout editor, optional Liquid Glass, theme import/export, reset and last-known-good recovery.
- [ ] External launcher icon aliases where Android launchers support them.
- [ ] Changes are opt-in and reversible.

### L. Full backup and restore — INCOMPLETE, HIGH PRIORITY
- [ ] Password-encrypted full archive of app-owned persistent files, databases, SharedPreferences, chats, memory, prompts, models, credentials, projects, extensions, installed app-managed engines, themes, browser state and settings.
- [ ] Include app-specific external files and removable-storage content only when the user explicitly selects/grants access.
- [ ] Full and selective backup/restore; automatic scheduled/pre-update backup where Android permits.
- [ ] Stage and validate restore before replacing live data; keep a rollback copy; verify SHA-256 and report skipped/inaccessible items.
- [ ] Never claim access to Android-protected data, other apps' private data, OS settings or files the app cannot read. State those limits clearly.

### M. Operations, diagnostics and reliability — NOT STARTED
- [ ] Background work for long tasks, resumable operations where possible, operation center and logs.
- [ ] Resource-aware behavior for low-RAM devices; large-file streaming and cancellation.
- [ ] Integrity checks, SHA-256, XML/JSON validation, file comparison, autosave and crash recovery.
- [ ] Central update center for app, engines, extensions, language packs and themes.
- [ ] Compatibility checks and actionable diagnostics.

### N. App controls — NOT STARTED
- [ ] Explicit developer-options on/off control.
- [ ] Real application restart action that relaunches the launcher activity; preserve pending work and stored data.
- [ ] Restart into maintenance/recovery mode where implemented.
- [ ] Restart/reload UI after theme or extension changes without losing data.

## Implementation log
- Baseline: branch `feature/mimaxai-3.1-complete-upgrade`, commit `5d6a4f43fb3268fa8a28855296fad24688f5a722`.
- Baseline GitHub Actions run `45782018386`: success; artifact `MiMaxAi-3.0-Debug-APK`.
- New work branch: `feature/mimaxai-3.2-full-upgrade`.
- [ ] 3.2.0 implementation work not yet validated.
