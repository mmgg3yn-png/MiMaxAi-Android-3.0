package com.mimax.ai.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** boot / init_boot / recovery (ANDROID!) و vendor_boot (VNDRBOOT): قراءة، استخراج، تعديل cmdline، واستبدال ramdisk. */
public final class BootImg {
    private BootImg() { }

    public static class Comp {
        public String name, note;
        public long off, len;
        public boolean truncated;
    }

    public static class Info {
        public String kind;            // boot | vendor_boot
        public int version;
        public long pageSize, size;
        public String cmdline = "", osVersion = "", name = "";
        public List<Comp> comps = new ArrayList<>();
        public Comp comp(String n) { for (Comp c : comps) if (c.name.equals(n)) return c; return null; }
    }

    public static boolean isBoot(byte[] h) { return h.length >= 8 && new String(h, 0, 8, StandardCharsets.US_ASCII).equals("ANDROID!"); }
    public static boolean isVendorBoot(byte[] h) { return h.length >= 8 && new String(h, 0, 8, StandardCharsets.US_ASCII).equals("VNDRBOOT"); }

    public static boolean looksLikeBoot(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[8];
            int n = in.read(h);
            return n == 8 && (isBoot(h) || isVendorBoot(h));
        } catch (IOException e) { return false; }
    }

    static long al(long n, long p) { return (n + p - 1) / p * p; }

    static String osVer(int v) {
        if (v == 0) return "";
        int a = (v >>> 25) & 0x7F, b = (v >>> 18) & 0x7F, c = (v >>> 11) & 0x7F, y = ((v >>> 4) & 0x7F) + 2000, m = v & 0xF;
        return a + "." + b + "." + c + " (patch " + y + "-" + (m < 10 ? "0" : "") + m + ")";
    }

    static void add(Info i, String name, long off, long len) {
        if (len <= 0) return;
        Comp c = new Comp();
        c.name = name; c.off = off; c.len = len;
        if (off + len > i.size) { c.truncated = true; c.note = "مبتور: يتجاوز نهاية الصورة بـ " + (off + len - i.size) + " بايت"; }
        i.comps.add(c);
    }

    public static Info parse(File f) throws IOException {
        try (Io.FileSource s = new Io.FileSource(f)) {
            byte[] hb = s.readUpTo(0, 4096);
            if (hb.length < 64) throw new Io.Corrupt("الملف أقصر من ترويسة الإقلاع", true);
            ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            Info i = new Info();
            i.size = s.size;
            if (isBoot(hb)) {
                i.kind = "boot";
                if (hb.length < 1648) throw new Io.Corrupt("ترويسة boot مبتورة", true);
                int ver = h.getInt(40);
                if (ver < 0 || ver > 4) throw new Io.Unsupported("إصدار ترويسة boot غير مدعوم: " + ver + " (قد تكون صيغة خاصة بشركة مصنّعة)");
                i.version = ver;
                long ksz = h.getInt(8) & 0xFFFFFFFFL;
                if (ver >= 3) {
                    i.pageSize = 4096;
                    i.osVersion = osVer(h.getInt(16));
                    i.cmdline = Io.cstr(hb, 44, 1536);
                    long o = 4096;
                    add(i, "kernel", o, ksz); o += al(ksz, 4096);
                    long rsz = h.getInt(12) & 0xFFFFFFFFL;
                    add(i, "ramdisk", o, rsz); o += al(rsz, 4096);
                    if (ver >= 4) add(i, "boot_signature", o, h.getInt(1580) & 0xFFFFFFFFL);
                } else {
                    long ps = h.getInt(36) & 0xFFFFFFFFL;
                    if (ps < 512 || (ps & (ps - 1)) != 0 || ps > (1 << 20)) throw new Io.Corrupt("حجم الصفحة في الترويسة غير صالح: " + ps);
                    i.pageSize = ps;
                    long rsz = h.getInt(16) & 0xFFFFFFFFL, ssz = h.getInt(24) & 0xFFFFFFFFL;
                    i.osVersion = osVer(h.getInt(44));
                    i.name = Io.cstr(hb, 48, 16);
                    i.cmdline = Io.cstr(hb, 64, 512) + Io.cstr(hb, 608, 1024);
                    long o = ps;
                    add(i, "kernel", o, ksz); o += al(ksz, ps);
                    add(i, "ramdisk", o, rsz); o += al(rsz, ps);
                    add(i, "second", o, ssz); o += al(ssz, ps);
                    if (ver >= 1) { long d = h.getInt(1632) & 0xFFFFFFFFL; add(i, "recovery_dtbo", h.getLong(1636), d); o += al(d, ps); }
                    if (ver >= 2) add(i, "dtb", o, h.getInt(1648) & 0xFFFFFFFFL);
                }
            } else if (isVendorBoot(hb)) {
                i.kind = "vendor_boot";
                if (hb.length < 2112) throw new Io.Corrupt("ترويسة vendor_boot مبتورة", true);
                int ver = h.getInt(8);
                if (ver != 3 && ver != 4) throw new Io.Unsupported("إصدار vendor_boot غير مدعوم: " + ver);
                i.version = ver;
                long ps = h.getInt(12) & 0xFFFFFFFFL;
                if (ps < 512 || (ps & (ps - 1)) != 0 || ps > (1 << 20)) throw new Io.Corrupt("حجم الصفحة غير صالح: " + ps);
                i.pageSize = ps;
                long vr = h.getInt(24) & 0xFFFFFFFFL, hs = h.getInt(2096) & 0xFFFFFFFFL, dtb = h.getInt(2100) & 0xFFFFFFFFL;
                i.cmdline = Io.cstr(hb, 28, 2048);
                i.name = Io.cstr(hb, 2080, 16);
                if (hs < 2112 || hs > 4096) throw new Io.Corrupt("حجم ترويسة vendor_boot غير صالح: " + hs);
                long o = al(hs, ps), vrOff = o;
                if (ver == 3) add(i, "vendor_ramdisk", o, vr);
                o += al(vr, ps);
                add(i, "dtb", o, dtb);
                o += al(dtb, ps);
                if (ver == 4) {
                    long tsz = h.getInt(2112) & 0xFFFFFFFFL, tn = h.getInt(2116) & 0xFFFFFFFFL, te = h.getInt(2120) & 0xFFFFFFFFL, bc = h.getInt(2124) & 0xFFFFFFFFL;
                    if (tn > 0) {
                        if (te < 108 || tn * te > tsz || o + tsz > s.size) throw new Io.Corrupt("جدول ramdisk في vendor_boot v4 تالف أو مبتور", o + tsz > s.size);
                        ByteBuffer t = s.le(o, (int) tsz);
                        for (int k = 0; k < tn; k++) {
                            int b = (int) (k * te);
                            byte[] nm = new byte[32];
                            t.position(b + 12); t.get(nm);
                            String n = Io.cstr(nm, 0, 32);
                            add(i, "vendor_ramdisk_" + (n.isEmpty() ? String.valueOf(k) : n), vrOff + (t.getInt(b + 4) & 0xFFFFFFFFL), t.getInt(b) & 0xFFFFFFFFL);
                        }
                    } else add(i, "vendor_ramdisk", vrOff, vr);
                    o += al(tsz, ps);
                    add(i, "bootconfig", o, bc);
                }
            } else {
                throw new Io.Corrupt("ليست صورة إقلاع Android (ANDROID! أو VNDRBOOT)");
            }
            return i;
        }
    }

    /** gzip | lz4 | xz | bzip2 | zstd | lzma | cpio | raw */
    public static String compression(byte[] h) {
        if (h.length >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) return "gzip";
        if (Lz4.isLz4(h)) return "lz4";
        if (h.length >= 6 && (h[0] & 0xFF) == 0xFD && h[1] == '7' && h[2] == 'z' && h[3] == 'X' && h[4] == 'Z') return "xz";
        if (h.length >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h') return "bzip2";
        if (h.length >= 4 && (h[0] & 0xFF) == 0x28 && (h[1] & 0xFF) == 0xB5 && (h[2] & 0xFF) == 0x2F && (h[3] & 0xFF) == 0xFD) return "zstd";
        if (h.length >= 3 && h[0] == 0x5D && h[1] == 0 && h[2] == 0) return "lzma";
        if (h.length >= 6 && new String(h, 0, 6, StandardCharsets.US_ASCII).startsWith("07070")) return "cpio";
        return "raw";
    }

    static void copyRange(RandomAccessFile r, long off, long len, OutputStream o) throws IOException {
        byte[] buf = new byte[1 << 20];
        r.seek(off);
        for (long left = len; left > 0; ) {
            int k = (int) Math.min(buf.length, left);
            r.readFully(buf, 0, k);
            o.write(buf, 0, k);
            left -= k;
        }
    }

    public static class Result {
        public List<String> lines = new ArrayList<>();
        public boolean ok = true;
        void fail(String m) { ok = false; lines.add("✗ " + m); }
    }

    /** يستخرج كل المكونات إلى ملفات ويفك ضغط ramdisk (gzip / lz4) ويفرد cpio. */
    public static Result extract(File img, Info info, File outDir, boolean unpackRamdisk, Io.Cancel c) throws IOException {
        Result r = new Result();
        outDir.mkdirs();
        long need = 0;
        for (Comp k : info.comps) if (!k.truncated) need += k.len;
        Io.checkSpace(outDir, need * 3);
        try (RandomAccessFile raf = new RandomAccessFile(img, "r")) {
            for (Comp k : info.comps) {
                if (c != null) c.check();
                if (k.truncated) { r.fail(k.name + ": " + k.note); continue; }
                raf.seek(k.off);
                byte[] head = new byte[(int) Math.min(16, k.len)];
                raf.readFully(head);
                String comp = compression(head);
                boolean rd = k.name.contains("ramdisk");
                String ext = k.name.equals("kernel") ? (comp.equals("gzip") ? ".gz" : "") : rd ? (comp.equals("raw") ? ".bin" : comp.equals("cpio") ? ".cpio" : "." + comp) : ".bin";
                File f = new File(outDir, k.name + ext);
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) { copyRange(raf, k.off, k.len, o); }
                if (f.length() != k.len) { r.fail(k.name + ": حجم الناتج لا يطابق"); continue; }
                r.lines.add("✓ " + k.name + " → " + f.getName() + " (" + Io.human(k.len) + (rd && !comp.equals("raw") ? "، ضغط: " + comp : "") + ")");
                if (unpackRamdisk && rd) {
                    try {
                        File cpio = toCpio(f, comp, new File(outDir, k.name + ".cpio"), c);
                        int n = Cpio.extractAll(cpio, new File(outDir, k.name + "_files"), c);
                        r.lines.add("✓ فرد " + k.name + " → " + k.name + "_files/ (" + n + " ملف)");
                    } catch (Io.CancelledException e) { throw e; }
                    catch (IOException e) { r.fail(k.name + ": " + e.getMessage()); }
                }
            }
        }
        if (info.comps.isEmpty()) r.fail("لا توجد مكونات في الصورة");
        return r;
    }

    /** يفك ضغط ramdisk إلى ملف cpio. gzip وlz4 مدعومان؛ غيرهما يرمي Unsupported باسم الخوارزمية. */
    public static File toCpio(File ramdisk, String comp, File cpioOut, Io.Cancel c) throws IOException {
        if (comp.equals("cpio")) return ramdisk;
        if (!comp.equals("gzip") && !comp.equals("lz4"))
            throw new Io.Unsupported(comp.equals("raw") ? "لا يوجد ضغط معروف ولا توقيع cpio" : "خوارزمية الضغط " + comp + " غير مدعومة في هذا الإصدار");
        boolean ok = false;
        try (InputStream in = new BufferedInputStream(new FileInputStream(ramdisk));
             OutputStream o = new BufferedOutputStream(new FileOutputStream(cpioOut))) {
            if (comp.equals("gzip")) {
                InputStream g = new GZIPInputStream(in);
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = g.read(b)) > 0) { if (c != null) c.check(); o.write(b, 0, n); }
            } else Lz4.decompress(in, o);
            ok = true;
        } catch (java.util.zip.ZipException e) {
            throw new Io.Corrupt("بيانات gzip في ramdisk تالفة: " + e.getMessage());
        } finally {
            if (!ok) cpioOut.delete();
        }
        return cpioOut;
    }

    /** يقرأ ramdisk إلى ملف cpio مؤقت في cacheDir للمعاينة. يعيد {cpio, الضغط}. */
    public static Object[] ramdiskCpio(File img, Info info, String compName, File cacheDir, Io.Cancel c) throws IOException {
        Comp k = info.comp(compName);
        if (k == null) throw new IOException("لا يوجد مكوّن " + compName);
        if (k.truncated) throw new Io.Corrupt(compName + ": " + k.note, true);
        cacheDir.mkdirs();
        File raw = new File(cacheDir, "ramdisk.raw");
        try (RandomAccessFile r = new RandomAccessFile(img, "r"); OutputStream o = new BufferedOutputStream(new FileOutputStream(raw))) {
            copyRange(r, k.off, k.len, o);
        }
        byte[] head = new byte[(int) Math.min(16, k.len)];
        try (InputStream in = new FileInputStream(raw)) { in.read(head); }
        String comp = compression(head);
        File cpio = toCpio(raw, comp, new File(cacheDir, "ramdisk.cpio"), c);
        return new Object[]{cpio, comp};
    }

    // ---------------- التعديل (يُكتب دائماً إلى ملف جديد) ----------------

    static void copyFile(File a, File b) throws IOException {
        try (InputStream i = new BufferedInputStream(new FileInputStream(a)); OutputStream o = new BufferedOutputStream(new FileOutputStream(b))) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    /** يغيّر سطر أوامر النواة (cmdline) في نسخة جديدة. */
    public static void setCmdline(File src, File dst, String cmdline) throws IOException {
        Info info = parse(src);
        byte[] cb = cmdline.getBytes(StandardCharsets.UTF_8);
        int off, max, off2 = -1, max2 = 0;
        if (info.kind.equals("vendor_boot")) { off = 28; max = 2048; }
        else if (info.version >= 3) { off = 44; max = 1536; }
        else { off = 64; max = 512; off2 = 608; max2 = 1024; }
        if (cb.length > max - 1 + (off2 >= 0 ? max2 - 1 : 0))
            throw new IOException("سطر الأوامر أطول من المسموح (" + (max - 1 + (off2 >= 0 ? max2 - 1 : 0)) + " بايت)");
        if (src.getCanonicalFile().equals(dst.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الأصل مباشرة");
        Io.checkSpace(dst.getAbsoluteFile().getParentFile(), src.length());
        copyFile(src, dst);
        boolean ok = false;
        try (RandomAccessFile r = new RandomAccessFile(dst, "rw")) {
            r.seek(off);
            r.write(new byte[max]);
            if (off2 >= 0) { r.seek(off2); r.write(new byte[max2]); }
            int first = Math.min(cb.length, max - 1);
            r.seek(off);
            r.write(cb, 0, first);
            if (cb.length > first) { r.seek(off2); r.write(cb, first, cb.length - first); }
            ok = true;
        } finally { if (!ok) dst.delete(); }
        Info chk = parse(dst);
        if (!chk.cmdline.equals(cmdline)) { dst.delete(); throw new IOException("فشل التحقق: سطر الأوامر الناتج لا يطابق"); }
    }

    /**
     * يبني صورة جديدة بـ ramdisk بديل (بايتات ramdisk كما ستُخزَّن، مثلاً gzip).
     * مدعوم: boot (كل الإصدارات)، vendor_boot v3. غير مدعوم: vendor_boot v4 (جدول ramdisk).
     */
    public static void replaceRamdisk(File src, File dst, File newRamdisk) throws IOException {
        Info info = parse(src);
        String rname = info.kind.equals("boot") ? "ramdisk" : "vendor_ramdisk";
        if (info.kind.equals("vendor_boot") && info.version == 4)
            throw new Io.Unsupported("تعديل ramdisk في vendor_boot v4 غير مدعوم (يحتاج إعادة بناء جدول ramdisk)");
        for (Comp k : info.comps) if (k.truncated) throw new Io.Corrupt("لا يمكن التعديل: " + k.name + " " + k.note, true);
        if (info.comp(rname) == null) throw new IOException("لا يوجد ramdisk في هذه الصورة");
        if (src.getCanonicalFile().equals(dst.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الأصل مباشرة");
        long ps = info.pageSize, newLen = newRamdisk.length();
        Io.checkSpace(dst.getAbsoluteFile().getParentFile(), src.length() + newLen);
        long hdrLen = info.kind.equals("boot") ? ps : al(Math.max(2112, 2128), ps);
        // ترتيب المكونات في الملف كما في الأصل
        List<Comp> order = new ArrayList<>();
        for (Comp k : info.comps) order.add(k);
        order.sort((a, b) -> Long.compare(a.off, b.off));
        boolean ok = false;
        try (RandomAccessFile r = new RandomAccessFile(src, "r"); FileOutputStream fo = new FileOutputStream(dst);
             BufferedOutputStream o = new BufferedOutputStream(fo, 1 << 20)) {
            byte[] hdr = new byte[(int) (info.kind.equals("boot") ? ps : Math.min(src.length(), al(info.kind.equals("vendor_boot") ? 2112 : 4096, ps)))];
            r.seek(0);
            r.readFully(hdr);
            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            // موضع كل مكوّن جديد
            long pos = al(hdr.length, ps);
            long[] newOff = new long[order.size()];
            long[] newSz = new long[order.size()];
            for (int i = 0; i < order.size(); i++) {
                Comp k = order.get(i);
                newOff[i] = pos;
                newSz[i] = k.name.equals(rname) ? newLen : k.len;
                pos += al(newSz[i], ps);
            }
            // تحديث حقول الترويسة
            if (info.kind.equals("boot")) {
                if (info.version >= 3) h.putInt(12, (int) newLen); else h.putInt(16, (int) newLen);
                if (info.version == 1 || info.version == 2) {
                    for (int i = 0; i < order.size(); i++) if (order.get(i).name.equals("recovery_dtbo")) h.putLong(1636, newOff[i]);
                }
            } else {
                h.putInt(24, (int) newLen);
            }
            o.write(hdr);
            for (long p = hdr.length; p < al(hdr.length, ps); p++) o.write(0);
            for (int i = 0; i < order.size(); i++) {
                Comp k = order.get(i);
                if (k.name.equals(rname)) {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(newRamdisk))) {
                        byte[] b = new byte[1 << 16];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    }
                } else copyRange(r, k.off, k.len, o);
                for (long p = newSz[i]; p < al(newSz[i], ps); p++) o.write(0);
            }
            ok = true;
        } finally { if (!ok) dst.delete(); }
        Info chk = parse(dst);
        Comp nc = chk.comp(rname);
        if (nc == null || nc.len != newLen || chk.comps.size() != info.comps.size()) { dst.delete(); throw new IOException("فشل التحقق من الصورة الناتجة"); }
        for (Comp k : chk.comps) if (k.truncated) { dst.delete(); throw new IOException("الصورة الناتجة مبتورة"); }
    }
}
