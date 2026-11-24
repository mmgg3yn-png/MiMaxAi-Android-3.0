# Mi_Max Ai Extension API 2.0

Installable extensions use an `.mt1` ZIP package containing `manifest.json` and (for JavaScript extensions) `main.js`. Extensions are installed, updated, disabled, and rolled back from Extension Manager; adding or updating an extension does not require rebuilding the APK.

## Manifest example

```json
{
  "id": "example.power.extension",
  "name": "Power Extension",
  "version": "1.0.0",
  "description": "Example using the native app extension bridge",
  "runtime": "javascript",
  "min_app_version": 32,
  "max_android_sdk": 1000,
  "permissions": [
    "ui_control",
    "theme_control",
    "app_settings",
    "app_navigation",
    "read_selected_file",
    "write_selected_file",
    "manage_extensions"
  ],
  "action": {
    "label": "Run extension",
    "handler": "javascript"
  }
}
```

## JavaScript API

- `api.getApiVersion()`, `api.getAppVersion()`, `api.getAppInfo()`
- `api.toast(message)`, `api.showMessage(title, message)`, `api.log(message)`
- `api.showUi(json)`: builds a native in-app panel. Items support `text` and `button`; button actions support `toast:...`, `message:...`, and `tool:...`.
- `api.openTool(name)`: opens an allow-listed app screen: `home`, `extensions`, `logo_bin`, `terminal`, `browser`, `agent`, `capabilities`, `backup`, `pc_bridge`.
- `api.applyTheme(json)`, `api.resetTheme()`: save custom app palette colors. Required core keys: `base`, `surface`, `surface2`, `line`, `tx`, `mut`, `ac`, `er`. Optional: `b0` through `b3`. Values are `#RRGGBB` or `#AARRGGBB`.
- `api.getAppSetting(key)`, `api.setAppSetting(key, value)`: allow-listed settings only: `model`, `audioModel`, `temp`, `prompt`, `style`, `theme`.
- `api.hasSelectedFile()`, `api.getSelectedFileName()`, `api.readSelectedText()`, `api.writeSelectedText(text)`. Writes create a timestamped backup next to the selected file.
- `api.listExtensions()`, `api.setExtensionEnabled(id, enabled)`
- `api.readData(name)`, `api.writeData(name, value)`: private data directory belonging to this extension only.

## Permissions

Permission identifiers are user-controlled in Extension Manager:

| Permission | Allows |
|---|---|
| `ui_control` | Create native extension panels |
| `theme_control` | Apply/reset the app's supported custom color palette |
| `app_settings` | Read/write the documented app settings |
| `app_navigation` | Open allow-listed app screens |
| `read_selected_file` | Read the selected file as UTF-8 text (up to 1 MiB) |
| `write_selected_file` | Replace the selected file as UTF-8 text (up to 1 MiB), with a backup |
| `manage_extensions` | List and enable/disable installed extensions |

## Important platform boundary

This API is intentionally powerful inside the documented Mi_Max Ai host surface, but it is not Android root. Java class reflection, arbitrary DEX/native loading, shell execution, direct Android system modification, and unrestricted access to other apps are not exposed. Android permissions and user-granted file access still apply. New host capabilities require an app update once; extensions using already-exposed APIs can then be added or updated as `.mt1` packages without another APK build.
