package com.mimax.ai.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * تعديل ext4 في مكانه (بدون إعادة بناء): استبدال محتوى ملف بحجم أقل أو يساوي مساحته المخصصة،
 * وتغيير الأذونات والمالك. يحدّث مجموع inode (metadata_csum) عند الحاجة. لا يضيف ولا يحذف ملفات.
 */
public final class Ext4Edit {
    private Ext4Edit() { }

    static final int[] T = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x82F63B78 : c >>> 1;
            T[i] = c;
        }
    }

    /** CRC32C خام (بلا قلب أولي/نهائي) كما يستخدمه ext4. */
    public static int crc32c(int crc, byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) crc = T[(crc ^ b[i]) & 0xFF] ^ (crc >>> 8);
        return crc;
    }

    static int le32(long v) { return (int) v; }

    static void fixCsum(Ext4 e, long ino, byte[] raw) {
        if (!e.hasMetadataCsum()) return;
        ByteBuffer d = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        boolean hi = raw.length > 128 && (d.getShort(128) & 0xFFFF) >= 4;
        raw[0x7C] = 0; raw[0x7D] = 0;
        if (hi) { raw[0x82] = 0; raw[0x83] = 0; }
        byte[] n4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(le32(ino)).array();
        byte[] g4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(d.getInt(0x64)).array();
        int crc = crc32c(e.csumSeed, n4, 0, 4);
        crc = crc32c(crc, g4, 0, 4);
        crc = crc32c(crc, raw, 0, raw.length);
        raw[0x7C] = (byte) crc; raw[0x7D] = (byte) (crc >>> 8);
        if (hi) { raw[0x82] = (byte) (crc >>> 16); raw[0x83] = (byte) (crc >>> 24); }
    }

    /** المساحة المخصصة للملف (بايت) بشرط أن تكون امتداداته متصلة من الكتلة 0 وبلا كتل غير مهيأة. */
    static long capacity(List<Ext4.Run> runs, int bs) throws IOException {
        long expect = 0, cap = 0;
        for (Ext4.Run r : runs) {
            if (r.zero) throw new Io.Unsupported("الملف يحتوي كتلاً محجوزة غير مهيأة (preallocated) — غير مدعوم للتعديل");
            if (r.lb != expect) throw new Io.Unsupported("الملف متناثر (فيه فجوات) — غير مدعوم للتعديل");
            expect += r.len; cap += r.len * bs;
        }
        return cap;
    }

    public static long capacityOf(File img, String path) throws IOException {
        try (Vsrc.FileV v = new Vsrc.FileV(img)) {
            Ext4 e = Ext4.open(v);
            Ext4.Node n = e.lookup(path);
            if (!n.isReg()) throw new IOException("ليس ملفاً عادياً: " + path);
            return capacity(e.runs(n), e.bs);
        }
    }

    public static void replaceContent(File img, String path, byte[] data) throws IOException {
        try (Vsrc.FileV v = new Vsrc.FileV(img); RandomAccessFile rw = new RandomAccessFile(img, "rw")) {
            Ext4 e = Ext4.open(v);
            Ext4.Node n = e.lookup(path);
            if (!n.isReg()) throw new IOException("ليس ملفاً عادياً: " + path);
            List<Ext4.Run> runs = e.runs(n);
            long cap = capacity(runs, e.bs);
            if (data.length > cap)
                throw new Io.Unsupported("الملف الجديد (" + data.length + " بايت) أكبر من المساحة المخصصة للملف (" + cap
                        + " بايت). المدعوم حالياً: بنفس الحجم أو أقل. الإضافة تتطلب تخصيص كتل جديدة.");
            long pos = 0;
            for (Ext4.Run r : runs) {
                long bytes = r.len * e.bs, take = Math.min(bytes, data.length - pos);
                if (take > 0) { rw.seek(r.pb * e.bs); rw.write(data, (int) pos, (int) take); pos += take; }
            }
            // تصفير بقية آخر كتلة مكتوبة كي لا تبقى بيانات قديمة
            long tail = data.length % e.bs == 0 ? 0 : e.bs - (data.length % e.bs);
            if (tail > 0) {
                long lblk = data.length / e.bs, phys = -1;
                for (Ext4.Run r : runs) if (lblk >= r.lb && lblk < r.lb + r.len) phys = (r.pb + (lblk - r.lb)) * e.bs + (data.length % e.bs);
                if (phys >= 0) { rw.seek(phys); rw.write(new byte[(int) tail]); }
            }
            byte[] raw = n.raw.clone();
            ByteBuffer d = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            d.putInt(4, (int) (data.length & 0xFFFFFFFFL));
            d.putInt(108, (int) (((long) data.length) >>> 32));
            fixCsum(e, n.ino, raw);
            rw.seek(e.inodePos(n.ino));
            rw.write(raw);
        }
        // تحقق: إعادة القراءة
        try (Vsrc.FileV v = new Vsrc.FileV(img)) {
            Ext4 e = Ext4.open(v);
            Ext4.Node n = e.lookup(path);
            byte[] back = e.content(n).read(0, (int) n.size);
            if (n.size != data.length || !Arrays.equals(back, data)) throw new IOException("فشل التحقق بعد الكتابة: المحتوى لا يطابق");
        }
    }

    public static void setMode(File img, String path, int perm) throws IOException {
        patchInode(img, path, (d, raw) -> {
            int old = d.getShort(0) & 0xFFFF;
            d.putShort(0, (short) ((old & 0xF000) | (perm & 0x0FFF)));
        });
    }

    public static void setOwner(File img, String path, int uid, int gid) throws IOException {
        patchInode(img, path, (d, raw) -> {
            d.putShort(2, (short) uid); d.putShort(120, (short) (uid >>> 16));
            d.putShort(24, (short) gid); d.putShort(122, (short) (gid >>> 16));
        });
    }

    interface Patch { void apply(ByteBuffer d, byte[] raw); }

    static void patchInode(File img, String path, Patch p) throws IOException {
        try (Vsrc.FileV v = new Vsrc.FileV(img); RandomAccessFile rw = new RandomAccessFile(img, "rw")) {
            Ext4 e = Ext4.open(v);
            Ext4.Node n = e.lookup(path);
            byte[] raw = n.raw.clone();
            p.apply(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), raw);
            fixCsum(e, n.ino, raw);
            rw.seek(e.inodePos(n.ino));
            rw.write(raw);
        }
    }
}
