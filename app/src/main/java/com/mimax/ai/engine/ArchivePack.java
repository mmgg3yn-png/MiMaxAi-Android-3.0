package com.mimax.ai.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** ZIP-compatible archive and Mi_Max package writer. Writes to a temporary file and keeps the source untouched. */
public final class ArchivePack {
    private ArchivePack() { }

    public static File create(File source, File output, String metadataEntry, String metadataJson,
                              Io.Progress progress, Io.Cancel cancel) throws IOException {
        if (source == null || !source.exists()) throw new IOException("المصدر غير موجود");
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("لا يتم ضغط الروابط الرمزية");
        File src = source.getCanonicalFile(), dst = output.getCanonicalFile();
        if (src.equals(dst) || (src.isDirectory() && dst.getPath().startsWith(src.getPath() + File.separator)))
            throw new IOException("لا يمكن إنشاء الأرشيف داخل المصدر نفسه");
        if (output.exists()) throw new IOException("ملف الناتج موجود بالفعل");
        List<File> entries = new ArrayList<>();
        if (source.isDirectory()) {
            File[] children = source.listFiles();
            if (children == null) throw new IOException("تعذر قراءة مجلد المصدر");
            java.util.Arrays.sort(children, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) collect(child, entries, cancel);
        } else {
            collect(source, entries, cancel);
        }
        long total = 0;
        for (File f : entries) if (f.isFile()) total += f.length();
        long extra = metadataJson == null ? 0 : metadataJson.getBytes(StandardCharsets.UTF_8).length;
        Io.checkSpace(output.getAbsoluteFile().getParentFile(), Math.max(1024, total + extra));
        File tmp = new File(output.getPath() + ".tmp");
        boolean ok = false;
        long done = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8)) {
            zip.setLevel(6);
            String root = source.isDirectory() ? source.getName() + "/" : "";
            if (source.isDirectory()) {
                ZipEntry rootEntry = new ZipEntry(root); rootEntry.setTime(source.lastModified());
                zip.putNextEntry(rootEntry); zip.closeEntry();
            }
            byte[] buffer = new byte[1 << 16];
            for (File f : entries) {
                if (cancel != null) cancel.check();
                String rel = relative(source, f);
                String name = root + rel + (f.isDirectory() && !rel.endsWith("/") ? "/" : "");
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(f.lastModified());
                zip.putNextEntry(ze);
                if (f.isFile()) {
                    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            if (cancel != null) cancel.check();
                            zip.write(buffer, 0, n);
                            done += n;
                            if (progress != null) progress.on(done, total + extra);
                        }
                    }
                }
                zip.closeEntry();
            }
            if (metadataJson != null) {
                if (metadataEntry == null || metadataEntry.startsWith("/") || metadataEntry.contains(".."))
                    throw new IOException("مسار بيانات الحزمة غير صالح");
                ZipEntry meta = new ZipEntry(metadataEntry);
                zip.putNextEntry(meta);
                byte[] bytes = metadataJson.getBytes(StandardCharsets.UTF_8);
                zip.write(bytes); done += bytes.length;
                if (progress != null) progress.on(done, total + extra);
                zip.closeEntry();
            }
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
        if (!tmp.renameTo(output)) { tmp.delete(); throw new IOException("تعذر حفظ الأرشيف الناتج"); }
        return output;
    }

    private static void collect(File f, List<File> out, Io.Cancel cancel) throws IOException {
        if (cancel != null) cancel.check();
        if (Files.isSymbolicLink(f.toPath())) return;
        out.add(f);
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) throw new IOException("تعذر قراءة المجلد: " + f.getPath());
            java.util.Arrays.sort(children, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) collect(child, out, cancel);
        }
    }

    private static String relative(File source, File f) throws IOException {
        if (source.isFile()) return source.getName();
        String root = source.getCanonicalPath() + File.separator;
        String path = f.getCanonicalPath();
        if (!path.startsWith(root)) throw new IOException("مسار خارج مجلد المصدر");
        return path.substring(root.length()).replace(File.separatorChar, '/');
    }
}
