# Python to APK template

Mi_Max Ai uses Buildozer and python-for-android to package a Python/Kivy application as an Android APK.

## Required files

- `main.py` — entry point. It must start a Kivy application and return a Kivy widget from `App.build()`.
- `requirements.txt` — optional, one Python package requirement per line.

The builder adds `python3,kivy`, targets both `armeabi-v7a` and `arm64-v8a`, and builds a Debug APK on GitHub Actions.

## Compatibility

Pure-Python dependencies are usually the easiest to package. Packages containing C/C++ extensions need a compatible python-for-android recipe and Android ABI support. A package working in desktop Python does not automatically work on Android. The workflow log identifies dependency or compilation failures; unsupported dependencies are not silently omitted.

This is a hosted build. Mi_Max Ai stores and edits project files on the phone, but does not bundle a local Android SDK/NDK or Python cross-compilation toolchain.
