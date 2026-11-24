package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExtensionDeveloperActivity extends Activity {
    private String pendingProjectId;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme t = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16),dp(12),dp(16),dp(12)); page.setBackgroundColor(t.base);
        TextView title = new TextView(this); title.setText("منشئ الإضافات"); title.setTextSize(21); title.setTextColor(t.tx); page.addView(title);
        TextView note = new TextView(this); note.setText("أنشئ إضافة .mt1 تعمل بعد تثبيتها دون إعادة بناء APK. اكتب javascript في خانة المعالج لاستخدام محرك JavaScript المدمج وواجهة API الآمنة."); note.setTextColor(t.mut); page.addView(note);
        EditText id = field("المعرّف: example.my.extension"); page.addView(id);
        EditText name = field("اسم الإضافة"); page.addView(name);
        EditText label = field("عنوان الإجراء"); page.addView(label);
        EditText handler = field("معالج مدعوم مثل open_terminal أو logo_bin_extractor"); page.addView(handler);
        Button make = new Button(this); make.setText("إنشاء manifest.json"); page.addView(make);
        make.setOnClickListener(v -> {
            try {
                String safeId = id.getText().toString().trim();
                String safeName = name.getText().toString().trim();
                String h = handler.getText().toString().trim();
                if (!safeId.matches("[A-Za-z0-9._-]{2,64}") || safeName.isEmpty() || h.isEmpty()) throw new Exception("أكمل المعرّف والاسم والمعالج.");
                JSONObject m = new JSONObject();
                m.put("id", safeId); m.put("name", safeName); m.put("version", "1.0.0");
                m.put("description", "إضافة تم إنشاؤها باستخدام Mi_Max Extension Developer.");
                m.put("runtime", "javascript".equals(h) ? "javascript" : "declarative");
                JSONObject action = new JSONObject(); action.put("label", label.getText().toString().trim().isEmpty() ? safeName : label.getText().toString().trim()); action.put("handler", h);
                m.put("action", action); m.put("permissions", new org.json.JSONArray());
                File dir = new File(getFilesDir(), "extension_projects/" + safeId); if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد المشروع.");
                File out = new File(dir, "manifest.json"); FileOutputStream stream = new FileOutputStream(out); stream.write(m.toString(2).getBytes("UTF-8")); stream.close();
                if ("javascript".equals(h)) {
                    File js = new File(dir, "main.js");
                    FileOutputStream script = new FileOutputStream(js);
                    script.write("api.toast(\\\"Plugin loaded\\\");".getBytes("UTF-8"));
                    script.close();
                }
                new android.app.AlertDialog.Builder(this).setTitle("تم إنشاء القالب").setMessage(out.getAbsolutePath() + "\nيمكنك ضغط مجلد المشروع إلى ملف .mt1 بعد إضافة ملفاتك.").setPositiveButton("حسناً", null).show();
            } catch (Exception e) { new android.app.AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("حسناً", null).show(); }
        });
        Button export = new Button(this); export.setText("تصدير المشروع كملف .mt1"); page.addView(export);
        export.setOnClickListener(v -> {
            pendingProjectId = id.getText().toString().trim();
            if (!pendingProjectId.matches("[A-Za-z0-9._-]{2,64}")) { new android.app.AlertDialog.Builder(this).setMessage("أدخل معرّف مشروع صحيحاً أولاً.").setPositiveButton("حسناً", null).show(); return; }
            Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT); pick.setType("application/zip"); pick.putExtra(Intent.EXTRA_TITLE, pendingProjectId + ".mt1");
            startActivityForResult(pick, 7310);
        });
        Button back = new Button(this); back.setText("رجوع"); page.addView(back); back.setOnClickListener(v -> finish());
        setContentView(page);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7310 || resultCode != RESULT_OK || data == null || data.getData() == null || pendingProjectId == null) return;
        try {
            File dir = new File(getFilesDir(), "extension_projects/" + pendingProjectId);
            OutputStream raw = getContentResolver().openOutputStream(data.getData());
            if (raw == null) throw new Exception("تعذر فتح ملف التصدير.");
            ZipOutputStream zip = new ZipOutputStream(raw);
            zipTree(dir, dir, zip); zip.close();
            new android.app.AlertDialog.Builder(this).setMessage("تم تصدير ملف الإضافة .mt1").setPositiveButton("حسناً", null).show();
        } catch (Exception e) { new android.app.AlertDialog.Builder(this).setMessage("فشل التصدير: " + e.getMessage()).setPositiveButton("حسناً", null).show(); }
    }
    private void zipTree(File root, File current, ZipOutputStream zip) throws Exception {
        File[] children = current.listFiles(); if (children == null) return;
        byte[] buffer = new byte[8192];
        for (File child : children) {
            if (child.isDirectory()) { zipTree(root, child, zip); continue; }
            String name = root.toURI().relativize(child.toURI()).getPath();
            zip.putNextEntry(new ZipEntry(name));
            FileInputStream in = new FileInputStream(child); int n;
            while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
            in.close(); zip.closeEntry();
        }
    }
    private EditText field(String hint) { EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); return e; }
}
