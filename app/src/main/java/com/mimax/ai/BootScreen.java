package com.mimax.ai;

import android.view.View;
import android.widget.LinearLayout;

import com.mimax.ai.engine.Archives;
import com.mimax.ai.engine.BootImg;
import com.mimax.ai.engine.Cpio;
import com.mimax.ai.engine.Io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** boot / init_boot / recovery / vendor_boot: معاينة المكونات والـ ramdisk، تعديل cmdline وملفات ramdisk وحفظها كصورة جديدة. */
class BootScreen {
    final MainActivity a;
    File img, cache, cpio;
    BootImg.Info info;
    List<Cpio.Entry> idx = new ArrayList<>();
    String rdName = "", rdComp = "";
    final Map<String, byte[]> pending = new LinkedHashMap<>();

    BootScreen(MainActivity a) { this.a = a; }

    void open(File f) {
        img = f;
        pending.clear();
        try { info = BootImg.parse(f); }
        catch (IOException e) { a.showError("تعذر قراءة صورة الإقلاع:\n" + e.getMessage()); return; }
        cache = new File(a.getCacheDir(), "boot");
        delRec(cache);
        cache.mkdirs();
        a.openScreen(f.getName(), this::render);
    }

    static void delRec(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) delRec(x);
        f.delete();
    }

    boolean canEditRamdisk() { return !(info.kind.equals("vendor_boot") && info.version == 4); }

    File newOut() {
        File dir = img.getAbsoluteFile().getParentFile();
        String n = img.getName();
        int i = n.lastIndexOf('.');
        String base = i > 0 ? n.substring(0, i) : n, ext = i > 0 ? n.substring(i) : ".img";
        File o = new File(dir, base + "_edited" + ext);
        int k = 2;
        while (o.exists()) o = new File(dir, base + "_edited" + (k++) + ext);
        return o;
    }

    void render() {
        a.header(img.getName(), true, false);
        LinearLayout p = a.page();
        p.addView(a.row(info.kind.equals("boot") ? "BOOT" : "VBOOT", img.getName(),
                (info.kind.equals("boot") ? "صورة إقلاع" : "vendor_boot") + " — ترويسة v" + info.version + " — " + Io.human(info.size), null));
        p.addView(a.row("PAGE", "حجم الصفحة", String.valueOf(info.pageSize), null));
        if (!info.osVersion.isEmpty()) p.addView(a.row("OS", "إصدار النظام", info.osVersion, null));
        if (!info.name.isEmpty()) p.addView(a.row("NAME", "الاسم", info.name, null));
        p.addView(a.row("CMD", "سطر أوامر النواة (اضغط للتعديل)", info.cmdline.isEmpty() ? "(فارغ)" : info.cmdline, v -> editCmdline()));
        p.addView(a.note("المكونات (اضغط على ramdisk لمعاينة ملفاته وتعديلها):"));
        for (final BootImg.Comp c : info.comps) {
            String sub = Io.human(c.len) + " — الإزاحة " + c.off + (c.truncated ? " — " + c.note : "");
            boolean rd = c.name.contains("ramdisk") && !c.truncated;
            p.addView(a.row(c.name.length() > 5 ? c.name.substring(0, 5).toUpperCase() : c.name.toUpperCase(), c.name, sub,
                    rd ? (View.OnClickListener) v -> openRamdisk(c.name) : null));
        }
        p.addView(a.btn("استخراج كل المكونات وفرد ramdisk", a.SF2, a.TX, v -> extractAll()));
        p.addView(a.note("لا يُكتب فوق الصورة الأصلية أبداً: التعديلات تُحفظ في ملف جديد بجانبها (_edited). الصورة المعدّلة تحتاج bootloader مفتوحاً وتعطيل التحقق (vbmeta) كي تُقلع، والتطبيق لا يفلّش أي قسم."));
        a.setPage(p);
    }

    void editCmdline() {
        a.input("سطر أوامر النواة", info.cmdline, s -> {
            final String cmd = s.trim();
            final File out = newOut();
            a.runBg("جارٍ إنشاء نسخة جديدة…", null, () -> BootImg.setCmdline(img, out, cmd),
                    () -> a.showError("تم حفظ نسخة بالسطر الجديد:\n" + out.getPath() + "\n\nالأصل لم يتغير."));
        });
    }

    void extractAll() {
        final File dest = new File(img.getAbsoluteFile().getParentFile(), ZipScreen.baseName(img.getName()) + "_unpacked");
        final Io.Cancel c = new Io.Cancel();
        final BootImg.Result[] r = new BootImg.Result[1];
        a.runBg("جارٍ الاستخراج…", c, () -> r[0] = BootImg.extract(img, info, dest, true, c), () -> {
            StringBuilder sb = new StringBuilder(r[0].ok ? "تم الاستخراج إلى:\n" + dest.getPath() : "اكتمل مع أخطاء. المجلد:\n" + dest.getPath());
            for (String l : r[0].lines) sb.append("\n").append(l);
            a.showError(sb.toString());
        });
    }

    void openRamdisk(final String name) {
        if (!name.equals(rdName)) pending.clear();
        final Object[][] box = new Object[1][];
        final Io.Cancel c = new Io.Cancel();
        a.runBg("جارٍ فك ضغط ramdisk…", c, () -> {
            box[0] = BootImg.ramdiskCpio(img, info, name, cache, c);
            cpio = (File) box[0][0];
            rdComp = (String) box[0][1];
            idx = Cpio.index(cpio);
            rdName = name;
        }, () -> a.openScreen("ramdisk", this::renderRamdisk));
    }

    void renderRamdisk() {
        a.header(rdName, true, false);
        LinearLayout p = a.page();
        boolean ed = canEditRamdisk();
        p.addView(a.note(idx.size() + " مدخل — ضغط ramdisk: " + rdComp + (ed ? "" : "\nتعديل ramdisk في vendor_boot v4 غير مدعوم (معاينة فقط).")
                + (ed && !rdComp.equals("gzip") && !rdComp.equals("cpio") ? "\nعند الحفظ سيُضغط ramdisk بـ gzip (تقبله النواة)." : "")));
        if (!pending.isEmpty() && ed) {
            p.addView(a.btn("حفظ صورة جديدة بالتعديلات (" + pending.size() + ")", a.AC, a.ON, v -> applyPending()));
        }
        for (final Cpio.Entry e : idx) {
            String tag = e.isDir() ? "DIR" : e.isLink() ? "LINK" : "FILE";
            String sub = e.isDir() ? "مجلد" : e.isLink() ? "رابط" : Io.human(e.size);
            if (pending.containsKey(e.name)) sub += " • معدَّل";
            p.addView(a.row(tag, e.name, sub, e.isReg() ? (View.OnClickListener) v -> openEntry(e) : null));
        }
        a.setPage(p);
    }

    void openEntry(final Cpio.Entry e) {
        final byte[][] box = new byte[1][];
        a.runBg("جارٍ القراءة…", null, () -> {
            byte[] pend = pending.get(e.name);
            box[0] = pend != null ? pend : Cpio.read(cpio, e, EditorScreen.MAX);
        }, () -> EditorScreen.open(a, e.name, box[0], canEditRamdisk(), data -> pending.put(e.name, data)));
    }

    void applyPending() {
        final File out = newOut();
        final Io.Cancel c = new Io.Cancel();
        a.runBg("جارٍ بناء الصورة الجديدة…", c, () -> {
            File c2 = new File(cache, "new.cpio"), gz = new File(cache, "new.cpio.gz");
            Cpio.rewrite(cpio, idx, pending, null, c2);
            Archives.gzip(c2, gz);
            BootImg.replaceRamdisk(img, out, gz);
        }, () -> {
            pending.clear();
            a.showError("تم حفظ الصورة الجديدة:\n" + out.getPath() + "\n\nالأصل لم يتغير. تحتاج bootloader مفتوحاً وتعطيل vbmeta لتجربتها، والتطبيق لا يفلّش شيئاً.");
            a.screens.pop();
            a.screens.peek().build.run();
        });
    }
}
