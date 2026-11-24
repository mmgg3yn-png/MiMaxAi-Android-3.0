package com.mimax.ai.engine;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** أساسيات المحرك: قراءة عشوائية، إلغاء، أخطاء مصنّفة، أدوات ملفات. Java خالص (يعمل على ARM32 وARM64). */
public final class Io {
    private Io() { }

    public interface Progress { void on(long done, long total); }

    public static class Cancel {
        public volatile boolean cancelled;
        public void check() throws CancelledException { if (cancelled) throw new CancelledException(); }
    }

    public static class CancelledException extends IOException {
        public CancelledException() { super("تم إلغاء العملية"); }
    }

    public static class Corrupt extends IOException {
        public final boolean truncated;
        public Corrupt(String m) { this(m, false); }
        public Corrupt(String m, boolean truncated) { super(m); this.truncated = truncated; }
    }

    public static class Unsupported extends IOException {
        public Unsupported(String m) { super(m); }
    }

    public static class FileSource implements Closeable {
        private final RandomAccessFile raf;
        public final long size;
        public FileSource(File f) throws IOException { raf = new RandomAccessFile(f, "r"); size = raf.length(); }
        public synchronized void readAt(long off, byte[] b, int bo, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > size)
                throw new Corrupt("قراءة خارج حدود الملف (الإزاحة " + off + "، الطول " + len + ") — الملف مبتور أو تالف", true);
            raf.seek(off);
            raf.readFully(b, bo, len);
        }
        public byte[] read(long off, int len) throws IOException { byte[] b = new byte[len]; readAt(off, b, 0, len); return b; }
        public byte[] readUpTo(long off, int len) throws IOException {
            long n = Math.min((long) len, size - off);
            return n <= 0 ? new byte[0] : read(off, (int) n);
        }
        public ByteBuffer le(long off, int len) throws IOException { return ByteBuffer.wrap(read(off, len)).order(ByteOrder.LITTLE_ENDIAN); }
        @Override public void close() throws IOException { raf.close(); }
    }

    public static void checkSpace(File dirOrFile, long need) throws IOException {
        File d = dirOrFile;
        while (d != null && !d.exists()) d = d.getParentFile();
        if (d == null) return;
        long free = d.getUsableSpace();
        long margin = Math.max(8L << 20, need / 100);
        if (free < need + margin)
            throw new IOException("مساحة التخزين غير كافية: المطلوب نحو " + human(need + margin) + " والمتاح " + human(free));
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = n;
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < 3);
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    /** يمنع الخروج من مجلد الوجهة (Zip Slip). */
    public static File safeChild(File root, String rel) throws IOException {
        if (rel == null || rel.isEmpty() || rel.indexOf('\0') >= 0) throw new IOException("اسم ملف غير صالح");
        File f = new File(root, rel.replace('\\', '/'));
        String rp = root.getCanonicalPath(), p = f.getCanonicalPath();
        if (!p.equals(rp) && !p.startsWith(rp + File.separator))
            throw new IOException("تم رفض مسار يخرج من مجلد الوجهة: " + rel);
        return f;
    }

    public static String cstr(byte[] b, int off, int max) {
        int e = off;
        while (e < off + max && e < b.length && b[e] != 0) e++;
        return new String(b, off, e - off, java.nio.charset.StandardCharsets.UTF_8);
    }
}
