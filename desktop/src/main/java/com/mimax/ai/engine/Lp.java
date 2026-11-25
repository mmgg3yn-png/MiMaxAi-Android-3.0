package com.mimax.ai.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Android Logical Partitions (super.img): قراءة LP Metadata، استخراج الأقسام، وإعادة التجميع بأقسام بديلة. */
public final class Lp {
    private Lp() { }
    public static final int GEO_MAGIC = 0x616c4467, META_MAGIC = 0x414C5067;
    static final int RESERVED = 4096, GEO_SIZE = 4096;

    public static class Part {
        public String name;
        public int attrs, group;
        public long size;
        public List<Vsrc.Seg> segs = new ArrayList<>();
    }

    public static class Group { public String name; public long maxSize; }

    public static class Meta {
        public int major, minor, slotCount, logicalBlockSize;
        public long maxSize, deviceSize, blockDeviceSize, firstLogicalSector;
        public boolean usedBackup;
        public List<Part> parts = new ArrayList<>();
        public List<Group> groups = new ArrayList<>();
        byte[] header, tables, geometry;
        long po, pn, pe, eo, en, ee, go, gn, ge, bo, bn, be;
        public long totalSize() { long t = 0; for (Part p : parts) t += p.size; return t; }
    }

    public static boolean hasGeometry(Vsrc s) {
        try { return s.size() >= RESERVED + 4 && s.le(RESERVED, 4).getInt(0) == GEO_MAGIC; } catch (IOException e) { return false; }
    }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static boolean checksumOk(byte[] data, int csumOff) {
        byte[] stored = Arrays.copyOfRange(data, csumOff, csumOff + 32), copy = data.clone();
        Arrays.fill(copy, csumOff, csumOff + 32, (byte) 0);
        return Arrays.equals(stored, sha256(copy));
    }

    public static Meta read(Vsrc s) throws IOException {
        long size = s.size();
        if (size < RESERVED + 2 * GEO_SIZE + 512) throw new Io.Corrupt("الملف أصغر من أن يحتوي بيانات LP Metadata", true);
        ByteBuffer geo = null;
        byte[] geoBytes = null;
        String geoErr = null;
        for (long off : new long[]{RESERVED, RESERVED + GEO_SIZE}) {
            try {
                ByteBuffer g = s.le(off, 52);
                if (g.getInt(0) != GEO_MAGIC) { geoErr = "توقيع Geometry غير موجود"; continue; }
                int ssz = g.getInt(4);
                if (ssz < 52 || ssz > GEO_SIZE) { geoErr = "حجم Geometry غير صالح"; continue; }
                if (!checksumOk(s.read(off, ssz), 8)) { geoErr = "مجموع التحقق لـ Geometry غير صحيح"; continue; }
                geo = g;
                geoBytes = s.read(off, GEO_SIZE);
                break;
            } catch (Io.Corrupt e) { geoErr = e.getMessage(); }
        }
        if (geo == null) throw new Io.Corrupt("بيانات LP Geometry تالفة: " + geoErr);
        long maxSize = geo.getInt(40) & 0xFFFFFFFFL;
        int slots = geo.getInt(44), lbs = geo.getInt(48);
        if (maxSize < 512 || maxSize % 512 != 0 || slots < 1 || slots > 16) throw new Io.Corrupt("قيم Geometry غير منطقية (max=" + maxSize + ", slots=" + slots + ")");
        long primary = RESERVED + 2L * GEO_SIZE, backup = size - maxSize * slots;
        Meta m = null;
        String primErr = null;
        try { m = readMeta(s, primary, maxSize); } catch (Io.Unsupported e) { throw e; } catch (IOException e) { primErr = e.getMessage(); }
        if (m == null) {
            try { m = readMeta(s, backup, maxSize); m.usedBackup = true; }
            catch (Io.Unsupported e) { throw e; }
            catch (IOException e) { throw new Io.Corrupt("LP Metadata تالفة. الأساسية: " + primErr + " — الاحتياطية: " + e.getMessage(), e instanceof Io.Corrupt && ((Io.Corrupt) e).truncated); }
        }
        m.slotCount = slots; m.maxSize = maxSize; m.deviceSize = size; m.logicalBlockSize = lbs <= 0 ? 4096 : lbs; m.geometry = geoBytes;
        if (m.blockDeviceSize > size)
            throw new Io.Corrupt("الملف (" + size + " بايت) أقصر من حجم super المعلن في Metadata (" + m.blockDeviceSize + " بايت) — الصورة مبتورة", true);
        return m;
    }

    static Meta readMeta(Vsrc s, long off, long maxSize) throws IOException {
        if (off < 0 || off + 128 > s.size()) throw new Io.Corrupt("موضع Metadata خارج الملف", true);
        ByteBuffer h = s.le(off, 128);
        if (h.getInt(0) != META_MAGIC) throw new Io.Corrupt("توقيع LP Metadata غير موجود عند الإزاحة " + off);
        int major = h.getShort(4) & 0xFFFF, minor = h.getShort(6) & 0xFFFF;
        if (major != 10) throw new Io.Unsupported("إصدار LP Metadata غير مدعوم: " + major + "." + minor);
        long hs = h.getInt(8) & 0xFFFFFFFFL, ts = h.getInt(44) & 0xFFFFFFFFL;
        if (hs < 128 || hs > 4096 || ts > maxSize) throw new Io.Corrupt("أحجام ترويسة Metadata غير صالحة");
        byte[] hdr = s.read(off, (int) hs);
        if (!checksumOk(hdr, 12)) throw new Io.Corrupt("مجموع التحقق لترويسة Metadata غير صحيح");
        byte[] tables = s.read(off + hs, (int) ts);
        if (!Arrays.equals(Arrays.copyOfRange(hdr, 48, 80), sha256(tables))) throw new Io.Corrupt("مجموع التحقق لجداول Metadata غير صحيح");
        ByteBuffer H = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN), T = ByteBuffer.wrap(tables).order(ByteOrder.LITTLE_ENDIAN);
        Meta m = new Meta();
        m.header = hdr; m.tables = tables; m.major = major; m.minor = minor;
        m.po = H.getInt(80) & 0xFFFFFFFFL; m.pn = H.getInt(84) & 0xFFFFFFFFL; m.pe = H.getInt(88) & 0xFFFFFFFFL;
        m.eo = H.getInt(92) & 0xFFFFFFFFL; m.en = H.getInt(96) & 0xFFFFFFFFL; m.ee = H.getInt(100) & 0xFFFFFFFFL;
        m.go = H.getInt(104) & 0xFFFFFFFFL; m.gn = H.getInt(108) & 0xFFFFFFFFL; m.ge = H.getInt(112) & 0xFFFFFFFFL;
        m.bo = H.getInt(116) & 0xFFFFFFFFL; m.bn = H.getInt(120) & 0xFFFFFFFFL; m.be = H.getInt(124) & 0xFFFFFFFFL;
        if (m.pe < 52 || m.ee < 24 || (m.gn > 0 && m.ge < 48) || (m.bn > 0 && m.be < 64)) throw new Io.Corrupt("أحجام مدخلات الجداول غير صالحة");
        if (m.po + m.pn * m.pe > ts || m.eo + m.en * m.ee > ts || m.go + m.gn * m.ge > ts || m.bo + m.bn * m.be > ts) throw new Io.Corrupt("جداول Metadata تتجاوز حجمها");
        if (m.bn >= 1) { m.firstLogicalSector = T.getLong((int) m.bo); m.blockDeviceSize = T.getLong((int) m.bo + 16); }
        for (long i = 0; i < m.gn; i++) {
            int o = (int) (m.go + i * m.ge);
            Group g = new Group();
            g.name = Io.cstr(tables, o, 36); g.maxSize = T.getLong(o + 40);
            m.groups.add(g);
        }
        for (long i = 0; i < m.pn; i++) {
            int o = (int) (m.po + i * m.pe);
            Part p = new Part();
            p.name = Io.cstr(tables, o, 36); p.attrs = T.getInt(o + 36); p.group = T.getInt(o + 48);
            long first = T.getInt(o + 40) & 0xFFFFFFFFL, num = T.getInt(o + 44) & 0xFFFFFFFFL;
            if (first + num > m.en) throw new Io.Corrupt("فهرس امتدادات القسم " + p.name + " خارج الجدول");
            long v = 0;
            for (long j = 0; j < num; j++) {
                int x = (int) (m.eo + (first + j) * m.ee);
                long sectors = T.getLong(x), target = T.getLong(x + 12), len = sectors * 512L;
                int type = T.getInt(x + 8), srcDev = T.getInt(x + 20);
                if (type == 0) {
                    if (srcDev != 0) throw new Io.Unsupported("القسم " + p.name + " يستخدم جهاز كتل إضافياً (super موزع على عدة أجهزة) — غير مدعوم");
                    long src = target * 512L;
                    if (src + len > s.size()) throw new Io.Corrupt("امتداد القسم " + p.name + " يتجاوز نهاية الملف — الصورة مبتورة", true);
                    p.segs.add(new Vsrc.Seg(v, len, src));
                } else if (type == 1) p.segs.add(new Vsrc.Seg(v, len, 0));
                else throw new Io.Unsupported("نوع امتداد غير معروف (" + type + ") في القسم " + p.name);
                v += len;
            }
            p.size = v;
            m.parts.add(p);
        }
        return m;
    }

    public static Vsrc.Ext open(Vsrc dev, Part p) { return new Vsrc.Ext(dev, p.segs, p.size); }

    public static long extract(Vsrc dev, Part p, File out, Io.Progress pr, Io.Cancel c) throws IOException {
        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        Io.checkSpace(dir, p.size);
        boolean ok = false;
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);
            open(dev, p).writeTo(raf, 0, c, pr);
            raf.setLength(p.size);
            ok = true;
        } finally { if (!ok) out.delete(); }
        if (out.length() != p.size) { out.delete(); throw new IOException("فشل التحقق: حجم القسم " + p.name + " الناتج " + out.length() + " لا يطابق " + p.size); }
        return p.size;
    }

    static long align(long n, long a) { return (n + a - 1) / a * a; }

    /** الحجم المتوقع لقسم بعد الاستبدال (مقرَّباً لكتلة LP المنطقية). */
    public static long newSize(Meta m, Part p, File repl) { return repl == null ? p.size : align(repl.length(), m.logicalBlockSize); }

    /**
     * يبني super جديداً: يحتفظ بترويسة وGeometry والمجموعات وجهاز الكتل من الأصل،
     * ويعيد كتابة جدولي الأقسام والامتدادات بتخطيط متتابع، ويستبدل بيانات الأقسام الواردة في repl (اسم → ملف).
     * يُكتب Metadata نفسها لكل الفتحات (slots) في النسختين الأساسية والاحتياطية.
     */
    public static long repack(Vsrc dev, Meta m, Map<String, File> repl, File out, Io.Progress p, Io.Cancel c) throws IOException {
        for (String k : repl.keySet()) {
            boolean found = false;
            for (Part pt : m.parts) if (pt.name.equals(k)) found = true;
            if (!found) throw new IOException("القسم «" + k + "» غير موجود في super الأصلي");
            if (!repl.get(k).isFile()) throw new IOException("ملف القسم البديل غير موجود: " + repl.get(k));
        }
        int np = m.parts.size();
        long lbs = m.logicalBlockSize;
        long first = m.firstLogicalSector > 0 ? m.firstLogicalSector * 512 : align(RESERVED + 2L * GEO_SIZE + m.maxSize * m.slotCount, 1 << 20);
        long limit = m.deviceSize - m.maxSize * m.slotCount;
        long[] size = new long[np], off = new long[np];
        long cursor = first;
        for (int i = 0; i < np; i++) {
            Part pt = m.parts.get(i);
            size[i] = newSize(m, pt, repl.get(pt.name));
            if (size[i] > 0) { off[i] = cursor; cursor += size[i]; }
        }
        if (cursor > limit) throw new IOException("حجم الأقسام الجديدة (" + Io.human(cursor - first) + ") أكبر من مساحة super المتاحة (" + Io.human(limit - first) + ")");
        for (int g = 0; g < m.groups.size(); g++) {
            long sum = 0;
            for (int i = 0; i < np; i++) if (m.parts.get(i).group == g) sum += size[i];
            long mx = m.groups.get(g).maxSize;
            if (mx > 0 && sum > mx) throw new IOException("مجموع أقسام المجموعة «" + m.groups.get(g).name + "» (" + Io.human(sum) + ") يتجاوز حدها (" + Io.human(mx) + ")");
        }
        int nExt = 0;
        for (long s : size) if (s > 0) nExt++;
        byte[] partT = new byte[(int) (np * m.pe)], extT = new byte[(int) (nExt * m.ee)];
        ByteBuffer P = ByteBuffer.wrap(partT).order(ByteOrder.LITTLE_ENDIAN), E = ByteBuffer.wrap(extT).order(ByteOrder.LITTLE_ENDIAN);
        int ei = 0;
        for (int i = 0; i < np; i++) {
            System.arraycopy(m.tables, (int) (m.po + i * m.pe), partT, (int) (i * m.pe), (int) m.pe);
            int o = (int) (i * m.pe);
            P.putInt(o + 40, ei); P.putInt(o + 44, size[i] > 0 ? 1 : 0);
            if (size[i] > 0) {
                int x = (int) (ei * m.ee);
                E.putLong(x, size[i] / 512); E.putInt(x + 8, 0); E.putLong(x + 12, off[i] / 512); E.putInt(x + 20, 0);
                ei++;
            }
        }
        byte[] groupT = Arrays.copyOfRange(m.tables, (int) m.go, (int) (m.go + m.gn * m.ge));
        byte[] devT = Arrays.copyOfRange(m.tables, (int) m.bo, (int) (m.bo + m.bn * m.be));
        byte[] tables = new byte[partT.length + extT.length + groupT.length + devT.length];
        System.arraycopy(partT, 0, tables, 0, partT.length);
        System.arraycopy(extT, 0, tables, partT.length, extT.length);
        System.arraycopy(groupT, 0, tables, partT.length + extT.length, groupT.length);
        System.arraycopy(devT, 0, tables, partT.length + extT.length + groupT.length, devT.length);
        byte[] hdr = m.header.clone();
        ByteBuffer H = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        H.putInt(44, tables.length);
        System.arraycopy(sha256(tables), 0, hdr, 48, 32);
        H.putInt(80, 0); H.putInt(84, np); H.putInt(88, (int) m.pe);
        H.putInt(92, partT.length); H.putInt(96, nExt); H.putInt(100, (int) m.ee);
        H.putInt(104, partT.length + extT.length); H.putInt(108, (int) m.gn); H.putInt(112, (int) m.ge);
        H.putInt(116, partT.length + extT.length + groupT.length); H.putInt(120, (int) m.bn); H.putInt(124, (int) m.be);
        Arrays.fill(hdr, 12, 44, (byte) 0);
        System.arraycopy(sha256(hdr), 0, hdr, 12, 32);
        byte[] meta = new byte[hdr.length + tables.length];
        System.arraycopy(hdr, 0, meta, 0, hdr.length);
        System.arraycopy(tables, 0, meta, hdr.length, tables.length);
        if (meta.length > m.maxSize) throw new IOException("بيانات Metadata (" + meta.length + ") أكبر من الحد المخصص (" + m.maxSize + ")");

        File dir = out.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        long dataTotal = 0;
        for (long s : size) dataTotal += s;
        Io.checkSpace(dir, dataTotal + m.maxSize * m.slotCount * 2 + (1 << 20));
        boolean ok = false;
        try (RandomAccessFile o = new RandomAccessFile(out, "rw")) {
            o.setLength(0);
            o.setLength(m.deviceSize);
            o.seek(RESERVED); o.write(m.geometry);
            o.seek(RESERVED + GEO_SIZE); o.write(m.geometry);
            for (int s = 0; s < m.slotCount; s++) {
                o.seek(RESERVED + 2L * GEO_SIZE + s * m.maxSize); o.write(meta);
                o.seek(limit + s * m.maxSize); o.write(meta);
            }
            long[] done = {0};
            final long tot = Math.max(1, dataTotal);
            byte[] buf = new byte[1 << 20];
            for (int i = 0; i < np; i++) {
                if (size[i] == 0) continue;
                Part pt = m.parts.get(i);
                File rf = repl.get(pt.name);
                if (rf == null) {
                    final long base = done[0];
                    open(dev, pt).writeTo(o, off[i], c, (d, t) -> { if (p != null) p.on(base + d, tot); });
                } else {
                    try (InputStream in = new FileInputStream(rf)) {
                        long w = 0;
                        o.seek(off[i]);
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (c != null) c.check();
                            o.write(buf, 0, n);
                            w += n;
                            if (p != null) p.on(done[0] + w, tot);
                        }
                    }
                }
                done[0] += size[i];
            }
            ok = true;
        } finally { if (!ok) out.delete(); }
        // تحقق: القراءة من جديد + مطابقة الأقسام المستبدلة بايتاً ببايت
        try (Vsrc.FileV v = new Vsrc.FileV(out)) {
            Meta r = read(v);
            if (r.parts.size() != np) throw new IOException("فشل التحقق: عدد الأقسام");
            byte[] a = new byte[1 << 20], b = new byte[1 << 20];
            for (int i = 0; i < np; i++) {
                if (r.parts.get(i).size != size[i] || !r.parts.get(i).name.equals(m.parts.get(i).name))
                    throw new IOException("فشل التحقق: القسم " + m.parts.get(i).name + " بحجم/اسم غير مطابق");
                File rf = repl.get(m.parts.get(i).name);
                if (rf == null) continue;
                Vsrc.Ext pe = open(v, r.parts.get(i));
                try (InputStream in = new FileInputStream(rf)) {
                    long pos = 0;
                    int n;
                    while ((n = in.read(a)) > 0) {
                        pe.readAt(pos, b, 0, n);
                        if (!Arrays.equals(Arrays.copyOf(a, n), Arrays.copyOf(b, n))) throw new IOException("فشل التحقق: بيانات القسم " + m.parts.get(i).name + " لا تطابق الملف البديل");
                        pos += n;
                    }
                }
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return out.length();
    }
}
