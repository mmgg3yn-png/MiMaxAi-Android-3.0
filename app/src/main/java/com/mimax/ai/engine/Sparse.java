package com.mimax.ai.engine;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Android Sparse: قراءة وتحقق وتحويل إلى Raw، والتحويل من Raw إلى Sparse (مثل img2simg). */
public final class Sparse {
    private Sparse() { }
    public static final int MAGIC = 0xED26FF3A;
    static final int RAW = 0xCAC1, FILL = 0xCAC2, DONT_CARE = 0xCAC3, CRC32 = 0xCAC4;

    public static boolean isSparse(Vsrc s) throws IOException { return s.size() >= 4 && s.le(0, 4).getInt(0) == MAGIC; }

    public static Vsrc.Ext open(Vsrc file) throws IOException {
        long fsz = file.size();
        if (fsz < 28) throw new Io.Corrupt("ترويسة Sparse مبتورة (الملف أقصر من 28 بايت)", true);
        ByteBuffer h = file.le(0, 28);
        if (h.getInt(0) != MAGIC) throw new Io.Corrupt("ليست صورة Sparse");
        int major = h.getShort(4) & 0xFFFF, fileHdr = h.getShort(8) & 0xFFFF, chunkHdr = h.getShort(10) & 0xFFFF;
        long blk = h.getInt(12) & 0xFFFFFFFFL, totalBlks = h.getInt(16) & 0xFFFFFFFFL, totalChunks = h.getInt(20) & 0xFFFFFFFFL;
        if (major != 1) throw new Io.Unsupported("إصدار Sparse غير مدعوم: " + major);
        if (fileHdr < 28 || chunkHdr < 12) throw new Io.Corrupt("أحجام ترويسة Sparse غير صالحة");
        if (blk == 0 || blk % 4 != 0) throw new Io.Corrupt("حجم الكتلة في Sparse غير صالح: " + blk);
        List<Vsrc.Seg> segs = new ArrayList<>();
        long pos = fileHdr, vblocks = 0;
        for (long i = 0; i < totalChunks; i++) {
            if (pos + chunkHdr > fsz) throw new Io.Corrupt("صورة Sparse مبتورة: ترويسة الجزء " + i + " من " + totalChunks + " خارج الملف", true);
            ByteBuffer c = file.le(pos, 12);
            int type = c.getShort(0) & 0xFFFF;
            long csz = c.getInt(4) & 0xFFFFFFFFL, tsz = c.getInt(8) & 0xFFFFFFFFL;
            if (tsz < chunkHdr) throw new Io.Corrupt("حجم الجزء " + i + " غير صالح");
            long dataLen = tsz - chunkHdr, v = vblocks * blk, len = csz * blk;
            switch (type) {
                case RAW:
                    if (dataLen != len) throw new Io.Corrupt("حجم بيانات الجزء RAW رقم " + i + " لا يطابق عدد كتله");
                    if (pos + tsz > fsz) throw new Io.Corrupt("صورة Sparse مبتورة: بيانات الجزء RAW رقم " + i + " ناقصة", true);
                    segs.add(new Vsrc.Seg(v, len, pos + chunkHdr));
                    break;
                case FILL:
                    if (dataLen != 4) throw new Io.Corrupt("جزء FILL رقم " + i + " بحجم بيانات غير صحيح");
                    if (pos + tsz > fsz) throw new Io.Corrupt("صورة Sparse مبتورة عند جزء FILL رقم " + i, true);
                    segs.add(new Vsrc.Seg(v, len, file.le(pos + chunkHdr, 4).getInt(0)));
                    break;
                case DONT_CARE:
                    if (dataLen != 0) throw new Io.Corrupt("جزء DONT_CARE رقم " + i + " يحتوي بيانات");
                    segs.add(new Vsrc.Seg(v, len, 0));
                    break;
                case CRC32:
                    if (dataLen != 4 || csz != 0) throw new Io.Corrupt("جزء CRC رقم " + i + " غير صالح");
                    break;
                default:
                    throw new Io.Corrupt("نوع جزء غير معروف 0x" + Integer.toHexString(type) + " عند الجزء " + i);
            }
            if (type != CRC32) vblocks += csz;
            pos += tsz;
            if (pos > fsz) throw new Io.Corrupt("صورة Sparse مبتورة", true);
        }
        if (vblocks != totalBlks) throw new Io.Corrupt("مجموع كتل الأجزاء (" + vblocks + ") لا يطابق الترويسة (" + totalBlks + ")");
        return new Vsrc.Ext(file, segs, vblocks * blk);
    }

    /** Sparse → Raw فعلياً. عند الإلغاء أو الخطأ يُحذف الناتج. */
    public static long toRaw(File in, File out, Io.Progress p, Io.Cancel c) throws IOException {
        if (in.getCanonicalFile().equals(out.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الملف الأصلي");
        Vsrc.FileV fs = new Vsrc.FileV(in);
        boolean ok = false;
        try {
            Vsrc.Ext ex = open(fs);
            File dir = out.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            Io.checkSpace(dir, ex.size());
            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                raf.setLength(0);
                ex.writeTo(raf, 0, c, p);
                raf.setLength(ex.size());
            }
            if (out.length() != ex.size()) throw new IOException("فشل التحقق: حجم الناتج " + out.length() + " لا يطابق المتوقع " + ex.size());
            ok = true;
            return ex.size();
        } finally {
            fs.close();
            if (!ok) out.delete();
        }
    }

    // ---------------- Raw → Sparse ----------------
    /** يصنّف كتلة: 0 = أصفار، 1 = تعبئة بكلمة واحدة متكررة، 2 = بيانات. */
    static int classify(byte[] b, int n, int[] fillOut) {
        int w = ((b[0] & 0xFF)) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
        for (int i = 4; i < n; i += 4) {
            int x = ((b[i] & 0xFF)) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
            if (x != w) return 2;
        }
        fillOut[0] = w;
        return w == 0 ? 0 : 1;
    }

    /** يحوّل صورة Raw إلى Sparse: أصفار/أنماط → FILL، والباقي RAW. حجم الكتلة 4096. */
    public static long fromRaw(File raw, File out, Io.Progress p, Io.Cancel c) throws IOException {
        if (raw.getCanonicalFile().equals(out.getCanonicalFile())) throw new IOException("لا يمكن الكتابة فوق الملف الأصلي");
        final int blk = 4096;
        long size = raw.length(), blocks = (size + blk - 1) / blk;
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        Io.checkSpace(dir, size); // أسوأ حالة: لا أصفار
        boolean ok = false;
        // المرحلة 1: تحديد الأجزاء
        List<long[]> chunks = new ArrayList<>(); // {type, blocks, fillValue}
        try (RandomAccessFile r = new RandomAccessFile(raw, "r")) {
            byte[] b = new byte[blk];
            int[] fv = new int[1];
            long curType = -1, curFill = 0, curCnt = 0;
            for (long i = 0; i < blocks; i++) {
                if (c != null) c.check();
                int n = (int) Math.min(blk, size - i * blk);
                java.util.Arrays.fill(b, (byte) 0);
                r.readFully(b, 0, n);
                int k = classify(b, blk, fv);
                long type = k == 2 ? RAW : FILL, fill = k == 2 ? 0 : fv[0];
                boolean same = curCnt > 0 && curType == type && (type == RAW ? curCnt < (1 << 20) : curFill == fill);
                if (same) curCnt++;
                else {
                    if (curCnt > 0) chunks.add(new long[]{curType, curCnt, curFill});
                    curType = type; curFill = fill; curCnt = 1;
                }
                if (p != null && (i & 1023) == 0) p.on(i * blk, size * 2);
            }
            if (curCnt > 0) chunks.add(new long[]{curType, curCnt, curFill});
        }
        // المرحلة 2: الكتابة
        try (RandomAccessFile r = new RandomAccessFile(raw, "r");
             DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
            ByteBuffer h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(MAGIC).putShort((short) 1).putShort((short) 0).putShort((short) 28).putShort((short) 12)
                    .putInt(blk).putInt((int) blocks).putInt(chunks.size()).putInt(0);
            o.write(h.array());
            long done = 0;
            byte[] buf = new byte[blk * 256];
            for (long[] ch : chunks) {
                if (c != null) c.check();
                boolean isRaw = ch[0] == RAW;
                long dataLen = isRaw ? ch[1] * blk : 4;
                ByteBuffer ch0 = ByteBuffer.allocate(isRaw ? 12 : 16).order(ByteOrder.LITTLE_ENDIAN);
                ch0.putShort((short) ch[0]).putShort((short) 0).putInt((int) ch[1]).putInt((int) (12 + dataLen));
                if (!isRaw) ch0.putInt((int) ch[2]);
                o.write(ch0.array());
                if (isRaw) {
                    long left = ch[1] * blk;
                    while (left > 0) {
                        if (c != null) c.check();
                        int n = (int) Math.min(buf.length, left);
                        java.util.Arrays.fill(buf, 0, n, (byte) 0);
                        long avail = Math.max(0, Math.min(n, size - done));
                        if (avail > 0) { r.seek(done); r.readFully(buf, 0, (int) avail); }
                        o.write(buf, 0, n);
                        done += n; left -= n;
                        if (p != null) p.on(size + Math.min(done, size), size * 2);
                    }
                } else done += ch[1] * blk;
            }
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
        // تحقق: الناتج يُقرأ وحجمه الافتراضي صحيح
        try (Vsrc.FileV v = new Vsrc.FileV(out)) {
            Vsrc.Ext e = open(v);
            if (e.size() != blocks * blk) { out.delete(); throw new IOException("فشل التحقق من Sparse الناتج"); }
        }
        return out.length();
    }
}
