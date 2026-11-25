package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.util.Base64;
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
    private File currentEditingFile;
    private boolean currentHtml;
    private static final int IMPORT_PROJECT_FILE = 931;\n    private static final int PICK_APP_ICON = 932;\n    private File selectedProjectIcon;

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
        root.addView(label("أنشئ مشاريع HTML وPython وحرّرها وعاينها محلياً. تحويل المشاريع إلى APK يتم على الهاتف؛ Python يستخدم Pyodide ويحتاج الإنترنت لتحميل بيئة التشغيل والمكتبات أول مرة.", 13, 0xFF536273));
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
        status = label("HTML: بناء APK محلي. Python: تشغيل عبر Pyodide وبناء APK محلي. Kotlin/Java: GitHub Actions.", 12, 0xFF536273);
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
        final String[] ids = html ? new String[]{"html/basic","html/offline","html/multipage"} : new String[]{"python/kivy","python/canvas"};
        final String[] labels = html ? new String[]{"HTML أساسي","HTML يعمل دون إنترنت","موقع متعدد الصفحات"} : new String[]{"Python محلي (Pyodide)","Python + Canvas"};
        new AlertDialog.Builder(this).setTitle(html ? "اختر قالب HTML" : "اختر قالب Python")
            .setItems(labels,(d,w)->promptProjectName(html,ids[w])).setNegativeButton("إلغاء",null).show();
    }

    private void promptProjectName(boolean html, String templateId) {
        EditText name=new EditText(this); name.setSingleLine(true); name.setHint("اسم المشروع");
        new AlertDialog.Builder(this).setTitle("اسم المشروع").setView(name).setNegativeButton("إلغاء",null)
            .setPositiveButton("إنشاء من القالب",(d,w)->{
                String raw=name.getText().toString().trim().toLowerCase(Locale.ROOT);
                String safe=raw.replaceAll("[^a-z0-9_-]","_");
                if(safe.length()<2){toast("اكتب اسماً صالحاً من حرفين على الأقل");return;}
                File dir=new File(workspace,safe);
                if(dir.exists()){toast("يوجد مشروع بهذا الاسم");return;}
                if(!dir.mkdirs()){toast("تعذر إنشاء المشروع");return;}
                try{
                    copyAssetTree("project-templates/"+templateId,dir);
                    if(!new File(dir,html?"main.html":"main.py").isFile()) throw new Exception("ملف البداية غير موجود في القالب");
                    openProject(dir,html);
                }catch(Exception ex){deleteTree(dir);toast("فشل إنشاء المشروع: "+ex.getMessage());}
            }).show();
    }

    private void copyAssetTree(String assetPath, File destination) throws Exception {
        String[] children=getAssets().list(assetPath);
        if(children!=null && children.length>0){
            if(!destination.exists()&&!destination.mkdirs()) throw new Exception("تعذر إنشاء مجلد");
            for(String child:children) copyAssetTree(assetPath+"/"+child,new File(destination,child));
            return;
        }
        try(InputStream in=getAssets().open(assetPath);FileOutputStream out=new FileOutputStream(destination)){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }

    private void openProject(File dir, boolean html) {
        currentProject = dir; currentHtml = html;
        File source = new File(dir, html ? "main.html" : "main.py");
        currentEditingFile = source;
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
            root.addView(button("ملفات المشروع / إضافة ملف", this::showProjectFiles));
            if (!html) root.addView(button("تعديل requirements.txt", this::editRequirements));
            if (html) root.addView(button("معاينة HTML دون إنترنت", this::previewHtml));
            root.addView(button("تحويل المشروع إلى APK محلياً", this::buildLocalApk));
            if (!html) root.addView(button("معلومات تشغيل Python والمكتبات", this::showPythonRuntimeInfo));
            root.addView(button("حالة محركات البناء", this::showOfflineBuildStatus));
            root.addView(button("العودة للمشاريع", this::showHome));
            setContentView(root);
        AppPalette.apply(this, root);
        } catch (Exception e) { toast("تعذر فتح المشروع: " + e.getMessage()); }
    }

    private void saveSource() {
        try { if (currentEditingFile == null) currentEditingFile = new File(currentProject, currentHtml ? "main.html" : "main.py"); write(currentEditingFile, editor.getText().toString()); toast("تم حفظ: " + currentEditingFile.getName()); }
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
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.loadUrl(Uri.fromFile(new File(currentProject, "main.html")).toString());
        LinearLayout root = root("معاينة HTML • " + currentProject.getName());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(button("العودة للمحرر", () -> openProject(currentProject, true)));
        setContentView(root);
        AppPalette.apply(this, root);
    }

    private void buildLocalApk() {
        if (currentProject == null) { toast("افتح مشروعاً أولاً"); return; }
        saveSource();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(12), dp(4), dp(12), dp(4));
        EditText appTitle = new EditText(this);
        appTitle.setSingleLine(true);
        appTitle.setHint("اسم التطبيق");
        appTitle.setText(currentProject.getName());
        EditText packageName = new EditText(this);
        packageName.setSingleLine(true);
        packageName.setHint("com.example.myapp");
        String segment = currentProject.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (segment.isEmpty() || !Character.isLetter(segment.charAt(0))) segment = "project_" + segment;
        packageName.setText("com.mimax.generated." + segment);
        form.addView(label("اسم التطبيق الظاهر", 13, 0xFF536273));
        form.addView(appTitle);
        form.addView(label("اسم الحزمة (Package ID)", 13, 0xFF536273));
        form.addView(packageName);
        Button iconButton = button(selectedProjectIcon == null ? "اختيار أيقونة من الهاتف" : "تغيير الأيقونة المحددة", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            startActivityForResult(pick, PICK_APP_ICON);
        });
        form.addView(iconButton);
        form.addView(label("سيتم تضمين الأيقونة كاختصار قابل للإضافة إلى الشاشة الرئيسية عند فتح التطبيق الناتج.", 11, 0xFF64748B));
        new AlertDialog.Builder(this).setTitle("إعدادات APK")
            .setView(form).setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء APK", (dialog, which) -> {
                String title = appTitle.getText().toString().trim();
                String pkg = packageName.getText().toString().trim();
                if (title.isEmpty() || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,7}")) {
                    toast("تحقق من اسم التطبيق واسم الحزمة"); return;
                }
                final File project = currentProject;
                final boolean html = currentHtml;
                final File icon = selectedProjectIcon;
                statusUpdate("جاري تجهيز APK ومحاذاته وتوقيعه على الهاتف...");
                new Thread(() -> {
                    try {
                        File apk = LocalApkBuilder.build(this, project, html, title, pkg, icon);
                        runOnUiThread(() -> {
                            pendingApk = apk;
                            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            save.addCategory(Intent.CATEGORY_OPENABLE);
                            save.setType("application/vnd.android.package-archive");
                            save.putExtra(Intent.EXTRA_TITLE, apk.getName());
                            statusUpdate("اكتمل البناء. اختر مكان حفظ APK.");
                            startActivityForResult(save, SAVE_APK);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            statusUpdate("فشل البناء المحلي: " + e.getMessage());
                            toast("فشل بناء APK محلياً: " + e.getMessage());
                        });
                    }
                }).start();
            }).show();
    }

    private void showPythonRuntimeInfo() {
        new AlertDialog.Builder(this).setTitle("Python المحلي داخل APK")
            .setMessage("يُضمّن ملف main.py داخل التطبيق الناتج ويُشغّل باستخدام Pyodide 0.27.7 داخل WebView. أول تشغيل يحتاج الإنترنت لتنزيل بيئة Python، وتُنزّل المكتبات من PyPI عبر micropip. ليست كل مكتبات pip متوافقة: المكتبات التي تحتاج امتدادات C/Native أو واجهة Kivy/SDL لا تعمل بهذا المحرك. هذا مسار Python محلي مستقل عن GitHub Actions.")
            .setPositiveButton("حسناً", null).show();
    }

    private void showOfflineBuildStatus() {
        new AlertDialog.Builder(this).setTitle("حالة محركات البناء")
            .setMessage("HTML: تُنسخ ملفات المشروع داخل قالب WebView ويُوقّع APK على الهاتف. Python: يُضمّن main.py ويعمل عبر Pyodide؛ تنزيل بيئة التشغيل والمكتبات يحتاج الإنترنت. ملفات Kotlin وJava الأصلية تبقى للبناء عبر GitHub Actions. قوالب APK العامة تستخدم معرف حزمة ثابت لكل نوع، لذلك لا يمكن تثبيت أكثر من مشروع مولّد من النوع نفسه بجانب الآخر حالياً.")
            .setPositiveButton("حسناً", null).show();
    }

    private void showProjectFiles() {
        if (currentProject == null) return;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ArrayList<File> files = new ArrayList<>(); collect(currentProject, files);
        for (File f : files) {
            String rel = currentProject.toURI().relativize(f.toURI()).getPath();
            list.addView(button(rel, () -> openProjectFile(f)));
        }
        sc.addView(list); box.addView(sc, new LinearLayout.LayoutParams(-1, dp(280)));
        box.addView(button("استيراد ملف من الهاتف", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("*/*");
            startActivityForResult(pick, IMPORT_PROJECT_FILE);
        }));
        new AlertDialog.Builder(this).setTitle("ملفات المشروع").setView(box).setNegativeButton("إغلاق", null).show();
    }

    private void openProjectFile(File file) {
        try {
            currentEditingFile = file;
            editor.setText(read(file));
            toast("تم فتح " + file.getName());
        } catch (Exception e) { toast("تعذر فتح الملف: " + e.getMessage()); }
    }

    private void editRequirements() {
        File req = new File(currentProject, "requirements.txt");
        try {
            currentEditingFile = req;
            editor.setText(req.exists() ? read(req) : "# مكتبة في كل سطر\\n");
            toast("عدّل المتطلبات ثم اضغط حفظ الملف");
        } catch (Exception e) { toast("تعذر فتح requirements.txt"); }
    }


    private void showCloudBuildDialog() {
        if (currentProject == null) { toast("افتح مشروعاً أولاً"); return; }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(12), dp(4), dp(12), dp(4));
        EditText appTitle = new EditText(this);
        appTitle.setSingleLine(true);
        appTitle.setHint("اسم التطبيق");
        appTitle.setText(currentProject.getName());
        EditText packageName = new EditText(this);
        packageName.setSingleLine(true);
        packageName.setHint("اسم الحزمة مثل com.example.myapp");
        packageName.setText("com.mimax.generated." + currentProject.getName().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT));
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setHint("GitHub Personal Access Token (لا يُحفظ)");
        token.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD );
        form.addView(label("اسم التطبيق", 13, 0xFF536273)); form.addView(appTitle);
        form.addView(label("اسم الحزمة", 13, 0xFF536273)); form.addView(packageName);
        form.addView(label("رمز GitHub بصلاحية Contents: Read and write", 13, 0xFF536273)); form.addView(token);
        form.addView(label("سيُرفع مصدر هذا المشروع فقط إلى مجلد خاص في المستودع، ثم يبدأ GitHub Actions البناء. لا تضع كلمة مرور أو مفتاحاً سرياً داخل ملفات المشروع.", 12, 0xFF64748B));
        new AlertDialog.Builder(this).setTitle("إنشاء APK من المشروع")
            .setView(form).setNegativeButton("إلغاء", null)
            .setPositiveButton("رفع وبدء البناء", (d, w) -> {
                String title = appTitle.getText().toString().trim();
                String pkg = packageName.getText().toString().trim();
                String pat = token.getText().toString().trim();
                if (title.isEmpty() || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,7}") || pat.isEmpty()) {
                    toast("تحقق من اسم التطبيق والحزمة ورمز GitHub"); return;
                }
                new Thread(() -> {
                    try {
                        uploadForCloudBuild(currentProject, currentHtml ? "html" : "python", title, pkg, pat);
                        runOnUiThread(() -> new AlertDialog.Builder(this)
                            .setTitle("تم رفع المشروع")
                            .setMessage("تم رفع ملفات المشروع وبدأ GitHub Actions البناء تلقائياً عند تسجيل التغيير. افتح صفحة Actions في المستودع لمتابعة النتيجة وتنزيل APK من Artifacts. هذا بناء سحابي وليس محلياً.")
                            .setPositiveButton("حسناً", null).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("فشل رفع/بدء البناء: " + e.getMessage()));
                    }
                }).start();
            }).show();
    }

    private void uploadForCloudBuild(File project, String type, String title, String packageName, String token) throws Exception {
        String id = "job-" + System.currentTimeMillis();
        String base = "builder-input/" + id + "/";
        JSONObject meta = new JSONObject();
        meta.put("type", type); meta.put("title", title); meta.put("package", packageName);
        meta.put("project", project.getName());
        putGithubFile(base + "project.json", meta.toString(), token, "Add Mi_Max Ai builder job metadata");
        File archive = new File(getCacheDir(), id + ".zip");
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
        try {
            byte[] bytes = new byte[(int) archive.length()];
            try (FileInputStream in = new FileInputStream(archive)) {
                int p=0,n; while(p<bytes.length && (n=in.read(bytes,p,bytes.length-p))>0) p+=n;
                if (p != bytes.length) throw new Exception("تعذرت قراءة أرشيف المشروع كاملاً");
            }
            putGithubFile(base + "project.zip", Base64.encodeToString(bytes, Base64.NO_WRAP), token,
                "Upload Mi_Max Ai project for APK build", true);
        } finally { archive.delete(); }
    }

    private void putGithubFile(String path, String content, String token, String message) throws Exception {
        putGithubFile(path, content, token, message, false);
    }

    private void putGithubFile(String path, String content, String token, String message, boolean alreadyBase64) throws Exception {
        String encoded = alreadyBase64 ? content : Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP);
        JSONObject body = new JSONObject();
        body.put("message", message); body.put("content", encoded); body.put("branch", "main");
        URL url = new URL("https://api.github.com/repos/mmgg3yn-png/MiMaxAi-Android-3.0/contents/" + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT"); conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes("UTF-8")); }
        int code = conn.getResponseCode();
        String response = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("GitHub API HTTP " + code + ": " + response);
    }

    private void buildCurrentApk() { showOfflineBuildStatus(); }

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
        if (request == IMPORT_PROJECT_FILE && result == RESULT_OK && data != null && data.getData() != null && currentProject != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                String name = "imported_" + System.currentTimeMillis();
                String display = data.getData().getLastPathSegment();
                if (display != null && display.matches("[A-Za-z0-9._-]{1,100}")) name = display;
                File dest = new File(currentProject, name);
                try (FileOutputStream out = new FileOutputStream(dest)) { byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))!=-1) out.write(buf,0,n); }
                toast("تم استيراد الملف: " + name);
                showProjectFiles();
            } catch (Exception e) { toast("فشل استيراد الملف: " + e.getMessage()); }
        }
        if (request == PICK_APP_ICON && result == RESULT_OK && data != null && data.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 FileOutputStream out = new FileOutputStream(new File(getCacheDir(), "mimax-project-icon.png"))) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                selectedProjectIcon = new File(getCacheDir(), "mimax-project-icon.png");
                toast("تم اختيار الأيقونة. اضغط إنشاء APK للمتابعة.");
            } catch (Exception e) { toast("تعذر قراءة الأيقونة: " + e.getMessage()); }
        }
        if (request == SAVE_APK && result == RESULT_OK && data != null && data.getData() != null && pendingApk != null) {
            try (InputStream in = new FileInputStream(pendingApk); OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                toast("تم حفظ ملف APK بنجاح");
            } catch (Exception e) { toast("تعذر حفظ APK: " + e.getMessage()); }
        }
    }
}
