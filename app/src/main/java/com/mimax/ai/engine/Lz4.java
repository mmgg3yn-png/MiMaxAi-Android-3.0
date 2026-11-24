package com.mimax.ai.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** فك ضغط LZ4: Frame و Legacy (يستخدمان في ramdisk). */
public final class Lz4 {
    private Lz4() { }
    public static final int FRAME_MAGIC = 0x184D2204, LEGACY_MAGIC = 0x184C2102;

    static int decodeBlock(byte[] src, int sp, int send, byte[] dst, int dp, int dmax) throws IOException {
        while (sp < send) {
            int token = src[sp++] & 0xFF, lit = token >>> 4;
            if (lit == 15) { int s; do { if (sp >= send) throw new Io.Corrupt("كتلة LZ4 تالفة"); s = src[sp++] & 0xFF; lit += s; } while (s == 255); }
            if (sp + lit > send || dp + lit > dmax) throw new Io.Corrupt("كتلة LZ4 تالفة (literals)");
            System.arraycopy(src, sp, dst, dp, lit);
            sp += lit; dp += lit;
            if (sp >= send) break;
            if (sp + 2 > send) throw new Io.Corrupt("كتلة LZ4 تالفة (offset)");
            int off = (src[sp] & 0xFF) | ((src[sp + 1] & 0xFF) << 8);
            sp += 2;
            if (off == 0 || off > dp) throw new Io.Corrupt("كتلة LZ4 تالفة (offset غير صالح)");
            int ml = token & 15;
            if (ml == 15) { int s; do { if (sp >= send) throw new Io.Corrupt("كتلة LZ4 تالفة"); s = src[sp++] & 0xFF; ml += s; } while (s == 255); }
            ml += 4;
            if (dp + ml > dmax) throw new Io.Corrupt("كتلة LZ4 تالفة (match)");
            for (int i = 0; i < ml; i++) { dst[dp] = dst[dp - off]; dp++; }
        }
        return dp;
    }

    static int u32(InputStream in, boolean eofOk) throws IOException {
        int b0 = in.read();
        if (b0 < 0) { if (eofOk) return -2; throw new Io.Corrupt("بيانات LZ4 مبتورة", true); }
        int b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b1 | b2 | b3) < 0) throw new Io.Corrupt("بيانات LZ4 مبتورة", true);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    static void full(InputStream in, byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) { int r = in.read(b, o, n - o); if (r < 0) throw new Io.Corrupt("بيانات LZ4 مبتورة", true); o += r; }
    }

    public static boolean isLz4(byte[] h) {
        if (h.length < 4) return false;
        int m = (h[0] & 0xFF) | ((h[1] & 0xFF) << 8) | ((h[2] & 0xFF) << 16) | ((h[3] & 0xFF) << 24);
        return m == FRAME_MAGIC || m == LEGACY_MAGIC;
    }

    public static void decompress(InputStream in, OutputStream out) throws IOException {
        int magic = u32(in, false);
        if (magic == LEGACY_MAGIC) {
            byte[] dst = new byte[8 << 20], cb = new byte[(8 << 20) + 1024];
            for (;;) {
                int sz = u32(in, true);
                if (sz == -2) return;
                if (sz == LEGACY_MAGIC) continue;
                if (sz <= 0 || sz > cb.length) throw new Io.Corrupt("حجم كتلة LZ4 Legacy غير صالح");
                full(in, cb, sz);
                out.write(dst, 0, decodeBlock(cb, 0, sz, dst, 0, dst.length));
            }
        }
        if (magic != FRAME_MAGIC) throw new Io.Corrupt("ليست بيانات LZ4");
        int flg = in.read(), bd = in.read();
        if (flg < 0 || bd < 0) throw new Io.Corrupt("ترويسة LZ4 مبتورة", true);
        if (((flg >> 6) & 3) != 1) throw new Io.Unsupported("إصدار LZ4 Frame غير مدعوم");
        boolean indep = ((flg >> 5) & 1) != 0, bchk = ((flg >> 4) & 1) != 0, csize = ((flg >> 3) & 1) != 0, cchk = ((flg >> 2) & 1) != 0;
        if ((flg & 1) != 0) throw new Io.Unsupported("LZ4 مع قاموس غير مدعوم");
        int bmax;
        switch ((bd >> 4) & 7) {
            case 4: bmax = 64 << 10; break;
            case 5: bmax = 256 << 10; break;
            case 6: bmax = 1 << 20; break;
            case 7: bmax = 4 << 20; break;
            default: throw new Io.Corrupt("حجم كتلة LZ4 غير صالح");
        }
        byte[] skip = new byte[8];
        if (csize) full(in, skip, 8);
        in.read();
        byte[] win = new byte[65536 + bmax], cb = new byte[bmax + 16];
        int dp = 0;
        for (;;) {
            int bsz = u32(in, false);
            if (bsz == 0) break;
            boolean unc = (bsz & 0x80000000) != 0;
            int len = bsz & 0x7FFFFFFF;
            if (len > bmax) throw new Io.Corrupt("كتلة LZ4 أكبر من الحد المعلن");
            full(in, cb, len);
            if (bchk) full(in, skip, 4);
            if (indep) dp = 0;
            int start = dp, end;
            if (unc) { System.arraycopy(cb, 0, win, dp, len); end = dp + len; }
            else end = decodeBlock(cb, 0, len, win, dp, win.length);
            out.write(win, start, end - start);
            if (!indep) { if (end > 65536) { System.arraycopy(win, end - 65536, win, 0, 65536); end = 65536; } dp = end; }
        }
        if (cchk) full(in, skip, 4);
    }
}
