# HTML to APK template

Mi_Max Ai packages a complete HTML/CSS/JavaScript project into a standalone Android APK using a native Android WebView wrapper.

## Project layout

- `main.html` — required entry page.
- Any adjacent CSS, JavaScript, images, fonts, JSON and other project assets — included in the APK under Android assets, preserving folders.

The wrapper opens `file:///android_asset/main.html`; it does not load your project from the internet. JavaScript and DOM storage are enabled. Network access is not granted by default in the generated wrapper.

## Build

Open the project in Mi_Max Ai Developer Studio and choose **Build APK via GitHub Actions**. The app uploads the project source and metadata to `builder-input/<job-id>/`; the workflow builds a Debug APK and publishes it as an Actions artifact.

The generated Debug APK is signed with the runner's debug key. It is suitable for testing, but the key is not stable between separate hosted runs. A release keystore workflow is not configured yet.
