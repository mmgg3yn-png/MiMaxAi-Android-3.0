import com.mimax.ai.engine.LogoBin;
import com.mimax.ai.engine.Io;
import com.mimax.ai.engine.ArchivePack;
import com.mimax.ai.engine.Archives;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

/** JVM round-trip tests for the MTK logo.bin table and raw compressed image preservation. */
public final class LogoBinTest {
    static void le(OutputStream o, long v) throws IOException {
        o.write((int)v & 255); o.write((int)(v >>> 8) & 255); o.write((int)(v >>> 16) & 255); o.write((int)(v >>> 24) & 255);
    }
    static void be(OutputStream o, long v) throws IOException {
        o.write((int)(v >>> 24) & 255); o.write((int)(v >>> 16) & 255); o.write((int)(v >>> 8) & 255); o.write((int)v & 255);
    }
    static byte[] zip(byte[] in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(b)) { d.write(in); }
        return b.toByteArray();
    }
    static byte[] blob(File f, int id) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.seek(512 + 8 + (id - 1) * 4L);
            long a = Integer.reverseBytes(r.readInt()) & 0xffffffffL;
            long z;
            if (id == LogoBin.inspect(f).count) z = LogoBin.inspect(f).blockSize;
            else { long pos = r.getFilePointer(); z = Integer.reverseBytes(r.readInt()) & 0xffffffffL; r.seek(pos); }
            byte[] out = new byte[(int)(z - a)];
            r.seek(512 + a); r.readFully(out); return out;
        }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); if (root.exists()) del(root); root.mkdirs();
        byte[] a = zip("opaque logo slot 1 raw pixels".getBytes(StandardCharsets.UTF_8));
        byte[] b = zip("opaque logo slot 2 raw pixels and metadata".getBytes(StandardCharsets.UTF_8));
        long block = 8 + 2 * 4 + a.length + b.length;
        File original = new File(root, "original.logo.bin");
        try (BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(original))) {
            be(o, LogoBin.MAGIC); le(o, block);
            byte[] type = new byte[32]; System.arraycopy("LOGO".getBytes(StandardCharsets.US_ASCII), 0, type, 0, 4); o.write(type);
            for (int i = 0; i < 472; i++) o.write(255);
            le(o, 2); le(o, block); le(o, 16); le(o, 16 + a.length);
            o.write(a); o.write(b);
        }
        LogoBin.Info info = LogoBin.inspect(original);
        check(info.count == 2 && info.blockSize == block, "header/table parse");
        File project = new File(root, "project");
        LogoBin.extract(original, project, "bgrale", null, null);
        check(new File(project, "logo_001.raw.z").isFile() && new File(project, "logo_002.raw.z").isFile(), "raw slots extracted");
        File rebuilt = new File(root, "rebuilt.logo.bin");
        LogoBin.repack(project, rebuilt);
        check(LogoBin.inspect(rebuilt).count == 2, "rebuilt header");
        check(Arrays.equals(a, blob(rebuilt, 1)) && Arrays.equals(b, blob(rebuilt, 2)), "raw compressed blobs preserved exactly");
        File broken = new File(root, "broken.bin");
        byte[] bytes = Files.readAllBytes(original.toPath()); bytes[0] = 0; Files.write(broken.toPath(), bytes);
        boolean rejected = false;
        try { LogoBin.inspect(broken); } catch (IOException expected) { rejected = true; }
        check(rejected, "invalid magic rejected");
        File packSource = new File(root, "addon-source");
        File nested = new File(packSource, "assets/config.txt"); nested.getParentFile().mkdirs();
        Files.write(nested.toPath(), "Mi_Max package".getBytes(StandardCharsets.UTF_8));
        File addon = new File(root, "sample.mt1");
        ArchivePack.create(packSource, addon, "META-INF/mimax/addon.json",
                "{\\"format\\":\\"MiMaxAddon\\",\\"formatVersion\\":1}", null, null);
        java.util.List<Archives.Item> archiveItems = Archives.list(addon);
        check(archiveItems.stream().anyMatch(i -> i.name.equals("META-INF/mimax/addon.json")), "add-on metadata written");
        File extracted = new File(root, "addon-extracted");
        Archives.extract(addon, extracted, null, null, null);
        check(new File(extracted, "addon-source/assets/config.txt").isFile(), "created package extracts");
        System.out.println("LogoBinTest: 7 checks passed (logo parse/extract/repack/corruption + ZIP package create/metadata/extract)");
    }
    static void del(File f) throws IOException {
        File[] children = f.listFiles(); if (children != null) for (File c : children) del(c);
        if (!f.delete()) throw new IOException("cannot delete " + f);
    }
}
