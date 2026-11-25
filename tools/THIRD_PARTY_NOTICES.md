# Third-party notices: Android logical-partition tools

Mi_Max Ai packages Android logical-partition command-line tools built from the AOSP-derived source tree maintained at:

- https://github.com/itsNileshHere/android-lptools
- Pinned source revision: 7ec860cfa95ed83dec579ab0459aad1c35ad48e4

The upstream project states that its sources are from the Android Open Source Project. The AOSP `partition_tools` sources (including `lpunpack`, `lpmake`, and `lpdump`) are licensed under the Apache License, Version 2.0. Copyright notices and license headers are retained in the upstream source files.

The Android build script compiles these tools for `armeabi-v7a` and `arm64-v8a`; it does not download prebuilt executables at app runtime. The resulting executables are packaged as app assets and run locally, offline.

This app does not package `lpflash` and does not write images to device block partitions. Repacking creates a separate output image.
