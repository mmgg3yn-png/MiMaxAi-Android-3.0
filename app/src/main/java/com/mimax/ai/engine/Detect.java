package com.mimax.ai.engine;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** يكتشف نوع الصورة من محتواها (لا من الامتداد) ويذكر الأدلة والحالة. */
public final class Detect {
    private Detect() { }
    public static final String VALID = "valid", CORRUPT = "corrupt", INCOMPLETE = "incomplete", UNSUPPORTED = "unsupported";

    public static class Info {
        public String kind = "unknown", label = "غير معروف", status = UNSUPPORTED, detail = "";
        public boolean sparse;
        public long size, rawSize;
        public List<String> evidence = new ArrayList<>();
        public String statusAr() {
            switch (status) {
                case VALID: return "صالحة";
                case CORRUPT: return "تالفة";
                case INCOMPLETE: return "غير مكتملة (مبتورة)";
                default: return "غير مدعومة";
            }
        }
    }

    static void fail(Info i, IOException e) {
        i.detail = e.getMessage();
        if (e instanceof Io.Unsupported) i.status = UNSUPPORTED;
        else if (e instanceof Io.Corrupt && ((Io.Corrupt) e).truncated) i.status = INCOMPLETE;
        else i.status = CORRUPT;
    }

    static void kind(Info i, String k, String label) { i.kind = k; i.label = label; }

    public static Info detect(File f) throws IOException {
        Info i = new Info();
        i.size = f.length();
        if (i.size == 0) { i.detail = "الملف فارغ"; return i; }
        try (Vsrc.FileV file = new Vsrc.FileV(f)) {
            Vsrc s = file;
            if (Sparse.isSparse(file)) {
                i.sparse = true;
                i.evidence.add("ترويسة Android Sparse (0xED26FF3A) عند الإزاحة 0");
                try {
                    s = Sparse.open(file);
                    i.rawSize = s.size();
                    i.evidence.add("تحقق Sparse نجح؛ الحجم بعد الفك Raw = " + Io.human(s.size()));
                } catch (IOException e) { kind(i, "sparse", "Android Sparse"); fail(i, e); return i; }
            } else {
                i.rawSize = i.size;
                i.evidence.add("لا توجد ترويسة Sparse: الملف Raw");
            }
            byte[] head = s.readUpTo(0, 12288);
            ByteBuffer b = ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN), be = ByteBuffer.wrap(head);
            try {
                if (head.length >= 4 && be.getInt(0) == LogoBin.MAGIC) {
                    kind(i, "logo", "MediaTek logo.bin");
                    LogoBin.Info li = LogoBin.inspect(f);
                    i.evidence.add("توقيع MTK 0x88168858 وترويسة LOGO صحيحة");
                    i.evidence.add(li.count + " صورة — حجم كتلة البيانات " + Io.human(li.blockSize));
                    i.status = VALID;
                } else if (head.length >= 4100 && b.getInt(4096) == Lp.GEO_MAGIC) {
                    kind(i, "super", "Super (Android Logical Partitions)");
                    i.evidence.add("توقيع LP Geometry (gDla) عند الإزاحة 4096");
                    Lp.Meta m = Lp.read(s);
                    i.evidence.add("LP Metadata " + m.major + "." + m.minor + " سليمة المجاميع، " + m.parts.size() + " قسم" + (m.usedBackup ? " (من النسخة الاحتياطية)" : ""));
                    i.status = VALID;
                } else if (BootImg.isBoot(head)) {
                    kind(i, "boot", "صورة إقلاع Android (boot/init_boot/recovery)");
                    i.evidence.add("توقيع ANDROID! عند الإزاحة 0");
                    i.status = VALID;
                    if (!i.sparse) { BootImg.Info bi = BootImg.parse(f); i.evidence.add("إصدار الترويسة " + bi.version + "، " + bi.comps.size() + " مكوّن"); for (BootImg.Comp c : bi.comps) if (c.truncated) { i.status = INCOMPLETE; i.detail = c.name + ": " + c.note; } }
                } else if (BootImg.isVendorBoot(head)) {
                    kind(i, "vendor_boot", "vendor_boot");
                    i.evidence.add("توقيع VNDRBOOT عند الإزاحة 0");
                    i.status = VALID;
                    if (!i.sparse) { BootImg.Info bi = BootImg.parse(f); i.evidence.add("إصدار الترويسة " + bi.version + "، " + bi.comps.size() + " مكوّن"); for (BootImg.Comp c : bi.comps) if (c.truncated) { i.status = INCOMPLETE; i.detail = c.name + ": " + c.note; } }
                } else if (head.length >= 1082 && (b.getShort(1080) & 0xFFFF) == 0xEF53) {
                    kind(i, "ext4", "نظام ملفات ext2/3/4");
                    i.evidence.add("توقيع ext (0xEF53) في الـ superblock عند الإزاحة 1080");
                    Ext4 e = Ext4.open(s);
                    i.evidence.add("حجم الكتلة " + e.bs + "، " + e.blocks + " كتلة" + (e.label.isEmpty() ? "" : "، الاسم: " + e.label) + (e.hasMetadataCsum() ? "، metadata_csum" : ""));
                    i.status = VALID;
                } else if (head.length >= 1028 && b.getInt(1024) == 0xE0F5E1E2) {
                    kind(i, "erofs", "نظام ملفات EROFS");
                    i.evidence.add("توقيع EROFS (0xE0F5E1E2) عند الإزاحة 1024");
                    i.detail = "تم التعرف عليه لكن استخراج محتوياته غير مدعوم في هذا الإصدار (يمكن حفظ القسم كما هو).";
                } else if (head.length >= 1028 && b.getInt(1024) == 0xF2F52010) {
                    kind(i, "f2fs", "نظام ملفات F2FS");
                    i.evidence.add("توقيع F2FS (0xF2F52010) عند الإزاحة 1024");
                    i.detail = "تم التعرف عليه لكن استخراج محتوياته غير مدعوم في هذا الإصدار.";
                } else if (head.length >= 4 && head[0] == 'h' && head[1] == 's' && head[2] == 'q' && head[3] == 's') {
                    kind(i, "squashfs", "SquashFS"); i.evidence.add("توقيع hsqs"); i.detail = "تم التعرف عليه لكن استخراجه غير مدعوم.";
                } else if (head.length >= 4 && head[0] == 'A' && head[1] == 'V' && head[2] == 'B' && head[3] == '0') {
                    kind(i, "vbmeta", "vbmeta (Android Verified Boot)"); i.evidence.add("توقيع AVB0"); i.detail = "صورة تحقق إقلاع؛ لا تحتوي ملفات للاستخراج.";
                } else if (head.length >= 4 && be.getInt(0) == 0xD00DFEED) {
                    kind(i, "dtb", "Device Tree Blob"); i.evidence.add("توقيع FDT"); i.detail = "تم التعرف عليه؛ لا يوجد محرك فك لهذه الصيغة.";
                } else if (head.length >= 4 && be.getInt(0) == 0xD7B7AB1E) {
                    kind(i, "dtbo", "Device Tree Overlay"); i.evidence.add("توقيع DTBO"); i.detail = "تم التعرف عليه؛ لا يوجد محرك فك لهذه الصيغة.";
                } else if (head.length >= 4 && head[0] == 'C' && head[1] == 'r' && head[2] == 'A' && head[3] == 'U') {
                    kind(i, "payload", "OTA payload.bin"); i.evidence.add("توقيع CrAU"); i.detail = "حزمة OTA؛ فكها غير مدعوم في هذا الإصدار.";
                } else {
                    i.detail = "نوع الصورة غير معروف أو غير مدعوم";
                    i.evidence.add("لم يطابق أي توقيع معروف في أول 12KB");
                }
            } catch (IOException e) { fail(i, e); }
        }
        if (i.status.equals(UNSUPPORTED) && i.detail.isEmpty()) i.detail = "نوع الصورة غير معروف أو غير مدعوم";
        return i;
    }
}
