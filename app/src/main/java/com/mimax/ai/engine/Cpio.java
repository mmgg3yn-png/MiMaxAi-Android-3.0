package com.mimax.ai.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** cpio بصيغة newc (070701/070702): فهرسة، قراءة مدخل، استخراج، وإعادة بناء مع استبدال ملفات. */
public final class Cpio {
    private Cpio() { }

    public static class Entry {
        public String name;
        public long mode, size, dataOff;
        long[] hdr = new long[13];
        public boolean isDir() { return (mode & 0xF000) == 0x4000; }
        public boolean isReg() { return (mode & 0xF000) == 0x8000; }
        public boolean isLink() { return (mode & 0xF000) == 0xA000; }
    }

    static long hex(byte[] h, int i) throws IOException {
        try { return Long.parseLong(new String(h, 6 + 8 * i, 8, StandardCharsets.US_ASCII), 16); }
        catch (NumberFormatException e) { throw new Io.Corrupt("ترويسة cpio تالفة"); }
    }

    /** يفهرس كل المدخلات دون تحميل البيانات في الذاكرة. */
    public static List<Entry> index(File f) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long pos = 0, len = r.length();
            byte[] h = new byte[110];
            boolean trailer = false;
            while (pos + 110 <= len) {
                r.seek(pos);
                r.readFully(h);
                String magic = new String(h, 0, 6, StandardCharsets.US_ASCII);
                if (!magic.equals("070701") && !magic.equals("070702")) throw new Io.Corrupt("توقيع cpio غير صالح عند " + pos + " (ليس newc)");
                Entry e = new Entry();
                for (int i = 0; i < 13; i++) e.hdr[i] = hex(h, i);
                e.mode = e.hdr[1]; e.size = e.hdr[6];
                long ns = e.hdr[11];
                if (ns < 1 || ns > 4096) throw new Io.Corrupt("طول اسم cpio غير منطقي");
                byte[] nb = new byte[(int) ns];
                r.readFully(nb);
                e.name = new String(nb, 0, nb.length - 1, StandardCharsets.UTF_8);
                long dataStart = (pos + 110 + ns + 3) & ~3L;
                e.dataOff = dataStart;
                pos = (dataStart + e.size + 3) & ~3L;
                if (e.name.equals("TRAILER!!!")) { trailer = true; break; }
                if (dataStart + e.size > len) throw new Io.Corrupt("أرشيف cpio مبتور أثناء " + e.name, true);
                out.add(e);
            }
            if (!trailer) throw new Io.Corrupt("أرشيف cpio بلا TRAILER (مبتور)", true);
        }
        return out;
    }

    public static byte[] read(File f, Entry e, long max) throws IOException {
        if (e.size > max) throw new IOException("الملف كبير (" + Io.human(e.size) + ") للعرض");
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) e.size];
            r.seek(e.dataOff);
            r.readFully(b);
            return b;
        }
    }

    public static String linkTarget(File f, Entry e) throws IOException { return new String(read(f, e, 4096), StandardCharsets.UTF_8); }

    public static int extractAll(File cpio, File dest, Io.Cancel c) throws IOException {
        List<Entry> idx = index(cpio);
        dest.mkdirs();
        int n = 0;
        try (RandomAccessFile r = new RandomAccessFile(cpio, "r")) {
            byte[] buf = new byte[1 << 16];
            for (Entry e : idx) {
                if (c != null) c.check();
                String rel = e.name;
                while (rel.startsWith("./")) rel = rel.substring(2);
                while (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.isEmpty() || rel.equals(".")) continue;
                File f = Io.safeChild(dest, rel);
                if (e.isDir()) { f.mkdirs(); }
                else if (e.isReg()) {
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
                        r.seek(e.dataOff);
                        for (long left = e.size; left > 0; ) {
                            int k = (int) Math.min(buf.length, left);
                            r.readFully(buf, 0, k);
                            o.write(buf, 0, k);
                            left -= k;
                        }
                    }
                    n++;
                } else if (e.isLink()) {
                    try {
                        if (f.getParentFile() != null) f.getParentFile().mkdirs();
                        java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(linkTarget(cpio, e)));
                    } catch (Throwable t) { /* نظام الملفات لا يدعم الروابط */ }
                }
            }
        }
        return n;
    }

    static void pad4(OutputStream o, long n) throws IOException { for (long i = 0; i < ((4 - (n & 3)) & 3); i++) o.write(0); }

    /** يعيد بناء الأرشيف مع استبدال محتوى ملفات (replace) أو حذف مدخلات (delete). */
    public static void rewrite(File src, List<Entry> idx, Map<String, byte[]> replace, java.util.Set<String> delete, File out) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(src, "r"); OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            for (Entry e : idx) {
                if (delete != null && delete.contains(e.name)) continue;
                byte[] rep = replace == null ? null : replace.get(e.name);
                long size = rep != null ? rep.length : e.size;
                byte[] nb = (e.name + "\0").getBytes(StandardCharsets.UTF_8);
                StringBuilder h = new StringBuilder("070701");
                for (int i = 0; i < 13; i++) {
                    long v = i == 6 ? size : i == 11 ? nb.length : i == 12 ? 0 : e.hdr[i];
                    h.append(String.format("%08X", v));
                }
                o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
                o.write(nb);
                pad4(o, 110 + nb.length);
                if (rep != null) o.write(rep);
                else {
                    r.seek(e.dataOff);
                    for (long left = e.size; left > 0; ) {
                        int k = (int) Math.min(buf.length, left);
                        r.readFully(buf, 0, k);
                        o.write(buf, 0, k);
                        left -= k;
                    }
                }
                pad4(o, size);
            }
            byte[] tn = "TRAILER!!!\0".getBytes(StandardCharsets.US_ASCII);
            StringBuilder h = new StringBuilder("070701");
            for (int i = 0; i < 13; i++) h.append(String.format("%08X", i == 4 ? 1 : i == 11 ? tn.length : 0));
            o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            o.write(tn);
            pad4(o, 110 + tn.length);
        }
    }
}
