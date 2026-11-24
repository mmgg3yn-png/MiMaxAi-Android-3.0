package com.mimax.ai;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ExtensionStoreActivity extends Activity {
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme t = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16),dp(14),dp(16),dp(14)); page.setBackgroundColor(t.base);
        TextView title = new TextView(this); title.setText("متجر إضافات Mi_Max"); title.setTextSize(22); title.setTextColor(t.tx); page.addView(title);
        TextView note = new TextView(this); note.setText("أدخل رابط HTTPS مباشر لملف إضافة .mt1. سيُنزّل الملف ثم يفتح مدير الإضافات لفحصه وتثبيته. لا تثبّت إضافات من مصادر لا تثق بها."); note.setTextColor(t.mut); page.addView(note);
        EditText url = new EditText(this); url.setSingleLine(true); url.setHint("https://example.com/extension.mt1"); page.addView(url);
        TextView status = new TextView(this); status.setTextColor(t.mut); page.addView(status);
        Button install = new Button(this); install.setText("تنزيل وفحص الإضافة"); page.addView(install);
        install.setOnClickListener(v -> {
            String value = url.getText().toString().trim();
            if (!value.startsWith("https://") || !value.toLowerCase(java.util.Locale.US).endsWith(".mt1")) {
                status.setText("استخدم رابط HTTPS مباشر ينتهي بـ .mt1"); return;
            }
            install.setEnabled(false); status.setText("جارٍ تنزيل الإضافة...");
            new Thread(() -> {
                File out = new File(getCacheDir(), "store-download.mt1");
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(value).openConnection();
                    connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(true);
                    connection.connect();
                    if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                    if (connection.getContentLengthLong() > 20L * 1024 * 1024) throw new Exception("حجم الملف أكبر من 20MB.");
                    InputStream in = connection.getInputStream(); FileOutputStream stream = new FileOutputStream(out);
                    byte[] buffer = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buffer)) != -1) { total += n; if (total > 20L * 1024 * 1024) throw new Exception("حجم الملف أكبر من 20MB."); stream.write(buffer,0,n); }
                    stream.close(); in.close();
                    runOnUiThread(() -> {
                        Intent i = new Intent(this, ExtensionManagerActivity.class);
                        i.putExtra("store_file_path", out.getAbsolutePath());
                        startActivity(i); install.setEnabled(true); status.setText("تم التنزيل. أكمل الفحص والتثبيت في مدير الإضافات.");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { install.setEnabled(true); status.setText("فشل التنزيل: " + e.getMessage()); });
                } finally { if (connection != null) connection.disconnect(); }
            }).start();
        });
        Button back = new Button(this); back.setText("رجوع"); page.addView(back); back.setOnClickListener(v -> finish());
        setContentView(page);
    }
}
