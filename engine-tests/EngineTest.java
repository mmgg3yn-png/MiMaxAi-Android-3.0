import com.mimax.ai.engine.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** اختبارات المحرك (ZIP / cpio / LZ4 / boot / vendor_boot). تشغيل: java EngineTest <مجلد-عمل> <تقرير.md> */
public class EngineTest {
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
    static void treeEq(File a, File b) throws IOException {
        Set<String> na = new TreeSet<>(Arrays.asList(a.list())), nb = new TreeSet<>(Arrays.asList(b.list()));
        check(na.equals(nb), "أسماء مختلفة في " + a + ": " + na + " مقابل " + nb);
        for (String n : na) {
            File x = new File(a, n), y = new File(b, n);
            if (x.isDirectory()) { check(y.isDirectory(), "ليس مجلداً " + y); treeEq(x, y); }
            else { check(Arrays.equals(all(x), all(y)), "محتوى مختلف: " + y); }
        }
    }
    static byte[] cat(byte[]... p) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] x : p) o.write(x); return o.toByteArray(); }
    static byte[] pad(byte[] b, int page) { return Arrays.copyOf(b, (b.length + page - 1) / page * page); }
    static byte[] gz(byte[] b) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(b); } return o.toByteArray(); }
    static byte[] gunz(byte[] b) throws IOException { return new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes(); }

    // ---- بناء cpio / LZ4 / boot اصطناعيين ----
    static byte[] cpio(Map<String, Object> entries) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int ino = 1;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            Object v = e.getValue();
            byte[] data = v == null ? new byte[0] : v instanceof String ? ((String) v).getBytes() : (byte[]) v;
            int mode = v == null ? 040755 : v instanceof String ? 0120777 : 0100644;
            cpioEntry(o, ino++, mode, e.getKey(), data);
        }
        cpioEntry(o, 0, 0, "TRAILER!!!", new byte[0]);
        return o.toByteArray();
    }
    static void cpioEntry(ByteArrayOutputStream o, int ino, int mode, String name, byte[] data) throws IOException {
        byte[] nb = (name + "\0").getBytes();
        o.write(String.format("070701%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X%08X", ino, mode, 0, 0, 1, 0, data.length, 0, 0, 0, 0, nb.length, 0).getBytes());
        o.write(nb); o.write(new byte[(4 - ((110 + nb.length) % 4)) % 4]);
        o.write(data); o.write(new byte[(4 - data.length % 4) % 4]);
    }
    static byte[] lz4Block(byte[] s, int off, int n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int lit = Math.min(n, 20), rem = n - lit, mlen = Math.min(rem, 16);
        boolean match = rem >= 8;
        if (match) for (int i = 0; i < mlen; i++) if (s[off + lit + i] != s[off + lit + i - lit]) { match = false; break; }
        if (!match) { seq(o, s, off, n, -1, 0); return o.toByteArray(); }
        seq(o, s, off, lit, lit, mlen);
        seq(o, s, off + lit + mlen, n - lit - mlen, -1, 0);
        return o.toByteArray();
    }
    static void seq(ByteArrayOutputStream o, byte[] s, int off, int lit, int mo, int ml) {
        int tl = Math.min(lit, 15), tm = mo < 0 ? 0 : Math.min(ml - 4, 15);
        o.write((tl << 4) | tm);
        if (lit >= 15) { int r = lit - 15; while (r >= 255) { o.write(255); r -= 255; } o.write(r); }
        o.write(s, off, lit);
        if (mo >= 0) { o.write(mo & 0xFF); o.write(mo >> 8); if (ml - 4 >= 15) { int r = ml - 4 - 15; while (r >= 255) { o.write(255); r -= 255; } o.write(r); } }
    }
    static byte[] lz4Frame(byte[] plain, boolean indep) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{0x04, 0x22, 0x4D, 0x18}); o.write(0x40 | (indep ? 0x20 : 0)); o.write(0x40); o.write(0);
        for (int pos = 0; pos < plain.length; ) {
            int n = Math.min(plain.length - pos, 30000);
            byte[] blk = lz4Block(plain, pos, n);
            o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blk.length).array()); o.write(blk);
            pos += n;
        }
        o.write(new byte[4]);
        return o.toByteArray();
    }

    /** boot v0–v4 اصطناعية حسب تخطيط AOSP bootimg.h */
    static byte[] bootV(int ver, byte[] kernel, byte[] ramdisk, byte[] second, byte[] dtb, byte[] dtbo, String cmd) throws IOException {
        int page = ver >= 3 ? 4096 : 2048;
        byte[] hdr = new byte[page];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        h.put("ANDROID!".getBytes());
        h.putInt(8, kernel.length);
        long dtboOff = page + pad(kernel, page).length + pad(ramdisk, page).length + pad(second, page).length;
        if (ver >= 3) {
            h.putInt(12, ramdisk.length); h.putInt(16, (14 << 25) | (24 << 4) | 3); h.putInt(20, ver >= 4 ? 4128 : 4112); h.putInt(40, ver);
            h.position(44); h.put(cmd.getBytes());
        } else {
            h.putInt(16, ramdisk.length); h.putInt(24, second.length); h.putInt(36, page); h.putInt(40, ver);
            h.position(64); h.put(cmd.getBytes());
            h.position(48); h.put("testdev".getBytes());
            if (ver >= 1) { h.putInt(1632, dtbo.length); h.putLong(1636, dtbo.length > 0 ? dtboOff : 0); h.putInt(1644, 1648); }
            if (ver >= 2) h.putInt(1648, dtb.length);
        }
        return ver >= 3 ? cat(hdr, pad(kernel, page), pad(ramdisk, page))
                : cat(hdr, pad(kernel, page), pad(ramdisk, page), pad(second, page), pad(dtbo, page), pad(dtb, page));
    }
    static byte[] vendorV3(byte[] vr, byte[] dtb, String cmd) throws IOException {
        byte[] hdr = new byte[4096];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        h.put("VNDRBOOT".getBytes()); h.putInt(8, 3); h.putInt(12, 4096); h.putInt(24, vr.length);
        h.position(28); h.put(cmd.getBytes()); h.putInt(2096, 2112); h.putInt(2100, dtb.length);
        return cat(hdr, pad(vr, 4096), pad(dtb, 4096));
    }

    public static void main(String[] a) throws Exception {
        W = new File(a[0]); del(W); W.mkdirs();
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("init", "#!/system/bin/sh\necho hi\n".getBytes());
        tree.put("sbin", null); tree.put("sbin/adbd", rnd(5000, 32));
        tree.put("etc", null); tree.put("etc/ملف.rc", "service x\n".getBytes(StandardCharsets.UTF_8));
        tree.put("default.prop", "ro.secure=1\nro.debuggable=0\n".getBytes());
        byte[] cpioData = cpio(tree);
        byte[] kernel = rnd(3000, 31), dtb = rnd(700, 33), second = rnd(1500, 34), dtbo = rnd(900, 35);

        // ===== ZIP =====
        File zdir = new File(W, "zsrc");
        w(new File(zdir, "a.txt"), "A line\n".getBytes()); w(new File(zdir, "مجلد/ملف عربي.txt"), "عربي".getBytes(StandardCharsets.UTF_8));
        w(new File(zdir, "big.bin"), rnd(3 << 20, 51)); w(new File(zdir, "sub/deep/x.cfg"), "k=v\n".getBytes());
        File zip = new File(W, "t.zip");
        ProcessBuilder pb = new ProcessBuilder("/usr/bin/zip", "-qr", zip.getPath(), "."); pb.directory(zdir); pb.start().waitFor();
        t("ZIP: عرض المدخلات وأسماء عربية", "zip -r من النظام (4 ملفات + مجلدات)", "كل المدخلات ظاهرة بأسمائها", () -> {
            List<Archives.Item> l = Archives.list(zip);
            Set<String> n = new HashSet<>(); for (Archives.Item i : l) n.add(i.name);
            check(n.contains("مجلد/ملف عربي.txt") && n.contains("sub/deep/x.cfg") && n.contains("a.txt"), n.toString());
            return l.size() + " مدخلاً: " + n;
        });
        t("ZIP: معاينة مدخل بدون فك + استخراج كامل مطابق", "قراءة a.txt في الذاكرة ثم استخراج الكل", "المحتوى مطابق والشجرة مطابقة بايتاً", () -> {
            check(new String(Archives.read(zip, "a.txt", 1 << 20)).equals("A line\n"), "معاينة");
            Archives.Report r = Archives.extract(zip, new File(W, "zout"), null, null, null);
            check(r.errors.isEmpty(), r.errors.toString());
            treeEq(zdir, new File(W, "zout"));
            return "ملفات=" + r.files + " بايتات=" + r.bytes;
        });
        t("ZIP: استخراج ملفات محددة فقط", "only={a.txt}", "ملف واحد فقط", () -> {
            Archives.Report r = Archives.extract(zip, new File(W, "zone"), new HashSet<>(Arrays.asList("a.txt")), null, null);
            check(r.files == 1 && new File(W, "zone/a.txt").exists() && !new File(W, "zone/big.bin").exists(), "r.files=" + r.files);
            return "استُخرج a.txt فقط";
        });
        t("ZIP: أرشيف مبتور", "قص إلى نصف الحجم", "Corrupt", () -> {
            File cut = new File(W, "cut.zip"); byte[] b = all(zip); Files.write(cut.toPath(), Arrays.copyOf(b, b.length / 2));
            return thrown(Io.Corrupt.class, () -> { Archives.list(cut); return ""; });
        });
        t("ZIP: بيانات تالفة (CRC) تُكتشف", "قلب بايتات في منتصف الأرشيف", "test() والاستخراج يسجلان خطأ ولا يبقى ملف ناقص", () -> {
            File bad = new File(W, "badcrc.zip"); byte[] b = all(zip); b[b.length / 2] ^= 0x5A; b[b.length / 2 + 1] ^= 0x5A; Files.write(bad.toPath(), b);
            List<String> e1 = Archives.test(bad);
            check(!e1.isEmpty(), "test لم يكتشف");
            Archives.Report r = Archives.extract(bad, new File(W, "zbad"), null, null, null);
            check(!r.errors.isEmpty(), "extract لم يكتشف");
            return "اكتُشف: " + e1;
        });
        t("ZIP: منع Zip Slip", "../evil.txt و a/../../evil2.txt و /abs_evil.txt", "رفض الثلاثة وعدم الكتابة خارج الوجهة", () -> {
            File z = new File(W, "slip.zip");
            try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(z))) {
                for (String n : new String[]{"../evil.txt", "a/../../evil2.txt", "/abs_evil.txt", "ok.txt"}) { o.putNextEntry(new ZipEntry(n)); o.write("x".getBytes()); o.closeEntry(); }
            }
            File dst = new File(W, "slip_dst");
            Archives.Report r = Archives.extract(z, dst, null, null, null);
            check(!new File(W, "evil.txt").exists() && !new File(W, "evil2.txt").exists(), "كُتب ملف خارج المجلد!");
            check(new File(dst, "ok.txt").exists(), "ok.txt");
            return "رُفض: " + r.errors.size() + "، ok.txt استُخرج";
        });
        t("ZIP: تعديل مدخل نصي وحذف مدخل (إعادة كتابة)", "استبدال a.txt وحذف sub/deep/x.cfg، مع مدخل STORED", "أرشيف جديد صالح بأداة unzip، الأصل لم يُمس، STORED بقي STORED", () -> {
            File z2 = new File(W, "mix.zip");
            try (ZipOutputStream o = new ZipOutputStream(new FileOutputStream(z2))) {
                ZipEntry st = new ZipEntry("resources.arsc"); st.setMethod(ZipEntry.STORED); byte[] d = rnd(1000, 71);
                CRC32 c = new CRC32(); c.update(d); st.setSize(d.length); st.setCompressedSize(d.length); st.setCrc(c.getValue());
                o.putNextEntry(st); o.write(d); o.closeEntry();
                o.putNextEntry(new ZipEntry("a.txt")); o.write("old\n".getBytes()); o.closeEntry();
                o.putNextEntry(new ZipEntry("gone.txt")); o.write("bye".getBytes()); o.closeEntry();
            }
            byte[] before = all(z2);
            File out = new File(W, "mix_new.zip");
            Map<String, byte[]> rep = new HashMap<>(); rep.put("a.txt", "new content ✓\n".getBytes(StandardCharsets.UTF_8));
            Archives.rewrite(z2, rep, new HashSet<>(Arrays.asList("gone.txt")), out, null);
            sh("/usr/bin/unzip", "-tq", out.getPath());
            check(Arrays.equals(before, all(z2)), "الأصل تغيّر!");
            check(new String(Archives.read(out, "a.txt", 100), StandardCharsets.UTF_8).equals("new content ✓\n"), "المحتوى الجديد");
            check(Archives.list(out).stream().noneMatch(i -> i.name.equals("gone.txt")), "gone.txt باقٍ");
            check(Archives.list(out).stream().filter(i -> i.name.equals("resources.arsc")).findFirst().get().stored, "STORED لم يبقَ");
            check(Arrays.equals(Archives.read(out, "resources.arsc", 5000), rnd(1000, 71)), "محتوى STORED");
            return "الأرشيف الجديد سليم، " + Archives.list(out).size() + " مدخل";
        });
        t("ZIP: منع الكتابة فوق الأصل", "rewrite(src, ..., src)", "IOException", () ->
                thrown(IOException.class, () -> { Archives.rewrite(zip, new HashMap<>(), null, zip, null); return ""; }));
        t("GZIP: فك وضغط ومبتور", "gzip من النظام ثم قصّه", "فك مطابق، المبتور Corrupt وبلا ناتج", () -> {
            File raw = new File(W, "g.bin"); w(raw, rnd(2 << 20, 61));
            sh("/usr/bin/gzip", "-kf", raw.getPath());
            File out = new File(W, "g.out");
            Archives.gunzip(new File(W, "g.bin.gz"), out, null);
            check(Arrays.equals(all(out), all(raw)), "محتوى");
            File g2 = new File(W, "g2.gz"); Archives.gzip(raw, g2);
            check(Arrays.equals(gunz(all(g2)), all(raw)), "gzip() ثم فك");
            File cut = new File(W, "gcut.gz"); byte[] b = all(new File(W, "g.bin.gz")); Files.write(cut.toPath(), Arrays.copyOf(b, b.length / 2));
            String m = thrown(Io.Corrupt.class, () -> { Archives.gunzip(cut, new File(W, "gcut.out"), null); return ""; });
            check(!new File(W, "gcut.out").exists(), "بقي ناتج");
            return m;
        });

        // ===== cpio =====
        File cpioF = new File(W, "ram.cpio"); Files.write(cpioF.toPath(), cpioData);
        t("cpio: فهرسة وقراءة مدخل", "ramdisk اصطناعي بـ 6 مدخلات", "الأسماء والأحجام صحيحة وقراءة default.prop", () -> {
            List<Cpio.Entry> idx = Cpio.index(cpioF);
            Map<String, Cpio.Entry> m = new HashMap<>(); for (Cpio.Entry e : idx) m.put(e.name, e);
            check(idx.size() == 6 && m.containsKey("etc/ملف.rc") && m.get("sbin/adbd").size == 5000 && m.get("sbin").isDir(), idx.size() + "");
            check(new String(Cpio.read(cpioF, m.get("default.prop"), 1000)).equals("ro.secure=1\nro.debuggable=0\n"), "قراءة");
            return idx.size() + " مدخلاً";
        });
        t("cpio: تعديل ملف وإعادة البناء", "تعديل default.prop (ro.debuggable=1) وحذف init", "الأرشيف الجديد صالح ومحتوى باقي الملفات مطابق", () -> {
            List<Cpio.Entry> idx = Cpio.index(cpioF);
            File out = new File(W, "ram2.cpio");
            Map<String, byte[]> rep = new HashMap<>(); rep.put("default.prop", "ro.secure=0\nro.debuggable=1\nextra=1\n".getBytes());
            Cpio.rewrite(cpioF, idx, rep, new HashSet<>(Arrays.asList("init")), out);
            List<Cpio.Entry> i2 = Cpio.index(out);
            Map<String, Cpio.Entry> m = new HashMap<>(); for (Cpio.Entry e : i2) m.put(e.name, e);
            check(!m.containsKey("init") && i2.size() == 5, "الحذف");
            check(new String(Cpio.read(out, m.get("default.prop"), 1000)).contains("debuggable=1"), "التعديل");
            check(Arrays.equals(Cpio.read(out, m.get("sbin/adbd"), 9999), rnd(5000, 32)), "محتوى adbd");
            return "5 مدخلات، التعديل والحذف نجحا";
        });
        t("cpio: مبتور وتوقيع خاطئ", "قص الأرشيف، وملف عشوائي", "Corrupt لكليهما", () -> {
            File cut = new File(W, "ram_cut.cpio"); Files.write(cut.toPath(), Arrays.copyOf(cpioData, cpioData.length - 40));
            String m1 = thrown(Io.Corrupt.class, () -> { Cpio.index(cut); return ""; });
            File junk = new File(W, "junk.cpio"); w(junk, rnd(500, 3));
            String m2 = thrown(Io.Corrupt.class, () -> { Cpio.index(junk); return ""; });
            return m1 + " | " + m2;
        });
        t("cpio: استخراج الكل", "الأرشيف الاصطناعي", "ملفات الشجرة المتوقعة", () -> {
            int n = Cpio.extractAll(cpioF, new File(W, "ram_out"), null);
            check(n == 4 && Arrays.equals(all(new File(W, "ram_out/sbin/adbd")), rnd(5000, 32)), "n=" + n);
            return n + " ملفات (init, adbd, ملف.rc, default.prop)";
        });

        // ===== LZ4 =====
        t("LZ4 Frame: كتل مستقلة وغير مستقلة ونص متكرر", "إطارات مبنية بمشفّر اختبار يدوي (literals + match)", "فك مطابق", () -> {
            for (boolean indep : new boolean[]{true, false}) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                Lz4.decompress(new ByteArrayInputStream(lz4Frame(cpioData, indep)), o);
                check(Arrays.equals(o.toByteArray(), cpioData), "indep=" + indep);
            }
            byte[] rep = new byte[5000]; for (int i = 0; i < rep.length; i++) rep[i] = (byte) "abcdefghij".charAt(i % 10);
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] fr = lz4Frame(rep, true);
            Lz4.decompress(new ByteArrayInputStream(fr), o);
            check(Arrays.equals(o.toByteArray(), rep), "نص متكرر");
            return "3 حالات مطابقة، حجم المضغوط للمتكرر " + fr.length;
        });
        t("LZ4 Frame: بيانات مبتورة", "قص الإطار", "Corrupt", () -> {
            byte[] fr = lz4Frame(cpioData, true);
            return thrown(Io.Corrupt.class, () -> { Lz4.decompress(new ByteArrayInputStream(Arrays.copyOf(fr, fr.length / 2)), new ByteArrayOutputStream()); return ""; });
        });

        // ===== boot =====
        for (int ver : new int[]{0, 1, 2, 3, 4}) {
            final int v = ver;
            File img = new File(W, "boot_v" + v + ".img");
            Files.write(img.toPath(), bootV(v, kernel, gz(cpioData), v < 3 ? second : new byte[0], v == 2 ? dtb : new byte[0], v == 1 || v == 2 ? dtbo : new byte[0], "console=ttyS0 androidboot.x=1"));
            t("boot v" + v + ": قراءة الترويسة واستخراج المكونات", "صورة اصطناعية حسب bootimg.h: kernel + ramdisk(gzip)" + (v < 3 ? " + second" : ""), "kernel مطابق وملفات ramdisk المفرودة مطابقة", () -> {
                BootImg.Info bi = BootImg.parse(img);
                check(bi.version == v && bi.cmdline.equals("console=ttyS0 androidboot.x=1"), bi.version + "/" + bi.cmdline);
                File out = new File(W, "bout_v" + v);
                BootImg.Result r = BootImg.extract(img, bi, out, true, null);
                check(r.ok, r.lines.toString());
                check(Arrays.equals(all(new File(out, "kernel")), kernel), "kernel");
                if (v < 3) check(Arrays.equals(all(new File(out, "second.bin")), second), "second");
                if (v == 1 || v == 2) check(Arrays.equals(all(new File(out, "recovery_dtbo.bin")), dtbo), "dtbo");
                if (v == 2) check(Arrays.equals(all(new File(out, "dtb.bin")), dtb), "dtb");
                check(Arrays.equals(all(new File(out, "ramdisk_files/sbin/adbd")), rnd(5000, 32)), "adbd");
                return "مكونات=" + bi.comps.size() + " OS=" + bi.osVersion;
            });
            t("boot v" + v + ": تعديل سطر الأوامر بدون فك", "cmdline جديد بدون أي استخراج", "نسخة جديدة بالـ cmdline الجديد وبقية البايتات مطابقة", () -> {
                File out = new File(W, "boot_v" + v + "_cmd.img");
                BootImg.setCmdline(img, out, "console=ttyMSM0 androidboot.selinux=permissive");
                BootImg.Info bi = BootImg.parse(out);
                check(bi.cmdline.equals("console=ttyMSM0 androidboot.selinux=permissive"), bi.cmdline);
                byte[] a1 = all(img), b1 = all(out);
                int diff = 0; for (int i = 0; i < a1.length; i++) if (a1[i] != b1[i]) diff++;
                int hdrEnd = v >= 3 ? 1536 + 44 : 1632;
                for (int i = hdrEnd; i < a1.length; i++) check(a1[i] == b1[i], "تغيّر بايت خارج الترويسة عند " + i);
                return "اختلف " + diff + " بايتاً داخل الترويسة فقط";
            });
            t("boot v" + v + ": استبدال ramdisk (تعديل ملف داخله) بدون فك الصورة", "تعديل default.prop داخل ramdisk ثم gzip وإعادة البناء", "ramdisk جديد بالتعديل، kernel/second/dtb مطابقة", () -> {
                BootImg.Info bi = BootImg.parse(img);
                Object[] rc = BootImg.ramdiskCpio(img, bi, "ramdisk", new File(W, "cache_v" + v), null);
                File cpio = (File) rc[0];
                List<Cpio.Entry> idx = Cpio.index(cpio);
                Map<String, byte[]> rep = new HashMap<>(); rep.put("default.prop", "ro.secure=0\nro.debuggable=1\n".getBytes());
                File c2 = new File(W, "cache_v" + v + "/new.cpio"); Cpio.rewrite(cpio, idx, rep, null, c2);
                File gz2 = new File(W, "cache_v" + v + "/new.cpio.gz"); Archives.gzip(c2, gz2);
                File out = new File(W, "boot_v" + v + "_rd.img");
                BootImg.replaceRamdisk(img, out, gz2);
                BootImg.Info ni = BootImg.parse(out);
                File out2 = new File(W, "bout2_v" + v);
                BootImg.Result r = BootImg.extract(out, ni, out2, true, null);
                check(r.ok, r.lines.toString());
                check(new String(all(new File(out2, "ramdisk_files/default.prop"))).contains("debuggable=1"), "التعديل");
                check(Arrays.equals(all(new File(out2, "kernel")), kernel), "kernel");
                if (v < 3) check(Arrays.equals(all(new File(out2, "second.bin")), second), "second");
                if (v == 1 || v == 2) check(Arrays.equals(all(new File(out2, "recovery_dtbo.bin")), dtbo), "dtbo (يتحقق من تحديث recovery_dtbo_offset)");
                if (v == 2) check(Arrays.equals(all(new File(out2, "dtb.bin")), dtb), "dtb");
                check(Arrays.equals(all(new File(out2, "ramdisk_files/sbin/adbd")), rnd(5000, 32)), "adbd");
                return "الحجم الجديد " + out.length() + " (الأصل " + img.length() + ")";
            });
        }
        File vimg = new File(W, "vendor_boot_v3.img");
        Files.write(vimg.toPath(), vendorV3(gz(cpioData), dtb, "vcmd=1"));
        t("vendor_boot v3: استخراج + تعديل cmdline + استبدال ramdisk", "ramdisk gzip + dtb", "كل العمليات تنجح والبيانات مطابقة", () -> {
            BootImg.Info bi = BootImg.parse(vimg);
            check(bi.kind.equals("vendor_boot") && bi.cmdline.equals("vcmd=1"), bi.kind + bi.cmdline);
            File o1 = new File(W, "vb_out"); BootImg.Result r = BootImg.extract(vimg, bi, o1, true, null);
            check(r.ok && Arrays.equals(all(new File(o1, "dtb.bin")), dtb), r.lines.toString());
            File c1 = new File(W, "vb_cmd.img"); BootImg.setCmdline(vimg, c1, "vcmd=2 foo=bar");
            check(BootImg.parse(c1).cmdline.equals("vcmd=2 foo=bar"), "cmdline");
            Object[] rc = BootImg.ramdiskCpio(vimg, bi, "vendor_ramdisk", new File(W, "vb_cache"), null);
            File cpio = (File) rc[0]; Map<String, byte[]> rep = new HashMap<>(); rep.put("default.prop", "x=1\n".getBytes());
            File c2 = new File(W, "vb_cache/n.cpio"); Cpio.rewrite(cpio, Cpio.index(cpio), rep, null, c2);
            File g2 = new File(W, "vb_cache/n.gz"); Archives.gzip(c2, g2);
            File o2 = new File(W, "vb_rd.img"); BootImg.replaceRamdisk(vimg, o2, g2);
            File o3 = new File(W, "vb_out2"); BootImg.Result r2 = BootImg.extract(o2, BootImg.parse(o2), o3, true, null);
            check(r2.ok && new String(all(new File(o3, "vendor_ramdisk_files/default.prop"))).equals("x=1\n") && Arrays.equals(all(new File(o3, "dtb.bin")), dtb), r2.lines.toString());
            return "تمّت العمليات الثلاث";
        });
        t("vendor_boot v4: جدول ramdisks (قراءة)", "جدول بمدخلين + bootconfig", "أربعة مكونات بأسمائها وبياناتها", () -> {
            byte[] r1 = gz(cpioData), r2 = rnd(800, 42), dtb4 = rnd(300, 43), bc = "androidboot.x=1\n".getBytes();
            byte[] table = new byte[216];
            ByteBuffer tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            tb.putInt(0, r1.length); tb.putInt(4, 0); tb.putInt(8, 1); tb.position(12); tb.put("platform".getBytes());
            tb.putInt(108, r2.length); tb.putInt(112, r1.length); tb.putInt(116, 3); tb.position(120); tb.put("dlkm".getBytes());
            byte[] hdr = new byte[4096]; ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            h.put("VNDRBOOT".getBytes()); h.putInt(8, 4); h.putInt(12, 4096); h.putInt(24, r1.length + r2.length);
            h.putInt(2096, 2128); h.putInt(2100, dtb4.length); h.putInt(2112, table.length); h.putInt(2116, 2); h.putInt(2120, 108); h.putInt(2124, bc.length);
            File f = new File(W, "vendor_boot_v4.img"); Files.write(f.toPath(), cat(hdr, pad(cat(r1, r2), 4096), pad(dtb4, 4096), pad(table, 4096), pad(bc, 4096)));
            BootImg.Info bi = BootImg.parse(f);
            check(bi.comps.size() == 4, "العدد " + bi.comps.size());
            File out = new File(W, "vb4_out"); BootImg.Result r = BootImg.extract(f, bi, out, true, null);
            check(Arrays.equals(all(new File(out, "vendor_ramdisk_dlkm.bin")), r2) && Arrays.equals(all(new File(out, "bootconfig.bin")), bc), r.lines.toString());
            String m = thrown(Io.Unsupported.class, () -> { BootImg.replaceRamdisk(f, new File(W, "vb4_x.img"), f); return ""; });
            return "المكونات: " + bi.comps.size() + " | تعديل ramdisk في v4 → " + m;
        });
        t("boot: ramdisk بصيغة LZ4 (Frame) معاينة وفك", "ramdisk مضغوط LZ4 Frame", "فهرسة cpio ناجحة من خلال ramdiskCpio", () -> {
            File f = new File(W, "boot_lz4.img"); Files.write(f.toPath(), bootV(2, kernel, lz4Frame(cpioData, false), second, dtb, dtbo, "x"));
            Object[] rc = BootImg.ramdiskCpio(f, BootImg.parse(f), "ramdisk", new File(W, "cache_lz4"), null);
            check(rc[1].equals("lz4") && Cpio.index((File) rc[0]).size() == 6, rc[1] + "");
            return "ضغط=" + rc[1] + "، 6 مدخلات";
        });
        t("boot: ضغط غير مدعوم (xz) يُعلن بالاسم", "ramdisk مضغوط بأداة xz", "Unsupported يذكر xz", () -> {
            File c = new File(W, "xz.cpio"); Files.write(c.toPath(), cpioData); sh("/usr/bin/xz", "-kf", c.getPath());
            File f = new File(W, "boot_xz.img"); Files.write(f.toPath(), bootV(2, kernel, all(new File(W, "xz.cpio.xz")), second, dtb, dtbo, "x"));
            String m = thrown(Io.Unsupported.class, () -> { BootImg.ramdiskCpio(f, BootImg.parse(f), "ramdisk", new File(W, "cache_xz"), null); return ""; });
            check(m.contains("xz"), m);
            BootImg.Result r = BootImg.extract(f, BootImg.parse(f), new File(W, "bout_xz"), true, null);
            check(!r.ok && new File(W, "bout_xz/ramdisk.xz").exists(), "يجب الفشل مع بقاء الملف الخام");
            return m;
        });
        t("boot: صورة مبتورة وإصدار ترويسة غير معروف", "قص داخل ramdisk، و header_version=9", "مبتور يُعلَن، وUnsupported للإصدار", () -> {
            byte[] img = bootV(2, kernel, gz(cpioData), second, dtb, dtbo, "x");
            File f = new File(W, "boot_cut.img"); Files.write(f.toPath(), Arrays.copyOf(img, 2048 + 4096 + 20));
            BootImg.Info bi = BootImg.parse(f);
            check(bi.comps.stream().anyMatch(c -> c.truncated), "لم يُكتشف البتر");
            String m1 = thrown(Io.Corrupt.class, () -> { BootImg.replaceRamdisk(f, new File(W, "bc_x.img"), f); return ""; });
            byte[] img2 = img.clone(); img2[40] = 9; File g = new File(W, "boot_v9.img"); Files.write(g.toPath(), img2);
            String m2 = thrown(Io.Unsupported.class, () -> { BootImg.parse(g); return ""; });
            return m1 + " | " + m2;
        });
        t("boot: حماية الأصل (لا كتابة فوقه) وcmdline طويل", "dst == src، و cmdline بطول 3000", "IOException لكليهما", () -> {
            File f = new File(W, "boot_v2.img");
            String m1 = thrown(IOException.class, () -> { BootImg.setCmdline(f, f, "x"); return ""; });
            String m2 = thrown(IOException.class, () -> { BootImg.setCmdline(f, new File(W, "long.img"), "a".repeat(3000)); return ""; });
            check(!new File(W, "long.img").exists(), "بقي ملف");
            return m1 + " | " + m2;
        });

        StringBuilder md = new StringBuilder("# تقرير اختبارات محرك Mi_Max Ai (ZIP / cpio / LZ4 / boot)\n\nالتاريخ: " + new Date() + "\nبيئة التشغيل: JVM " + System.getProperty("java.version") + " على Linux (ليس جهاز Android)\n\n");
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
