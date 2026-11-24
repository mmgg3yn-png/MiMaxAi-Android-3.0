package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safe .mt1 package manager.
 * Imported .mt1 files are declarative metadata only; bundled native tools are
 * explicitly listed and launched as app-owned activities.
 */
public class ExtensionManagerActivity extends Activity {
    private static final int PICK_MT1 = 810;
    private LinearLayout list;
    private File root;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new File(getFilesDir(), "extensions");
        if (!root.exists()) root.mkdirs();
        Theme th = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(10), dp(14), dp(14));
        page.setBackgroundColor(th.base);

        TextView title = new TextView(this);
        title.setText("إدارة الإضافات .mt1");
        title.setTextColor(th.tx);
        title.setTextSize(21);
        page.addView(title);

        TextView info = new TextView(this);
        info.setText("إضافات مدمجة وأخرى مستوردة. يتم فحص ملفات .mt1 ولا تُنفّذ ملفاتها البرمجية تلقائياً.");
        info.setTextColor(th.mut);
        info.setTextSize(13);
        page.addView(info);

        Button install = new Button(this);
        install.setText("استيراد إضافة .mt1");
        install.setAllCaps(false);
        page.addView(install, new LinearLayout.LayoutParams(-1, dp(48)));
        install.setOnClickListener(v -> pick());

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = new Button(this);
        back.setText("العودة");
        back.setAllCaps(false);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        back.setOnClickListener(v -> finish());
        setContentView(page);
        render();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, PICK_MT1); }
        catch (Exception e) { toast("تعذر فتح منتقي الملفات"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MT1 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try { install(data.getData()); }
        catch (Exception e) { toast("رفضت الإضافة: " + (e.getMessage() == null ? e.toString() : e.getMessage())); }
        render();
    }

    private void install(Uri uri) throws Exception {
        File stage = new File(getCacheDir(), "mt1-stage-" + System.currentTimeMillis());
        if (!stage.mkdirs()) throw new Exception("تعذر إنشاء مجلد مؤقت.");
        JSONObject manifest = null;
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[8192];
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("تعذر قراءة الملف.");
        ZipInputStream zip = new ZipInputStream(raw);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 300) throw new Exception("الإضافة تحتوي على عناصر كثيرة.");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains(":"))
                    throw new Exception("مسار غير آمن داخل الإضافة.");
                File out = new File(stage, name);
                String base = stage.getCanonicalPath();
                String full = out.getCanonicalPath();
                if (!full.startsWith(base + File.separator) && !full.equals(base))
                    throw new Exception("مسار خارج الحزمة.");
                if (entry.isDirectory()) out.mkdirs();
                else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream fos = new FileOutputStream(out);
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n;
                        if (total > 20L * 1024 * 1024) { fos.close(); throw new Exception("حجم الإضافة يتجاوز 20MB."); }
                        fos.write(buffer, 0, n);
                    }
                    fos.close();
                    if ("manifest.json".equals(name)) {
                        byte[] bytes = readFile(out, 128 * 1024);
                        manifest = new JSONObject(new String(bytes, "UTF-8"));
                    }
                }
                zip.closeEntry();
            }
        } finally { zip.close(); }

        if (manifest == null) throw new Exception("ملف manifest.json غير موجود.");
        String id = manifest.optString("id", "");
        String name = manifest.optString("name", "");
        String version = manifest.optString("version", "");
        if (!id.matches("[A-Za-z0-9._-]{2,64}")) throw new Exception("معرّف الإضافة غير صالح.");
        if (name.trim().isEmpty() || version.trim().isEmpty()) throw new Exception("الاسم والإصدار مطلوبان.");
        String runtime = manifest.optString("runtime", "declarative");
        if (!"declarative".equals(runtime)
                && !("native".equals(runtime) && "mimax.logo.bin.extractor".equals(id)
                && "com.mimax.ai.LogoBinExtractorActivity".equals(manifest.optString("entrypoint", ""))))
            throw new Exception("نوع التشغيل غير مدعوم؛ يسمح فقط بامتداد Logo.bin المدمج.");
        File target = new File(root, id);
        if (target.exists()) deleteTree(target);
        if (!stage.renameTo(target)) throw new Exception("تعذر تثبيت الإضافة.");
        toast("تم تثبيت " + name + " (" + version + ")");
    }

    private byte[] readFile(File f, int max) throws Exception {
        if (f.length() > max) throw new Exception("manifest أكبر من الحد.");
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        in.close();
        return b;
    }

    private void render() {
        list.removeAllViews();
        addBuiltinExtractor();
        File[] dirs = root.listFiles();
        if (dirs == null || dirs.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد إضافات مستوردة حالياً.");
            empty.setTextSize(14);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
            return;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File mf = new File(dir, "manifest.json");
            try {
                JSONObject m = new JSONObject(new String(readFile(mf, 128 * 1024), "UTF-8"));
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackgroundColor(Theme.get(Store.theme()).surface);
                TextView label = new TextView(this);
                label.setText(m.optString("name", dir.getName()) + "  •  " + m.optString("version", ""));
                label.setTextSize(16);
                label.setTextColor(Theme.get(Store.theme()).tx);
                card.addView(label);
                TextView desc = new TextView(this);
                desc.setText(m.optString("description", "بدون وصف"));
                desc.setTextColor(Theme.get(Store.theme()).mut);
                card.addView(desc);
                if ("mimax.logo.bin.extractor".equals(m.optString("id", ""))) {
                    Button open = new Button(this);
                    open.setText("فتح أداة فك logo.bin");
                    open.setAllCaps(false);
                    card.addView(open);
                    open.setOnClickListener(v -> startActivity(new Intent(this, LogoBinExtractorActivity.class)));
                }
                Button remove = new Button(this);
                remove.setText("إزالة الإضافة");
                remove.setAllCaps(false);
                card.addView(remove);
                remove.setOnClickListener(v -> {
                    if (deleteTree(dir)) { toast("تمت إزالة الإضافة"); render(); }
                    else toast("تعذرت إزالة بعض الملفات");
                });
                addCard(card);
            } catch (Exception e) {
                TextView bad = new TextView(this);
                bad.setText("إضافة غير صالحة: " + dir.getName());
                bad.setTextColor(0xFFFF5555);
                list.addView(bad);
            }
        }
    }

    private void addBuiltinExtractor() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(Theme.get(Store.theme()).surface);
        TextView label = new TextView(this);
        label.setText("Logo.bin Extractor  •  1.0.0");
        label.setTextSize(16);
        label.setTextColor(Theme.get(Store.theme()).tx);
        card.addView(label);
        TextView desc = new TextView(this);
        desc.setText("إضافة مدمجة: استخراج صور MediaTek logo.bin إلى PNG أو RGBA وتصديرها كملف ZIP. قراءة فقط.");
        desc.setTextColor(Theme.get(Store.theme()).mut);
        card.addView(desc);
        Button open = new Button(this);
        open.setText("فتح الإضافة");
        open.setAllCaps(false);
        card.addView(open);
        open.setOnClickListener(v -> startActivity(new Intent(this, LogoBinExtractorActivity.class)));
        addCard(card);
    }

    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        list.addView(card, p);
    }

    private boolean deleteTree(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        }
        return !f.exists() || f.delete();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
