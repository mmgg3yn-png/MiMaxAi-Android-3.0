package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mimax.ai.engine.Io;
import com.mimax.ai.engine.LogoBin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Full local MediaTek logo.bin workflow: inspect, extract, preview, replace PNG frames and repack. */
public final class LogoBinActivity extends Activity {
    private static final int PICK_SOURCE = 9400, PICK_REPLACEMENT = 9401;
    private File source, project;
    private LinearLayout rows;
    private TextView status;
    private String pendingName;
    private volatile boolean busy;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        String path = getIntent() == null ? null : getIntent().getStringExtra("file_path");
        if (path == null || path.trim().isEmpty()) pickSource();
        else setSource(new File(path));
    }

    private void setSource(File file) {
        if (file == null || !file.isFile() || !LogoBin.looksLike(file)) {
            toast("الملف المحدد ليس ملف MediaTek logo.bin صالحاً");
            return;
        }
        source = file;
        project = new File(source.getAbsoluteFile().getParentFile(), base(source.getName()) + "_logo_project");
        inspectAndRender();
    }

    private void pickSource() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_SOURCE);
    }

    private String base(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(dp(5), dp(5), dp(5), dp(5));
        return t;
    }

    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); return b;
    }

    private void buildUi() {
        int bg = 0xFFF2F4F7, fg = 0xFF17212D;
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));
        root.setBackgroundColor(bg);
        root.addView(text("محرر صور MediaTek logo.bin", 20, fg));
        root.addView(text("فك حقيقي إلى مجلد، معاينة PNG، استبدال الصور، ثم إعادة تجميع ملف جديد دون لمس الأصل.", 12, fg));
        status = text("جارٍ فحص الملف…", 12, fg); root.addView(status);
        root.addView(button("فك الصور إلى مجلد المشروع", this::extract));
        root.addView(button("إعادة تجميع logo.bin من التعديلات", this::repack));
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(button("إغلاق", this::finish));
        setContentView(root);
    }

    private void inspectAndRender() {
        new Thread(() -> {
            String info;
            try {
                LogoBin.Info i = LogoBin.inspect(source);
                info = "عدد الصور: " + i.count + " • حجم جدول الصور: " + i.blockSize
                        + " بايت • الملف: " + source.length() + " بايت";
            } catch (Exception e) { info = "تعذر قراءة الملف: " + e.getMessage(); }
            final String msg = info;
            runOnUiThread(() -> { status.setText(msg); renderProject(); });
        }, "LogoBinInspect").start();
    }

    private void extract() {
        if (busy) return;
        busy = true; status.setText("جارٍ فك صور logo.bin…");
        new Thread(() -> {
            String result;
            try {
                LogoBin.extract(source, project, "bgrale", new Io.Cancel(), null);
                result = "اكتمل الفك إلى: " + project.getAbsolutePath();
            } catch (Exception e) { result = "فشل الفك: " + e.getMessage(); }
            final String msg = result;
            runOnUiThread(() -> { busy = false; status.setText(msg); renderProject(); });
        }, "LogoBinExtract").start();
    }

    private void renderProject() {
        rows.removeAllViews();
        if (!LogoBin.isProject(project)) {
            rows.addView(text("لم يتم فك الملف بعد. اضغط زر فك الصور لإنشاء مجلد قابل للتعديل.", 13, 0xFF536273));
            return;
        }
        File[] files = project.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        if (files.length == 0) rows.addView(text("لم تُكتشف أبعاد معروفة لإطارات PNG. ملفات raw.z الأصلية محفوظة ويمكن إعادة تجميعها دون فقد.", 12, 0xFF8A4B00));
        for (File f : files) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(6), dp(8), dp(6));
            card.setBackgroundColor(0xFFFFFFFF);
            TextView name = text(f.getName(), 12, 0xFF17212D); card.addView(name);
            ImageView preview = new ImageView(this);
            preview.setAdjustViewBounds(true);
            preview.setMaxHeight(dp(190));
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bitmap = thumbnail(f);
            if (bitmap != null) preview.setImageBitmap(bitmap);
            card.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
            card.addView(button("استبدال هذه الصورة", () -> { pendingName = f.getName(); pickReplacement(); }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8); rows.addView(card, lp);
        }
        File[] raw = project.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".raw.z"));
        rows.addView(text("إطارات محفوظة خاماً: " + (raw == null ? 0 : raw.length)
                + " — تُستخدم تلقائياً عند عدم وجود PNG بديل للإطار.", 11, 0xFF536273));
    }

    private Bitmap thumbnail(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / sample > 900 || o.outHeight / sample > 900) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    private void pickReplacement() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_REPLACEMENT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_SOURCE) {
            File imported = new File(getCacheDir(), "selected-logo-" + System.currentTimeMillis() + ".bin");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(imported)) {
                if (in == null) throw new Exception("تعذر فتح الملف المحدد");
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                if (!LogoBin.looksLike(imported)) throw new Exception("توقيع MediaTek LOGO غير موجود");
                setSource(imported);
            } catch (Exception e) { toast("تعذر فتح logo.bin: " + e.getMessage()); }
            return;
        }
        if (request != PICK_REPLACEMENT || project == null || pendingName == null) return;
        File target = new File(project, pendingName);
        File temp = new File(project, pendingName + ".pending");
        File backup = new File(project, pendingName + ".previous");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new Exception("تعذر فتح الصورة المختارة");
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        } catch (Exception e) { temp.delete(); toast("فشل استيراد الصورة: " + e.getMessage()); return; }
        BitmapFactory.Options original = new BitmapFactory.Options(); original.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(target.getAbsolutePath(), original);
        BitmapFactory.Options replacement = new BitmapFactory.Options(); replacement.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getAbsolutePath(), replacement);
        if (replacement.outWidth <= 0 || replacement.outHeight <= 0) {
            temp.delete(); toast("الملف المختار ليس صورة قابلة للقراءة"); return;
        }
        if (original.outWidth > 0 && (original.outWidth != replacement.outWidth || original.outHeight != replacement.outHeight)) {
            temp.delete();
            toast("أبعاد الصورة الجديدة مختلفة. يجب أن تكون " + original.outWidth + "×" + original.outHeight + " بكسل للحفاظ على توافق إطار الإقلاع.");
            return;
        }
        if (backup.exists()) backup.delete();
        if (target.exists() && !target.renameTo(backup)) { temp.delete(); toast("تعذر حفظ نسخة من الصورة الأصلية"); return; }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            temp.delete(); toast("تعذر اعتماد الصورة الجديدة؛ تمت محاولة استعادة الأصل"); return;
        }
        backup.delete();
        toast("تم استبدال " + pendingName + " داخل مجلد المشروع");
        renderProject();
    }

    private void repack() {
        if (busy) return;
        if (!LogoBin.isProject(project)) { toast("فك الملف أولاً"); return; }
        File output = new File(source.getParentFile(), base(source.getName()) + "_edited.bin");
        if (output.exists()) output = new File(source.getParentFile(),
                base(source.getName()) + "_edited_" + System.currentTimeMillis() + ".bin");
        final File target = output;
        new AlertDialog.Builder(this).setTitle("إعادة تجميع logo.bin")
                .setMessage("سيُنشأ ملف جديد من الصور المعدّلة والبيانات الخام المحفوظة.\n\n" + target.getAbsolutePath()
                        + "\n\nتأكد من أن أبعاد الصور وتنسيق الألوان متوافقان مع جهازك.")
                .setNegativeButton("إلغاء", null).setPositiveButton("إعادة التجميع", (d, w) -> {
                    busy = true; status.setText("جارٍ إعادة التجميع والتحقق…");
                    new Thread(() -> {
                        String msg;
                        try {
                            LogoBin.repack(project, target);
                            LogoBin.inspect(target);
                            msg = "نجحت إعادة التجميع والتحقق من الترويسة. الناتج: " + target.getAbsolutePath();
                        } catch (Exception e) { msg = "فشلت إعادة التجميع: " + e.getMessage(); }
                        final String result = msg;
                        runOnUiThread(() -> { busy = false; status.setText(result); toast(result); });
                    }, "LogoBinRepack").start();
                }).show();
    }
}
