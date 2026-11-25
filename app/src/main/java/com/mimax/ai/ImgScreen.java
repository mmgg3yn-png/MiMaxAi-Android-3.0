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
        p.addView(a.btn("عرض تقرير AOSP lpdump", a.SF2, a.TX, v -> showLpDump()));
        p.addView(a.btn("إعادة تجميع super بالأقسام المعدَّلة", a.SF2, a.TX, v -> repack(false)));
        p.addView(a.btn("إعادة تجميع + حفظ نسخة Sparse", a.SF2, a.TX, v -> repack(true)));
        p.addView(a.note("طريقة التعديل: استخرج الأقسام ← افتح القسم (مثلاً system.img) وعدّل ملفاته فتُحفظ نسخة باسم <القسم>_edited.img بجانبه ← أعد التجميع، فتُستخدم النسخ المعدَّلة تلقائياً.\nيُكتب super جديد بجانب الأصل ولا يُلمس الأصل، والتطبيق لا يفلّش أي قسم."));
    }

    void partMenu(final Lp.Part pt) {
        if (pt.size == 0) { a.toast("القسم فارغ"); return; }
        a.gb().setTitle(pt.name).setItems(new String[]{"استخراج هذا القسم بأداة AOSP lpunpack", "استخراج وفتح"}, (d, w) -> {
            final File dir = partsDir();
            final File out = new File(dir, pt.name + ".img");
            final File parent = img.getAbsoluteFile().getParentFile();
            final File raw = info.sparse ? unique(sibling("_lpunpack.raw.img")) : img;
            final File stage = unique(new File(parent, base(img) + "_part_stage"));
            final boolean openAfter = w == 1;
            a.runBgP("AOSP lpunpack — " + pt.name, null, progress -> {
                LpTools.requireUnpackCompatible(meta);
                if (info.sparse) {
                    Io.checkSpace(parent, info.rawSize + pt.size);
                    Sparse.toRaw(img, raw, progress, null);
                }
                if (!stage.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد مؤقت للاستخراج");
                LpTools.run(a, "lpunpack",
                        java.util.Arrays.asList("-p", Lp.unpackPartitionName(pt, 0), raw.getAbsolutePath(), stage.getAbsolutePath()),
                        null, progress);
                File staged = new File(stage, Lp.unpackFileName(pt, 0));
                if (!staged.isFile() || staged.length() != pt.size) {
                    throw new java.io.IOException("فشل التحقق من القسم المستخرج " + pt.name);
                }
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الأقسام");
                moveFile(staged, out);
                deleteTree(stage);
            }, () -> {
                if (openAfter) files().img.open(out);
                else a.showError("تم استخراج القسم باستخدام AOSP lpunpack:\n" + out.getPath());
            });
        }).show();
    }

    FileScreen files() { return a.files; }

    /** Traditional simg2img -> AOSP lpunpack workflow. The source is never modified. */
    void unpackTraditional() {
        final File dir = partsDir();
        final File parent = img.getAbsoluteFile().getParentFile();
        final File raw = info.sparse ? unique(sibling("_lpunpack.raw.img")) : img;
        final File stage = unique(new File(parent, base(img) + "_parts_stage"));
        final long rawBytes = info.sparse ? info.rawSize : 0;
        final long partitionsBytes = meta.totalSize();
        final long required = rawBytes + partitionsBytes;
        a.confirm("سيتم استخدام أداة AOSP lpunpack الحقيقية بعد تحويل Sparse إلى Raw عند الحاجة."
                + "\n\nالمساحة المطلوبة تقديرياً: " + Io.human(required)
                + "\nالمتاح: " + Io.human(parent.getUsableSpace())
                + "\n\nالناتج: " + dir.getPath()
                + (info.sparse ? "\nوسيُحفظ ملف Raw مؤقت/مساعد بجانب الصورة: " + raw.getName() : "")
                + "\n\nلن يتغير ملف super الأصلي.", () -> {
            final Io.Cancel cancel = new Io.Cancel();
            final String[] report = new String[1];
            a.runBgP("AOSP lpunpack — استخراج الأقسام", cancel, progress -> {
                LpTools.requireUnpackCompatible(meta);
                Io.checkSpace(parent, required);
                if (info.sparse) Sparse.toRaw(img, raw, progress, cancel);
                if (!stage.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد مؤقت للاستخراج");
                report[0] = LpTools.run(a, "lpunpack",
                        java.util.Arrays.asList(raw.getAbsolutePath(), stage.getAbsolutePath()),
                        cancel, progress);
                for (Lp.Part part : meta.parts) {
                    if (cancel != null) cancel.check();
                    if (part.size == 0) continue;
                    File extracted = new File(stage, Lp.unpackFileName(part, 0));
                    if (!extracted.isFile() || extracted.length() != part.size) {
                        throw new java.io.IOException("فشل التحقق من القسم " + part.name
                                + " — المتوقع " + Io.human(part.size) + "، الناتج "
                                + (extracted.exists() ? Io.human(extracted.length()) : "غير موجود"));
                    }
                }
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الأقسام: " + dir);
                for (Lp.Part part : meta.parts) {
                    if (part.size > 0) moveFile(new File(stage, part.name + ".img"),
                            new File(dir, part.name + ".img"));
                }
                deleteTree(stage);
                progress.on(1, 1);
            }, () -> a.showError("اكتمل الفك باستخدام AOSP lpunpack.\n"
                    + (info.sparse ? "Raw: " + raw.getPath() + "\n" : "")
                    + "الأقسام: " + dir.getPath()
                    + (report[0] == null || report[0].isEmpty() ? "" : "\n\n" + report[0])));
        });
    }

    void showLpDump() {
        final File work = new File(a.getCacheDir(), "lp-dump-" + System.currentTimeMillis());
        final File raw = info.sparse ? new File(work, "super.raw.img") : img;
        final Io.Cancel cancel = new Io.Cancel();
        final String[] dump = new String[1];
        a.runBgP("AOSP lpdump — قراءة Metadata", cancel, progress -> {
            if (info.sparse) {
                if (!work.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد مؤقت");
                Io.checkSpace(work, info.rawSize);
                Sparse.toRaw(img, raw, progress, cancel);
            }
            dump[0] = LpTools.run(a, "lpdump",
                    java.util.Collections.singletonList(raw.getAbsolutePath()), cancel, progress);
            if (info.sparse) deleteTree(work);
            progress.on(1, 1);
        }, () -> a.showError(dump[0] == null || dump[0].isEmpty()
                ? "اكتمل lpdump دون إخراج نصي." : dump[0]));
    }

    void moveFile(File source, File target) throws java.io.IOException {
        if (target.exists() && !target.delete()) throw new java.io.IOException("تعذر استبدال الملف القديم: " + target);
        if (source.renameTo(target)) return;
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        if (target.length() != source.length()) {
            target.delete();
            throw new java.io.IOException("فشل نقل الملف بعد الاستخراج: " + source.getName());
        }
        source.delete();
    }

    void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    void extractSelected() {
        if (sel.isEmpty()) { a.toast("حدّد قسماً واحداً على الأقل"); return; }
        final File dir = partsDir();
        final File parent = img.getAbsoluteFile().getParentFile();
        final File raw = info.sparse ? unique(sibling("_lpunpack.raw.img")) : img;
        final List<Lp.Part> todo = new ArrayList<>();
        long need = info.sparse ? info.rawSize : 0;
        for (Lp.Part pt : meta.parts) {
            if (sel.contains(pt.name) && pt.size > 0) {
                todo.add(pt);
                need += pt.size;
            }
        }
        final long required = need;
        a.confirm("سيستخدم التطبيق أداة AOSP lpunpack لاستخراج " + todo.size()
                + " قسم إلى:\n" + dir.getPath()
                + "\nالمطلوب تقديرياً: " + Io.human(required)
                + "\nالمتاح: " + Io.human(parent.getUsableSpace())
                + "\nلن يتغير super الأصلي.", () -> {
            final Io.Cancel cancel = new Io.Cancel();
            a.runBgP("AOSP lpunpack — استخراج الأقسام المحددة", cancel, progress -> {
                LpTools.requireUnpackCompatible(meta);
                Io.checkSpace(parent, required);
                if (info.sparse) Sparse.toRaw(img, raw, progress, cancel);
                File stage = unique(new File(parent, base(img) + "_selected_stage"));
                if (!stage.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد مؤقت للاستخراج");
                List<String> args = new ArrayList<>();
                for (Lp.Part pt : todo) { args.add("-p"); args.add(Lp.unpackPartitionName(pt, 0)); }
                args.add(raw.getAbsolutePath());
                args.add(stage.getAbsolutePath());
                LpTools.run(a, "lpunpack", args, cancel, progress);
                for (Lp.Part pt : todo) {
                    File extracted = new File(stage, Lp.unpackFileName(pt, 0));
                    if (!extracted.isFile() || extracted.length() != pt.size) {
                        throw new java.io.IOException("فشل التحقق من القسم " + pt.name);
                    }
                }
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الأقسام");
                for (Lp.Part pt : todo) moveFile(new File(stage, pt.name + ".img"),
                        new File(dir, pt.name + ".img"));
                deleteTree(stage);
                progress.on(1, 1);
            }, () -> a.showError("اكتمل استخراج " + todo.size()
                    + " قسم باستخدام AOSP lpunpack إلى:\n" + dir.getPath()));
        });
    }

    void repack(final boolean alsoSparse) {
        final File editedDir = partsDir();
        final File work = new File(a.getCacheDir(), "lp-repack-" + System.currentTimeMillis());
        final File sourceRaw = info.sparse ? new File(work, "source.raw.img") : img;
        final File output = unique(sibling(alsoSparse ? "_new.sparse.img" : "_new.img"));
        final File verifyRaw = alsoSparse ? new File(work, "output.raw.img") : output;
        final File verifyDir = new File(work, "verify");
        final long workNeeded = (info.sparse ? info.rawSize : 0) + meta.totalSize()
                + (alsoSparse ? meta.deviceSize : 0);
        a.confirm("سيُعاد بناء super باستخدام أداة AOSP lpmake الحقيقية."
                + "\n\nسيتم استخراج الأقسام الأصلية إلى مجلد مؤقت، ثم استخدام نسخ _edited عند وجودها."
                + "\nسيُفحص الناتج بواسطة lpdump وlpunpack وتُقارن بيانات الأقسام."
                + "\n\nالمساحة التقديرية: " + Io.human(workNeeded)
                + "\nالمتاح: " + img.getAbsoluteFile().getParentFile().getUsableSpace()
                + "\nالناتج الجديد: " + output.getPath()
                + "\n\nلن يُكتب فوق super الأصلي.", () -> {
            final Io.Cancel cancel = new Io.Cancel();
            final String[] dump = new String[1];
            a.runBgP("AOSP lpmake — إعادة بناء super والتحقق", cancel, progress -> {
                if (!work.exists() && !work.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد العمل المؤقت");
                Io.checkSpace(work, workNeeded);
                Io.checkSpace(output.getAbsoluteFile().getParentFile(),
                        alsoSparse ? meta.totalSize() : meta.deviceSize);
                LpTools.requireUnpackCompatible(meta);
                for (int slot = 1; slot < meta.slotCount; slot++) {
                    final int slotNumber = slot;
                    final Lp.Meta[] otherSlot = new Lp.Meta[1];
                    withSrc(img, info.sparse, source -> otherSlot[0] = Lp.read(source, slotNumber));
                    if (!Lp.sameLayout(meta, otherSlot[0])) {
                        throw new com.mimax.ai.engine.Io.Unsupported("Metadata slot " + slot
                                + " يختلف عن slot 0. أُوقف lpmake حتى لا يستبدل بيانات A/B ببيانات نسخة واحدة.");
                    }
                }
                if (info.sparse) Sparse.toRaw(img, sourceRaw, progress, cancel);

                File extractedDir = new File(work, "original_parts");
                if (!extractedDir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الأقسام الأصلية");
                LpTools.run(a, "lpunpack",
                        java.util.Arrays.asList(sourceRaw.getAbsolutePath(), extractedDir.getAbsolutePath()),
                        cancel, progress);

                Map<String, File> images = new LinkedHashMap<>();
                for (Lp.Part part : meta.parts) {
                    if (cancel != null) cancel.check();
                    File edited = new File(editedDir, part.name + "_edited.img");
                    File original = new File(extractedDir, Lp.unpackFileName(part, 0));
                    if (part.size == 0 && !original.exists() && !original.createNewFile()) {
                        throw new java.io.IOException("تعذر إنشاء القسم الفارغ " + part.name);
                    }
                    File chosen = edited.isFile() ? edited : original;
                    if (!chosen.isFile()) throw new java.io.IOException("ملف القسم غير موجود: " + part.name);
                    images.put(part.name, chosen);
                }

                List<String> args = LpTools.lpmakeArgs(meta, images, output, alsoSparse);
                LpTools.run(a, "lpmake", args, cancel, progress);
                if (!output.isFile() || output.length() == 0) {
                    throw new java.io.IOException("lpmake لم ينتج ملف super صالحاً.");
                }

                if (alsoSparse) {
                    Sparse.toRaw(output, verifyRaw, progress, cancel);
                }
                dump[0] = LpTools.run(a, "lpdump",
                        java.util.Collections.singletonList(verifyRaw.getAbsolutePath()), cancel, progress);
                if (!verifyDir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد التحقق");
                LpTools.run(a, "lpunpack",
                        java.util.Arrays.asList(verifyRaw.getAbsolutePath(), verifyDir.getAbsolutePath()),
                        cancel, progress);

                for (Lp.Part part : meta.parts) {
                    if (cancel != null) cancel.check();
                    File actual = new File(verifyDir, Lp.unpackFileName(part, 0));
                    LpTools.verifyPaddedImage(images.get(part.name), actual);
                }
                deleteTree(work);
                progress.on(1, 1);
            }, () -> a.showError("اكتمل بناء super باستخدام AOSP lpmake.\n"
                    + "الملف: " + output.getPath() + "\n"
                    + "الحجم: " + Io.human(output.length()) + "\n\n"
                    + "نجح lpdump في قراءة Metadata، ونجح lpunpack في استخراج جميع الأقسام، "
                    + "وتم التحقق من بياناتها ومقارنتها بملفات الإدخال."
                    + (dump[0] == null || dump[0].isEmpty() ? "" : "\n\n" + dump[0])));
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
