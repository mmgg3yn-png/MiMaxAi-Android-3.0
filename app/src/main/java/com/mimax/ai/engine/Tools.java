package com.mimax.ai.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** مدير أدوات الطرفية: تثبيت ملف تنفيذي أو حزمة (zip / tar.gz) من رابط أو ملف، وسرد الأدوات وحذفها. */
public final class Tools {
    public final File root;

    public static class Item { public String name, source, files; public long time; }

    public Tools(File root) {
        this.root = root;
        new File(root, "bin").mkdirs();
        new File(root, "pkg").mkdirs();
    }

    public File bin() { return new File(root, "bin"); }
    File reg() { return new File(root, "registry.txt"); }

    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        try {
            if (!reg().isFile()) return out;
            for (String l : Files.readAllLines(reg().toPath(), StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length < 4) continue;
                Item i = new Item();
                i.name = p[0]; i.source = p[1]; i.files = p[3];
                try { i.time = Long.parseLong(p[2]); } catch (NumberFormatException e) { i.time = 0; }
                out.add(i);
            }
        } catch (IOException e) { /* سجل غير قابل للقراءة */ }
        return out;
    }

    void save(List<Item> l) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Item i : l) sb.append(i.name).append('\t').append(i.source).append('\t').append(i.time).append('\t').append(i.files).append('\n');
        Files.write(reg().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String clean(String n) {
        String s = n.replaceAll("[^A-Za-z0-9._+-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) throw new IllegalArgumentException("اسم الأداة غير صالح");
        return s;
    }

    static byte[] head(File f, int n) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[n];
            int o = 0, r;
            while (o < n && (r = in.read(b, o, n - o)) > 0) o += r;
            return Arrays.copyOf(b, o);
        }
    }

    /** elf | script | zip | tar | targz | gz | unknown */
    public static String kindOf(File f) throws IOException {
        byte[] h = head(f, 512);
        if (h.length >= 4 && h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return "elf";
        if (h.length >= 2 && h[0] == '#' && h[1] == '!') return "script";
        if (h.length >= 4 && h[0] == 'P' && h[1] == 'K' && (h[2] == 3 || h[2] == 5)) return "zip";
        if (h.length >= 262 && new String(h, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) return "tar";
        if (h.length >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) {
            try (InputStream g = new GZIPInputStream(new FileInputStream(f))) {
                byte[] t = new byte[512];
                int o = 0, r;
                while (o < 512 && (r = g.read(t, o, 512 - o)) > 0) o += r;
                if (o >= 262 && new String(t, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) return "targz";
            } catch (IOException e) { return "gz"; }
            return "gz";
        }
        return "unknown";
    }

    /** معمارية ملف ELF حسب e_machine. */
    public static String elfArch(File f) throws IOException {
        byte[] h = head(f, 20);
        if (h.length < 20) return null;
        int m = (h[18] & 0xFF) | ((h[19] & 0xFF) << 8);
        switch (m) {
            case 0x28: return "armeabi-v7a";
            case 0xB7: return "arm64-v8a";
            case 0x3E: return "x86_64";
            case 0x03: return "x86";
            default: return null;
        }
    }

    static String shPath() { return new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/sh"; }

    static String sanitizeFile(String n) { return n.replaceAll("[^A-Za-z0-9._+-]", "_"); }

    /** يثبّت ملفاً محلياً. abis: المعماريات التي يدعمها الجهاز (Build.SUPPORTED_ABIS). يعيد ملخصاً. */
    public String installFile(File f, String name, String source, String[] abis, Io.Cancel c) throws IOException {
        String nm = clean(name);
        String kind = kindOf(f);
        List<String> files = new ArrayList<>();
        String summary;
        switch (kind) {
            case "elf": {
                String arch = elfArch(f);
                if (arch != null && abis != null && !Arrays.asList(abis).contains(arch))
                    throw new IOException("الملف مبني لمعمارية " + arch + " والجهاز يدعم: " + String.join(", ", abis));
                File dst = new File(bin(), nm);
                Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                dst.setExecutable(true, false);
                files.add(nm);
                summary = "تم تثبيت الملف التنفيذي «" + nm + "»" + (arch != null ? " (" + arch + ")" : "");
                break;
            }
            case "script": {
                File dst = new File(bin(), nm);
                Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                dst.setExecutable(true, false);
                files.add(nm);
                summary = "تم تثبيت السكربت «" + nm + "»";
                break;
            }
            case "gz": {
                File tmp = new File(root, "tmp_" + System.nanoTime());
                try { Archives.gunzip(f, tmp, c); return installFile(tmp, name, source, abis, c); } finally { tmp.delete(); }
            }
            case "zip": case "tar": case "targz": {
                File dst = new File(root, "pkg/" + nm);
                deleteRec(dst);
                dst.mkdirs();
                if (kind.equals("zip")) Archives.extract(f, dst, null, null, c);
                else Archives.extractTar(f, kind.equals("targz"), dst, c);
                link(dst, files, abis);
                summary = files.isEmpty() ? "فُكّت الحزمة في pkg/" + nm + " لكن لم أجد ملفات تنفيذية في bin/ أو ELF"
                        : "تم تثبيت الحزمة «" + nm + "» وأضيفت الأوامر: " + String.join(", ", files);
                break;
            }
            default:
                throw new IOException("نوع الملف غير مدعوم: ليس ELF ولا سكربت ولا zip/tar.gz");
        }
        List<Item> all = list();
        all.removeIf(i -> i.name.equals(nm));
        Item it = new Item();
        it.name = nm; it.source = source == null ? f.getName() : source; it.time = System.currentTimeMillis(); it.files = String.join(",", files);
        all.add(it);
        save(all);
        return summary;
    }

    void link(File dir, List<String> files, String[] abis) throws IOException {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File x : l) {
            if (java.nio.file.Files.isSymbolicLink(x.toPath())) continue;
            if (x.isDirectory()) { link(x, files, abis); continue; }
            String pn = x.getParentFile().getName();
            boolean inBin = pn.equals("bin") || pn.equals("sbin");
            boolean elf = "elf".equals(kindOf(x));
            if (!inBin && !elf) continue;
            if (elf) { String a = elfArch(x); if (a != null && abis != null && !Arrays.asList(abis).contains(a)) continue; }
            x.setExecutable(true, false);
            String nm = sanitizeFile(x.getName());
            File lk = new File(bin(), nm);
            lk.delete();
            // سكربت وسيط يستدعي الملف بمساره الحقيقي كي تعمل السكربتات والملفات المرتبطة بمسارها (dirname $0)
            Files.write(lk.toPath(), ("#!" + shPath() + "\nexec \"" + x.getAbsolutePath().replace("\"", "\\\"") + "\" \"$@\"\n").getBytes(StandardCharsets.UTF_8));
            lk.setExecutable(true, false);
            if (!files.contains(nm)) files.add(nm);
        }
    }

    public String installUrl(String url, String name, String[] abis, Fetch.Opts o, Io.Progress p, Io.Cancel c) throws IOException {
        Fetch.Info info = Fetch.probe(url, o);
        String nm = name != null && !name.trim().isEmpty() ? name.trim() : info.name.replaceAll("(?i)\\.(tar\\.gz|tgz|zip|gz|tar|bin|exe)$", "");
        File tmp = new File(root, "dl_" + System.nanoTime());
        try {
            Fetch.download(url, tmp, o, p, c);
            return installFile(tmp, nm, url, abis, c);
        } finally {
            Fetch.discard(tmp);
            tmp.delete();
        }
    }

    public void remove(String name) throws IOException {
        List<Item> all = list();
        Item found = null;
        for (Item i : all) if (i.name.equals(name)) found = i;
        if (found == null) throw new IOException("الأداة غير مثبتة: " + name);
        for (String f : found.files.split(",")) if (!f.isEmpty()) new File(bin(), f).delete();
        deleteRec(new File(root, "pkg/" + found.name));
        all.remove(found);
        save(all);
    }

    static void deleteRec(File f) {
        File[] l = f.listFiles();
        if (l != null && !java.nio.file.Files.isSymbolicLink(f.toPath())) for (File x : l) deleteRec(x);
        f.delete();
    }
}
