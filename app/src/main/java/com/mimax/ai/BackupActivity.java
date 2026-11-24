package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

public class BackupActivity extends Activity {
    private static final int EXPORT = 81, IMPORT = 82;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(0xFFF4F6F8);

        TextView title = new TextView(this);
        title.setText("النسخ الاحتياطي والاستعادة");
        title.setTextColor(0xFF17212D);
        title.setTextSize(19);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView note = new TextView(this);
        note.setText("احفظ محادثاتك وذاكرتك والبرمبت والثيم والنماذج والإعدادات في ملف JSON، ثم استعدها على جهاز آخر. مفتاح OpenRouter لا يُضمَّن في النسخة الاحتياطية لحمايته.");
        note.setTextColor(0xFF526172);
        note.setTextSize(13);
        root.addView(note);

        Button export = new Button(this);
        export.setText("تصدير نسخة احتياطية");
        export.setAllCaps(false);
        root.addView(export, new LinearLayout.LayoutParams(-1, dp(48)));
        export.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "MiMaxAi-backup.json");
            startActivityForResult(i, EXPORT);
        });

        Button imp = new Button(this);
        imp.setText("استعادة نسخة احتياطية");
        imp.setAllCaps(false);
        root.addView(imp, new LinearLayout.LayoutParams(-1, dp(48)));
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            startActivityForResult(i, IMPORT);
        });

        TextView warning = new TextView(this);
        warning.setText("تنبيه: الاستعادة تستبدل المحادثات والذاكرة والإعدادات الحالية بالبيانات الموجودة في الملف. لا تحذف الملف الأصلي حتى تتأكد من نجاح الاستعادة.");
        warning.setTextColor(0xFF9B4B32);
        warning.setTextSize(13);
        warning.setPadding(0, dp(16), 0, dp(16));
        root.addView(warning);

        Button back = new Button(this);
        back.setText("العودة إلى Mi_Max Ai");
        back.setAllCaps(false);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        back.setOnClickListener(v -> finish());
        setContentView(root);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == EXPORT) {
            try { exportTo(uri); }
            catch (Exception e) { toast("فشل التصدير: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage())); }
        } else if (requestCode == IMPORT) {
            new AlertDialog.Builder(this)
                    .setTitle("تأكيد استعادة النسخة")
                    .setMessage("ستُستبدل المحادثات والذاكرة والإعدادات الموجودة بالبيانات داخل الملف. هل تريد المتابعة؟")
                    .setPositiveButton("استعادة", (d, w) -> {
                        try { importFrom(uri); }
                        catch (Exception e) { toast("فشل الاستعادة: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage())); }
                    })
                    .setNegativeButton("إلغاء", null)
                    .show();
        }
    }

    private void exportTo(Uri uri) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "MiMaxAiBackup");
        root.put("version", 1);
        JSONObject data = new JSONObject();
        Map<String, ?> all = Store.sp.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key.equals("key") || key.equals("session") || key.startsWith("google")) continue;
            Object value = e.getValue();
            if (value instanceof String || value instanceof Boolean || value instanceof Number) data.put(key, value);
        }
        root.put("data", data);
        OutputStream out = getContentResolver().openOutputStream(uri);
        out.write(root.toString(2).getBytes("UTF-8"));
        out.close();
        toast("تم تصدير النسخة الاحتياطية");
    }

    private void importFrom(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (bytes.size() + n > 5 * 1024 * 1024) throw new Exception("ملف النسخة أكبر من 5MB");
            bytes.write(buffer, 0, n);
        }
        in.close();
        JSONObject root = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
        if (!"MiMaxAiBackup".equals(root.optString("format")) || root.optInt("version") != 1)
            throw new Exception("ملف النسخة غير متوافق");
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new Exception("النسخة لا تحتوي بيانات");
        String apiKey = Store.get("key", "");
        android.content.SharedPreferences.Editor e = Store.sp.edit().clear();
        if (apiKey != null && !apiKey.isEmpty()) e.putString("key", apiKey);
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equals("key") || key.equals("session") || key.startsWith("google")) continue;
            Object value = data.opt(key);
            if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) e.putInt(key, (Integer) value);
            else if (value instanceof Long) e.putLong(key, (Long) value);
            else if (value instanceof Number) e.putFloat(key, ((Number) value).floatValue());
            else if (value instanceof String) e.putString(key, (String) value);
        }
        e.apply();
        toast("تمت الاستعادة. أعد فتح التطبيق لتطبيق كل الإعدادات.");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
