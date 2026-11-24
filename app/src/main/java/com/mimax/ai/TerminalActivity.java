package com.mimax.ai;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Terminal محلي محدود داخل مساحة التطبيق.
 * لا يستخدم root ولا ينفذ أوامر shell مركبة؛ الأدوات تعمل بصلاحيات التطبيق فقط.
 */
public class TerminalActivity extends Activity {
    private LinearLayout log;
    private ScrollView scroll;
    private EditText input;
    private File cwd;
    private File workspace;
    private final Set<String> allowed = new HashSet<>(Arrays.asList(
            "ls", "date", "uname", "getprop", "df", "du", "id", "whoami",
            "head", "tail", "wc", "grep", "which", "env", "stat"
    ));

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = new File(getFilesDir(), "workspace");
        if (!workspace.exists()) workspace.mkdirs();
        cwd = workspace;
        getWindow().setStatusBarColor(Color.rgb(14, 18, 24));
        getWindow().setNavigationBarColor(Color.rgb(14, 18, 24));
        build();
        line("Mi_Max Ai Terminal 3.0", "#7CDBA5");
        line("بيئة Android محلية بصلاحيات التطبيق — بدون root.", "#AAB4C3");
        line("المجلد: " + workspace.getAbsolutePath(), "#AAB4C3");
        line("اكتب help لعرض الأوامر المتاحة.", "#AAB4C3");
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.rgb(14, 18, 24));

        TextView title = new TextView(this);
        title.setText("Mi_Max Ai  •  Terminal");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        scroll = new ScrollView(this);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        log.setPadding(dp(4), dp(6), dp(4), dp(10));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF8995A5);
        input.setHint("اكتب أمراً...");
        input.setTextSize(14);
        input.setBackgroundColor(0xFF202936);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT);
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button run = new Button(this);
        run.setText("تشغيل");
        run.setAllCaps(false);
        bar.addView(run, new LinearLayout.LayoutParams(dp(84), dp(48)));
        run.setOnClickListener(v -> executeInput());
        input.setOnEditorActionListener((v, action, event) -> {
            executeInput();
            return true;
        });
        root.addView(bar);
        Button back = new Button(this);
        back.setText("العودة إلى Mi_Max Ai");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
        setContentView(root);
    }

    private void line(String text, String color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor(color));
        t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(2), 0, dp(2));
        log.addView(t);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        while (log.getChildCount() > 500) log.removeViewAt(0);
    }

    private void executeInput() {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return;
        input.setText("");
        line("$ " + raw, "#7CDBA5");
        new Thread(() -> {
            String result = runCommand(raw);
            runOnUiThread(() -> {
                if (!result.isEmpty()) line(result, "#E6EDF3");
            });
        }).start();
    }

    private String runCommand(String raw) {
        if (raw.length() > 500) return "خطأ: الأمر طويل جداً.";
        if (raw.matches(".*[;&|<>$\\\\].*") || raw.indexOf(96) >= 0 || raw.contains(".."))
            return "مرفوض: الرموز المركبة ومسارات الرجوع للخلف غير مسموحة.";
        String[] a = raw.split("\\s+");
        String cmd = a[0];
        if (cmd.equals("help")) return "الأوامر: help, pwd, ls, cd, mkdir, touch, cat, echo, date, uname, getprop, df, du, id, whoami, head, tail, wc, grep, which, env, stat, clear\nكل الملفات محصورة داخل مجلد workspace الخاص بالتطبيق. لا يوجد root أو ADB/Fastboot محلي.";
        if (cmd.equals("clear")) { runOnUiThread(() -> log.removeAllViews()); return ""; }
        if (cmd.equals("pwd")) return cwd.getAbsolutePath();
        if (cmd.equals("cd")) {
            String dest = a.length > 1 ? a[1] : ".";
            File f = safeFile(dest);
            if (f == null || !f.isDirectory()) return "cd: المجلد غير موجود أو خارج مساحة العمل";
            cwd = f;
            return "";
        }
        if (cmd.equals("echo")) return raw.length() > 5 ? raw.substring(5) : "";
        if (cmd.equals("mkdir") || cmd.equals("touch")) {
            if (a.length < 2) return "استخدم: " + cmd + " اسم_الملف";
            File f = safeFile(a[1]);
            if (f == null) return "المسار غير مسموح.";
            try {
                if (cmd.equals("mkdir")) {
                    if (!f.mkdirs() && !f.isDirectory()) return "تعذر إنشاء المجلد.";
                } else {
                    File p = f.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    if (!f.exists() && !f.createNewFile()) return "تعذر إنشاء الملف.";
                }
                return "تم.";
            } catch (Exception e) { return "خطأ: " + e.getMessage(); }
        }
        if (cmd.equals("cat")) {
            if (a.length < 2) return "استخدم: cat اسم_الملف";
            File f = safeFile(a[1]);
            if (f == null || !f.isFile()) return "الملف غير موجود داخل workspace.";
            try {
                if (f.length() > 128 * 1024) return "الملف أكبر من حد العرض (128KB).";
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                StringBuilder b = new StringBuilder(); String l;
                while ((l = r.readLine()) != null) b.append(l).append('\n');
                r.close(); return b.toString();
            } catch (Exception e) { return "خطأ: " + e.getMessage(); }
        }
        if (!allowed.contains(cmd)) return "الأمر غير متاح في هذه النسخة. اكتب help.";
        List<String> args = new ArrayList<>();
        args.add("/system/bin/" + cmd);
        for (int i = 1; i < a.length; i++) {
            if (a[i].startsWith("/") || a[i].contains(":") || a[i].contains("~")) return "المسارات المطلقة غير مسموحة.";
            args.add(a[i]);
        }
        try {
            Process p = new ProcessBuilder(args).directory(cwd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder(); String l; int count = 0;
            while ((l = r.readLine()) != null && count++ < 300) out.append(l).append('\n');
            r.close();
            int code = p.waitFor();
            if (count >= 300) out.append("\n[تم اختصار المخرجات]");
            if (code != 0) out.append("\n[exit ").append(code).append("]");
            return out.toString();
        } catch (Exception e) { return "تعذر تنفيذ الأمر: " + e.getMessage(); }
    }

    private File safeFile(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("..") || path.contains(":")) return null;
        File f = new File(cwd, path);
        try {
            String base = workspace.getCanonicalPath();
            String full = f.getCanonicalPath();
            return full.equals(base) || full.startsWith(base + File.separator) ? f : null;
        } catch (Exception e) { return null; }
    }
}
