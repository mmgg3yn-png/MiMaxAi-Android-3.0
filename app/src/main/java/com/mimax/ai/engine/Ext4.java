package com.mimax.ai.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** قارئ ext4 في مساحة المستخدم (بدون ROOT): استعراض، قراءة، واستخراج. التعديل في Ext4Edit. */
public final class Ext4 {

    public static class Node {
        public long ino;
        public int mode, flags, uid, gid;
        public long size, mtime;
        public byte[] iblock, raw;
        public boolean isDir() { return (mode & 0xF000) == 0x4000; }
        public boolean isReg() { return (mode & 0xF000) == 0x8000; }
        public boolean isLink() { return (mode & 0xF000) == 0xA000; }
    }

    public static class Entry { public String name; public long ino; public int ftype; }

    public static class Run { public long lb, len, pb; public boolean zero; }

    public static class Report {
        public long files, dirs, links, symlinkManifest, special, bytes;
        public List<String> errors = new ArrayList<>();
        public boolean ok() { return errors.isEmpty(); }
    }

    public static class Stats { public long files, dirs, links, special, bytes; }

    final Vsrc src;
    public int bs, isz, dsz, compat, incompat, roCompat;
    public long blocks, inodes, ipg, firstData, gdtBlock;
    public boolean is64;
    public String label = "";
    public byte[] uuid = new byte[16];
    public int csumSeed;

    private Ext4(Vsrc s) { this.src = s; }

    static final int[] BAD = {0x1, 0x8, 0x10, 0x100, 0x1000, 0x8000, 0x10000};
    static final String[] BAD_NAMES = {"compression", "journal_dev", "meta_bg", "mmp", "dirdata", "inline_data", "encrypt"};

    public static boolean looksLikeExt(Vsrc s) {
        try { return s.size() >= 2048 && (s.le(1080, 2).getShort(0) & 0xFFFF) == 0xEF53; } catch (IOException e) { return false; }
    }

    public boolean hasMetadataCsum() { return (roCompat & 0x400) != 0; }

    public static Ext4 open(Vsrc s) throws IOException {
        if (s.size() < 2048) throw new Io.Corrupt("الملف أصغر من أن يكون ext4", true);
        ByteBuffer b = s.le(1024, 1024);
        if ((b.getShort(56) & 0xFFFF) != 0xEF53) throw new Io.Corrupt("توقيع ext4 غير موجود");
        Ext4 e = new Ext4(s);
        long logBs = b.getInt(24) & 0xFFFFFFFFL;
        if (logBs > 6) throw new Io.Corrupt("حجم كتلة ext4 غير صالح");
        e.bs = 1024 << (int) logBs;
        e.ipg = b.getInt(40) & 0xFFFFFFFFL;
        long bpg = b.getInt(32) & 0xFFFFFFFFL;
        e.firstData = b.getInt(20) & 0xFFFFFFFFL;
        e.inodes = b.getInt(0) & 0xFFFFFFFFL;
        long rev = b.getInt(76) & 0xFFFFFFFFL;
        e.isz = rev == 0 ? 128 : (b.getShort(88) & 0xFFFF);
        e.compat = b.getInt(92); e.incompat = b.getInt(96); e.roCompat = b.getInt(100);
        e.is64 = (e.incompat & 0x80) != 0;
        e.dsz = e.is64 ? (b.getShort(254) & 0xFFFF) : 32;
        e.blocks = (b.getInt(4) & 0xFFFFFFFFL) + (e.is64 ? (b.getInt(336) & 0xFFFFFFFFL) << 32 : 0);
        e.label = Io.cstr(b.array(), 120, 16);
        System.arraycopy(b.array(), 104, e.uuid, 0, 16);
        e.csumSeed = (e.incompat & 0x2000) != 0 ? b.getInt(0x270) : Ext4Edit.crc32c(-1, e.uuid, 0, 16);
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < BAD.length; i++) if ((e.incompat & BAD[i]) != 0) bad.append(bad.length() > 0 ? ", " : "").append(BAD_NAMES[i]);
        if (bad.length() > 0) throw new Io.Unsupported("نظام الملفات يستخدم ميزات ext4 غير مدعومة: " + bad);
        if (e.ipg == 0 || bpg == 0 || e.isz < 128 || e.isz > e.bs || (e.isz & (e.isz - 1)) != 0 || (e.is64 && e.dsz < 64) || e.inodes == 0 || e.blocks == 0)
            throw new Io.Corrupt("ترويسة ext4 تحتوي قيماً غير منطقية");
        if (e.blocks * e.bs > s.size())
            throw new Io.Corrupt("الصورة أقصر (" + s.size() + ") من حجم نظام الملفات المعلن (" + (e.blocks * e.bs) + ") — مبتورة", true);
        e.gdtBlock = e.firstData + 1;
        return e;
    }

    public long inodePos(long n) throws IOException {
        if (n < 1 || n > inodes) throw new Io.Corrupt("رقم inode خارج النطاق: " + n);
        long g = (n - 1) / ipg, idx = (n - 1) % ipg;
        ByteBuffer gd = src.le(gdtBlock * bs + g * dsz, dsz);
        long tbl = (gd.getInt(8) & 0xFFFFFFFFL) + (is64 ? (gd.getInt(0x28) & 0xFFFFFFFFL) << 32 : 0);
        return tbl * bs + idx * isz;
    }

    public Node inode(long n) throws IOException {
        byte[] raw = src.read(inodePos(n), isz);
        ByteBuffer d = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Node nd = new Node();
        nd.ino = n; nd.raw = raw;
        nd.mode = d.getShort(0) & 0xFFFF;
        nd.size = (d.getInt(4) & 0xFFFFFFFFL) | ((nd.mode & 0xF000) == 0x8000 ? (d.getInt(108) & 0xFFFFFFFFL) << 32 : 0);
        nd.mtime = d.getInt(16) & 0xFFFFFFFFL;
        nd.flags = d.getInt(32);
        nd.uid = (d.getShort(2) & 0xFFFF) | ((d.getShort(120) & 0xFFFF) << 16);
        nd.gid = (d.getShort(24) & 0xFFFF) | ((d.getShort(122) & 0xFFFF) << 16);
        nd.iblock = new byte[60];
        System.arraycopy(raw, 40, nd.iblock, 0, 60);
        return nd;
    }

    void walk(byte[] data, int base, List<Run> out, int depth) throws IOException {
        ByteBuffer d = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if ((d.getShort(base) & 0xFFFF) != 0xF30A) throw new Io.Corrupt("شجرة extents تالفة (توقيع خاطئ)");
        int n = d.getShort(base + 2) & 0xFFFF, dep = d.getShort(base + 6) & 0xFFFF;
        if (depth > 5) throw new Io.Corrupt("عمق شجرة extents غير منطقي");
        if (base + 12 + n * 12 > data.length) throw new Io.Corrupt("عدد مدخلات extents يتجاوز الكتلة");
        for (int i = 0; i < n; i++) {
            int o = base + 12 + i * 12;
            if (dep == 0) {
                Run r = new Run();
                r.lb = d.getInt(o) & 0xFFFFFFFFL;
                int len = d.getShort(o + 4) & 0xFFFF;
                if (len > 32768) { r.zero = true; len -= 32768; }
                r.len = len;
                r.pb = ((long) (d.getShort(o + 6) & 0xFFFF) << 32) | (d.getInt(o + 8) & 0xFFFFFFFFL);
                out.add(r);
            } else {
                long leaf = (d.getInt(o + 4) & 0xFFFFFFFFL) | ((long) (d.getShort(o + 8) & 0xFFFF) << 32);
                walk(src.read(leaf * bs, bs), 0, out, depth + 1);
            }
        }
    }

    void mapBlocks(Node nd, List<Run> out) throws IOException {
        ByteBuffer p = ByteBuffer.wrap(nd.iblock).order(ByteOrder.LITTLE_ENDIAN);
        long nb = (nd.size + bs - 1) / bs;
        long[] lb = {0};
        for (int i = 0; i < 12 && lb[0] < nb; i++) addBlk(out, lb[0]++, p.getInt(i * 4) & 0xFFFFFFFFL);
        for (int lvl = 1; lvl <= 3; lvl++) indirect(p.getInt((11 + lvl) * 4) & 0xFFFFFFFFL, lvl, lb, nb, out);
    }

    void indirect(long blk, int lvl, long[] lb, long nb, List<Run> out) throws IOException {
        if (lb[0] >= nb) return;
        long per = bs / 4;
        if (blk == 0) { long span = 1; for (int i = 0; i < lvl; i++) span *= per; lb[0] += span; return; }
        ByteBuffer d = ByteBuffer.wrap(src.read(blk * bs, bs)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < per && lb[0] < nb; i++) {
            long p = d.getInt(i * 4) & 0xFFFFFFFFL;
            if (lvl == 1) addBlk(out, lb[0]++, p); else indirect(p, lvl - 1, lb, nb, out);
        }
    }

    static void addBlk(List<Run> out, long lb, long pb) {
        if (pb == 0) return;
        if (!out.isEmpty()) {
            Run l = out.get(out.size() - 1);
            if (l.lb + l.len == lb && l.pb + l.len == pb && !l.zero) { l.len++; return; }
        }
        Run r = new Run(); r.lb = lb; r.len = 1; r.pb = pb; out.add(r);
    }

    /** امتدادات الملف مرتبة (extents أو block-map). */
    public List<Run> runs(Node nd) throws IOException {
        if ((nd.flags & 0x10000000) != 0) throw new Io.Unsupported("الملف يستخدم inline-data (غير مدعوم)");
        List<Run> runs = new ArrayList<>();
        if ((nd.flags & 0x80000) != 0) walk(nd.iblock, 0, runs, 0); else mapBlocks(nd, runs);
        runs.sort((a, b) -> Long.compare(a.lb, b.lb));
        return runs;
    }

    public Vsrc.Ext content(Node nd) throws IOException {
        List<Vsrc.Seg> segs = new ArrayList<>();
        for (Run r : runs(nd)) {
            long v = r.lb * bs, len = r.len * bs;
            if (v >= nd.size) continue;
            if (r.zero) { segs.add(new Vsrc.Seg(v, len, 0)); continue; }
            if ((r.pb + r.len) * bs > src.size()) throw new Io.Corrupt("امتداد ملف خارج حدود الصورة (inode " + nd.ino + ") — الصورة تالفة أو مبتورة", true);
            segs.add(new Vsrc.Seg(v, len, r.pb * bs));
        }
        return new Vsrc.Ext(src, segs, nd.size);
    }

    public List<Entry> list(Node dir) throws IOException {
        if (!dir.isDir()) throw new IOException("ليس مجلداً");
        if (dir.size > (256L << 20)) throw new Io.Corrupt("حجم المجلد غير منطقي");
        byte[] data = content(dir).read(0, (int) dir.size);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        List<Entry> out = new ArrayList<>();
        int o = 0;
        while (o + 8 <= data.length) {
            long ino = b.getInt(o) & 0xFFFFFFFFL;
            int rec = b.getShort(o + 4) & 0xFFFF, nl = data[o + 6] & 0xFF;
            if (rec < 8 || (rec & 3) != 0 || o + rec > data.length || 8 + nl > rec) throw new Io.Corrupt("مدخلات المجلد (inode " + dir.ino + ") تالفة عند الإزاحة " + o);
            if (ino != 0) {
                Entry e = new Entry();
                e.ino = ino;
                e.name = new String(data, o + 8, nl, StandardCharsets.UTF_8);
                e.ftype = data[o + 7] & 0xFF;
                if (!e.name.equals(".") && !e.name.equals("..")) out.add(e);
            }
            o += rec;
        }
        return out;
    }

    public String readLink(Node nd) throws IOException {
        if (nd.size < 60 && (nd.flags & 0x80000) == 0) return Io.cstr(nd.iblock, 0, (int) nd.size);
        return new String(content(nd).read(0, (int) nd.size), StandardCharsets.UTF_8);
    }

    public Node root() throws IOException {
        Node r = inode(2);
        if (!r.isDir()) throw new Io.Corrupt("inode الجذر (2) ليس مجلداً");
        return r;
    }

    public Node lookup(String path) throws IOException {
        Node cur = root();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            Node next = null;
            for (Entry e : list(cur)) if (e.name.equals(part)) { next = inode(e.ino); break; }
            if (next == null) throw new IOException("المسار غير موجود: " + path);
            cur = next;
        }
        return cur;
    }

    public Stats scan(Io.Cancel c) throws IOException {
        Stats s = new Stats();
        scanDir(root(), s, new HashSet<Long>(), c);
        return s;
    }

    void scanDir(Node d, Stats s, Set<Long> path, Io.Cancel c) throws IOException {
        if (!path.add(d.ino)) throw new Io.Corrupt("حلقة في بنية المجلدات (inode " + d.ino + ")");
        s.dirs++;
        for (Entry e : list(d)) {
            if (c != null) c.check();
            Node n = inode(e.ino);
            if (n.isDir()) scanDir(n, s, path, c);
            else if (n.isReg()) { s.files++; s.bytes += n.size; }
            else if (n.isLink()) s.links++;
            else s.special++;
        }
        path.remove(d.ino);
    }

    /** يستخرج كل المحتويات؛ أي ملف يفشل يُسجَّل في التقرير ولا يُتجاهل بصمت. */
    public Report extractAll(File dest, Io.Progress p, Io.Cancel c) throws IOException {
        Stats st = scan(c);
        dest.mkdirs();
        Io.checkSpace(dest, st.bytes);
        Report r = new Report();
        long[] done = {0};
        List<String> manifest = new ArrayList<>();
        extractDir(root(), dest, "", r, st.bytes, done, p, c, new HashSet<Long>(), manifest);
        if (!manifest.isEmpty()) Files.write(new File(dest, "_symlinks.txt").toPath(), String.join("\n", manifest).getBytes(StandardCharsets.UTF_8));
        if (r.errors.isEmpty() && (r.files != st.files || r.bytes != st.bytes))
            r.errors.add("عدم تطابق الإجماليات: المتوقع " + st.files + " ملف/" + st.bytes + " بايت، الفعلي " + r.files + "/" + r.bytes);
        return r;
    }

    /** يستخرج مجلداً أو ملفاً واحداً بمساره داخل الصورة. */
    public Report extractPath(String path, File dest, Io.Cancel c) throws IOException {
        Node n = lookup(path);
        Report r = new Report();
        dest.mkdirs();
        List<String> manifest = new ArrayList<>();
        if (n.isDir()) extractDir(n, new File(dest, path.equals("/") || path.isEmpty() ? "root" : new File(path).getName()), "", r, 0, new long[]{0}, null, c, new HashSet<Long>(), manifest);
        else {
            File f = new File(dest, new File(path).getName());
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(0); content(n).writeTo(raf, 0, c, null); raf.setLength(n.size); }
            r.files = 1; r.bytes = n.size;
        }
        return r;
    }

    void extractDir(Node d, File out, String rel, Report r, long total, long[] done, Io.Progress p, Io.Cancel c, Set<Long> path, List<String> manifest) throws IOException {
        if (!path.add(d.ino)) throw new Io.Corrupt("حلقة في بنية المجلدات (inode " + d.ino + ")");
        r.dirs++;
        out.mkdirs();
        for (Entry e : list(d)) {
            if (c != null) c.check();
            String nm = e.name, relPath = rel + "/" + nm;
            try {
                if (nm.isEmpty() || nm.equals(".") || nm.equals("..") || nm.indexOf('/') >= 0 || nm.indexOf('\0') >= 0) throw new IOException("اسم غير صالح");
                File f = Io.safeChild(out, nm);
                Node n = inode(e.ino);
                if (n.isDir()) extractDir(n, f, relPath, r, total, done, p, c, path, manifest);
                else if (n.isReg()) {
                    Vsrc.Ext ex = content(n);
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                        raf.setLength(0);
                        final long base = done[0];
                        ex.writeTo(raf, 0, c, (dn, tt) -> { if (p != null) p.on(base + dn, total); });
                        raf.setLength(n.size);
                    }
                    if (f.length() != n.size) throw new IOException("حجم الملف الناتج " + f.length() + " ≠ " + n.size);
                    done[0] += n.size;
                    r.files++; r.bytes += n.size;
                    f.setLastModified(n.mtime * 1000);
                } else if (n.isLink()) {
                    String target = readLink(n);
                    try { Files.createSymbolicLink(f.toPath(), Paths.get(target)); r.links++; }
                    catch (Throwable t) { manifest.add(relPath + " -> " + target); r.symlinkManifest++; }
                } else r.special++;
            } catch (Io.CancelledException ce) { throw ce; }
            catch (IOException ex) { r.errors.add(relPath + ": " + ex.getMessage()); }
        }
        path.remove(d.ino);
    }
}
