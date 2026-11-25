package com.mimax.ai.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** ZIP (و JAR / APK / APKS / XAPK) وGZIP: عرض، استخراج، تحقق CRC، وإعادة كتابة مع تعديل مدخلات. */
public final class Archives {
    private Archives() { }

    public static class Item {
        public String name;
        public long size, packed, crc;
        public boolean dir, stored;
    }

    public static boolean isZip(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5);
        } catch (IOException e) { return false; }
    }

    public static List<Item> list(File f) throws IOException {
        List<Item> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Item i = new Item();
                i.name = e.getName(); i.size = e.getSize(); i.packed = e.getCompressedSize(); i.crc = e.getCrc();
                i.dir = e.isDirectory(); i.stored = e.getMethod() == ZipEntry.STORED;
                out.add(i);
            }
        } catch (ZipException e) {
            throw new Io.Corrupt("أرشيف ZIP تالف: " + e.getMessage());
        }
        return out;
    }

    /** يقرأ مدخلاً في الذاكرة (للمعاينة) مع تحقق CRC. */
    public static byte[] read(File f, String name, long max) throws IOException {
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new IOException("المدخل غير موجود: " + name);
            if (e.getSize() > max) throw new IOException("الملف كبير (" + Io.human(e.getSize()) + ") للمعاينة");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            CRC32 crc = new CRC32();
            try (InputStream in = z.getInputStream(e)) {
                byte[] b = new byte[1 << 15];
                int n;
                while ((n = in.read(b)) > 0) { bo.write(b, 0, n); crc.update(b, 0, n); }
            }
            if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) throw new Io.Corrupt("مجموع CRC لا يطابق: " + name);
            return bo.toByteArray();
        }
    }

    /** يستخرج مدخلاً واحداً إلى ملف. */
    public static void extractOne(File f, String name, File out) throws IOException {
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            ZipEntry e = z.getEntry(name);
            if (e == null) throw new IOException("المدخل غير موجود: " + name);
            copyEntry(z, e, out, null, null);
        }
    }

    static long copyEntry(ZipFile z, ZipEntry e, File out, Io.Cancel c, long[] progress) throws IOException {
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        boolean ok = false;
        CRC32 crc = new CRC32();
        long w = 0;
        try (InputStream in = z.getInputStream(e); OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                if (c != null) c.check();
                o.write(b, 0, n);
                crc.update(b, 0, n);
                w += n;
            }
            if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) throw new ZipException("مجموع CRC لا يطابق (الملف تالف)");
            if (e.getSize() >= 0 && w != e.getSize()) throw new ZipException("الحجم المفكوك لا يطابق المعلن");
            ok = true;
            return w;
        } finally {
            if (!ok) out.delete();
        }
    }

    public static class Report {
        public long files, dirs, bytes;
        public List<String> errors = new ArrayList<>();
    }

    /** يستخرج الكل أو المحدد (only). يمنع Zip Slip ويتحقق من CRC والمساحة. */
    public static Report extract(File f, File dest, Set<String> only, Io.Progress p, Io.Cancel c) throws IOException {
        Report r = new Report();
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            long total = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (only != null && !only.contains(e.getName())) continue;
                if (!e.isDirectory() && e.getSize() > 0) total += e.getSize();
            }
            dest.mkdirs();
            Io.checkSpace(dest, total);
            long done = 0;
            en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (only != null && !only.contains(e.getName())) continue;
                if (c != null) c.check();
                File out;
                try { out = Io.safeChild(dest, e.getName()); } catch (IOException ex) { r.errors.add(ex.getMessage()); continue; }
                if (e.isDirectory()) { out.mkdirs(); r.dirs++; continue; }
                try {
                    long w = copyEntry(z, e, out, c, null);
                    done += w; r.files++; r.bytes += w;
                    if (p != null) p.on(done, total);
                } catch (Io.CancelledException ce) {
                    throw ce;
                } catch (IOException ex) {
                    r.errors.add(e.getName() + ": " + ex.getMessage());
                }
            }
        } catch (ZipException e) {
            throw new Io.Corrupt("أرشيف ZIP تالف: " + e.getMessage());
        }
        return r;
    }

    /** يفحص كل المدخلات (CRC) ويعيد قائمة الأخطاء. */
    public static List<String> test(File f) throws IOException {
        List<String> errs = new ArrayList<>();
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            byte[] b = new byte[1 << 16];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                CRC32 crc = new CRC32();
                long w = 0;
                try (InputStream in = z.getInputStream(e)) {
                    int n;
                    while ((n = in.read(b)) > 0) { crc.update(b, 0, n); w += n; }
                    if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) errs.add(e.getName() + ": CRC لا يطابق");
                    else if (e.getSize() >= 0 && w != e.getSize()) errs.add(e.getName() + ": الحجم لا يطابق");
                } catch (IOException ex) { errs.add(e.getName() + ": " + ex.getMessage()); }
            }
        } catch (ZipException e) {
            throw new Io.Corrupt("أرشيف ZIP تالف: " + e.getMessage());
        }
        return errs;
    }

    /** يكتب نسخة جديدة من الأرشيف مع استبدال/حذف مدخلات. الأصل لا يُمس. المدخلات STORED تبقى STORED. */
    public static void rewrite(File src, Map<String, byte[]> replace, Set<String> delete, File out, Io.Cancel c) throws IOException {
        if (src.getCanonicalFile().equals(out.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الأصل مباشرة");
        Io.checkSpace(out.getAbsoluteFile().getParentFile(), src.length() + 1024);
        boolean ok = false;
        try (ZipFile z = new ZipFile(src, StandardCharsets.UTF_8);
             ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[1 << 16];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (c != null) c.check();
                String n = e.getName();
                if (delete != null && delete.contains(n)) continue;
                byte[] rep = replace == null ? null : replace.get(n);
                ZipEntry ne = new ZipEntry(n);
                if (e.getTime() != -1) ne.setTime(e.getTime());
                if (e.isDirectory()) { zo.putNextEntry(ne); zo.closeEntry(); continue; }
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    if (rep != null) {
                        CRC32 cr = new CRC32(); cr.update(rep);
                        ne.setSize(rep.length); ne.setCompressedSize(rep.length); ne.setCrc(cr.getValue());
                    } else {
                        ne.setSize(e.getSize()); ne.setCompressedSize(e.getSize()); ne.setCrc(e.getCrc());
                    }
                }
                zo.putNextEntry(ne);
                if (rep != null) zo.write(rep);
                else try (InputStream in = z.getInputStream(e)) {
                    int k;
                    while ((k = in.read(buf)) > 0) { if (c != null) c.check(); zo.write(buf, 0, k); }
                }
                zo.closeEntry();
            }
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
        List<String> errs = test(out);
        if (!errs.isEmpty()) { out.delete(); throw new Io.Corrupt("فشل التحقق من الأرشيف الناتج: " + errs.get(0)); }
    }

    // ---- TAR / TAR.GZ ----
    /** يستخرج tar أو tar.gz مع الحفاظ على بت التنفيذ. يمنع Zip Slip. */
    public static int extractTar(File f, boolean gz, File dest, Io.Cancel c) throws IOException {
        dest.mkdirs();
        int count = 0;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(f), 1 << 16);
             InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw) {
            byte[] h = new byte[512];
            String longName = null;
            int zeros = 0;
            for (;;) {
                if (c != null) c.check();
                int o = 0;
                while (o < 512) { int r = in.read(h, o, 512 - o); if (r < 0) break; o += r; }
                if (o == 0) break;
                if (o < 512) throw new Io.Corrupt("أرشيف tar مبتور", true);
                boolean allZero = true;
                for (byte b : h) if (b != 0) { allZero = false; break; }
                if (allZero) { if (++zeros >= 2) break; continue; }
                zeros = 0;
                long sum = 0;
                for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? 32 : (h[i] & 0xFF);
                String cs = new String(h, 148, 8, StandardCharsets.US_ASCII).replace("\0", "").trim();
                if (cs.isEmpty() || Long.parseLong(cs, 8) != sum) throw new Io.Corrupt("ترويسة tar تالفة (مجموع التحقق لا يطابق)");
                String name = Io.cstr(h, 0, 100), prefix = Io.cstr(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                char type = (char) (h[156] == 0 ? '0' : h[156]);
                long size = tarSize(h);
                long pad = (512 - size % 512) % 512;
                if (type == 'L') {
                    byte[] d = new byte[(int) size];
                    new java.io.DataInputStream(in).readFully(d);
                    skipN(in, pad);
                    longName = Io.cstr(d, 0, d.length);
                    continue;
                }
                if (type == 'x' || type == 'g' || type == 'K') { skipN(in, size + pad); continue; }
                if (longName != null) { name = longName; longName = null; }
                int mode = (int) tarOctal(h, 100, 8);
                File out = Io.safeChild(dest, name);
                if (type == '5' || name.endsWith("/")) { out.mkdirs(); continue; }
                if (type == '0' || type == '7') {
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        byte[] b = new byte[1 << 16];
                        for (long left = size; left > 0; ) {
                            int r = in.read(b, 0, (int) Math.min(b.length, left));
                            if (r < 0) throw new Io.Corrupt("أرشيف tar مبتور أثناء الملف " + name, true);
                            os.write(b, 0, r);
                            left -= r;
                        }
                    }
                    skipN(in, pad);
                    if ((mode & 0111) != 0) out.setExecutable(true, false);
                    count++;
                } else if (type == '2') {
                    String target = Io.cstr(h, 157, 100);
                    try { if (out.getParentFile() != null) out.getParentFile().mkdirs(); java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(target)); } catch (Throwable t) { /* غير مدعوم */ }
                    skipN(in, size + pad);
                } else skipN(in, size + pad);
            }
        } catch (ZipException e) {
            throw new Io.Corrupt("ملف tar.gz تالف: " + e.getMessage());
        }
        return count;
    }

    static long tarOctal(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) { long v = h[off] & 0x7F; for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xFF); return v; }
        String s = new String(h, off, len, StandardCharsets.US_ASCII).replace("\0", "").trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    static long tarSize(byte[] h) { return tarOctal(h, 124, 12); }

    static void skipN(InputStream in, long n) throws IOException {
        byte[] b = new byte[1 << 14];
        while (n > 0) { int r = in.read(b, 0, (int) Math.min(b.length, n)); if (r < 0) throw new Io.Corrupt("أرشيف tar مبتور", true); n -= r; }
    }

    // ---- GZIP ----
    public static void gunzip(File in, File out, Io.Cancel c) throws IOException {
        boolean ok = false;
        try (InputStream g = new GZIPInputStream(new BufferedInputStream(new FileInputStream(in)));
             OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = g.read(b)) > 0) { if (c != null) c.check(); o.write(b, 0, n); }
            ok = true;
        } catch (ZipException e) {
            throw new Io.Corrupt("بيانات gzip تالفة: " + e.getMessage());
        } catch (java.io.EOFException e) {
            throw new Io.Corrupt("بيانات gzip مبتورة", true);
        } finally {
            if (!ok) out.delete();
        }
    }

    public static void gzip(File in, File out) throws IOException {
        try (InputStream i = new BufferedInputStream(new FileInputStream(in));
             OutputStream o = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
        }
    }
}
