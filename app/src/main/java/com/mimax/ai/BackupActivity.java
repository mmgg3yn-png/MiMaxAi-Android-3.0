package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackupActivity extends Activity {
    private static final int EXPORT = 81, IMPORT = 82;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText password;
    private ProgressDialog progress;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        Theme theme = Theme.get(Store.theme());
        getWindow().setStatusBarColor(theme.base);
        getWindow().setNavigationBarColor(theme.base);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(theme.base);

        TextView title = new TextView(this);
        title.setText("النسخ الاحتياطي الشامل");
        title.setTextColor(theme.tx); title.setTextSize(19);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView note = new TextView(this);
        note.setText("ينشئ ملفاً مشفراً بكلمة مرور ويشمل بيانات Mi_Max Ai الخاصة بالتطبيق: المحادثات والذاكرة والإعدادات ومفاتيح الاتصال والمشاريع والإضافات ومحركات الأدوات وقواعد البيانات والملفات المحلية. استخدم كلمة مرور قوية واحفظها؛ لا يمكن استعادة النسخة دونها.");
        note.setTextColor(theme.mut); note.setTextSize(13);
        root.addView(note);

        password = new EditText(this);
        password.setSingleLine(true);
        password.setTextColor(theme.tx);
        password.setHintTextColor(theme.mut);
        password.setHint("كلمة مرور النسخة (8 أحرف على الأقل)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(52));
        pp.topMargin = dp(12);
        root.addView(password, pp);

        Button export = button("إنشاء نسخة كاملة مشفّرة", theme.ac, theme.base);
        root.addView(export, new LinearLayout.LayoutParams(-1, dp(48)));
        export.setOnClickListener(v -> {
            if (!validPassword()) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "MiMaxAi-Full-Backup.mmaxbackup");
            startActivityForResult(i, EXPORT);
        });

        Button restore = button("استعادة نسخة كاملة", theme.surface2, theme.tx);
        root.addView(restore, new LinearLayout.LayoutParams(-1, dp(48)));
        restore.setOnClickListener(v -> {
            if (!validPassword()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, IMPORT);
        });

        TextView warning = new TextView(this);
        warning.setText("مهم: تُنسخ بيانات التطبيق التي يستطيع Android إتاحتها لهذا التطبيق فقط. لا يمكن نسخ بيانات التطبيقات الأخرى أو ملفات النظام المحمية أو الأذونات الخاصة بالنظام. محتويات USB/SD الخارجية لا تُضمّن تلقائياً؛ يمكن إضافتها لاحقاً بعد منح الوصول لها.");
        warning.setTextColor(0xFFFFB36B); warning.setTextSize(12);
        warning.setPadding(0, dp(14), 0, dp(12));
        root.addView(warning);

        Button back = button("العودة", theme.surface2, theme.tx);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        back.setOnClickListener(v -> finish());
        setContentView(root);
        AppPalette.apply(this, root);
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(fg);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        return b;
    }

    private boolean validPassword() {
        if (password.getText().toString().length() < 8) {
            password.setError("أدخل 8 أحرف على الأقل");
            return false;
        }
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String pass = password.getText().toString();
        if (requestCode == EXPORT) runBackup(uri, pass);
        else if (requestCode == IMPORT) new AlertDialog.Builder(this)
                .setTitle("تأكيد الاستعادة الشاملة")
                .setMessage("سيتم استبدال بيانات التطبيق الحالية بالبيانات الموجودة في النسخة. أنشئ نسخة احتياطية من الحالة الحالية أولاً. هل تريد المتابعة؟")
                .setPositiveButton("استعادة", (d, w) -> runRestore(uri, pass))
                .setNegativeButton("إلغاء", null).show();
    }

    private void runBackup(Uri uri, String pass) {
        showProgress("جارٍ إنشاء النسخة المشفّرة…");
        worker.execute(() -> {
            try {
                FullBackupEngine.export(this, uri, pass);
                main.post(() -> { hideProgress(); toast("اكتملت النسخة الاحتياطية المشفّرة."); });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                main.post(() -> { hideProgress(); toast("فشل النسخ: " + message); });
            }
        });
    }

    private void runRestore(Uri uri, String pass) {
        showProgress("جارٍ فحص النسخة واستعادتها…");
        worker.execute(() -> {
            try {
                FullBackupEngine.RestoreReport report = FullBackupEngine.restore(this, uri, pass);
                main.post(() -> {
                    hideProgress();
                    new AlertDialog.Builder(this).setTitle("اكتملت الاستعادة")
                            .setMessage(report.summary() + "\nأعد تشغيل التطبيق لتطبيق جميع البيانات المستعادة.")
                            .setPositiveButton("إعادة تشغيل الآن", (d, w) -> restart())
                            .setNegativeButton("لاحقاً", null).show();
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "النسخة تالفة أو كلمة المرور غير صحيحة" : e.getMessage();
                main.post(() -> { hideProgress(); toast("فشلت الاستعادة: " + message); });
            }
        });
    }

    private void restart() {
        try { AppRestart.restart(this); }
        catch (Exception e) { toast("تعذر إعادة التشغيل: " + e.getMessage()); }
    }
    private void showProgress(String message) {
        progress = new ProgressDialog(this);
        progress.setMessage(message); progress.setIndeterminate(true); progress.setCancelable(false); progress.show();
    }
    private void hideProgress() { if (progress != null && progress.isShowing()) progress.dismiss(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        hideProgress();
        worker.shutdown();
        super.onDestroy();
    }
}
