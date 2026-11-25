package com.mimax.ai;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.mimax.ai.engine.Archives;
import com.mimax.ai.engine.ArchivePack;
import com.mimax.ai.engine.BootImg;
import com.mimax.ai.engine.Io;
import com.mimax.ai.engine.LogoBin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** مدير الملفات: تصفح كل ذاكرة الهاتف، نسخ/نقل/حذف/إعادة تسمية، وفتح ZIP وboot والصور والوسائط والنصوص. */
class FileScreen {
    static final int REQ = 4;
    final MainActivity a;
    final ZipScreen zip;
    final BootScreen boot;
    final ImgScreen img;
    File cur, clip;
    boolean clipCut, pendingPermission;
    final SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US);

    FileScreen(MainActivity a) {
        this.a = a;
        zip = new ZipScreen(a);
        boot = new BootScreen(a);
        img = new ImgScreen(a);
    }

    // ---------------- الصلاحيات ----------------
    boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void requestAccess() {
        pendingPermission = true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ);
        }
    }

    void openRoot() {
        if (!hasAccess()) { a.openScreen("الملفات", this::renderPermission); return; }
        File start = new File(Store.get("lastDir", Environment.getExternalStorageDirectory().getPath()));
        if (!start.isDirectory() || !start.canRead()) start = Environment.getExternalStorageDirectory();
        final File s = start;
        a.openScreen("الملفات", () -> renderDir(s));
    }

    void renderPermission() {
        a.header("الملفات", false, false);
        LinearLayout p = a.page();
        p.addView(a.box("لعرض كل ملفات الهاتف وتعديلها يحتاج التطبيق إذن «الوصول إلى كل الملفات». يُستخدم محلياً فقط، ولا يُرسل أي ملف إلى الإنترنت."));
        p.addView(a.btn("منح الإذن", a.AC, a.ON, v -> requestAccess()));
        a.setPage(p);
    }

    // ---------------- التصفح ----------------
    static final String[] SORTS = {"الاسم", "الحجم", "التاريخ", "النوع"};

    static String ext(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    Comparator<File> comparator(final int s) {
        return (x, y) -> {
            boolean dx = x.isDirectory(), dy = y.isDirectory();
            if (dx != dy) return dx ? -1 : 1;
            if (dx || s == 0) return x.getName().compareToIgnoreCase(y.getName());
            if (s == 1) return Long.compare(y.length(), x.length());
            if (s == 2) return Long.compare(y.lastModified(), x.lastModified());
            int c = ext(x).compareToIgnoreCase(ext(y));
            return c != 0 ? c : x.getName().compareToIgnoreCase(y.getName());
        };
    }

    void renderDir(final File dir) {
        cur = dir;
        Store.put("lastDir", dir.getPath());
        a.header(dir.getName().isEmpty() ? "/" : dir.getName(), a.screens.size() > 1, false);
        final boolean hidden = Store.flag("showHidden", false);
        int sortTmp = 0;
        try { sortTmp = Integer.parseInt(Store.get("fsort", "0")); } catch (Exception e) { /* تجاهل */ }
        final int sort = sortTmp;
        File[] arr = dir.listFiles();
        final List<File> list = new ArrayList<>();
        if (arr != null) for (File f : arr) if (hidden || !f.getName().startsWith(".")) list.add(f);
        Collections.sort(list, comparator(sort));
        final File parent = dir.getParentFile();
        final boolean up = parent != null && parent.canRead();

        LinearLayout hv = new LinearLayout(a);
        hv.setOrientation(LinearLayout.VERTICAL);
        hv.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        TextView crumb = a.tv(dir.getPath(), a.MUT, 12);
        crumb.setTextDirection(View.TEXT_DIRECTION_LTR);
        crumb.setGravity(Gravity.LEFT);
        hv.addView(crumb);
        hv.addView(a.note(arr == null ? "لا يمكن قراءة هذا المجلد (صلاحية أو مسار محمي)."
                : list.size() + " عنصر — المتاح في القرص " + Io.human(dir.getUsableSpace())));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(a.small("فرز: " + SORTS[sort], v -> a.gb().setTitle("فرز حسب").setItems(SORTS, (d, w) -> {
            Store.put("fsort", String.valueOf(w));
            renderDir(dir);
        }).show()));
        row.addView(gap());
        row.addView(a.small(hidden ? "إخفاء المخفية" : "إظهار المخفية", v -> { Store.setFlag("showHidden", !hidden); renderDir(dir); }));
        row.addView(gap());
        row.addView(a.small("مجلد جديد", v -> a.input("اسم المجلد", "", s -> {
            File nd = new File(dir, s.trim());
            if (s.trim().isEmpty() || nd.exists() || !nd.mkdirs()) a.toast("تعذر إنشاء المجلد");
            renderDir(dir);
        })));
        row.addView(gap());
        row.addView(a.small("أماكن", v -> a.openScreen("أماكن", this::renderPlaces)));
        if (clip != null) {
            row.addView(gap());
            row.addView(a.small("لصق (" + (clipCut ? "نقل" : "نسخ") + ": " + clip.getName() + ")", v -> paste(dir)));
        }
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        hv.addView(hs);

        final int off = up ? 1 : 0;
        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        lv.setClipToPadding(false);
        lv.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(16));
        lv.addHeaderView(hv, null, false);
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return list.size() + off; }
            @Override public Object getItem(int i) { return null; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View c, ViewGroup vg) {
                FrameLayout w = new FrameLayout(a);
                w.setPadding(a.dp(4), a.dp(3), a.dp(4), a.dp(3));
                View r;
                if (up && i == 0) {
                    r = a.row("↑", "..", "المجلد الأعلى", v -> a.openScreen(parent.getName(), () -> renderDir(parent)));
                } else {
                    final File f = list.get(i - off);
                    r = a.row(f.isDirectory() ? "DIR" : ZipScreen.tagOf(f.getName()), f.getName(), subOf(f), v -> open(f));
                    r.setOnLongClickListener(v -> { menu(f); return true; });
                }
                w.addView(r, new FrameLayout.LayoutParams(-1, -2));
                return w;
            }
        });
        a.content.removeAllViews();
        a.content.addView(lv, new FrameLayout.LayoutParams(-1, -1));
    }

    View gap() { return new View(a) { { setMinimumWidth(a.dp(6)); } }; }

    String subOf(File f) {
        String d = df.format(new Date(f.lastModified()));
        return f.isDirectory() ? d : Io.human(f.length()) + " — " + d;
    }

    // ---------------- فتح الملفات ----------------
    void open(final File f) {
        if (f.isDirectory()) { a.openScreen(f.getName(), () -> renderDir(f)); return; }
        String n = f.getName().toLowerCase();
        if (LogoBin.looksLike(f)) { img.open(f); return; }
        if (ApkInstaller.isInstallable(n)) {
            a.gb().setTitle(f.getName()).setItems(new String[]{"تثبيت التطبيق", "تصفح كأرشيف"}, (d, w) -> { if (w == 0) a.installer.install(f); else zip.open(f); }).show();
            return;
        }
        if (ZipScreen.zipLikeName(n)) { zip.open(f); return; }
        if (n.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) { showImage(f); return; }
        if (MediaUi.isVideo(n)) { a.media.playVideo(f); return; }
        if (MediaUi.isAudio(n)) { a.media.playAudio(f); return; }
        if ((n.endsWith(".img") || n.endsWith(".bin") || n.contains("boot") || n.contains("recovery")) && BootImg.looksLikeBoot(f)) {
            boot.open(f);
            return;
        }
        if (n.endsWith(".img") || n.endsWith(".simg") || n.endsWith(".raw") || n.endsWith(".sparse")) { img.open(f); return; }
        if (f.length() > 0 && Archives.isZip(f)) { zip.open(f); return; }
        openText(f);
    }

    void openText(final File f) {
        if (f.length() > EditorScreen.MAX) {
            a.showError("الملف كبير (" + Io.human(f.length()) + ") للتحرير النصي، والحد 2MB.\nيمكنك نسخه أو نقله أو حذفه من قائمة الضغط المطوّل.");
            return;
        }
        final byte[][] box = new byte[1][];
        a.runBg("جارٍ القراءة…", null, () -> {
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int o = 0, n;
                while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
                box[0] = b;
            }
        }, () -> EditorScreen.open(a, f.getName(), box[0], true, data -> {
            File bak = new File(f.getPath() + ".bak");
            if (!bak.exists()) ZipScreen.copy(f, bak);
            try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        }));
    }

    void showImage(final File f) {
        final Bitmap[] bm = new Bitmap[1];
        a.runBg("جارٍ التحميل…", null, () -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 2048) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            bm[0] = BitmapFactory.decodeFile(f.getPath(), o);
        }, () -> {
            if (bm[0] == null) { a.toast("تعذر عرض الصورة"); return; }
            final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(a);
            iv.setBackgroundColor(Color.BLACK);
            iv.setImageBitmap(bm[0]);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setOnClickListener(v -> d.dismiss());
            d.setContentView(iv);
            d.show();
        });
    }

    // ---------------- القائمة والعمليات ----------------
    void menu(final File f) {
        List<String> L = new ArrayList<>();
        final List<ZipScreen.Act> A = new ArrayList<>();
        L.add("فتح"); A.add(() -> open(f));
        L.add("ضغط إلى ZIP");
        A.add(() -> createArchive(f, ".zip", null, null));
        if (f.isDirectory()) {
            L.add("إنشاء حزمة إضافة .mt1");
            A.add(() -> createArchive(f, ".mt1", "META-INF/mimax/addon.json",
                    "{\n  \"format\": \"MiMaxAddon\",\n  \"formatVersion\": 1\n}\n"));
            L.add("إنشاء حزمة ثيم .mt_1");
            A.add(() -> createArchive(f, ".mt_1", "META-INF/mimax/theme.json",
                    "{\n  \"format\": \"MiMaxTheme\",\n  \"formatVersion\": 1\n}\n"));
        }
        if (LogoBin.isProject(f)) {
            L.add("إعادة تجميع مشروع logo.bin");
            A.add(() -> {
                final File out = unique(new File(f.getParentFile(), f.getName() + "_new.logo.bin"));
                a.confirm("إعادة تجميع صور المشروع إلى ملف logo.bin جديد؟\n" + out.getPath(), () ->
                        a.runBg("إعادة تجميع logo.bin…", null, () -> LogoBin.repack(f, out),
                                () -> a.showError("تم إنشاء الملف والتحقق منه:\n" + out.getPath())));
            });
        }
        if (f.isFile()) { L.add("فتح كنص"); A.add(() -> openText(f)); L.add("فتح كصورة ROM"); A.add(() -> img.open(f)); }
        if (f.isFile() && ApkInstaller.isInstallable(f.getName())) { L.add("تثبيت التطبيق"); A.add(() -> a.installer.install(f)); }
        L.add("نسخ"); A.add(() -> { clip = f; clipCut = false; a.toast("تم النسخ — افتح المجلد الوجهة واضغط «لصق»"); renderDir(cur); });
        L.add("قص"); A.add(() -> { clip = f; clipCut = true; a.toast("تم القص — افتح المجلد الوجهة واضغط «لصق»"); renderDir(cur); });
        L.add("إعادة تسمية"); A.add(() -> a.input("الاسم الجديد", f.getName(), s -> {
            File t = new File(f.getParentFile(), s.trim());
            if (s.trim().isEmpty() || t.exists()) a.toast("اسم غير صالح أو موجود");
            else if (!f.renameTo(t)) a.toast("تعذر إعادة التسمية");
            renderDir(cur);
        }));
        L.add("حذف"); A.add(() -> a.confirm("حذف «" + f.getName() + "» نهائياً؟", () -> {
            final Io.Cancel c = new Io.Cancel();
            a.runBg("جارٍ الحذف…", c, () -> { if (!deleteRec(f, c) && f.exists()) throw new IOException("تعذر حذف بعض الملفات"); },
                    () -> renderDir(cur));
        }));
        L.add("خصائص"); A.add(() -> props(f));
        final String[] labels = L.toArray(new String[0]);
        a.gb().setTitle(f.getName()).setItems(labels, (d, w) -> A.get(w).go()).show();
    }

    void createArchive(final File source, final String extension, final String metadataEntry, final String metadataJson) {
        final File out = unique(new File(source.getParentFile(), source.getName() + extension));
        a.confirm("إنشاء أرشيف جديد دون تعديل المصدر؟\n\nالمصدر: " + source.getPath()
                + "\nالناتج: " + out.getPath(), () -> {
            final Io.Cancel cancel = new Io.Cancel();
            a.runBgP("ضغط الملفات…", cancel,
                    progress -> ArchivePack.create(source, out, metadataEntry, metadataJson, progress, cancel),
                    () -> a.showError("تم إنشاء الأرشيف:\n" + out.getPath() + "\nالحجم: " + Io.human(out.length())));
        });
    }

    void props(final File f) {
        final long[] size = {f.length()};
        a.runBg("جارٍ الحساب…", null, () -> { if (f.isDirectory()) size[0] = sizeOf(f, null); }, () -> a.showError(
                "المسار:\n" + f.getPath() + "\n\nالنوع: " + (f.isDirectory() ? "مجلد" : "ملف")
                        + "\nالحجم: " + Io.human(size[0]) + " (" + size[0] + " بايت)"
                        + "\nآخر تعديل: " + df.format(new Date(f.lastModified()))
                        + "\nقراءة: " + (f.canRead() ? "نعم" : "لا") + " — كتابة: " + (f.canWrite() ? "نعم" : "لا")
                        + "\nالمتاح في القرص: " + Io.human(f.getUsableSpace())));
    }

    File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int i = n.lastIndexOf('.');
        String base = i > 0 ? n.substring(0, i) : n, e = i > 0 ? n.substring(i) : "";
        for (int k = 2; ; k++) {
            File c = new File(f.getParentFile(), base + " (" + k + ")" + e);
            if (!c.exists()) return c;
        }
    }

    void paste(final File dir) {
        if (clip == null) return;
        final File src = clip;
        final boolean cut = clipCut;
        final File dst = unique(new File(dir, src.getName()));
        final Io.Cancel c = new Io.Cancel();
        a.runBg(cut ? "جارٍ النقل…" : "جارٍ النسخ…", c, () -> {
            if (src.isDirectory() && dst.getCanonicalPath().startsWith(src.getCanonicalPath() + File.separator))
                throw new IOException("لا يمكن نسخ مجلد إلى داخل نفسه");
            if (cut && src.renameTo(dst)) return;
            Io.checkSpace(dir, sizeOf(src, c));
            copyRec(src, dst, c);
            if (cut) deleteRec(src, c);
        }, () -> {
            if (cut) clip = null;
            a.toast("تم");
            renderDir(dir);
        });
    }

    long sizeOf(File f, Io.Cancel c) throws IOException {
        if (c != null) c.check();
        if (Files.isSymbolicLink(f.toPath())) return 0;
        if (f.isFile()) return f.length();
        long t = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) t += sizeOf(x, c);
        return t;
    }

    void copyRec(File s, File d, Io.Cancel c) throws IOException {
        try {
            if (Files.isSymbolicLink(s.toPath())) {
                try { Files.createSymbolicLink(d.toPath(), Files.readSymbolicLink(s.toPath())); } catch (Throwable t) { /* غير مدعوم هنا */ }
                return;
            }
            if (s.isDirectory()) {
                d.mkdirs();
                File[] l = s.listFiles();
                if (l != null) for (File x : l) copyRec(x, new File(d, x.getName()), c);
                return;
            }
            try (InputStream in = new FileInputStream(s); OutputStream o = new FileOutputStream(d)) {
                byte[] b = new byte[1 << 17];
                int n;
                while ((n = in.read(b)) > 0) { c.check(); o.write(b, 0, n); }
            }
        } catch (Io.CancelledException e) {
            deleteRec(d, null);
            throw e;
        }
    }

    boolean deleteRec(File f, Io.Cancel c) throws IOException {
        if (c != null) c.check();
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File x : l) deleteRec(x, c);
        }
        return f.delete();
    }

    // ---------------- الأماكن ----------------
    void renderPlaces() {
        a.header("أماكن", true, false);
        LinearLayout p = a.page();
        List<File> vols = new ArrayList<>();
        vols.add(Environment.getExternalStorageDirectory());
        File[] fs = a.getExternalFilesDirs(null);
        if (fs != null) for (File f : fs) {
            if (f == null) continue;
            String path = f.getAbsolutePath();
            int i = path.indexOf("/Android/");
            if (i > 0) { File r = new File(path.substring(0, i)); if (!vols.contains(r)) vols.add(r); }
        }
        for (File v : vols)
            p.addView(placeRow(v.equals(Environment.getExternalStorageDirectory()) ? "التخزين الداخلي" : "بطاقة / تخزين خارجي: " + v.getName(), v,
                    Io.human(v.getUsableSpace()) + " متاح من " + Io.human(v.getTotalSpace())));
        p.addView(a.note("اختصارات"));
        String[][] sc = {{"التنزيلات", Environment.DIRECTORY_DOWNLOADS}, {"الكاميرا والصور", Environment.DIRECTORY_DCIM},
                {"الصور", Environment.DIRECTORY_PICTURES}, {"الفيديو", Environment.DIRECTORY_MOVIES},
                {"الموسيقى", Environment.DIRECTORY_MUSIC}, {"المستندات", Environment.DIRECTORY_DOCUMENTS}};
        for (String[] s : sc) {
            File d = Environment.getExternalStoragePublicDirectory(s[1]);
            if (d != null && d.isDirectory()) p.addView(placeRow(s[0], d, d.getPath()));
        }
        File tg = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Telegram");
        if (tg.isDirectory()) p.addView(placeRow("تنزيلات تيليجرام", tg, tg.getPath()));
        a.setPage(p);
    }

    View placeRow(String title, final File dir, String sub) {
        return a.row("DIR", title, sub, v -> a.openScreen(dir.getName(), () -> renderDir(dir)));
    }
}
