# Mi_Max Ai .mt1 Extension API v1

A `.mt1` file is a ZIP container with a different extension. It must contain a root `manifest.json`.

## Supported package types

- `theme`: declarative theme colors in `resources/theme.json`.
- `ui`: declarative screen in `app/screen.json`.
- `tool`: declarative tool page in `app/tool.json`, executed only through the host's allow-listed native actions.

The host does **not** load arbitrary DEX/JAR/native libraries, run shell commands, or execute JavaScript from an extension. This prevents a downloaded extension from gaining unrestricted access to the app process.

## Tool manifest

```json
{
  "format": "MiMaxExtension",
  "formatVersion": 1,
  "apiVersion": 1,
  "id": "sample_file_tools",
  "name": "Sample File Tools",
  "version": "1.0.0",
  "type": "tool",
  "description": "File information and inspection tools",
  "permissions": []
}
```

## app/tool.json

```json
{
  "title": "File Tools",
  "actions": [
    {"id":"info","title":"File information","kind":"file_info"},
    {"id":"hash","title":"Calculate SHA-256","kind":"sha256"},
    {"id":"hex","title":"Hex preview","kind":"hex_preview"},
    {"id":"text","title":"Text preview","kind":"text_preview"},
    {"id":"detect","title":"Detect image signature","kind":"detect_image"}
  ]
}
```

## Host actions currently implemented

| kind | Behavior |
|---|---|
| `file_info` | Read display name, size and MIME type |
| `sha256` | Calculate SHA-256 using a streaming read |
| `hex_preview` | Show the first 4096 bytes as hexadecimal |
| `text_preview` | Show up to 16 KiB as UTF-8 text |
| `detect_image` | Inspect known file signatures; not a full integrity check |

## Installation and safety

- Maximum extracted package size: 20 MiB.
- Maximum files: 300.
- Tool specification: maximum 128 KiB.
- Maximum 20 actions per tool package.
- Only the action kinds listed above are accepted.
- Paths are checked against ZIP traversal.
- Extensions are stored in the app-private files directory.
- Tool extensions require explicit enabling before opening.

## Runtime and language extensions

A language/runtime package is **not executable in API v1**. A future API must provide a real compatible runtime adapter (including ARM32/ARM64 support, permissions, lifecycle, and resource limits) before a language package can be advertised as runnable. Merely bundling a script or naming a language does not make it executable.
