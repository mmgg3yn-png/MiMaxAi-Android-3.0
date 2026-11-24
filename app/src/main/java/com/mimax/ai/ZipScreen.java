package com.mimax.ai;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.mimax.ai.engine.Archives;
import com.mimax.ai.engine.Io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** مستكشف ZIP / APK / JAR: تصفح ومعاينة بدون فك، تعديل نصوص داخل الأرشيف، حذف مدخلات، استخراج، وفحص CRC. */
class ZipScreen {
    final MainActivity a;
    File zip;
    List<Archives.Item> items = new ArrayList<>();

    ZipScreen(MainActivity a) { this.a = a; }

    static boolean zipLikeName(String n) {
        return n.toLowerCase().matches(".*\\.(zip|apk|jar|apks|xapk|aar|zipx)$");
    }

    void open(File f) {
        zip = f;
        try { items = Archives.list(f); }
        catch (IOException e) { a.showError("تعذر فتح الأرشيف:\n" + e.getMessage()); return; }
        a.openScreen(f.getName(), () -> render(""));
    }

    static String tagOf(String n) {
        int i = n.lastIndexOf('.');
        if (i < 0 || i == n.length() - 1) return "FILE";
        String e = n.substring(i + 1).toUpperCase();
        return e.length() > 5 ? "FILE" : e;
    }

    void render(final String prefix) {
        a.header(prefix.isEmpty() ? zip.getName() : prefix, true, false);
        LinearLayout p = a.page();
        if (prefix.isEmpty()) {
            long total = 0;
            int n = 0;
            for (Archives.Item i : items) if (!i.dir) { total += Math.max(0, i.size); n++; }
            boolean apk = zip.getName().toLowerCase().endsWith(".apk");
            p.addView(a.note(n + " ملف — الحجم بعد الفك " + Io.human(total) + " — حجم الأرشيف " + Io.human(zip.length())
                    + (apk ? "\nتنبيه: أي تعديل داخل APK يُبطل توقيعه، ولن يُثبَّت قبل إعادة توقيعه." : "")));
            p.addView(a.btn("استخراج الكل", a.AC, a.ON, v -> extractAll()));
            p.addView(a.btn("فحص سلامة الأرشيف (CRC)", a.SF2, a.TX, v -> testZip()));
        }
        TreeMap<String, Integer> dirs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Archives.Item> fl = new ArrayList<>();
        for (Archives.Item i : items) {
            if (!i.name.startsWith(prefix)) continue;
            String rest = i.name.substring(prefix.length());
            if (rest.isEmpty()) continue;
            int s = rest.indexOf('/');
            if (s >= 0) { String d = rest.substring(0, s); Integer c = dirs.get(d); dirs.put(d, c == null ? 1 : c + 1); }
            else if (!i.dir) fl.add(i);
        }
        Collections.sort(fl, (x, y) -> x.name.compareToIgnoreCase(y.name));
        if (dirs.isEmpty() && fl.isEmpty()) p.addView(a.note("المجلد فارغ"));
        for (final Map.Entry<String, Integer> d : dirs.entrySet()) {
            final String np = prefix + d.getKey() + "/";
            p.addView(a.row("DIR", d.getKey(), d.getValue() + " عنصر", v -> a.openScreen(np, () -> render(np))));
        }
        for (final Archives.Item it : fl) {
            final String shortName = it.name.substring(prefix.length());
            p.addView(a.row(tagOf(shortName), shortName, Io.human(Math.max(0, it.size)) + (it.stored ? " — مخزَّن" : "")
                    + (it.size > 0 && it.packed >= 0 ? " — مضغوط " + Io.human(it.packed) : ""), v -> entryMenu(it)));
        }
        a.setPage(p);
    }

    interface Act { void go(); }

    void menu(String title, String[] labels, final Act[] acts) {
        a.gb().setTitle(title).setItems(labels, (d, w) -> acts[w].go()).show();
    }

    void entryMenu(final Archives.Item it) {
        final String n = it.name;
        List<String> L = new ArrayList<>();
        List<Act> A = new ArrayList<>();
        L.add("معاينة بدون فك"); A.add(() -> preview(it));
        L.add("تعديل (نص)"); A.add(() -> edit(it));
        if (MediaUi.isAudio(n) || MediaUi.isVideo(n)) { L.add("تشغيل"); A.add(() -> play(it)); }
        L.add("استخراج هذا الملف"); A.add(() -> extractOne(it));
        L.add("حذف من الأرشيف"); A.add(() -> a.confirm("حذف «" + n + "» من الأرشيف؟ (تُحفظ نسخة .bak قبل أول تعديل)", () -> {
            Set<String> del = new HashSet<>();
            del.add(n);
            a.runBg("جارٍ إعادة كتابة الأرشيف…", null, () -> commit(null, del), () -> { a.toast("تم الحذف"); a.screens.peek().build.run(); });
        }));
        menu(n, L.toArray(new String[0]), A.toArray(new Act[0]));
    }

    void preview(final Archives.Item it) {
        final String n = it.name.toLowerCase();
        if (n.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) {
            final byte[][] box = new byte[1][];
            a.runBg("جارٍ القراءة…", null, () -> box[0] = Archives.read(zip, it.name, 24L << 20), () -> showImage(box[0]));
            return;
        }
        if (MediaUi.isAudio(n) || MediaUi.isVideo(n)) { play(it); return; }
        final byte[][] box = new byte[1][];
        a.runBg("جارٍ القراءة…", null, () -> box[0] = Archives.read(zip, it.name, EditorScreen.MAX),
                () -> EditorScreen.open(a, it.name, box[0], false, null));
    }

    void edit(final Archives.Item it) {
        final byte[][] box = new byte[1][];
        a.runBg("جارٍ القراءة…", null, () -> box[0] = Archives.read(zip, it.name, EditorScreen.MAX), () ->
                EditorScreen.open(a, it.name, box[0], true, data -> {
                    Map<String, byte[]> rep = new HashMap<>();
                    rep.put(it.name, data);
                    commit(rep, null);
                }));
    }

    /** يكتب أرشيفاً جديداً ثم يستبدل الأصل، مع نسخة .bak قبل أول تعديل. */
    void commit(Map<String, byte[]> rep, Set<String> del) throws Exception {
        File dir = zip.getAbsoluteFile().getParentFile();
        File tmp = new File(dir, zip.getName() + ".tmp"), bak = new File(dir, zip.getName() + ".bak");
        Archives.rewrite(zip, rep, del, tmp, null);
        if (!bak.exists()) copy(zip, bak);
        if (!tmp.renameTo(zip)) { copy(tmp, zip); tmp.delete(); }
        items = Archives.list(zip);
    }

    static void copy(File s, File d) throws IOException {
        try (InputStream in = new FileInputStream(s); OutputStream o = new FileOutputStream(d)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
    }

    void play(final Archives.Item it) {
        final File out = new File(a.getCacheDir(), "zipmedia/" + new File(it.name).getName());
        a.runBg("جارٍ تجهيز الملف…", null, () -> {
            out.getParentFile().mkdirs();
            Archives.extractOne(zip, it.name, out);
        }, () -> { if (MediaUi.isVideo(out.getName())) a.media.playVideo(out); else a.media.playAudio(out); });
    }

    void extractOne(final Archives.Item it) {
        final File dest = new File(zip.getAbsoluteFile().getParentFile(), baseName(zip.getName()) + "_extracted/" + it.name);
        a.runBg("جارٍ الاستخراج…", null, () -> {
            Io.checkSpace(dest.getParentFile(), Math.max(0, it.size));
            Archives.extractOne(zip, it.name, dest);
        }, () -> a.showError("تم الاستخراج إلى:\n" + dest.getPath()));
    }

    static String baseName(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    void extractAll() {
        final File dest = new File(zip.getAbsoluteFile().getParentFile(), baseName(zip.getName()));
        final Io.Cancel c = new Io.Cancel();
        final Archives.Report[] r = new Archives.Report[1];
        a.runBg("جارٍ الاستخراج…", c, () -> r[0] = Archives.extract(zip, dest, null, null, c), () -> {
            StringBuilder sb = new StringBuilder("تم استخراج " + r[0].files + " ملف (" + Io.human(r[0].bytes) + ") إلى:\n" + dest.getPath());
            if (!r[0].errors.isEmpty()) {
                sb.append("\n\nأخطاء (").append(r[0].errors.size()).append("):");
                for (int i = 0; i < Math.min(8, r[0].errors.size()); i++) sb.append("\n• ").append(r[0].errors.get(i));
            }
            a.showError(sb.toString());
        });
    }

    void testZip() {
        final List<String>[] r = new List[1];
        a.runBg("جارٍ فحص كل المدخلات…", null, () -> r[0] = Archives.test(zip), () -> {
            if (r[0].isEmpty()) a.showError("الأرشيف سليم: كل المدخلات اجتازت فحص CRC.");
            else {
                StringBuilder sb = new StringBuilder("وُجدت " + r[0].size() + " مشكلة:");
                for (int i = 0; i < Math.min(10, r[0].size()); i++) sb.append("\n• ").append(r[0].get(i));
                a.showError(sb.toString());
            }
        });
    }

    void showImage(byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 2048) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (bm == null) { a.toast("تعذر عرض الصورة"); return; }
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(a);
        iv.setBackgroundColor(Color.BLACK);
        iv.setImageBitmap(bm);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener((View v) -> d.dismiss());
        d.setContentView(iv);
        d.show();
    }
}
