package com.mimax.ai.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** قراءة ملفات كبيرة بصفحات، بحث عن تسلسل بايتات، وتطبيق تعديلات بايت-ببايت. بلا تحميل كامل في الذاكرة. */
public final class HexFile implements Closeable {
    private final RandomAccessFile raf;
    public final long size;

    public HexFile(File f) throws IOException { raf = new RandomAccessFile(f, "r"); size = raf.length(); }

    public synchronized byte[] read(long off, int len) throws IOException {
        if (off < 0 || off > size) throw new IOException("إزاحة خارج الملف");
        int n = (int) Math.min(len, size - off);
        byte[] b = new byte[Math.max(0, n)];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    /** يبحث عن pat ابتداءً من from. يعيد الإزاحة أو -1. */
    public long find(byte[] pat, long from, Io.Progress p, Io.Cancel c) throws IOException {
        if (pat.length == 0) return -1;
        final int CH = 1 << 20;
        byte[] buf = new byte[CH + pat.length];
        long pos = Math.max(0, from);
        while (pos < size) {
            if (c != null) c.check();
            int n = (int) Math.min(buf.length, size - pos);
            synchronized (this) { raf.seek(pos); raf.readFully(buf, 0, n); }
            for (int i = 0; i + pat.length <= n; i++) {
                int j = 0;
                while (j < pat.length && buf[i + j] == pat[j]) j++;
                if (j == pat.length) return pos + i;
            }
            if (n < buf.length) break;
            pos += CH;
            if (p != null) p.on(pos, size);
        }
        return -1;
    }

    @Override public void close() throws IOException { raf.close(); }

    public static byte[] parseHex(String s) throws IOException {
        String h = s.replaceAll("(?i)0x", "").replaceAll("[\\s,:-]", "");
        if (h.isEmpty() || h.length() % 2 != 0 || !h.matches("[0-9a-fA-F]+")) throw new IOException("تسلسل سداسي عشري غير صالح: " + s);
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static byte[] textBytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) sb.append(String.format("%02X", b[i]));
        return sb.toString();
    }

    /** يكتب نسخة جديدة من src مع تطبيق التعديلات (إزاحة → بايت). الأصل لا يُمس. */
    public static void saveCopy(File src, File dst, Map<Long, Byte> changes, Io.Progress p, Io.Cancel c) throws IOException {
        if (src.getCanonicalFile().equals(dst.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الأصل مباشرة");
        long size = src.length();
        for (long k : changes.keySet()) if (k < 0 || k >= size) throw new IOException("تعديل خارج حدود الملف عند " + k);
        Io.checkSpace(dst.getAbsoluteFile().getParentFile(), size);
        boolean ok = false;
        TreeMap<Long, Byte> ch = new TreeMap<>(changes);
        try (InputStream in = new BufferedInputStream(new FileInputStream(src), 1 << 20); OutputStream o = new BufferedOutputStream(new FileOutputStream(dst), 1 << 20)) {
            byte[] b = new byte[1 << 20];
            long pos = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                if (c != null) c.check();
                for (Map.Entry<Long, Byte> e : ch.subMap(pos, pos + n).entrySet()) b[(int) (e.getKey() - pos)] = e.getValue();
                o.write(b, 0, n);
                pos += n;
                if (p != null) p.on(pos, size);
            }
            ok = true;
        } finally { if (!ok) dst.delete(); }
        if (dst.length() != size) { dst.delete(); throw new IOException("فشل التحقق: حجم الناتج لا يطابق"); }
    }

    /** يطبّق التعديلات على الملف نفسه، مع نسخة .bak اختيارية قبلها. */
    public static void applyInPlace(File f, Map<Long, Byte> changes, boolean backup) throws IOException {
        long size = f.length();
        for (long k : changes.keySet()) if (k < 0 || k >= size) throw new IOException("تعديل خارج حدود الملف عند " + k);
        if (backup) {
            File bak = new File(f.getPath() + ".bak");
            if (!bak.exists()) {
                Io.checkSpace(f.getAbsoluteFile().getParentFile(), size);
                try (InputStream in = new FileInputStream(f); OutputStream o = new FileOutputStream(bak)) {
                    byte[] b = new byte[1 << 20];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
            }
        }
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            for (Map.Entry<Long, Byte> e : new TreeMap<>(changes).entrySet()) { r.seek(e.getKey()); r.write(e.getValue()); }
        }
    }
}
