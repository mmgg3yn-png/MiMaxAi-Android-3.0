package com.mimax.ai.engine;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** مصدر بيانات افتراضي للقراءة العشوائية: ملف، أو مقاطع (Sparse / أقسام super / امتدادات ext4). */
public abstract class Vsrc implements Closeable {
    public abstract long size();
    public abstract void readAt(long off, byte[] b, int bo, int len) throws IOException;
    @Override public void close() throws IOException { }

    public byte[] read(long off, int len) throws IOException { byte[] b = new byte[len]; readAt(off, b, 0, len); return b; }
    public byte[] readUpTo(long off, int len) throws IOException {
        long n = Math.min((long) len, size() - off);
        return n <= 0 ? new byte[0] : read(off, (int) n);
    }
    public ByteBuffer le(long off, int len) throws IOException { return ByteBuffer.wrap(read(off, len)).order(ByteOrder.LITTLE_ENDIAN); }

    public static class FileV extends Vsrc {
        private final RandomAccessFile raf;
        private final long size;
        public FileV(File f) throws IOException { raf = new RandomAccessFile(f, "r"); size = raf.length(); }
        @Override public long size() { return size; }
        @Override public synchronized void readAt(long off, byte[] b, int bo, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > size)
                throw new Io.Corrupt("قراءة خارج حدود الملف (الإزاحة " + off + "، الطول " + len + ") — الصورة مبتورة أو تالفة", true);
            raf.seek(off);
            raf.readFully(b, bo, len);
        }
        @Override public void close() throws IOException { raf.close(); }
    }

    /** مقطع: بيانات من الأصل (src) أو أصفار/نمط تعبئة. */
    public static class Seg {
        public final long v, len, src;
        public final boolean zero;
        public final int fill;
        public Seg(long v, long len, long src) { this.v = v; this.len = len; this.src = src; this.zero = false; this.fill = 0; }
        public Seg(long v, long len, int fill) { this.v = v; this.len = len; this.src = -1; this.zero = true; this.fill = fill; }
    }

    /** مصدر مبني من مقاطع مرتبة حسب v. الفجوات تُقرأ أصفاراً. */
    public static class Ext extends Vsrc {
        public final Vsrc parent;
        public final List<Seg> segs;
        private final long size;
        public Ext(Vsrc parent, List<Seg> segs, long size) { this.parent = parent; this.segs = segs; this.size = size; }
        @Override public long size() { return size; }

        private int find(long off) {
            int lo = 0, hi = segs.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                Seg g = segs.get(mid);
                if (off < g.v) hi = mid - 1; else if (off >= g.v + g.len) lo = mid + 1; else return mid;
            }
            return -(lo + 1);
        }

        @Override public void readAt(long off, byte[] b, int bo, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > size)
                throw new Io.Corrupt("قراءة خارج حدود المصدر الافتراضي (الإزاحة " + off + "، الطول " + len + ")", true);
            while (len > 0) {
                int i = find(off), n;
                if (i < 0) {
                    int ins = -i - 1;
                    long next = ins < segs.size() ? segs.get(ins).v : size;
                    n = (int) Math.min(len, next - off);
                    java.util.Arrays.fill(b, bo, bo + n, (byte) 0);
                } else {
                    Seg g = segs.get(i);
                    long rel = off - g.v;
                    n = (int) Math.min(len, g.len - rel);
                    if (g.zero) {
                        if (g.fill == 0) java.util.Arrays.fill(b, bo, bo + n, (byte) 0);
                        else for (int k = 0; k < n; k++) b[bo + k] = (byte) (g.fill >>> (8 * (int) ((rel + k) & 3)));
                    } else parent.readAt(g.src + rel, b, bo, n);
                }
                off += n; bo += n; len -= n;
            }
        }

        /** يكتب كامل المحتوى إلى ملف (الأصفار تُترك فراغات ثم يُضبط الطول). */
        public void writeTo(RandomAccessFile raf, long dstOff, Io.Cancel c, Io.Progress p) throws IOException {
            byte[] buf = new byte[1 << 20];
            long done = 0;
            for (Seg g : segs) {
                if (g.zero && g.fill == 0) { done += g.len; }
                else if (g.zero) {
                    long left = g.len, rel = 0;
                    raf.seek(dstOff + g.v);
                    while (left > 0) {
                        if (c != null) c.check();
                        int n = (int) Math.min(buf.length, left);
                        for (int k = 0; k < n; k++) buf[k] = (byte) (g.fill >>> (8 * (int) ((rel + k) & 3)));
                        raf.write(buf, 0, n);
                        left -= n; rel += n; done += n;
                        if (p != null) p.on(done, size);
                    }
                } else {
                    for (long o = 0; o < g.len; ) {
                        if (c != null) c.check();
                        int n = (int) Math.min(buf.length, g.len - o);
                        parent.readAt(g.src + o, buf, 0, n);
                        raf.seek(dstOff + g.v + o);
                        raf.write(buf, 0, n);
                        o += n; done += n;
                        if (p != null) p.on(done, size);
                    }
                }
            }
            if (raf.length() < dstOff + size) raf.setLength(dstOff + size);
        }
    }
}
