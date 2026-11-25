package com.mimax.ai;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mimax.ai.engine.Detect;
import com.mimax.ai.engine.Ext4;
import com.mimax.ai.engine.Ext4Edit;
import com.mimax.ai.engine.Io;
import com.mimax.ai.engine.Lp;
import com.mimax.ai.engine.Sparse;
import com.mimax.ai.engine.Vsrc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** صور الرومات: اكتشاف النوع، Sparse⇄Raw، super (فك/إعادة تجميع)، وext4 (تصفح/استخراج/تعديل في المكان). */
class ImgScreen {
    final MainActivity a;
    File img;
    Detect.Info info;
    Lp.Meta meta;
    final Set<String> sel = new HashSet<>();

    ImgScreen(MainActivity a) { this.a = a; }

    interface SrcJob { void run(Vsrc s) throws Exception; }

    static String base(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    File sibling(String suffix) { return new File(img.getAbsoluteFile().getParentFile(), base(img) + suffix); }

    File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int i = n.lastIndexOf('.');
        String b = i > 0 ? n.substring(0, i) : n, e = i > 0 ? n.substring(i) : "";
        for (int k = 2; ; k++) { File c = new File(f.getParentFile(), b + "_" + k + e); if (!c.exists()) return c; }
    }

    /** يفتح المصدر (يفك Sparse افتراضياً) ثم ينفّذ العمل. */
    void withSrc(File f, boolean sparse, SrcJob j) throws Exception {
        try (Vsrc.FileV fv = new Vsrc.FileV(f)) { j.run(sparse ? Sparse.open(fv) : fv); }
    }

    // ---------------- الدخول ----------------
    void open(final File f) {
        img = f; sel.clear(); meta = null;
        final Detect.Info[] box = new Detect.Info[1];
        a.runBg("جارٍ تحليل الصورة…", null, () -> box[0] = Detect.detect(f), () -> {
            info = box[0];
            a.openScreen(f.getName(), this::render);
        });
    }

    static String tag(String k) {
        switch (k) {
            case "super": return "SUPER";
            case "ext4": return "EXT4";
            case "erofs": return "EROFS";
            case "f2fs": return "F2FS";
            case "boot": return "BOOT";
            case "sparse": return "SPRS";
            default: return "IMG";
        }
    }

    boolean ok() { return info.status.equals(Detect.VALID); }

    void render() {
        a.header(img.getName(), true, false);
        LinearLayout p = a.page();
        p.addView(a.row(tag(info.kind), info.label, (info.sparse ? "Sparse" : "Raw") + " — " + Io.human(info.size)
                + (info.sparse ? " ← " + Io.human(info.rawSize) + " بعد الفك" : "") + " — الحالة: " + info.statusAr(), null));
        if (!info.detail.isEmpty()) p.addView(a.box(info.detail));
        StringBuilder ev = new StringBuilder("الأدلة التقنية:");
        for (String e : info.evidence) ev.append("\n• ").append(e);
        p.addView(a.note(ev.toString()));

        boolean broken = info.kind.equals("sparse") && !ok();
        if (info.sparse && !broken) p.addView(a.btn("تحويل Sparse → Raw", a.SF2, a.TX, v -> toRaw()));
        if (!info.sparse) p.addView(a.btn("تحويل Raw → Sparse", a.SF2, a.TX, v -> toSparse()));
        if (info.kind.equals("super") && ok()) superSection(p);
        if (info.kind.equals("ext4") && ok()) ext4Section(p);
        a.setPage(p);
    }

    // ---------------- Sparse ⇄ Raw ----------------
    void toRaw() {
        final File out = unique(sibling(".raw.img"));
        final Io.Cancel c = new Io.Cancel();
        a.runBgP("تحويل Sparse → Raw", c, p -> Sparse.toRaw(img, out, p, c),
                () -> a.showError("تم التحويل إلى:\n" + out.getPath() + "\n(" + Io.human(out.length()) + ")"));
    }

    void toSparse() {
        final File out = unique(sibling(".sparse.img"));
        final Io.Cancel c = new Io.Cancel();
        a.runBgP("تحويل Raw → Sparse", c, p -> Sparse.fromRaw(img, out, p, c),
                () -> a.showError("تم التحويل إلى:\n" + out.getPath() + "\n(" + Io.human(out.length()) + " من " + Io.human(img.length()) + ")"));
    }

    // ---------------- super ----------------
    File partsDir() { return sibling("_parts"); }

    void superSection(LinearLayout p) {
        if (meta == null) {
            try { withSrc(img, info.sparse, s -> meta = Lp.read(s)); }
            catch (Exception e) { p.addView(a.box("تعذر قراءة الأقسام: " + e.getMessage())); return; }
        }
        long tot = meta.totalSize();
        p.addView(a.note(meta.parts.size() + " قسم — المجموع " + Io.human(tot) + " من " + Io.human(meta.deviceSize)
                + (meta.usedBackup ? "\nتنبيه: قُرئت Metadata من النسخة الاحتياطية." : "")
                + "\nاضغط على القسم لتحديده، واضغط مطولاً لاستخراجه وفتحه."));
        for (final Lp.Part pt : meta.parts) {
            final View r = a.row(sel.contains(pt.name) ? "☑" : "☐", pt.name, pt.size == 0 ? "فارغ (حجمه صفر)" : Io.human(pt.size)
                    + (pt.segs.size() > 1 ? " — " + pt.segs.size() + " امتدادات" : ""), v -> {
                if (sel.contains(pt.name)) sel.remove(pt.name); else sel.add(pt.name);
                ((TextView) ((LinearLayout) v).getChildAt(0)).setText(sel.contains(pt.name) ? "☑" : "☐");
            });
            r.setOnLongClickListener(v -> { partMenu(pt); return true; });
            p.addView(r);
        }
        p.addView(a.btn("تحديد كل الأقسام غير الفارغة", a.SF2, a.TX, v -> {
            for (Lp.Part pt : meta.parts) if (pt.size > 0) sel.add(pt.name);
            render();
        }));
        p.addView(a.btn("استخراج الأقسام المحددة", a.AC, a.ON, v -> extractSelected()));
        p.addView(a.btn("فك super بالطريقة التقليدية (Sparse → Raw → أقسام)", a.SF2, a.TX, v -> unpackTraditional()));
        p.addView(a.btn("إعادة تجميع super بالأقسام المعدَّلة", a.SF2, a.TX, v -> repack(false)));
        p.addView(a.btn("إعادة تجميع + حفظ نسخة Sparse", a.SF2, a.TX, v -> repack(true)));
        p.addView(a.note("طريقة التعديل: استخرج الأقسام ← افتح القسم (مثلاً system.img) وعدّل ملفاته فتُحفظ نسخة باسم <القسم>_edited.img بجانبه ← أعد التجميع، فتُستخدم النسخ المعدَّلة تلقائياً.\nيُكتب super جديد بجانب الأصل ولا يُلمس الأصل، والتطبيق لا يفلّش أي قسم."));
    }

    void partMenu(final Lp.Part pt) {
        if (pt.size == 0) { a.toast("القسم فارغ"); return; }
        a.gb().setTitle(pt.name).setItems(new String[]{"استخراج هذا القسم", "استخراج وفتحه"}, (d, w) -> {
            final File out = new File(partsDir(), pt.name + ".img");
            final boolean openAfter = w == 1;
            a.runBgP("استخراج " + pt.name, null, p -> withSrc(img, info.sparse, s -> Lp.extract(s, pt, out, p, null)), () -> {
                if (openAfter) files().img.open(out); else a.showError("تم الاستخراج:\n" + out.getPath());
            });
        }).show();
    }

    FileScreen files() { return a.files; }

    /**
     * Traditional simg2img -> lpunpack workflow, implemented with the app's
     * in-process Java readers. The source is never modified.
     */
    void unpackTraditional() {
        final File dir = unique(partsDir());
        final File raw = info.sparse ? unique(sibling(".raw.img")) : img;
        final long rawBytes = info.sparse ? info.rawSize : 0;
        final long partitionBytes = meta.totalSize();
        final long required = rawBytes + partitionBytes;
        String steps = info.sparse
                ? "1) تحويل Sparse إلى Raw: " + Io.human(rawBytes)
                    + "\n2) استخراج أقسام LP: " + Io.human(partitionBytes)
                : "1) قراءة Raw مباشرة (لا حاجة للتحويل)"
                    + "\n2) استخراج أقسام LP: " + Io.human(partitionBytes);
        long available = img.getAbsoluteFile().getParentFile().getUsableSpace();
        a.confirm("فك super بالطريقة التقليدية (simg2img ثم lpunpack)\n\n"
                + steps + "\n\nمجلد الناتج: " + dir.getPath()
                + "\nالمساحة المطلوبة تقريباً: " + Io.human(required)
                + "\nالمساحة المتاحة: " + Io.human(available)
                + "\n\nسيُحفظ Raw بجانب الصورة عند الحاجة، وتُستخرج الأقسام في مجلد جديد. لن يتغير الملف الأصلي.",
                () -> {
            final Io.Cancel cancel = new Io.Cancel();
            a.runBgP("Sparse → Raw → LP partitions", cancel, progress -> {
                if (required > 0) Io.checkSpace(img.getAbsoluteFile().getParentFile(), required);
                if (info.sparse) Sparse.toRaw(img, raw, progress, cancel);
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الناتج: " + dir);
                try (Vsrc.FileV source = new Vsrc.FileV(raw)) {
                    Lp.Meta parsed = Lp.read(source);
                    if (parsed.parts.isEmpty()) throw new java.io.IOException("لم يتم العثور على أقسام LP");
                    long total = Math.max(1, parsed.totalSize());
                    long completed = 0;
                    for (Lp.Part part : parsed.parts) {
                        if (cancel != null) cancel.check();
                        if (part.size <= 0) continue;
                        final long base = completed;
                        File out = new File(dir, part.name + ".img");
                        Lp.extract(source, part, out,
                                (done, ignored) -> progress.on(base + done, total),
                                cancel);
                        completed += part.size;
                    }
                }
            }, () -> a.showError("اكتمل استخراج أقسام super.\n"
                    + (info.sparse ? "صورة Raw: " + raw.getPath() + "\n" : "")
                    + "مجلد الأقسام: " + dir.getPath()
                    + "\n\nيمكنك فتح ملفات الأقسام من مدير الملفات."));
        });
    }

    void extractSelected() {
        if (sel.isEmpty()) { a.toast("حدّد قسماً واحداً على الأقل"); return; }
        final File dir = partsDir();
        final List<Lp.Part> todo = new ArrayList<>();
        long need = 0;
        for (Lp.Part pt : meta.parts) if (sel.contains(pt.name) && pt.size > 0) { todo.add(pt); need += pt.size; }
        final long fneed = need;
        a.confirm("سيُستخرج " + todo.size() + " قسم (" + Io.human(need) + ") إلى:\n" + dir.getPath() + "\nالمتاح: " + Io.human(img.getAbsoluteFile().getParentFile().getUsableSpace()), () -> {
            final Io.Cancel c = new Io.Cancel();
            a.runBgP("استخراج الأقسام", c, p -> {
                Io.checkSpace(dir.exists() ? dir : dir.getParentFile(), fneed);
                withSrc(img, info.sparse, s -> {
                    long done = 0;
                    for (Lp.Part pt : todo) {
                        final long base = done;
                        Lp.extract(s, pt, new File(dir, pt.name + ".img"), (d0, t0) -> p.on(base + d0, fneed), c);
                        done += pt.size;
                    }
                });
            }, () -> a.showError("تم استخراج " + todo.size() + " قسم إلى:\n" + dir.getPath()));
        });
    }

    void repack(final boolean alsoSparse) {
        final File dir = partsDir();
        final Map<String, File> repl = new LinkedHashMap<>();
        for (Lp.Part pt : meta.parts) { File e = new File(dir, pt.name + "_edited.img"); if (e.isFile()) repl.put(pt.name, e); }
        if (repl.isEmpty()) {
            a.showError("لا توجد أقسام معدَّلة.\nضع نسخة معدّلة باسم <القسم>_edited.img داخل:\n" + dir.getPath() + "\nثم أعد المحاولة.");
            return;
        }
        final File out = unique(sibling("_new.img"));
        StringBuilder sb = new StringBuilder("سيُبنى super جديد بالأقسام المستبدلة:");
        long add = 0;
        for (Map.Entry<String, File> e : repl.entrySet()) { sb.append("\n• ").append(e.getKey()).append(" ← ").append(Io.human(e.getValue().length())); add += e.getValue().length(); }
        sb.append("\n\nالناتج: ").append(out.getPath()).append("\nالمطلوب نحو ").append(Io.human(meta.deviceSize)).append(alsoSparse ? " + نسخة Sparse" : "");
        a.confirm(sb.toString(), () -> {
            final Io.Cancel c = new Io.Cancel();
            a.runBgP("إعادة تجميع super", c, p -> {
                withSrc(img, info.sparse, s -> Lp.repack(s, Lp.read(s), repl, out, p, c));
                if (alsoSparse) {
                    File sp = unique(sibling("_new.sparse.img"));
                    Sparse.fromRaw(out, sp, null, c);
                }
            }, () -> a.showError("تم بناء super الجديد:\n" + out.getPath() + (alsoSparse ? "\nوتمّ حفظ نسخة Sparse بجانبه." : "")
                    + "\n\nتحقق التطبيق من قراءة الناتج ومطابقة الأقسام المستبدلة بايتاً ببايت.\nالفلاش (fastboot) مسؤوليتك، والتطبيق لا يكتب على أقسام الجهاز."));
        });
    }

    // ---------------- ext4 ----------------
    File editedFile() { return sibling("_edited.img"); }

    File work() { File e = editedFile(); return e.exists() ? e : img; }

    void withWork(SrcJob j) throws Exception {
        File e = editedFile();
        if (e.exists()) withSrc(e, false, j); else withSrc(img, info.sparse, j);
    }

    void ext4Section(LinearLayout p) {
        p.addView(a.btn("تصفح الملفات وتعديلها", a.AC, a.ON, v -> a.openScreen("/", () -> renderExt("/"))));
        p.addView(a.btn("استخراج كل الملفات", a.SF2, a.TX, v -> extractAllExt()));
        p.addView(a.note("التعديل يتم في نسخة جديدة (_edited) ولا يُلمس الأصل. المدعوم: استبدال محتوى ملف بحجم أقل أو يساوي مساحته المخصصة، وتغيير الأذونات والمالك. إضافة ملفات أو حذفها أو تكبيرها غير مدعوم بعد."
                + (editedFile().exists() ? "\nتوجد نسخة معدَّلة: " + editedFile().getName() : "")));
    }

    void extractAllExt() {
        final File dest = sibling("_files");
        final Io.Cancel c = new Io.Cancel();
        final Ext4.Report[] r = new Ext4.Report[1];
        a.runBgP("استخراج ملفات ext4", c, p -> withWork(s -> r[0] = Ext4.open(s).extractAll(dest, p, c)), () -> {
            StringBuilder sb = new StringBuilder("استُخرج " + r[0].files + " ملف و" + r[0].dirs + " مجلد (" + Io.human(r[0].bytes) + ") إلى:\n" + dest.getPath());
            if (r[0].links + r[0].symlinkManifest > 0) sb.append("\nالروابط الرمزية: ").append(r[0].links + r[0].symlinkManifest).append(r[0].symlinkManifest > 0 ? " (سُجّلت في _symlinks.txt لأن المسار لا يدعمها)" : "");
            if (!r[0].errors.isEmpty()) { sb.append("\n\nأخطاء (").append(r[0].errors.size()).append("):"); for (int i = 0; i < Math.min(8, r[0].errors.size()); i++) sb.append("\n• ").append(r[0].errors.get(i)); }
            a.showError(sb.toString());
        });
    }

    void renderExt(final String path) {
        a.header(path, true, false);
        final List<Ext4.Entry> es = new ArrayList<>();
        final Map<Long, Ext4.Node> nodes = new LinkedHashMap<>();
        final Map<Long, String> links = new LinkedHashMap<>();
        a.runBg("جارٍ قراءة المجلد…", null, () -> withWork(s -> {
            Ext4 e = Ext4.open(s);
            Ext4.Node d = e.lookup(path);
            es.addAll(e.list(d));
            for (Ext4.Entry en : es) {
                try {
                    Ext4.Node n = e.inode(en.ino);
                    nodes.put(en.ino, n);
                    if (n.isLink()) links.put(en.ino, e.readLink(n));
                } catch (Exception ex) { /* مدخل تالف: يُعرض بلا تفاصيل */ }
            }
        }), () -> {
            Collections.sort(es, (x, y) -> {
                Ext4.Node nx = nodes.get(x.ino), ny = nodes.get(y.ino);
                boolean dx = nx != null && nx.isDir(), dy = ny != null && ny.isDir();
                if (dx != dy) return dx ? -1 : 1;
                return x.name.compareToIgnoreCase(y.name);
            });
            LinearLayout p = a.page();
            if (editedFile().exists()) p.addView(a.note("تعرض هذه الشاشة النسخة المعدَّلة: " + editedFile().getName()));
            if (es.isEmpty()) p.addView(a.note("المجلد فارغ"));
            for (final Ext4.Entry en : es) {
                final Ext4.Node n = nodes.get(en.ino);
                final String full = (path.equals("/") ? "" : path) + "/" + en.name;
                if (n == null) { p.addView(a.row("ERR", en.name, "تعذر قراءة المدخل", null)); continue; }
                if (n.isDir()) p.addView(a.row("DIR", en.name, "مجلد — " + Integer.toOctalString(n.mode & 0xFFF), v -> a.openScreen(full, () -> renderExt(full))));
                else if (n.isLink()) p.addView(a.row("LINK", en.name, "→ " + links.get(en.ino), null));
                else if (n.isReg()) p.addView(a.row(ZipScreen.tagOf(en.name), en.name, Io.human(n.size) + " — " + Integer.toOctalString(n.mode & 0xFFF) + " — " + n.uid + ":" + n.gid, v -> fileMenu(full, n)));
                else p.addView(a.row("DEV", en.name, "ملف خاص", null));
            }
            a.setPage(p);
        });
    }

    void fileMenu(final String full, final Ext4.Node n) {
        a.gb().setTitle(full).setItems(new String[]{"معاينة / تعديل نصي", "استخراج هذا الملف", "تغيير الأذونات", "تغيير المالك (uid:gid)", "خصائص"}, (d, w) -> {
            if (w == 0) editText(full, n);
            else if (w == 1) extractFile(full);
            else if (w == 2) a.input("الأذونات بنظام ثماني (مثل 755)", Integer.toOctalString(n.mode & 0xFFF), s -> ensureEdited(() ->
                    a.runBg("جارٍ التعديل…", null, () -> { Ext4Edit.setMode(editedFile(), full, Integer.parseInt(s.trim(), 8)); }, () -> a.toast("تم تغيير الأذونات"))));
            else if (w == 3) a.input("المالك uid:gid", n.uid + ":" + n.gid, s -> ensureEdited(() ->
                    a.runBg("جارٍ التعديل…", null, () -> {
                        String[] t = s.trim().split(":");
                        Ext4Edit.setOwner(editedFile(), full, Integer.parseInt(t[0]), Integer.parseInt(t[1]));
                    }, () -> a.toast("تم تغيير المالك"))));
            else props(full, n);
        }).show();
    }

    void props(final String full, final Ext4.Node n) {
        final long[] cap = {-1};
        a.runBg("جارٍ القراءة…", null, () -> { try { cap[0] = Ext4Edit.capacityOf(work().isFile() && !info.sparse || editedFile().exists() ? work() : tmpRaw(), full); } catch (Exception e) { cap[0] = -1; } },
                () -> a.showError(full + "\nالحجم: " + n.size + " بايت\nالأذونات: " + Integer.toOctalString(n.mode & 0xFFF) + "\nالمالك: " + n.uid + ":" + n.gid
                        + (cap[0] >= 0 ? "\nأقصى حجم للتعديل: " + cap[0] + " بايت" : "\nالمساحة المخصصة: غير متاحة (صورة Sparse أو ملف متناثر)")));
    }

    File tmpRaw() { return img; }

    void extractFile(final String full) {
        final File dest = sibling("_files");
        a.runBgP("استخراج الملف", null, p -> withWork(s -> { Ext4 e = Ext4.open(s); e.extractPath(full, dest, null); }),
                () -> a.showError("تم الاستخراج إلى:\n" + dest.getPath()));
    }

    void editText(final String full, final Ext4.Node n) {
        if (n.size > EditorScreen.MAX) { a.showError("الملف كبير (" + Io.human(n.size) + ") للتحرير النصي. يمكنك استخراجه."); return; }
        final byte[][] box = new byte[1][];
        a.runBg("جارٍ القراءة…", null, () -> withWork(s -> { Ext4 e = Ext4.open(s); Ext4.Node nn = e.lookup(full); box[0] = e.content(nn).read(0, (int) nn.size); }),
                () -> EditorScreen.open(a, new File(full).getName(), box[0], true, data -> {
                    ensureEditedSync();
                    Ext4Edit.replaceContent(editedFile(), full, data);
                }));
    }

    /** ينشئ النسخة المعدَّلة إن لم توجد (مع تأكيد وتقدّم)، ثم يتابع. */
    void ensureEdited(final Runnable next) {
        if (editedFile().exists()) { next.run(); return; }
        if (info.sparse) {
            a.showError("هذه صورة Sparse. حوّلها إلى Raw أولاً (زر «تحويل Sparse → Raw») ثم افتح الـ Raw المحوَّل لتعديله.");
            return;
        }
        final long size = img.length();
        a.confirm("التعديل يتم على نسخة جديدة اسمها " + editedFile().getName() + " (" + Io.human(size) + ").\nالمتاح: " + Io.human(img.getAbsoluteFile().getParentFile().getUsableSpace()) + "\nإنشاء النسخة؟", () -> {
            final Io.Cancel c = new Io.Cancel();
            a.runBgP("نسخ الصورة", c, p -> copyTo(img, editedFile(), p, c), next);
        });
    }

    void ensureEditedSync() throws Exception {
        if (editedFile().exists()) return;
        if (info.sparse) throw new Io.Unsupported("صورة Sparse: حوّلها إلى Raw أولاً ثم عدّل");
        copyTo(img, editedFile(), null, null);
    }

    static void copyTo(File s, File d, Io.Progress p, Io.Cancel c) throws java.io.IOException {
        Io.checkSpace(d.getAbsoluteFile().getParentFile(), s.length());
        boolean ok = false;
        try (java.io.InputStream in = new java.io.FileInputStream(s); java.io.OutputStream o = new java.io.FileOutputStream(d)) {
            byte[] b = new byte[1 << 20];
            long done = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                if (c != null) c.check();
                o.write(b, 0, n);
                done += n;
                if (p != null) p.on(done, s.length());
            }
            ok = true;
        } finally { if (!ok) d.delete(); }
    }
}
