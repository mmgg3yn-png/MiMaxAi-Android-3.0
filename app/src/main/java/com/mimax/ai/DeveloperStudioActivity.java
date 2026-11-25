package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Project workspace for HTML and Python source projects.
 * HTML APKs are built as a WebView Android wrapper; Python APKs use Buildozer/Kivy on a Linux PC Bridge.
 */
public class DeveloperStudioActivity extends Activity {
    private static final int SAVE_APK = 930;
    private File workspace, pendingApk;
    private EditText hostField, tokenField, editor;
    private TextView status;
    private File currentProject;
    private boolean currentHtml;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private TextView label(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(4), dp(5), dp(4), dp(5)); return t;
    }
    private Button button(String s, Runnable action) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        workspace = new File(getFilesDir(), "workspace");
        if (!workspace.exists()) workspace.mkdirs();
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        showHome();
    }

    private LinearLayout root(String titleText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.setBackgroundColor(0xFFF1F4F8);
        TextView title = label(titleText, 21, 0xFF17212D);
        title.setTypeface(null, 1);
        root.addView(title);
        return root;
    }

    private void showHome() {
        LinearLayout root = root("Mi_Max Ai • Developer Studio");
        root.addView(label("مساحة مشاريع HTML وPython. محرر محلي، معاينة HTML، وبناء APK عبر PC Bridge.", 13, 0xFF536273));
        hostField = new EditText(this);
        hostField.setSingleLine(true);
        hostField.setText(Store.get("dev_pc_host", ""));
        hostField.setHint("PC Bridge: http://192.168.1.x:8765");
        root.addView(hostField, new LinearLayout.LayoutParams(-1, dp(46)));
        tokenField = new EditText(this);
        tokenField.setSingleLine(true);
        tokenField.setText(Store.get("dev_pc_token", ""));
        tokenField.setHint("رمز PC Bridge");
        root.addView(tokenField, new LinearLayout.LayoutParams(-1, dp(46)));
        root.addView(button("حفظ اتصال الكمبيوتر", () -> saveConnection()));
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("مشروع HTML جديد", () -> createProject(true)), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(button("مشروع Python جديد", () -> createProject(false)), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        root.addView(label("مشاريعك", 17, 0xFF17212D));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        File[] dirs = workspace.listFiles();
        int found = 0;
        if (dirs != null) {
            for (File dir : dirs) {
                if (!dir.isDirectory() || dir.getName().startsWith("HTML_APK_BUILD") || dir.getName().startsWith("PYTHON_APK_BUILD")) continue;
                File html = new File(dir, "main.html");
                File python = new File(dir, "main.py");
                if (!html.isFile() && !python.isFile()) continue;
                found++;
                final boolean isHtml = html.isFile();
                LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(10), dp(6), dp(10), dp(6)); card.setBackgroundColor(0xFFFFFFFF);
                TextView name = label(dir.getName() + (isHtml ? "  •  HTML" : "  •  Python"), 15, 0xFF17212D);
                name.setTypeface(null, 1); card.addView(name);
                card.addView(button("فتح المحرر", () -> openProject(dir, isHtml)));
                list.addView(card);
            }
        }
        if (found == 0) list.addView(label("لا توجد مشاريع بعد. أنشئ مشروع HTML أو Python.", 13, 0xFF64748B));
        scroll.addView(list); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("HTML APK: Gradle على الكمبيوتر. Python APK: Buildozer على Linux.", 12, 0xFF536273);
        root.addView(status);
        root.addView(button("العودة", this::finish));
        setContentView(root);
        AppPalette.apply(this, root);
    }

    private void saveConnection() {
        Store.put("dev_pc_host", hostField.getText().toString().trim());
        Store.put("dev_pc_token", tokenField.getText().toString().trim());
        toast("تم حفظ إعدادات PC Bridge");
    }

    private void createProject(boolean html) {
        EditText name = new EditText(this);
        name.setSingleLine(true); name.setHint("اسم المشروع");
        new AlertDialog.Builder(this).setTitle(html ? "مشروع HTML جديد" : "مشروع Python جديد")
            .setView(name).setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء", (d, w) -> {
                String raw = name.getText().toString().trim().toLowerCase(Locale.ROOT);
                String safe = raw.replaceAll("[^a-z0-9_-]", "_");
                if (safe.length() < 2) { toast("اكتب اسماً صالحاً من حرفين على الأقل"); return; }
                File dir = new File(workspace, safe);
                if (dir.exists()) { toast("يوجد مشروع بهذا الاسم"); return; }
                if (!dir.mkdirs()) { toast("تعذر إنشاء المشروع"); return; }
                try {
                    if (html) write(new File(dir, "main.html"), defaultHtml());
                    else write(new File(dir, "main.py"), defaultPython());
                    openProject(dir, html);
                } catch (Exception e) { toast("فشل إنشاء المشروع: " + e.getMessage()); }
            }).show();
    }

    private String defaultHtml() {
        return "<!doctype html>\n<html lang=\"ar\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>My App</title><style>body{font-family:sans-serif;background:#101827;color:#fff;padding:24px}button{padding:12px 18px;border:0;border-radius:12px;background:#60a5fa}</style></head><body dir=\"rtl\"><h1>تطبيقي الأول</h1><p>عدّل هذا الملف من Mi_Max Ai.</p><button onclick=\"document.querySelector('p').textContent='JavaScript يعمل!'\">جرّب</button></body></html>";
    }

    private String defaultPython() {
        return "from kivy.app import App\nfrom kivy.uix.boxlayout import BoxLayout\nfrom kivy.uix.label import Label\nfrom kivy.uix.button import Button\n\nclass MyApp(App):\n    def build(self):\n        root = BoxLayout(orientation='vertical', padding=24, spacing=16)\n        root.add_widget(Label(text='My Python Android App'))\n        root.add_widget(Button(text='Hello', on_release=lambda *_: print('Hello from Kivy')))\n        return root\n\nMyApp().run()\n";
    }

    private void openProject(File dir, boolean html) {
        currentProject = dir; currentHtml = html;
        File source = new File(dir, html ? "main.html" : "main.py");
        try {
            String text = read(source);
            LinearLayout root = root((html ? "HTML Studio" : "Python Studio") + " • " + dir.getName());
            TextView path = label(source.getAbsolutePath(), 11, 0xFF64748B);
            root.addView(path);
            editor = new EditText(this);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setTextDirection(View.TEXT_DIRECTION_LTR);
            editor.setText(text);
            editor.setTextSize(13);
            editor.setTypeface(android.graphics.Typeface.MONOSPACE);
            editor.setMinLines(12);
            editor.setHorizontallyScrolling(true);
            editor.setTextColor(0xFF17212D);
            editor.setHintTextColor(0xFF64748B);
            editor.setBackgroundColor(0xFFFFFFFF);
            editor.setPadding(dp(10), dp(10), dp(10), dp(10));
            ScrollView scroll = new ScrollView(this); scroll.addView(editor);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(button("حفظ الملف", this::saveSource));
            if (html) root.addView(button("معاينة HTML", this::previewHtml));
            root.addView(button("تحويل المشروع إلى APK", this::buildCurrentApk));
            root.addView(button("العودة للمشاريع", this::showHome));
            setContentView(root);
        AppPalette.apply(this, root);
        } catch (Exception e) { toast("تعذر فتح المشروع: " + e.getMessage()); }
    }

    private void saveSource() {
        try { write(new File(currentProject, currentHtml ? "main.html" : "main.py"), editor.getText().toString()); toast("تم حفظ المشروع"); }
        catch (Exception e) { toast("فشل الحفظ: " + e.getMessage()); }
    }

    private void previewHtml() {
        saveSource();
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        String base = Uri.fromFile(new File(currentProject, "main.html")).toString();
        web.loadDataWithBaseURL(base, editor.getText().toString(), "text/html", "UTF-8", null);
        LinearLayout root = root("معاينة HTML • " + currentProject.getName());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(button("العودة للمحرر", () -> openProject(currentProject, true)));
        setContentView(root);
        AppPalette.apply(this, root);
    }

    private void buildCurrentApk() {
        saveSource();
        final String host = Store.get("dev_pc_host", "").trim();
        final String token = Store.get("dev_pc_token", "").trim();
        if (host.isEmpty() || token.isEmpty()) { toast("أدخل عنوان PC Bridge والرمز في صفحة Developer Studio أولاً"); return; }
        final String kind = currentHtml ? "HTML" : "Python";
        statusUpdate("جارٍ تجهيز مشروع " + kind + " للبناء...");
        new Thread(() -> {
            try {
                File project = currentHtml ? makeHtmlAndroidProject() : makePythonAndroidProject();
                uploadProject(project, host, token);
                JSONObject result;
                if (currentHtml) {
                    result = postJson(host + "/run", token, new JSONObject().put("command", "gradle --no-daemon :app:assembleDebug"), 950000);
                } else {
                    result = postJson(host + "/python-build", token, new JSONObject(), 1850000);
                }
                if (!result.optBoolean("ok", false) && result.optInt("exit_code", -1) != 0)
                    throw new Exception(result.optString("error", result.optString("output", "فشل البناء")));
                String log = result.optString("output", "اكتمل البناء.");
                File apk = downloadApk(host, token, currentHtml ? "html-apk" : "python-apk");
                pendingApk = apk;
                runOnUiThread(() -> {
                    statusUpdate("نجح البناء. حجم APK: " + apk.length() + " بايت");
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType("application/vnd.android.package-archive");
                    save.putExtra(Intent.EXTRA_TITLE, currentProject.getName() + ".apk");
                    startActivityForResult(save, SAVE_APK);
                    toast("تم بناء APK. اختر مكان حفظه.");
                });
            } catch (Exception e) {
                String message = "فشل بناء " + kind + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                runOnUiThread(() -> { statusUpdate(message); toast(message); });
            }
        }).start();
    }

    private void statusUpdate(String s) {
        runOnUiThread(() -> { if (status != null) status.setText(s); });
    }

    private File makeHtmlAndroidProject() throws Exception {
        File dir = new File(workspace, "HTML_APK_BUILD");
        deleteTree(dir); new File(dir, "app/src/main/assets").mkdirs();
        new File(dir, "app/src/main/java/com/mimax/generated").mkdirs();
        write(new File(dir, "settings.gradle"), "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name='MiMaxHtmlApp'\ninclude ':app'\n");
        write(new File(dir, "build.gradle"), "plugins { id 'com.android.application' version '8.5.2' apply false }\n");
        write(new File(dir, "app/build.gradle"), "plugins { id 'com.android.application' }\nandroid { namespace 'com.mimax.generated'; compileSdk 34; defaultConfig { applicationId 'com.mimax.generated'; minSdk 26; targetSdk 34; versionCode 1; versionName '1.0' } }\n");
        write(new File(dir, "app/src/main/AndroidManifest.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><uses-permission android:name=\"android.permission.INTERNET\"/><application android:label=\"MiMax HTML App\" android:usesCleartextTraffic=\"true\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>");
        String java = "package com.mimax.generated;\nimport android.app.Activity;import android.os.Bundle;import android.webkit.WebView;import android.webkit.WebSettings;import android.webkit.WebViewClient;import android.webkit.WebChromeClient;\npublic class MainActivity extends Activity { public void onCreate(Bundle b){super.onCreate(b); WebView w=new WebView(this); WebSettings s=w.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); w.setWebViewClient(new WebViewClient()); w.setWebChromeClient(new WebChromeClient()); setContentView(w); w.loadUrl(\"file:///android_asset/index.html\"); } }";
        write(new File(dir, "app/src/main/java/com/mimax/generated/MainActivity.java"), java);
        write(new File(dir, "app/src/main/assets/index.html"), read(new File(currentProject, "main.html")));
        return dir;
    }

    private File makePythonAndroidProject() throws Exception {
        File dir = new File(workspace, "PYTHON_APK_BUILD");
        deleteTree(dir); dir.mkdirs();
        write(new File(dir, "main.py"), read(new File(currentProject, "main.py")));
        String spec = "[app]\ntitle = " + currentProject.getName() + "\npackage.name = " + currentProject.getName().replaceAll("[^A-Za-z0-9]", "") + "\npackage.domain = org.mimax.generated\nsource.dir = .\nsource.include_exts = py,kv,png,jpg,jpeg,json,txt\nversion = 1.0\nrequirements = python3,kivy\norientation = portrait\nfullscreen = 0\nandroid.permissions = INTERNET\nandroid.api = 34\nandroid.minapi = 26\nandroid.archs = armeabi-v7a, arm64-v8a\n\n[buildozer]\nlog_level = 2\nwarn_on_root = 1\n";
        write(new File(dir, "buildozer.spec"), spec);
        write(new File(dir, "README.txt"), "Mi_Max Ai Python APK project. Requires Linux, Buildozer, Android SDK and NDK on the connected PC. Kivy runtime is used.\n");
        return dir;
    }

    private void uploadProject(File project, String host, String token) throws Exception {
        File archive = new File(getCacheDir(), "mimax-code-project.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            ArrayList<File> files = new ArrayList<>(); collect(project, files);
            byte[] buffer = new byte[8192];
            for (File file : files) {
                String rel = project.toURI().relativize(file.toURI()).getPath();
                zip.putNextEntry(new ZipEntry(rel));
                try (FileInputStream in = new FileInputStream(file)) {
                    int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(trimHost(host) + "/upload").openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(180000);
        c.setDoOutput(true); c.setFixedLengthStreamingMode(archive.length());
        c.setRequestProperty("X-MiMax-Token", token); c.setRequestProperty("Content-Type", "application/zip");
        try (OutputStream out = c.getOutputStream(); FileInputStream in = new FileInputStream(archive)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        int code = c.getResponseCode();
        String body = readStream(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect(); archive.delete();
        JSONObject response = new JSONObject(body);
        if (code >= 400 || !response.optBoolean("ok")) throw new Exception(response.optString("error", "فشل رفع المشروع إلى الكمبيوتر"));
    }

    private JSONObject postJson(String url, String token, JSONObject body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(timeout);
        c.setDoOutput(true); c.setRequestProperty("X-MiMax-Token", token);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes("UTF-8")); }
        int code = c.getResponseCode();
        JSONObject response = new JSONObject(readStream(code < 400 ? c.getInputStream() : c.getErrorStream()));
        c.disconnect();
        if (code >= 400 && !response.has("exit_code")) throw new Exception(response.optString("error", "HTTP " + code));
        return response;
    }

    private File downloadApk(String host, String token, String endpoint) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(trimHost(host) + "/download/" + endpoint).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(120000);
        c.setRequestProperty("X-MiMax-Token", token);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception(readStream(c.getErrorStream()));
        File out = new File(getCacheDir(), "mimax-" + endpoint + ".apk");
        try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) fos.write(buffer, 0, n);
        }
        c.disconnect();
        if (out.length() < 1024) throw new Exception("ملف APK الناتج أصغر من المتوقع");
        return out;
    }

    private String trimHost(String host) { return host.endsWith("/") ? host.substring(0, host.length() - 1) : host; }
    private String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder b = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        }
    }
    private void collect(File dir, ArrayList<File> out) {
        File[] children = dir.listFiles(); if (children == null) return;
        for (File f : children) { if (f.isDirectory()) collect(f, out); else out.add(f); }
    }
    private void write(File f, String text) throws Exception {
        File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes("UTF-8")); }
    }
    private String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) { int p=0,n; while(p<b.length && (n=in.read(b,p,b.length-p))>0)p+=n; }
        return new String(b, "UTF-8");
    }
    private void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children=f.listFiles(); if(children!=null) for(File c:children) deleteTree(c);
        f.delete();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_APK && result == RESULT_OK && data != null && data.getData() != null && pendingApk != null) {
            try (InputStream in = new FileInputStream(pendingApk); OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                toast("تم حفظ ملف APK بنجاح");
            } catch (Exception e) { toast("تعذر حفظ APK: " + e.getMessage()); }
        }
    }
}
