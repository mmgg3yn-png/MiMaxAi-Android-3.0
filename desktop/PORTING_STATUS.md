# Mi_Max Ai Windows parity plan

This file is the source-of-truth checklist for bringing the Windows desktop build to functional parity with the Android application. A feature is not marked complete merely because a tab or button exists: it must have a working Windows implementation and a test.

## Design contract
- Keep the Android visual identity: navigation hierarchy, card shapes, spacing, typography, icon language, theme palette, and settings vocabulary.
- Reuse the exact Android theme definitions and settings semantics wherever platform-independent.
- Windows-specific implementations may replace Android APIs, but must preserve the user-facing capability.
- Do not show a feature as supported until its Windows implementation and test are present.
- Keep x86 support; avoid dependencies that require x64-only native binaries.

## Android feature inventory

| Android source | Capability to preserve | Windows implementation direction | Status |
|---|---|---|---|
| MainActivity / Store | Home, chat, settings, local profile, prompt, memory, model choices, temperature, font scale, motion, stream, auto-memory | Desktop shell + persistent local profile/settings/chat store | Pending |
| Theme / AppPalette / Backdrop / Glass | All six palettes, custom colors, three styles, animated glass backdrop | Shared palette model and Swing custom painting; preserve exact theme values | In progress: palette identifiers added; full visual parity pending |
| Api / Models / Vault | OpenRouter, model discovery, text/image/audio model selection, API key handling | HTTP client, model catalogue, secure local key storage, modality-aware requests | Partial: basic text request only |
| AgentActivity | Agent task workspace, file attachment, proposed changes, export, optional PC connection | Sandboxed workspace and explicit approval for file changes | Pending |
| BrowserActivity / FastDownloadTask | Browser, tabs, downloads, desktop browsing | Embedded browser engine and download manager; system browser fallback only if embedded engine unavailable | Pending |
| FileScreen / EditorScreen | File manager, long-press/context actions, text editing, file operations | Native filesystem browser/editor with safe confirmations | Pending |
| ZipScreen / Archives / ArchivePack | Browse, preview, edit, delete, extract, CRC, create ZIP/MT1/MT_1 | Shared archive engine + desktop file dialogs | Partial: listing/extraction/creation subset |
| ImgScreen + engine | Detect, sparse/raw, super metadata, partition extraction/repack, ext4 operations, logo.bin | Shared Java engines; validate each operation and large-file handling | Partial |
| BootImg / Cpio / Ext4 / Ext4Edit / Lp / Sparse / LogoBin | Boot/vendor_boot, ramdisk, ext4, logical partitions, MediaTek logo | Shared engines plus desktop workflows | Partial; individual workflows need verification |
| TerminalActivity / TermuxBridge / TerminalSessionStore | Persistent terminal sessions, commands, packages, Python/pip workflows | Native Windows terminal + optional bundled Python; separate WSL integration when available | Pending |
| DeveloperStudioActivity / LocalApkBuilder / BinaryManifestPatcher | Project workspace, APK tooling/build workflows | Windows-compatible build tools; Android APK build remains distinct from Windows EXE build | Pending |
| ExtensionActivity / ExtensionEngine / ExtensionScreenActivity | MT1 extensions, enable/disable, permissions, UI extensions, recovery and logs | Sandboxed extension host, manifest validation, per-extension permissions and rollback | Pending |
| ThemeStudioActivity | Create/edit/export themes | Theme editor using the same palette schema and MT_1 package format | Pending |
| MediaUi | Audio/video playback, seek/speed controls and media actions | Windows media backend with codec capability detection | Pending |
| BackupActivity | Export/import settings, chats, memory, prompt and theme without API key | Versioned JSON backup with validation and restore confirmation | Pending |
| RemotePcActivity | Authenticated PC agent status and command bridge | Local authenticated agent; restrict commands and require confirmation for sensitive actions | Pending |
| AskAiWidgetProvider / SearchWidgetProvider | Android home-screen widgets | Windows tray/desktop quick actions where supported | Platform adaptation required |
| Vault / ApkInstaller | Key vault and APK installation flow | Windows credential storage; APK install via configured ADB only | Platform adaptation required |

## Completion gates
1. Inventory every Android screen, setting, action, and engine entry point.
2. Implement a Windows equivalent or a clearly labeled platform-specific replacement.
3. Test on Windows x86 and x64, including paths with Arabic characters and large files.
4. Verify settings persistence, backup/restore, and theme switching.
5. Compare screenshots against Android for every major screen and all themes.
6. Publish new installers only after both architectures pass.

## Current honest state
The current desktop build is an early port, not feature parity. The AI screen is text-only and minimal; ROM/archive/logo tools cover only a subset; the newly added palette list is not yet the complete Android appearance system. Do not describe this build as complete.
