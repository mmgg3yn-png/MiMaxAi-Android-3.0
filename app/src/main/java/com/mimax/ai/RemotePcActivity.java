package com.mimax.ai;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class RemotePcActivity extends Activity {
    private EditText host, token, command, confirm;
    private TextView output;
    private LinearLayout container;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(0xFFF4F6F8);
        container = root;

        TextView title = new TextView(this);
        title.setText("اتصال الكمبيوتر — ADB / Fastboot");
        title.setTextColor(0xFF17212D);
        title.setTextSize(19);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView note = new TextView(this);
        note.setText("شغّل pc-agent/mimax_pc_agent.py على الكمبيوتر أولاً. الهاتف والكمبيوتر يجب أن يكونا على نفس شبكة Wi‑Fi. الاتصال محلي برمز عشوائي.");
        note.setTextColor(0xFF526172);
        note.setTextSize(13);
        root.addView(note);

        host = field("عنوان الكمبيوتر", "http://192.168.1.100:8765");
        token = field("رمز الاتصال", "");
        Button connect = new Button(this);
        connect.setText("اختبار الاتصال");
        connect.setAllCaps(false);
        root.addView(connect, new LinearLayout.LayoutParams(-1, dp(44)));
        connect.setOnClickListener(v -> request(false));

        command = field("أمر ADB / Fastboot", "adb devices");
        confirm = field("تأكيد إضافي للتفليش فقط", "");
        confirm.setHint("اتركه فارغاً إلا إذا كنت متأكداً — اكتب FLASH");
        Button run = new Button(this);
        run.setText("تنفيذ الأمر على الكمبيوتر");
        run.setAllCaps(false);
        root.addView(run, new LinearLayout.LayoutParams(-1, dp(48)));
        run.setOnClickListener(v -> request(true));

        ScrollView sc = new ScrollView(this);
        output = new TextView(this);
        output.setText("لم يتم الاتصال بعد.");
        output.setTextColor(0xFF17212D);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        output.setBackgroundColor(0xFFFFFFFF);
        sc.addView(output);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        Button back = new Button(this);
        back.setText("العودة إلى Mi_Max Ai");
        back.setAllCaps(false);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
        back.setOnClickListener(v -> finish());
        setContentView(root);
    }

    private EditText field(String label, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(label);
        e.setTextSize(14);
        e.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(6);
        container.addView(e, p);
        return e;
    }

    private android.view.View rootView() { return ((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0); }

    private void request(boolean execute) {
        String base = host.getText().toString().trim().replaceAll("/+$", "");
        String key = token.getText().toString().trim();
        if (!(base.startsWith("http://") || base.startsWith("https://")) || key.isEmpty()) {
            toast("أدخل عنوان الكمبيوتر ورمز الاتصال.");
            return;
        }
        String cmd = command.getText().toString().trim();
        String conf = confirm.getText().toString().trim();
        if (execute && cmd.toLowerCase().startsWith("fastboot flash") && !"FLASH".equals(conf)) {
            toast("للتفليش يلزم كتابة FLASH في خانة التأكيد.");
            return;
        }
        output.setText(execute ? "جارٍ تنفيذ الأمر..." : "جارٍ اختبار الاتصال...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(base + (execute ? "/run" : "/status"));
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(execute ? 130000 : 15000);
                c.setRequestProperty("X-MiMax-Token", key);
                c.setRequestProperty("Accept", "application/json");
                if (execute) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    JSONObject body = new JSONObject();
                    body.put("command", cmd);
                    body.put("confirm", conf);
                    OutputStream os = c.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.close();
                }
                int status = c.getResponseCode();
                InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder data = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) data.append(line);
                reader.close();
                JSONObject result = new JSONObject(data.toString());
                String text;
                if (execute) text = result.optString("output", result.optString("error", "لا توجد مخرجات"))
                        + "\n\nHTTP " + status + " — exit " + result.optInt("exit_code", -1);
                else text = "الاتصال: " + (result.optBoolean("ok") ? "ناجح" : "فشل")
                        + "\nADB: " + result.optBoolean("adb") + "\nFastboot: " + result.optBoolean("fastboot")
                        + "\nWorkspace: " + result.optString("workspace") + "\nHTTP " + status;
                final String show = text;
                runOnUiThread(() -> output.setText(show));
            } catch (Exception e) {
                final String msg = "فشل الاتصال: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage());
                runOnUiThread(() -> output.setText(msg));
            } finally { if (c != null) c.disconnect(); }
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
