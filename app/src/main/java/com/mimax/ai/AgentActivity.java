package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.net.HttpURLConnection;
import java.net.URL;

public class AgentActivity extends Activity {
    private static final int PICK_FILE = 71, EXPORT_WORKSPACE = 72;
    private EditText task;
    private TextView output;
    private Button apply;
    private String attachedName = "";
    private String attachedText = "";
    private JSONArray pending = new JSONArray();
    private File workspace;
    private EditText pcHost, pcToken;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = new File(getFilesDir(), "workspace");
        if (!workspace.exists()) workspace.mkdirs();
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(0xFFF4F6F8);
        TextView title = new TextView(this);
        title.setText("Mi_Max Ai Agent");
        title.setTextSize(20);
        title.setTextColor(0xFF17212D);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView info = new TextView(this);
        info.setText("وكيل برمجي يعمل بنموذج OpenRouter. يستورد مشروع ZIP، يقرأ ملفاته ويقترح تعديلات لا تُحفظ إلا بعد موافقتك. يمكنك رفع مساحة العمل إلى الكمبيوتر وتشغيل بناء Gradle.");
        info.setTextColor(0xFF536273);
        info.setTextSize(13);
        root.addView(info);

        task = new EditText(this);
        task.setHint("اكتب المهمة: حلّل ملفاً، أنشئ كوداً، أو عدّل مشروعاً داخل مساحة العمل...");
        task.setTextSize(14);
        task.setMinLines(3);
        task.setGravity(Gravity.TOP | Gravity.START);
        task.setBackgroundColor(0xFFFFFFFF);
        task.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(10);
        root.addView(task, tp);

        Button pick = new Button(this);
        pick.setText("استيراد ملف نصي أو مشروع ZIP");
        pick.setAllCaps(false);
        root.addView(pick, new LinearLayout.LayoutParams(-1, dp(44)));
        pick.setOnClickListener(v -> pickFile());

        pcHost = new EditText(this);
        pcHost.setSingleLine(true);
        pcHost.setHint("عنوان PC Bridge مثل http://192.168.1.5:8765");
        pcHost.setTextSize(13);
        pcHost.setTextColor(0xFF17212D);
        pcHost.setBackgroundColor(0xFFFFFFFF);
        pcHost.setPadding(dp(10), 0, dp(10), 0);
        root.addView(pcHost, new LinearLayout.LayoutParams(-1, dp(44)));

        pcToken = new EditText(this);
        pcToken.setSingleLine(true);
        pcToken.setHint("رمز PC Bridge");
        pcToken.setTextSize(13);
        pcToken.setTextColor(0xFF17212D);
        pcToken.setBackgroundColor(0xFFFFFFFF);
        pcToken.setPadding(dp(10), 0, dp(10), 0);
        root.addView(pcToken, new LinearLayout.LayoutParams(-1, dp(44)));

        Button uploadPc = new Button(this);
        uploadPc.setText("رفع مساحة العمل إلى الكمبيوتر");
        uploadPc.setAllCaps(false);
        root.addView(uploadPc, new LinearLayout.LayoutParams(-1, dp(44)));
        uploadPc.setOnClickListener(v -> uploadWorkspaceToPc());

        Button buildPc = new Button(this);
        buildPc.setText("بناء APK على الكمبيوتر");
        buildPc.setAllCaps(false);
        root.addView(buildPc, new LinearLayout.LayoutParams(-1, dp(44)));
        buildPc.setOnClickListener(v -> buildOnPc());

        Button send = new Button(this);
        send.setText("إرسال المهمة إلى الوكيل");
        send.setAllCaps(false);
        root.addView(send, new LinearLayout.LayoutParams(-1, dp(46)));
        send.setOnClickListener(v -> runAgent());

        ScrollView sc = new ScrollView(this);
        output = new TextView(this);
        output.setText("مساحة العمل: " + workspace.getAbsolutePath() + "\nارفق ملفاً أو اكتب مهمة ثم أرسلها.");
        output.setTextColor(0xFF17212D);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        output.setBackgroundColor(0xFFFFFFFF);
        sc.addView(output);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = dp(8);
        root.addView(sc, sp);

        apply = new Button(this);
        apply.setText("اعتماد التغييرات وحفظ الملفات");
        apply.setAllCaps(false);
        apply.setEnabled(false);
        root.addView(apply, new LinearLayout.LayoutParams(-1, dp(48)));
        apply.setOnClickListener(v -> applyActions());

        Button exportWorkspace = new Button(this);
        exportWorkspace.setText("تصدير مساحة العمل ZIP");
        exportWorkspace.setAllCaps(false);
        root.addView(exportWorkspace, new LinearLayout.LayoutParams(-1, dp(44)));
        exportWorkspace.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "MiMaxAi-workspace.zip");
            startActivityForResult(i, EXPORT_WORKSPACE);
        });

        Button back = new Button(this);
        back.setText("العودة إلى Mi_Max Ai");
        back.setAllCaps(false);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
        back.setOnClickListener(v -> finish());
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, PICK_FILE); }
        catch (Exception e) { toast("تعذر فتح منتقي الملفات"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == EXPORT_WORKSPACE) {
                exportWorkspaceTo(uri);
                return;
            }
            if (requestCode != PICK_FILE) return;
            String name = displayName(uri);
            if (name.toLowerCase().endsWith(".zip")) {
                importZip(uri);
                attachedName = name;
                attachedText = "تم استيراد مشروع ZIP إلى مساحة العمل.\n" + snapshot();
                toast("تم فك المشروع داخل مساحة العمل");
            } else {
                InputStream in = getContentResolver().openInputStream(uri);
                byte[] buf = new byte[8192];
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n; while ((n = in.read(buf)) != -1 && out.size() < 100 * 1024) out.write(buf, 0, n);
                in.close();
                attachedText = new String(out.toByteArray(), "UTF-8");
                attachedName = name;
                toast("تم إرفاق الملف: " + attachedName);
            }
        } catch (Exception e) { toast("تعذرت العملية: " + e.getMessage()); }
    }



    private String pcBase() {
        String base = pcHost.getText().toString().trim().replaceAll("/+$", "");
        if (!(base.startsWith("http://") || base.startsWith("https://")) || pcToken.getText().toString().trim().isEmpty())
            throw new IllegalArgumentException("أدخل عنوان الكمبيوتر ورمز الاتصال أولاً.");
        return base;
    }

    private void uploadWorkspaceToPc() {
        final String base;
        final String key = pcToken.getText().toString().trim();
        try { base = pcBase(); } catch (Exception e) { toast(e.getMessage()); return; }
        output.setText("جارٍ ضغط مساحة العمل وإرسالها إلى الكمبيوتر...");
        new Thread(() -> {
            File archive = new File(getCacheDir(), "mimax-workspace-upload.zip");
            try {
                ArrayList<File> files = new ArrayList<>();
                collectAll(workspace, files, 2000);
                long total = 0;
                ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive));
                byte[] buffer = new byte[8192];
                for (File file : files) {
                    total += file.length();
                    if (total > 100L * 1024 * 1024) throw new Exception("مساحة العمل أكبر من حد الرفع (100MB).");
                    String rel = workspace.toURI().relativize(file.toURI()).getPath();
                    zip.putNextEntry(new ZipEntry(rel));
                    FileInputStream in = new FileInputStream(file);
                    int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                    in.close();
                    zip.closeEntry();
                }
                zip.close();
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/upload").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(180000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(archive.length());
                c.setRequestProperty("X-MiMax-Token", key);
                c.setRequestProperty("Content-Type", "application/zip");
                OutputStream out = c.getOutputStream();
                FileInputStream in = new FileInputStream(archive);
                int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                in.close(); out.close();
                int status = c.getResponseCode();
                InputStream response = status < 400 ? c.getInputStream() : c.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
                StringBuilder text = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) text.append(line);
                reader.close(); c.disconnect();
                JSONObject result = new JSONObject(text.toString());
                String message = result.optBoolean("ok") ? "تم رفع مساحة العمل. العناصر: " + result.optInt("files") + "\nيمكنك الآن تشغيل بناء APK على الكمبيوتر." : result.optString("error", "فشل الرفع");
                runOnUiThread(() -> output.setText(message));
            } catch (Exception e) {
                String message = "فشل رفع مساحة العمل: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage());
                runOnUiThread(() -> output.setText(message));
            } finally { if (archive.exists()) archive.delete(); }
        }).start();
    }

    private void buildOnPc() {
        final String base;
        final String key = pcToken.getText().toString().trim();
        try { base = pcBase(); } catch (Exception e) { toast(e.getMessage()); return; }
        output.setText("جارٍ تشغيل Gradle على الكمبيوتر...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/run").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(950000);
                c.setDoOutput(true);
                c.setRequestProperty("X-MiMax-Token", key);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject();
                body.put("command", "gradle --no-daemon :app:assembleDebug");
                OutputStream out = c.getOutputStream();
                out.write(body.toString().getBytes("UTF-8")); out.close();
                int status = c.getResponseCode();
                InputStream response = status < 400 ? c.getInputStream() : c.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
                StringBuilder text = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) text.append(line).append('\n');
                reader.close();
                JSONObject result = new JSONObject(text.toString());
                String message = result.optString("output", result.optString("error", "لا توجد مخرجات")) + "\nHTTP " + status + " — exit " + result.optInt("exit_code", -1);
                runOnUiThread(() -> output.setText(message));
            } catch (Exception e) {
                String message = "فشل البناء عن بعد: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage());
                runOnUiThread(() -> output.setText(message));
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    private String displayName(Uri uri) {
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.moveToFirst()) return c.getString(i);
            } finally { c.close(); }
        }
        return uri.getLastPathSegment() == null ? "attached.txt" : uri.getLastPathSegment();
    }

    private void importZip(Uri uri) throws Exception {
        InputStream raw = getContentResolver().openInputStream(uri);
        ZipInputStream zip = new ZipInputStream(raw);
        byte[] buffer = new byte[8192];
        long total = 0;
        int count = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (++count > 500) throw new Exception("المشروع يحتوي على أكثر من 500 عنصر.");
            String name = entry.getName().replace('\\', '/');
            if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains(":"))
                throw new Exception("مسار غير آمن داخل ملف ZIP.");
            File out = new File(workspace, name);
            String base = workspace.getCanonicalPath();
            String full = out.getCanonicalPath();
            if (!full.startsWith(base + File.separator) && !full.equals(base))
                throw new Exception("مسار خارج مساحة العمل.");
            if (entry.isDirectory()) out.mkdirs();
            else {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > 50L * 1024 * 1024) {
                        fos.close();
                        throw new Exception("حجم الملفات بعد فك الضغط تجاوز 50MB.");
                    }
                    fos.write(buffer, 0, n);
                }
                fos.close();
            }
            zip.closeEntry();
        }
        zip.close();
    }

    private void exportWorkspaceTo(Uri uri) throws Exception {
        OutputStream raw = getContentResolver().openOutputStream(uri);
        ZipOutputStream zip = new ZipOutputStream(raw);
        ArrayList<File> files = new ArrayList<>();
        collectAll(workspace, files, 2000);
        byte[] buffer = new byte[8192];
        long total = 0;
        for (File f : files) {
            total += f.length();
            if (total > 100L * 1024 * 1024) throw new Exception("مساحة العمل أكبر من حد التصدير (100MB).");
            String rel = workspace.toURI().relativize(f.toURI()).getPath();
            zip.putNextEntry(new ZipEntry(rel));
            FileInputStream in = new FileInputStream(f);
            int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
            in.close();
            zip.closeEntry();
        }
        zip.finish();
        zip.close();
        toast("تم تصدير ملفات مساحة العمل");
    }

    private void collectAll(File dir, ArrayList<File> out, int limit) {
        if (out.size() >= limit) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (out.size() >= limit) return;
            if (f.isDirectory()) collectAll(f, out, limit);
            else out.add(f);
        }
    }

    private String snapshot() {
        StringBuilder b = new StringBuilder();
        ArrayList<File> files = new ArrayList<>();
        collect(workspace, files);
        int total = 0;
        for (File f : files) {
            if (files.size() > 20) break;
            String rel = workspace.toURI().relativize(f.toURI()).getPath();
            b.append("\n--- ").append(rel).append(" ---\n");
            if (f.length() > 24 * 1024 || total > 100 * 1024) {
                b.append("[ملف غير نصي أو كبير؛ لم يُضمَّن محتواه]\n");
                continue;
            }
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] data = new byte[(int) f.length()];
                int n = in.read(data); in.close();
                String text = new String(data, 0, Math.max(0, n), "UTF-8");
                if (text.indexOf(0) >= 0) { b.append("[محتوى ثنائي]\n"); continue; }
                b.append(text).append('\n');
                total += n;
            } catch (Exception e) { b.append("[تعذرت القراءة]\n"); }
        }
        if (files.isEmpty()) b.append("\n[مساحة العمل فارغة]");
        return b.toString();
    }

    private void collect(File dir, ArrayList<File> out) {
        if (out.size() >= 20) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (out.size() >= 20) return;
            if (f.isDirectory()) collect(f, out); else out.add(f);
        }
    }

    private void runAgent() {
        String request = task.getText().toString().trim();
        if (request.isEmpty()) { toast("اكتب المهمة أولاً"); return; }
        if (Store.key().isEmpty()) { toast("أضف مفتاح OpenRouter من الإعدادات أولاً"); return; }
        output.setText("الوكيل يحلل المهمة وملفات مساحة العمل...");
        apply.setEnabled(false);
        pending = new JSONArray();
        new Thread(() -> {
            try {
                JSONArray msgs = new JSONArray();
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content",
                        "أنت وكيل برمجي داخل تطبيق Mi_Max Ai. حلّل الملفات والمهمة. لا تدّع تنفيذ شيء. أعد JSON صالحاً فقط بالشكل: "
                        + "{\"reply\":\"شرح عربي موجز\",\"actions\":[{\"type\":\"write_file\",\"path\":\"relative/path\",\"content\":\"full file content\"}]}. "
                        + "أنشئ ملفات كاملة أو تعديلات كاملة فقط عند الحاجة. لا تستخدم مسارات مطلقة أو .. ولا تحذف ملفات. "
                        + "لا تضف أوامر خطرة أو أوامر تفليش. إذا لم تكن متأكداً فاطلب معلومات بدلاً من التخمين. "
                        + "لا تضع Markdown fences حول JSON.");
                msgs.put(sys);
                JSONObject user = new JSONObject();
                user.put("role", "user");
                String context = "المهمة: " + request + "\n\nملفات مساحة العمل الحالية:" + snapshot();
                if (!attachedText.isEmpty()) context += "\n\nالملف المرفق (" + attachedName + "):\n" + attachedText;
                user.put("content", context);
                msgs.put(user);
                String model = Models.resolve(Store.model());
                String response = Api.chat(Store.key(), model, msgs, Math.min(0.4, Store.temp()), false, null, null);
                JSONObject parsed = parse(response);
                String reply = parsed.optString("reply", response);
                JSONArray actions = parsed.optJSONArray("actions");
                JSONArray safe = new JSONArray();
                if (actions != null) {
                    for (int i = 0; i < actions.length() && i < 10; i++) {
                        JSONObject a = actions.optJSONObject(i);
                        if (a == null || !"write_file".equals(a.optString("type"))) continue;
                        String path = a.optString("path", "");
                        String content = a.optString("content", "");
                        if (validPath(path) && content.length() <= 500000) {
                            JSONObject clean = new JSONObject();
                            clean.put("type", "write_file"); clean.put("path", path); clean.put("content", content);
                            safe.put(clean);
                        }
                    }
                }
                final JSONArray finalActions = safe;
                final String finalReply = reply;
                runOnUiThread(() -> {
                    pending = finalActions;
                    output.setText(finalReply + (pending.length() > 0 ? "\n\nملفات مقترحة للتعديل:" : "\n\nلا توجد تغييرات ملفات معلّقة."));
                    for (int i = 0; i < pending.length(); i++) {
                        JSONObject a = pending.optJSONObject(i);
                        output.append("\n\n• " + a.optString("path") + " (" + a.optString("content").length() + " حرف)");
                    }
                    apply.setEnabled(pending.length() > 0);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "تعذر تنفيذ المهمة" : e.getMessage();
                runOnUiThread(() -> output.setText("فشل الوكيل: " + msg));
            }
        }).start();
    }

    private JSONObject parse(String text) throws Exception {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new Exception("النموذج لم يُرجع JSON صالحاً. أعد المحاولة.");
        return new JSONObject(text.substring(start, end + 1));
    }

    private boolean validPath(String path) {
        if (path == null || path.trim().isEmpty() || path.startsWith("/") || path.contains("..") || path.contains("\\") || path.length() > 180) return false;
        File f = new File(workspace, path);
        try {
            String base = workspace.getCanonicalPath();
            String full = f.getCanonicalPath();
            return full.startsWith(base + File.separator) || full.equals(base);
        } catch (Exception e) { return false; }
    }

    private void applyActions() {
        int done = 0;
        try {
            for (int i = 0; i < pending.length(); i++) {
                JSONObject a = pending.getJSONObject(i);
                String path = a.getString("path");
                if (!validPath(path)) continue;
                File f = new File(workspace, path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream out = new FileOutputStream(f, false);
                out.write(a.getString("content").getBytes("UTF-8"));
                out.close();
                done++;
            }
            pending = new JSONArray();
            apply.setEnabled(false);
            output.append("\n\nتم حفظ " + done + " ملف داخل مساحة العمل الخاصة بالتطبيق.");
            toast("تم تطبيق التغييرات");
        } catch (Exception e) { toast("فشل الحفظ: " + e.getMessage()); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
