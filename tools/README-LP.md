# Mi_Max Ai — Native Android Logical Partition Tools

This integration uses AOSP-derived command-line implementations rather than the previous Java-only LP repacker.

## Tools packaged in the APK

- `lpunpack`: extracts logical partition images from a raw super image.
- `lpmake`: builds a new super image from partition image files and LP metadata parameters.
- `lpdump`: prints the parsed LP metadata for inspection.

The app's existing Java Sparse reader/writer performs Sparse ↔ Raw conversion around these commands. All operations run locally after installation; the app does not download executables at runtime.

## Architectures

The workflow compiles the pinned source for:

- `armeabi-v7a` (32-bit ARM)
- `arm64-v8a` (64-bit ARM)

The app selects the binary matching its process ABI, copies it into private app storage, validates its ELF machine type, and marks it read-only before execution.

## Reproducible source

Run `tools/build-aosp-lp-tools-android.sh` from the repository root with Android NDK 27.2.12479018 installed. It fetches the pinned source revision and stages the native tools under `app/src/main/assets/lp-tools/<abi>/`. The Android workflow runs this staging step before Gradle packages app assets.

## Safety and compatibility checks

- Extraction writes to a staging directory and only replaces visible partition files after output-size checks pass.
- Repacking writes a new file; it does not flash or write to device block partitions.
- Repacked output is checked with `lpdump`, extracted again with `lpunpack`, and compared against every input partition (allowing only zero padding).
- Repacking is refused for split/multiple block-device super images, unsupported LP flags, ZERO extents unsupported by the pinned `lpunpack`, or A/B metadata slots whose layouts/extents differ. These cases must not be silently rewritten with incomplete metadata.

The APK has not been built or device-tested as part of this source integration. Build and on-device verification remain pending explicit authorization.
