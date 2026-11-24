import com.mimax.ai.engine.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** اختبارات محرك الصور: Sparse (ذهاباً وإياباً)، super (فك وإعادة تجميع)، ext4 (استخراج وتعديل)، والاكتشاف. */
public class ImgTest {
    interface T { String run() throws Exception; }
    static List<String[]> rows = new ArrayList<>();
    static int pass, fail;
    static File W;

    static void t(String name, String data, String expected, T body) {
        try { String a = body.run(); rows.add(new String[]{name, data, expected, a, "نجح"}); pass++; }
        catch (Throwable e) { rows.add(new String[]{name, data, expected, String.valueOf(e), "فشل"}); fail++; }
    }
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " => " + out);
        return out;
    }
    static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
    static byte[] all(File f) throws IOException { return Files.readAllBytes(f.toPath()); }
    static void w(File f, byte[] b) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), b); }
    static <E extends Throwable> String thrown(Class<E> c, T body) throws Exception {
        try { body.run(); } catch (Throwable e) {
            if (c.isInstance(e)) return e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new AssertionError("استثناء مختلف: " + e);
        }
        throw new AssertionError("لم يُرمَ أي استثناء");
    }
    static void del(File f) { File[] l = f.listFiles(); if (l != null) for (File x : l) del(x); f.delete(); }
    static void fsck(File img) throws Exception { try { sh("/usr/sbin/e2fsck", "-fn", img.getPath()); } catch (IOException e) { throw new AssertionError("e2fsck فشل: " + e.getMessage()); } }
    static void treeEq(File a, File b, Set<String> skip, String rel) throws IOException {
        Set<String> na = new TreeSet<>(Arrays.asList(a.list())), nb = new TreeSet<>(Arrays.asList(b.list()));
        na.remove("lost+found"); nb.remove("lost+found"); na.remove("_symlinks.txt"); nb.remove("_symlinks.txt");
        check(na.equals(nb), "أسماء مختلفة في " + a + ": " + na + " مقابل " + nb);
        for (String n : na) {
            File x = new File(a, n), y = new File(b, n);
            String r = rel + "/" + n;
            if (Files.isSymbolicLink(x.toPath())) { check(Files.isSymbolicLink(y.toPath()) && Files.readSymbolicLink(x.toPath()).equals(Files.readSymbolicLink(y.toPath())), "رابط " + r); }
            else if (x.isDirectory()) treeEq(x, y, skip, r);
            else if (!skip.contains(r)) check(Arrays.equals(all(x), all(y)), "محتوى مختلف: " + r);
        }
    }
    static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    static void le(ByteBuffer b, int o, long v, int n) { for (int i = 0; i < n; i++) b.put(o + i, (byte) (v >>> (8 * i))); }

    static class PartSpec { String name; byte[] data; int pieces; PartSpec(String n, byte[] d, int p) { name = n; data = d; pieces = p; } }

    /** يبني super.img (LP metadata v10.2) حسب مواصفة liblp: مجموعة واحدة بحد groupMax. */
    static byte[] buildSuper(List<PartSpec> specs, int maxMeta, int slots, long groupMax, long extraSpace) throws Exception {
        long dataStart = ((12288L + (long) maxMeta * slots) + (1 << 20) - 1) / (1 << 20) * (1 << 20);
        List<long[]> ext = new ArrayList<>();
        List<int[]> partIdx = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long cur = dataStart;
        for (PartSpec p : specs) {
            int first = ext.size();
            if (p.data.length > 0) {
                int per = (p.data.length / 512 + p.pieces - 1) / p.pieces;
                long left = p.data.length / 512;
                int pos = 0;
                for (int k = 0; k < p.pieces && left > 0; k++) {
                    long sec = Math.min(per, left);
                    ext.add(new long[]{sec, 0, cur / 512});
                    data.write(p.data, pos, (int) (sec * 512));
                    data.write(new byte[4096]);
                    cur += sec * 512 + 4096; pos += sec * 512; left -= sec;
                }
            }
            partIdx.add(new int[]{first, ext.size() - first});
        }
        int np = partIdx.size(), ne = ext.size();
        ByteBuffer t = ByteBuffer.allocate(np * 52 + ne * 24 + 48 + 64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < np; i++) {
            t.position(i * 52); t.put(specs.get(i).name.getBytes(StandardCharsets.UTF_8));
            t.putInt(i * 52 + 36, 1); t.putInt(i * 52 + 40, partIdx.get(i)[0]); t.putInt(i * 52 + 44, partIdx.get(i)[1]); t.putInt(i * 52 + 48, 0);
        }
        int eo = np * 52;
        for (int i = 0; i < ne; i++) { t.putLong(eo + i * 24, ext.get(i)[0]); t.putInt(eo + i * 24 + 8, 0); t.putLong(eo + i * 24 + 12, ext.get(i)[2]); t.putInt(eo + i * 24 + 20, 0); }
        int go = eo + ne * 24, bo = go + 48;
        t.position(go); t.put("default".getBytes()); t.putLong(go + 40, groupMax);
        long total = dataStart + data.size() + extraSpace + (long) maxMeta * slots;
        t.putLong(bo, 2048); t.putInt(bo + 8, 1048576); t.putLong(bo + 16, total);
        byte[] tables = t.array();
        byte[] hdr = new byte[256];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0, 0x414C5067); h.putShort(4, (short) 10); h.putShort(6, (short) 2); h.putInt(8, 256);
        h.putInt(44, tables.length); System.arraycopy(sha(tables), 0, hdr, 48, 32);
        h.putInt(80, 0); h.putInt(84, np); h.putInt(88, 52); h.putInt(92, eo); h.putInt(96, ne); h.putInt(100, 24);
        h.putInt(104, go); h.putInt(108, 1); h.putInt(112, 48); h.putInt(116, bo); h.putInt(120, 1); h.putInt(124, 64);
        System.arraycopy(sha(hdr), 0, hdr, 12, 32);
        byte[] geo = new byte[4096];
        ByteBuffer g = ByteBuffer.wrap(geo).order(ByteOrder.LITTLE_ENDIAN);
        g.putInt(0, 0x616c4467); g.putInt(4, 52); g.putInt(40, maxMeta); g.putInt(44, slots); g.putInt(48, 4096);
        System.arraycopy(sha(Arrays.copyOf(geo, 52)), 0, geo, 8, 32);
        byte[] img = new byte[(int) total];
        System.arraycopy(geo, 0, img, 4096, 4096); System.arraycopy(geo, 0, img, 8192, 4096);
        byte[] meta = new byte[hdr.length + tables.length];
        System.arraycopy(hdr, 0, meta, 0, hdr.length); System.arraycopy(tables, 0, meta, hdr.length, tables.length);
        for (int s = 0; s < slots; s++) {
            System.arraycopy(meta, 0, img, 12288 + s * maxMeta, meta.length);
            System.arraycopy(meta, 0, img, (int) (total - (long) maxMeta * slots) + s * maxMeta, meta.length);
        }
        System.arraycopy(data.toByteArray(), 0, img, (int) dataStart, data.size());
        return img;
    }

    static byte[] bootV2(byte[] kernel, byte[] ramdisk) throws IOException {
        byte[] hdr = new byte[2048];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        h.put("ANDROID!".getBytes()); h.putInt(8, kernel.length); h.putInt(16, ramdisk.length); h.putInt(36, 2048); h.putInt(40, 2);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(hdr); o.write(Arrays.copyOf(kernel, (kernel.length + 2047) / 2048 * 2048)); o.write(Arrays.copyOf(ramdisk, (ramdisk.length + 2047) / 2048 * 2048));
        return o.toByteArray();
    }

    /** صورة raw فيها: كتل أصفار، كتل نمط، كتل بيانات، وكتلة أخيرة جزئية. */
    static byte[] mixedRaw() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] pat = new byte[4096]; for (int i = 0; i < 4096; i += 4) { pat[i] = 0x44; pat[i + 1] = 0x33; pat[i + 2] = 0x22; pat[i + 3] = 0x11; }
        o.writeBytes(new byte[4096 * 3]); o.writeBytes(pat); o.writeBytes(pat); o.writeBytes(rnd(4096 * 2, 5)); o.writeBytes(new byte[4096 * 2]); o.writeBytes(rnd(1000, 6));
        return o.toByteArray();
    }

    static int[] chunkTypes(File sp) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(sp, "r")) {
            byte[] h = new byte[28]; r.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int n = b.getInt(20); long pos = 28; int[] cnt = new int[3];
            for (int i = 0; i < n; i++) {
                r.seek(pos); byte[] c = new byte[12]; r.readFully(c);
                ByteBuffer cb = ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN);
                int type = cb.getShort(0) & 0xFFFF; long tsz = cb.getInt(8) & 0xFFFFFFFFL;
                cnt[type == 0xCAC1 ? 0 : type == 0xCAC2 ? 1 : 2]++;
                pos += tsz;
            }
            return cnt;
        }
    }

    public static void main(String[] a) throws Exception {
        W = new File(a[0]); del(W); W.mkdirs();
        File src = new File(W, "src");
        w(new File(src, "etc/config.txt"), "hello config\nسطر عربي\n".getBytes(StandardCharsets.UTF_8));
        w(new File(src, "system/build.prop"), "ro.build.type=user\nro.secure=1\nro.debuggable=0\nro.product.name=testdev\n# padding padding padding padding padding\n".getBytes());
        w(new File(src, "app/عربي ملف.txt"), "محتوى عربي".getBytes(StandardCharsets.UTF_8));
        w(new File(src, "bin/big.bin"), rnd(3 << 20, 1));
        w(new File(src, "bin/empty"), new byte[0]);
        w(new File(src, "bin/tool.sh"), "#!/system/bin/sh\necho tool\n".getBytes());
        try (RandomAccessFile r = new RandomAccessFile(new File(src, "lib").getAbsoluteFile().mkdirs() ? new File(src, "lib/holes.bin") : null, "rw")) { r.write(rnd(100, 2)); r.seek(5L << 20); r.write(rnd(50, 3)); }
        for (int i = 0; i < 300; i++) w(new File(src, "many/file_" + i + ".dat"), rnd(10 + i, 100 + i));
        Files.createSymbolicLink(new File(src, "link").toPath(), Paths.get("etc/config.txt"));

        File e4 = new File(W, "ext4.img"), e4n = new File(W, "ext4_nocsum.img"), e2 = new File(W, "ext2_1k.img"), inl = new File(W, "inline.img");
        sh("/usr/sbin/mke2fs", "-q", "-F", "-t", "ext4", "-b", "4096", "-L", "sys", "-d", src.getPath(), e4.getPath(), "24M");
        sh("/usr/sbin/mke2fs", "-q", "-F", "-t", "ext4", "-b", "4096", "-O", "^metadata_csum,^has_journal", "-d", src.getPath(), e4n.getPath(), "24M");
        sh("/usr/sbin/mke2fs", "-q", "-F", "-t", "ext2", "-b", "1024", "-d", src.getPath(), e2.getPath(), "24M");
        sh("/usr/sbin/mke2fs", "-q", "-F", "-t", "ext4", "-O", "inline_data", "-d", src.getPath(), inl.getPath(), "24M");
        String feats = sh("/usr/sbin/dumpe2fs", "-h", e4.getPath());
        boolean csumDefault = feats.contains("metadata_csum");

        // ================= Sparse =================
        File mixed = new File(W, "mixed.raw"); Files.write(mixed.toPath(), mixedRaw());
        t("Sparse: Raw→Sparse تصنيف الأجزاء (FILL/RAW) والعودة مطابقة", "صورة فيها أصفار ونمط 0x11223344 وبيانات وكتلة أخيرة جزئية (1000B)", "FILL للأصفار والنمط، RAW للبيانات، والعودة مطابقة (مع حشو آخر كتلة)", () -> {
            File sp = new File(W, "mixed.sparse"); Sparse.fromRaw(mixed, sp, null, null);
            int[] c = chunkTypes(sp);
            check(c[0] >= 1 && c[1] >= 2 && c[2] == 0, "RAW=" + c[0] + " FILL=" + c[1] + " DC=" + c[2]);
            File back = new File(W, "mixed.back"); long n = Sparse.toRaw(sp, back, null, null);
            byte[] orig = all(mixed), bk = all(back);
            check(n == 4096L * 10 && bk.length == 4096 * 10, "الحجم " + n);
            check(Arrays.equals(orig, Arrays.copyOf(bk, orig.length)), "المحتوى");
            for (int i = orig.length; i < bk.length; i++) check(bk[i] == 0, "الحشو ليس أصفاراً");
            return "RAW=" + c[0] + " FILL=" + c[1] + "، الحجم " + sp.length() + " ← " + mixed.length();
        });
        t("Sparse: صورة ext4 (24MB) ذهاباً وإياباً + اكتشاف", "ext4.img → Sparse → Raw", "مطابق بايتاً، والاكتشاف يعرفها Sparse+ext4 صالحة", () -> {
            File sp = new File(W, "ext4.sparse"); long sz = Sparse.fromRaw(e4, sp, null, null);
            File back = new File(W, "ext4.back"); Sparse.toRaw(sp, back, null, null);
            check(Arrays.equals(all(back), all(e4)), "المحتوى");
            Detect.Info i = Detect.detect(sp);
            check(i.sparse && i.kind.equals("ext4") && i.status.equals(Detect.VALID), i.kind + "/" + i.status + "/" + i.detail);
            return "Sparse " + sz + " بايت من " + e4.length() + "؛ الاكتشاف: " + i.label + " / " + i.statusAr();
        });
        t("Sparse: صورة مبتورة / نوع جزء مجهول / عدد كتل خاطئ", "تعديل Sparse صحيح بثلاث طرق", "Corrupt لكل حالة", () -> {
            File sp = new File(W, "ext4.sparse"); byte[] b = all(sp);
            File cut = new File(W, "cut.sparse"); Files.write(cut.toPath(), Arrays.copyOf(b, b.length / 2));
            String m1 = thrown(Io.Corrupt.class, () -> { Sparse.toRaw(cut, new File(W, "cut.raw"), null, null); return ""; });
            check(!new File(W, "cut.raw").exists(), "بقي ناتج");
            byte[] b2 = b.clone(); b2[28] = (byte) 0xFE; b2[29] = (byte) 0xCA; File f2 = new File(W, "badtype.sparse"); Files.write(f2.toPath(), b2);
            String m2 = thrown(Io.Corrupt.class, () -> { Sparse.toRaw(f2, new File(W, "bt.raw"), null, null); return ""; });
            byte[] b3 = b.clone(); b3[16] = 1; b3[17] = 0; b3[18] = 0; b3[19] = 0; File f3 = new File(W, "badblk.sparse"); Files.write(f3.toPath(), b3);
            String m3 = thrown(Io.Corrupt.class, () -> { Sparse.toRaw(f3, new File(W, "bb.raw"), null, null); return ""; });
            return m1 + " | " + m2 + " | " + m3;
        });
        t("Sparse: إلغاء التحويل (في الاتجاهين) لا يترك ناتجاً", "إلغاء عند أول تقدم", "CancelledException وحذف الناتج", () -> {
            File sp = new File(W, "ext4.sparse"); Io.Cancel c = new Io.Cancel(); File o1 = new File(W, "c1.raw"), o2 = new File(W, "c2.sparse");
            String m1 = thrown(Io.CancelledException.class, () -> { Sparse.toRaw(sp, o1, (d, tt) -> c.cancelled = true, c); return ""; });
            Io.Cancel c2 = new Io.Cancel();
            String m2 = thrown(Io.CancelledException.class, () -> { Sparse.fromRaw(e4, o2, (d, tt) -> c2.cancelled = true, c2); return ""; });
            check(!o1.exists() && !o2.exists(), "بقي ناتج");
            return m1 + " | " + m2;
        });
        t("Sparse: نقص مساحة التخزين", "Sparse يعلن 8TB من DONT_CARE", "IOException «مساحة التخزين غير كافية» قبل الكتابة", () -> {
            File f = new File(W, "huge.sparse"); long blocks = (8L << 40) / 4096;
            ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0xED26FF3A).putShort((short) 1).putShort((short) 0).putShort((short) 28).putShort((short) 12).putInt(4096).putInt((int) blocks).putInt(1).putInt(0);
            b.putShort((short) 0xCAC3).putShort((short) 0).putInt((int) blocks).putInt(12);
            Files.write(f.toPath(), b.array());
            String m = thrown(IOException.class, () -> { Sparse.toRaw(f, new File(W, "huge.raw"), null, null); return ""; });
            check(m.contains("مساحة التخزين غير كافية") && !new File(W, "huge.raw").exists(), m);
            return m;
        });
        t("Sparse: ملف كبير 2.2GB وإزاحات تتجاوز 2^31 (ذهاب وإياب)", "Raw منطقي 2.2GB: بيانات في البداية والنهاية وأصفار بينهما", "Sparse صغير جداً والعودة مطابقة للنهاية", () -> {
            File big = new File(W, "big.raw"); long size = 2200L << 20;
            try (RandomAccessFile r = new RandomAccessFile(big, "rw")) { r.setLength(size); r.write(rnd(4096, 11)); r.seek(size - 4096); r.write(rnd(4096, 12)); }
            long t0 = System.currentTimeMillis();
            File sp = new File(W, "big.sparse"); long sz = Sparse.fromRaw(big, sp, null, null);
            check(sz < 100000, "حجم Sparse كبير " + sz);
            File back = new File(W, "big.back"); long n = Sparse.toRaw(sp, back, null, null);
            check(n == size, "الحجم " + n);
            try (RandomAccessFile r = new RandomAccessFile(back, "r")) {
                byte[] x = new byte[4096]; r.readFully(x); check(Arrays.equals(x, rnd(4096, 11)), "البداية");
                r.seek(size - 4096); r.readFully(x); check(Arrays.equals(x, rnd(4096, 12)), "النهاية");
                r.seek(1L << 31); r.readFully(x); check(Arrays.equals(x, new byte[4096]), "المنتصف");
            }
            big.delete(); back.delete();
            return "Sparse " + sz + " بايت من 2.2GB في " + (System.currentTimeMillis() - t0) + "ms";
        });

        // ================= ext4 =================
        for (File img : new File[]{e4, e4n, e2}) {
            final File fi = img;
            t("ext4: سلامة الصورة واستخراج كامل مطابق (" + fi.getName() + (fi == e4 && csumDefault ? "، metadata_csum" : "") + ")", "صورة mke2fs 1.47 فيها 300 ملف + ملف 3MB + ملف بفجوات + عربي + رابط", "e2fsck نظيف والشجرة مطابقة", () -> {
                fsck(fi);
                File dst = new File(W, "out_" + fi.getName());
                try (Vsrc.FileV s = new Vsrc.FileV(fi)) {
                    Ext4.Report r = Ext4.open(s).extractAll(dst, null, null);
                    check(r.ok(), r.errors.toString());
                    treeEq(src, dst, new HashSet<>(), "");
                    return "ملفات=" + r.files + " روابط=" + (r.links + r.symlinkManifest) + " بايتات=" + r.bytes;
                }
            });
        }
        t("ext4: ميزات غير مدعومة (inline_data) وصورة مبتورة", "mke2fs -O inline_data ونصف صورة", "Unsupported / INCOMPLETE", () -> {
            String m = thrown(Io.Unsupported.class, () -> { try (Vsrc.FileV s = new Vsrc.FileV(inl)) { Ext4.open(s); } return ""; });
            File cut = new File(W, "ext4_cut.img"); Files.write(cut.toPath(), Arrays.copyOf(all(e4), (int) (e4.length() / 2)));
            Detect.Info i = Detect.detect(cut);
            check(i.status.equals(Detect.INCOMPLETE), i.status);
            return m + " | " + i.statusAr();
        });

        // ---- التعديل ----
        for (File base : new File[]{e4, e4n, e2}) {
            final File fb = base;
            t("ext4 تعديل: استبدال محتوى ملف بأقل/نفس الحجم (" + fb.getName() + ")", "build.prop: نص أقصر ثم نص بنفس الحجم ثم أطول ضمن الكتلة", "e2fsck نظيف بعد كل خطوة، والمحتوى صحيح، وباقي الملفات مطابقة", () -> {
                File img = new File(W, "edit_" + fb.getName()); Files.copy(fb.toPath(), img.toPath(), StandardCopyOption.REPLACE_EXISTING);
                byte[] shorter = "ro.build.type=userdebug\nro.secure=0\n".getBytes();
                Ext4Edit.replaceContent(img, "/system/build.prop", shorter); fsck(img);
                byte[] orig = all(new File(src, "system/build.prop"));
                byte[] same = orig.clone(); same[0] = 'R';
                Ext4Edit.replaceContent(img, "/system/build.prop", same); fsck(img);
                long cap = Ext4Edit.capacityOf(img, "/system/build.prop");
                byte[] fill = new byte[(int) cap]; Arrays.fill(fill, (byte) 'x');
                Ext4Edit.replaceContent(img, "/system/build.prop", fill); fsck(img);
                Ext4Edit.replaceContent(img, "/system/build.prop", shorter); fsck(img);
                File dst = new File(W, "outedit_" + fb.getName());
                try (Vsrc.FileV s = new Vsrc.FileV(img)) { Ext4.Report r = Ext4.open(s).extractAll(dst, null, null); check(r.ok(), r.errors.toString()); }
                check(Arrays.equals(all(new File(dst, "system/build.prop")), shorter), "المحتوى");
                treeEq(src, dst, new HashSet<>(Arrays.asList("/system/build.prop")), "");
                return "السعة " + cap + " بايت؛ كل الخطوات اجتازت e2fsck";
            });
        }
        t("ext4 تعديل: ملف أكبر من المساحة، مسار غير ملف، مسار غير موجود", "build.prop بحجم السعة+1، مجلد، ورابط، ومسار وهمي", "Unsupported / IOException وبدون أي تغيير في الصورة", () -> {
            File img = new File(W, "edit_err.img"); Files.copy(e4.toPath(), img.toPath(), StandardCopyOption.REPLACE_EXISTING);
            byte[] before = all(img);
            long cap = Ext4Edit.capacityOf(img, "/system/build.prop");
            String m1 = thrown(Io.Unsupported.class, () -> { Ext4Edit.replaceContent(img, "/system/build.prop", new byte[(int) cap + 1]); return ""; });
            String m2 = thrown(IOException.class, () -> { Ext4Edit.replaceContent(img, "/system", new byte[1]); return ""; });
            String m3 = thrown(IOException.class, () -> { Ext4Edit.replaceContent(img, "/no/such/file", new byte[1]); return ""; });
            check(Arrays.equals(before, all(img)), "الصورة تغيّرت!");
            return m1 + " | " + m2 + " | " + m3;
        });
        t("ext4 تعديل: الأذونات والمالك", "chmod 0750 وchown 1000:2000 على bin/tool.sh", "القيم تُقرأ صحيحة وe2fsck نظيف", () -> {
            for (File base : new File[]{e4, e4n}) {
                File img = new File(W, "perm_" + base.getName()); Files.copy(base.toPath(), img.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Ext4Edit.setMode(img, "/bin/tool.sh", 0750); Ext4Edit.setOwner(img, "/bin/tool.sh", 1000, 2000); fsck(img);
                try (Vsrc.FileV s = new Vsrc.FileV(img)) {
                    Ext4.Node n = Ext4.open(s).lookup("/bin/tool.sh");
                    check((n.mode & 0xFFF) == 0750 && n.isReg() && n.uid == 1000 && n.gid == 2000, Integer.toOctalString(n.mode) + " " + n.uid + ":" + n.gid);
                }
            }
            return "تم على الصورتين (مع metadata_csum وبدونه)";
        });

        // ================= super =================
        byte[] sysData = all(e4), vendData = rnd(3 << 20, 21), prodData = rnd(1 << 20, 22);
        List<PartSpec> specs = Arrays.asList(new PartSpec("system_a", sysData, 3), new PartSpec("vendor_a", vendData, 1), new PartSpec("product_a", prodData, 2), new PartSpec("system_b", new byte[0], 1));
        long extra = 40L << 20;
        File sup = new File(W, "super.img"); Files.write(sup.toPath(), buildSuper(specs, 65536, 2, 0, extra));
        t("super: قراءة الأقسام والتحقق (مع قسم بحجم صفر)", "super مبني حسب liblp v10.2: 3 أقسام + system_b فارغ", "الأقسام بأحجامها الصحيحة", () -> {
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) {
                Lp.Meta m = Lp.read(s);
                check(m.parts.size() == 4 && m.parts.get(0).size == sysData.length && m.parts.get(3).size == 0, "الأقسام");
                check(m.groups.size() == 1 && m.groups.get(0).name.equals("default"), "المجموعات");
                Detect.Info i = Detect.detect(sup);
                check(i.kind.equals("super") && i.status.equals(Detect.VALID), i.kind + i.status);
                return m.parts.size() + " أقسام، firstLogicalSector=" + m.firstLogicalSector + "، lbs=" + m.logicalBlockSize;
            }
        });
        t("super: إعادة تجميع بدون استبدال تحفظ كل البيانات", "repack(بلا بدائل)", "أقسام مطابقة بايتاً وMetadata صالحة", () -> {
            File out = new File(W, "super_same.img");
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { Lp.repack(s, Lp.read(s), new HashMap<>(), out, null, null); }
            try (Vsrc.FileV s = new Vsrc.FileV(out)) {
                Lp.Meta m = Lp.read(s);
                check(Arrays.equals(sha(Lp.open(s, m.parts.get(0)).read(0, sysData.length)), sha(sysData)), "system_a");
                check(Arrays.equals(sha(Lp.open(s, m.parts.get(1)).read(0, vendData.length)), sha(vendData)), "vendor_a");
                check(Arrays.equals(sha(Lp.open(s, m.parts.get(2)).read(0, prodData.length)), sha(prodData)), "product_a");
                check(m.parts.get(0).segs.size() == 1, "الامتدادات صارت متصلة");
            }
            return "مطابق؛ الحجم " + out.length() + " = " + sup.length();
        });
        t("super: استبدال قسم بملف أكبر وآخر أصغر ثم فك مجدداً", "vendor_a ← 5MB، product_a ← 700KB (غير مُحاذى)", "الأحجام مُقرَّبة لـ4096 والبيانات مطابقة والبقية سليمة", () -> {
            byte[] nv = rnd(5 << 20, 31), np = rnd(700 * 1000, 32);
            File fv = new File(W, "vendor_new.img"), fp = new File(W, "product_new.img"); Files.write(fv.toPath(), nv); Files.write(fp.toPath(), np);
            File out = new File(W, "super_repl.img");
            Map<String, File> repl = new HashMap<>(); repl.put("vendor_a", fv); repl.put("product_a", fp);
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { Lp.repack(s, Lp.read(s), repl, out, null, null); }
            try (Vsrc.FileV s = new Vsrc.FileV(out)) {
                Lp.Meta m = Lp.read(s);
                check(m.parts.get(1).size == 5L << 20 && m.parts.get(2).size == ((700000 + 4095) / 4096) * 4096L, "الأحجام " + m.parts.get(1).size + "/" + m.parts.get(2).size);
                check(Arrays.equals(Lp.open(s, m.parts.get(1)).read(0, nv.length), nv), "vendor_a");
                check(Arrays.equals(Lp.open(s, m.parts.get(2)).read(0, np.length), np), "product_a");
                byte[] tail = Lp.open(s, m.parts.get(2)).read(np.length, (int) m.parts.get(2).size - np.length);
                for (byte x : tail) check(x == 0, "الحشو ليس أصفاراً");
                check(Arrays.equals(sha(Lp.open(s, m.parts.get(0)).read(0, sysData.length)), sha(sysData)), "system_a سليم");
                File o = new File(W, "pv.img"); Lp.extract(s, m.parts.get(1), o, null, null); check(Arrays.equals(all(o), nv), "extract");
            }
            return "تمّ؛ الحجم " + out.length();
        });
        t("super: دورة كاملة: فك system_a → تعديل ملف داخل ext4 → إعادة تجميع → فك والتحقق", "build.prop داخل system_a", "الصورة النهائية فيها التعديل وe2fsck نظيف", () -> {
            File d = new File(W, "cyc"); d.mkdirs();
            File sys = new File(d, "system_a.img");
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { Lp.Meta m = Lp.read(s); Lp.extract(s, m.parts.get(0), sys, null, null); }
            File sysE = new File(d, "system_a_edited.img"); Files.copy(sys.toPath(), sysE.toPath());
            Ext4Edit.replaceContent(sysE, "/system/build.prop", "ro.build.type=userdebug\nro.secure=0\nro.debuggable=1\n".getBytes()); fsck(sysE);
            File out = new File(d, "super_new.img");
            Map<String, File> repl = new HashMap<>(); repl.put("system_a", sysE);
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { Lp.repack(s, Lp.read(s), repl, out, null, null); }
            File fin = new File(d, "system_final.img");
            try (Vsrc.FileV s = new Vsrc.FileV(out)) { Lp.Meta m = Lp.read(s); Lp.extract(s, m.parts.get(0), fin, null, null); }
            fsck(fin);
            try (Vsrc.FileV s = new Vsrc.FileV(fin)) {
                Ext4 e = Ext4.open(s); Ext4.Node n = e.lookup("/system/build.prop");
                check(new String(e.content(n).read(0, (int) n.size)).contains("ro.debuggable=1"), "التعديل غير موجود");
                Ext4.Report r = e.extractAll(new File(d, "final_files"), null, null);
                check(r.ok(), r.errors.toString());
                treeEq(src, new File(d, "final_files"), new HashSet<>(Arrays.asList("/system/build.prop")), "");
            }
            return "الدورة نجحت: الصورة النهائية اجتازت e2fsck وفيها التعديل وباقي الملفات مطابقة";
        });
        t("super: تجاوز حجم super أو حد المجموعة أو قسم مجهول", "قسم 100MB في super بمساحة ~45MB؛ مجموعة بحد 10MB؛ اسم وهمي", "IOException برسالة واضحة وبلا ناتج", () -> {
            File big = new File(W, "huge_part.img"); try (RandomAccessFile r = new RandomAccessFile(big, "rw")) { r.setLength(100L << 20); }
            File out = new File(W, "super_x.img");
            Map<String, File> r1 = new HashMap<>(); r1.put("vendor_a", big);
            String m1;
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { m1 = thrown(IOException.class, () -> { Lp.repack(s, Lp.read(s), r1, out, null, null); return ""; }); }
            File limited = new File(W, "super_lim.img"); Files.write(limited.toPath(), buildSuper(specs, 65536, 2, 10L << 20, extra));
            String m2;
            try (Vsrc.FileV s = new Vsrc.FileV(limited)) { m2 = thrown(IOException.class, () -> { Lp.repack(s, Lp.read(s), new HashMap<>(), out, null, null); return ""; }); }
            Map<String, File> r3 = new HashMap<>(); r3.put("no_such", big);
            String m3;
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { m3 = thrown(IOException.class, () -> { Lp.repack(s, Lp.read(s), r3, out, null, null); return ""; }); }
            check(!out.exists(), "بقي ناتج");
            check(m1.contains("أكبر من مساحة super") && m2.contains("يتجاوز حدها") && m3.contains("غير موجود"), m1 + "|" + m2 + "|" + m3);
            return m1 + " | " + m2 + " | " + m3;
        });
        t("super: كل الفتحات تُحدَّث، والنسخة الاحتياطية تنقذ عند تلف الأساسية", "تلف Metadata الأساسية بعد إعادة التجميع", "القراءة ناجحة من الاحتياطية (usedBackup)", () -> {
            File out = new File(W, "super_bk.img");
            try (Vsrc.FileV s = new Vsrc.FileV(sup)) { Lp.repack(s, Lp.read(s), new HashMap<>(), out, null, null); }
            byte[] b = all(out);
            byte[] s0 = Arrays.copyOfRange(b, 12288, 12288 + 600), s1 = Arrays.copyOfRange(b, 12288 + 65536, 12288 + 65536 + 600);
            check(Arrays.equals(s0, s1), "الفتحتان مختلفتان");
            b[12288 + 300] ^= 0x7F; Files.write(out.toPath(), b);
            try (Vsrc.FileV s = new Vsrc.FileV(out)) { Lp.Meta m = Lp.read(s); check(m.usedBackup && m.parts.size() == 4, "usedBackup=" + m.usedBackup); }
            return "الفتحتان متطابقتان، والاحتياطية تعمل";
        });
        t("super داخل Sparse: فك وتجميع (Sparse→super→Sparse)", "super مُرمَّز Sparse", "الاكتشاف يعرفها، التجميع ثم Raw→Sparse ثم الفك صحيح", () -> {
            File ssp = new File(W, "super.sparse"); Sparse.fromRaw(sup, ssp, null, null);
            Detect.Info i = Detect.detect(ssp);
            check(i.sparse && i.kind.equals("super"), i.kind);
            File raw = new File(W, "super_from_sparse.raw"); Sparse.toRaw(ssp, raw, null, null);
            File out = new File(W, "super_from_sparse_new.img"); File fv = new File(W, "vendor_new.img");
            Map<String, File> repl = new HashMap<>(); repl.put("vendor_a", fv);
            try (Vsrc.FileV s = new Vsrc.FileV(raw)) { Lp.repack(s, Lp.read(s), repl, out, null, null); }
            File osp = new File(W, "super_final.sparse"); Sparse.fromRaw(out, osp, null, null);
            try (Vsrc.FileV s = new Vsrc.FileV(osp)) {
                Vsrc.Ext e = Sparse.open(s); Lp.Meta m = Lp.read(e);
                check(Arrays.equals(Lp.open(e, m.parts.get(1)).read(0, 5 << 20), all(fv)), "vendor_a");
            }
            return "Sparse " + ssp.length() + " → تجميع → Sparse " + osp.length();
        });
        t("super: ملف ليس LP / مبتور", "بيانات عشوائية، وsuper مقصوص إلى 60%", "غير معروف / غير مكتملة", () -> {
            File r = new File(W, "random.img"); w(r, rnd(1 << 20, 77));
            Detect.Info i1 = Detect.detect(r); check(i1.kind.equals("unknown") && i1.detail.contains("غير معروف أو غير مدعوم"), i1.kind);
            File cut = new File(W, "super_cut.img"); byte[] b = all(sup); Files.write(cut.toPath(), Arrays.copyOf(b, (int) (b.length * 0.6)));
            Detect.Info i2 = Detect.detect(cut); check(i2.kind.equals("super") && !i2.status.equals(Detect.VALID), i2.status);
            return i1.detail + " | " + i2.statusAr() + ": " + i2.detail;
        });

        // ================= Detect متنوع =================
        t("Detect: boot / EROFS / F2FS / ملف فارغ", "boot v2 اصطناعية، 1024 صفر + توقيع EROFS، توقيع F2FS، ملف فارغ", "الأنواع صحيحة وبحالة مناسبة", () -> {
            File b = new File(W, "d_boot.img"); Files.write(b.toPath(), bootV2(rnd(3000, 1), rnd(2000, 2)));
            Detect.Info ib = Detect.detect(b); check(ib.kind.equals("boot") && ib.status.equals(Detect.VALID), ib.kind + ib.status);
            byte[] z = new byte[4096]; ByteBuffer.wrap(z).order(ByteOrder.LITTLE_ENDIAN).putInt(1024, 0xE0F5E1E2);
            File er = new File(W, "d_erofs.img"); Files.write(er.toPath(), z);
            Detect.Info ie = Detect.detect(er); check(ie.kind.equals("erofs") && ie.status.equals(Detect.UNSUPPORTED), ie.kind);
            byte[] z2 = new byte[4096]; ByteBuffer.wrap(z2).order(ByteOrder.LITTLE_ENDIAN).putInt(1024, 0xF2F52010);
            File ff = new File(W, "d_f2fs.img"); Files.write(ff.toPath(), z2);
            check(Detect.detect(ff).kind.equals("f2fs"), "f2fs");
            File em = new File(W, "d_empty.img"); em.createNewFile();
            check(Detect.detect(em).detail.equals("الملف فارغ"), "فارغ");
            return "boot=" + ib.statusAr() + "، EROFS معروف وغير مدعوم الاستخراج، F2FS معروف، فارغ";
        });

        StringBuilder md = new StringBuilder("# تقرير اختبارات محرك الصور (Sparse / super / ext4)\n\nالتاريخ: " + new Date() + "\nبيئة التشغيل: JVM " + System.getProperty("java.version") + " على Linux (ليس جهاز Android)\nصور ext4 أنشأتها أداة mke2fs 1.47 وفُحصت بـ e2fsck؛ صور super وboot اصطناعية بُنيت حسب مواصفة liblp/AOSP ولم تُجرَّب على صورة حقيقية من جهاز.\n\n");
        md.append("**النتيجة: ").append(pass).append(" نجح / ").append(fail).append(" فشل من ").append(pass + fail).append("**\n\n| # | الاختبار | البيانات | المتوقع | الفعلي | الحالة |\n|---|---|---|---|---|---|\n");
        int n = 1;
        for (String[] r : rows) md.append("| ").append(n++).append(" | ").append(esc(r[0])).append(" | ").append(esc(r[1])).append(" | ").append(esc(r[2])).append(" | ").append(esc(r[3])).append(" | ").append(r[4]).append(" |\n");
        Files.write(Paths.get(a[1]), md.toString().getBytes(StandardCharsets.UTF_8));
        for (String[] r : rows) System.out.println((r[4].equals("نجح") ? "PASS " : "FAIL ") + r[0] + (r[4].equals("نجح") ? "" : "\n     -> " + r[3]));
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
    static String esc(String s) { s = s.replace("|", "\\|").replace("\n", " "); return s.length() > 350 ? s.substring(0, 350) + "…" : s; }
}
