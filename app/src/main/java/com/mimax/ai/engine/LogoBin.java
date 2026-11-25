package com.mimax.ai.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * MediaTek logo.bin reader/writer.
 * Layout: 512-byte MTK header, logo count, block size, offset table, then zlib blobs.
 * Raw .z files are retained beside PNG previews so unknown pixel layouts remain lossless.
 */
public final class LogoBin {
    private LogoBin() { }
    public static final int HEADER_SIZE = 512;
    public static final int MAGIC = 0x88168858;
    private static final long MAX_INFLATED = 96L << 20;
    private static final Pattern NAME = Pattern.compile("^logo_(\\d{3,})_(bgrale|bgrabe|rgbale|rgbabe|rgb565le|rgb565be)\\.png$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW = Pattern.compile("^logo_(\\d{3,})\\.raw\\.z$", Pattern.CASE_INSENSITIVE);

    public static final class Info {
        public int count;
        public long blockSize, fileSize;
        public String type;
    }

    private static final class Parsed {
        Info info;
        List<Long> offsets = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
    }

    public static boolean looksLike(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return r.length() >= HEADER_SIZE + 8 && r.readInt() == MAGIC;
        } catch (Exception e) { return false; }
    }

    public static Info inspect(File f) throws IOException { return parse(f).info; }

    private static Parsed parse(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long length = r.length();
            if (length < HEADER_SIZE + 8) throw new Io.Corrupt("ملف logo.bin أقصر من الترويسة", true);
            if (r.readInt() != MAGIC) throw new Io.Unsupported("توقيع MediaTek logo.bin غير موجود");
            long headerSize = readU32LE(r);
            byte[] type = new byte[32];
            r.readFully(type);
            String t = new String(type, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
            if (!t.equalsIgnoreCase("LOGO")) throw new Io.Unsupported("ملف MTK ليس من نوع LOGO: " + t);
            r.seek(HEADER_SIZE);
            long countL = readU32LE(r), blockSize = readU32LE(r);
            if (countL < 1 || countL > 4096) throw new Io.Corrupt("عدد صور logo.bin غير منطقي: " + countL);
            if (headerSize != blockSize) throw new Io.Corrupt("حجم الترويسة لا يطابق حجم جدول الصور");
            long tableBytes = 8L + countL * 4L;
            if (tableBytes > blockSize || HEADER_SIZE + tableBytes > length)
                throw new Io.Corrupt("جدول إزاحات الصور مبتور", true);
            Parsed p = new Parsed();
            p.info = new Info(); p.info.count = (int) countL; p.info.blockSize = blockSize; p.info.fileSize = length; p.info.type = t;
            long previous = tableBytes;
            for (int i = 0; i < countL; i++) {
                long off = readU32LE(r);
                if (off < tableBytes || off < previous || off > blockSize)
                    throw new Io.Corrupt("إزاحة صورة غير صالحة عند العنصر " + (i + 1));
                p.offsets.add(off);
                previous = off;
            }
            for (int i = 0; i < p.offsets.size(); i++) {
                long end = i + 1 < p.offsets.size() ? p.offsets.get(i + 1) : blockSize;
                long start = p.offsets.get(i);
                if (end < start || HEADER_SIZE + end > length)
                    throw new Io.Corrupt("بيانات الصورة " + (i + 1) + " تتجاوز نهاية الملف", true);
                p.ends.add(end);
            }
            return p;
        }
    }

    private static long readU32LE(RandomAccessFile r) throws IOException {
        int a = r.read(), b = r.read(), c = r.read(), d = r.read();
        if ((a | b | c | d) < 0) throw new IOException("ترويسة مبتورة");
        return (a & 255L) | ((b & 255L) << 8) | ((c & 255L) << 16) | ((d & 255L) << 24);
    }

    public static File extract(File source, File outDir, String mode, Io.Cancel cancel, Io.Progress progress) throws IOException {
        Parsed p = parse(source);
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الاستخراج");
        Io.checkSpace(outDir, p.info.blockSize);
        String color = normalizeMode(mode);
        try (RandomAccessFile r = new RandomAccessFile(source, "r")) {
            long total = p.info.count, done = 0;
            for (int i = 0; i < p.info.count; i++) {
                if (cancel != null) cancel.check();
                long start = HEADER_SIZE + p.offsets.get(i), len = p.ends.get(i) - p.offsets.get(i);
                if (len <= 0 || len > Integer.MAX_VALUE) throw new Io.Corrupt("حجم بيانات الصورة غير صالح: " + (i + 1));
                byte[] blob = new byte[(int) len];
                r.seek(start); r.readFully(blob);
                File raw = new File(outDir, String.format(Locale.US, "logo_%03d.raw.z", i + 1));
                try (FileOutputStream o = new FileOutputStream(raw)) { o.write(blob); }
                try {
                    byte[] pixels = inflate(blob);
                    int bpp = color.startsWith("rgb565") ? 2 : 4;
                    int[] wh = guessDimensions(pixels.length, bpp);
                    if (wh != null) {
                        Bitmap bitmap = toBitmap(pixels, wh[0], wh[1], color);
                        File png = new File(outDir, String.format(Locale.US, "logo_%03d_%s.png", i + 1, color));
                        try (FileOutputStream o = new FileOutputStream(png)) {
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, o)) throw new IOException("فشل حفظ PNG");
                        } finally { bitmap.recycle(); }
                    }
                } catch (Exception ignored) {
                    // Keep the exact compressed blob; unsupported pixel layout can still be repacked losslessly.
                }
                done++;
                if (progress != null) progress.on(done, total);
            }
        }
        String manifest = "Mi_Max Ai logo.bin project\nsource=" + source.getName() + "\ncount=" + p.info.count
                + "\nmode=" + color + "\nblockSize=" + p.info.blockSize + "\n";
        try (FileOutputStream o = new FileOutputStream(new File(outDir, "logo_project.txt"))) {
            o.write(manifest.getBytes(StandardCharsets.UTF_8));
        }
        return outDir;
    }

    private static byte[] inflate(byte[] blob) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InflaterInputStream in = new InflaterInputStream(new BufferedInputStream(new java.io.ByteArrayInputStream(blob)))) {
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) > 0) {
                if ((long) out.size() + n > MAX_INFLATED) throw new IOException("حجم الصورة بعد فك الضغط يتجاوز الحد الآمن");
                out.write(b, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static int[] guessDimensions(int bytes, int bpp) {
        if (bytes <= 0 || bytes % bpp != 0) return null;
        int[][] known = {{720,1600},{720,1560},{720,1520},{720,1440},{720,1280},{1080,2400},{1080,2340},
                {1080,2280},{1080,2160},{1080,1920},{1440,3200},{1440,3040},{1440,2960},{1440,2880},
                {1440,2560},{480,854},{480,800},{540,960},{600,1024},{640,1136},{750,1334},
                {28,28},{36,50},{50,36},{30,60},{60,30},{40,45},{45,40},{56,14},{14,56},{7,112},{112,7}};
        for (int[] d : known) if ((long) d[0] * d[1] * bpp == bytes) return d;
        long pixels = bytes / bpp;
        int bestW = 0, bestH = 0;
        for (int w = 1; (long) w * w <= pixels && w <= 8192; w++) {
            if (pixels % w != 0) continue;
            long h = pixels / w;
            if (h > 32768) continue;
            int ww = (int) w, hh = (int) h;
            if (hh > ww) {
                double ratio = (double) hh / ww;
                if (ratio >= 1.5 && ratio <= 2.5) { bestW = ww; bestH = hh; }
            }
        }
        return bestW == 0 ? null : new int[]{bestW, bestH};
    }

    private static Bitmap toBitmap(byte[] data, int width, int height, String mode) {
        int n = width * height;
        int[] colors = new int[n];
        if (mode.startsWith("rgb565")) {
            for (int i = 0, p = 0; i < n; i++, p += 2) {
                int v = mode.endsWith("le") ? ((data[p] & 255) | ((data[p + 1] & 255) << 8))
                        : (((data[p] & 255) << 8) | (data[p + 1] & 255));
                int rr = ((v >> 11) & 31) * 255 / 31, gg = ((v >> 5) & 63) * 255 / 63, bb = (v & 31) * 255 / 31;
                colors[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        } else {
            for (int i = 0, p = 0; i < n; i++, p += 4) {
                int c0 = data[p] & 255, c1 = data[p + 1] & 255, c2 = data[p + 2] & 255, c3 = data[p + 3] & 255;
                int rr = mode.startsWith("bgr") ? c2 : c0;
                int bb = mode.startsWith("bgr") ? c0 : c2;
                colors[i] = (c3 << 24) | (rr << 16) | (c1 << 8) | bb;
            }
        }
        Bitmap bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bm.setPixels(colors, 0, width, 0, 0, width, height);
        return bm;
    }

    private static byte[] fromBitmap(Bitmap src, String mode) throws IOException {
        Bitmap bm = src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        int w = bm.getWidth(), h = bm.getHeight();
        if ((long) w * h > (MAX_INFLATED / 2)) throw new IOException("الصورة كبيرة جداً لإعادة التجميع");
        int[] colors = new int[w * h];
        bm.getPixels(colors, 0, w, 0, 0, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int c : colors) {
            int a = (c >>> 24) & 255, r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
            if (mode.startsWith("rgb565")) {
                int v = ((r * 31 / 255) << 11) | ((g * 63 / 255) << 5) | (b * 31 / 255);
                if (mode.endsWith("le")) { out.write(v & 255); out.write((v >>> 8) & 255); }
                else { out.write((v >>> 8) & 255); out.write(v & 255); }
            } else {
                if (mode.startsWith("bgr")) { out.write(b); out.write(g); out.write(r); }
                else { out.write(r); out.write(g); out.write(b); }
                out.write(a);
            }
        }
        if (bm != src) bm.recycle();
        return out.toByteArray();
    }

    private static String normalizeMode(String mode) {
        String m = mode == null ? "bgrale" : mode.toLowerCase(Locale.ROOT);
        if (!m.matches("bgrale|bgrabe|rgbale|rgbabe|rgb565le|rgb565be")) return "bgrale";
        return m;
    }

    public static boolean isProject(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) if (f.getName().equals("logo_project.txt") || RAW.matcher(f.getName()).matches()) return true;
        return false;
    }

    public static File repack(File projectDir, File output) throws IOException {
        if (!isProject(projectDir)) throw new IOException("المجلد ليس مشروع logo.bin مفكوكاً بواسطة Mi_Max Ai");
        File[] files = projectDir.listFiles();
        if (files == null) throw new IOException("تعذر قراءة مجلد المشروع");
        int max = 0;
        for (File f : files) {
            Matcher m = RAW.matcher(f.getName());
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            Matcher p = NAME.matcher(f.getName());
            if (p.matches()) max = Math.max(max, Integer.parseInt(p.group(1)));
        }
        if (max < 1 || max > 4096) throw new IOException("لا توجد صور قابلة لإعادة التجميع");
        List<byte[]> blobs = new ArrayList<>();
        for (int id = 1; id <= max; id++) {
            String prefix = String.format(Locale.US, "logo_%03d", id);
            File png = null, raw = null;
            for (File f : files) {
                if (f.getName().startsWith(prefix + "_") && f.getName().toLowerCase(Locale.ROOT).endsWith(".png")) png = f;
                if (f.getName().equals(prefix + ".raw.z")) raw = f;
            }
            if (png != null) {
                Matcher m = NAME.matcher(png.getName());
                if (!m.matches()) throw new IOException("اسم صورة غير صالح: " + png.getName());
                Bitmap bm = BitmapFactory.decodeFile(png.getPath());
                if (bm == null) throw new IOException("تعذر قراءة الصورة: " + png.getName());
                String mode = normalizeMode(m.group(2));
                byte[] pixels;
                try { pixels = fromBitmap(bm, mode); } finally { bm.recycle(); }
                blobs.add(deflate(pixels));
            } else if (raw != null) {
                blobs.add(readAll(raw));
            } else {
                throw new IOException("الصورة رقم " + id + " مفقودة؛ أعد ملف PNG أو raw.z الخاص بها");
            }
        }
        long blockSize = 8L + blobs.size() * 4L;
        for (byte[] b : blobs) blockSize += b.length;
        if (blockSize > 0xFFFFFFFFL) throw new IOException("حجم logo.bin الناتج يتجاوز حد الصيغة");
        Io.checkSpace(output.getAbsoluteFile().getParentFile(), blockSize + HEADER_SIZE);
        File tmp = new File(output.getPath() + ".tmp");
        boolean ok = false;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            writeU32BE(out, MAGIC);
            writeU32LE(out, blockSize);
            byte[] type = new byte[32]; byte[] logo = "LOGO".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(logo, 0, type, 0, logo.length); out.write(type);
            for (int i = 0; i < 472; i++) out.write(0xFF);
            writeU32LE(out, blobs.size());
            writeU32LE(out, blockSize);
            long offset = 8L + blobs.size() * 4L;
            for (byte[] b : blobs) { writeU32LE(out, offset); offset += b.length; }
            for (byte[] b : blobs) out.write(b);
            ok = true;
        } finally { if (!ok) tmp.delete(); }
        if (output.exists() && !output.delete()) { tmp.delete(); throw new IOException("تعذر استبدال الملف الناتج"); }
        if (!tmp.renameTo(output)) { tmp.delete(); throw new IOException("تعذر حفظ logo.bin الناتج"); }
        inspect(output);
        return output;
    }

    private static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out)) { d.write(input); }
        return out.toByteArray();
    }

    private static byte[] readAll(File f) throws IOException {
        if (f.length() > Integer.MAX_VALUE) throw new IOException("ملف صورة خام أكبر من الحد");
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int o = 0, n;
            while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
            if (o != b.length) throw new IOException("ملف صورة خام مبتور");
        }
        return b;
    }

    private static void writeU32LE(java.io.OutputStream out, long v) throws IOException {
        out.write((int) v & 255); out.write((int) (v >>> 8) & 255); out.write((int) (v >>> 16) & 255); out.write((int) (v >>> 24) & 255);
    }
    private static void writeU32BE(java.io.OutputStream out, long v) throws IOException {
        out.write((int) (v >>> 24) & 255); out.write((int) (v >>> 16) & 255); out.write((int) (v >>> 8) & 255); out.write((int) v & 255);
    }
}
