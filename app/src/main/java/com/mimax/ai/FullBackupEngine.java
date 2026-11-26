package com.mimax.ai;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-encrypted full backup of Mi_Max Ai's own app-private data.
 * Android-owned data outside this app's accessible sandbox is intentionally not claimed.
 */
public final class FullBackupEngine {
    private static final byte[] MAGIC = new byte[]{'M','M','A','X','B','K','3','2'};
    private static final int VERSION = 2;
    private static final int ITERATIONS = 150000;
    private static final int MAX_ENTRIES = 100000;
    private static final long MAX_RESTORE_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final String MANIFEST = "backup-manifest.json";

    public static final class RestoreReport {
        public int restoredRoots;
        public int skippedRoots;
        public int files;
        public long bytes;
        public String summary() {
            return "تمت استعادة " + restoredRoots + " مجلدات رئيسية و" + files
                    + " ملفاً (" + bytes + " بايت). المجلدات غير المتاحة: " + skippedRoots;
        }
    }

    private static final class Root {
        final String id;
        final File dir;
        Root(String id, File dir) { this.id=id; this.dir=dir; }
    }

    private FullBackupEngine() {}

    public static void export(Context context, Uri destination, String password) throws Exception {
        if (password == null || password.length() < 8) throw new Exception("كلمة المرور يجب أن تكون 8 أحرف على الأقل");
        LinkedHashMap<String, Root> roots = roots(context);
        OutputStream raw = context.getContentResolver().openOutputStream(destination, "w");
        if (raw == null) throw new Exception("تعذر فتح ملف النسخة للكتابة");
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt); random.nextBytes(iv);
        try {
            raw.write(MAGIC); raw.write(salt); raw.write(iv); raw.flush();
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv);
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new CipherOutputStream(raw, cipher)));
            JSONObject manifest = new JSONObject();
            manifest.put("format", "MiMaxAiFullBackup");
            manifest.put("version", VERSION);
            manifest.put("package", context.getPackageName());
            manifest.put("createdAt", System.currentTimeMillis());
            manifest.put("sdk", Build.VERSION.SDK_INT);
            JSONArray rootList = new JSONArray();
            JSONObject checksums = new JSONObject();
            int[] fileCount = {0};
            long[] byteCount = {0L};
            for (Root root : roots.values()) {
                JSONObject r = new JSONObject();
                r.put("id", root.id);
                r.put("present", root.dir != null && root.dir.isDirectory());
                rootList.put(r);
                if (root.dir != null && root.dir.isDirectory()) {
                    zip.putNextEntry(new ZipEntry("payload/" + root.id + "/"));
                    zip.closeEntry();
                    addTree(zip, root.dir, "payload/" + root.id, root.id, checksums, fileCount, byteCount);
                }
            }
            manifest.put("roots", rootList);
            manifest.put("files", checksums);
            manifest.put("fileCount", fileCount[0]);
            manifest.put("totalBytes", byteCount[0]);
            byte[] m = manifest.toString().getBytes("UTF-8");
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(m);
            zip.closeEntry();
            zip.finish();
            zip.close();
        } catch (Exception e) {
            try { raw.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static void addTree(ZipOutputStream zip, File dir, String zipBase, String rootId,
                                JSONObject checksums, int[] count, long[] bytes) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String rel = child.getAbsolutePath().substring(dir.getAbsolutePath().length());
            while (rel.startsWith(File.separator)) rel = rel.substring(1);
            rel = rel.replace(File.separatorChar, '/');
            String entry = zipBase + "/" + rel;
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(entry + "/")); zip.closeEntry();
                addTree(zip, child, entry, rootId, checksums, count, bytes);
            } else if (child.isFile()) {
                if (++count[0] > MAX_ENTRIES) throw new Exception("عدد الملفات أكبر من الحد الآمن");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                zip.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        zip.write(buffer, 0, n); digest.update(buffer, 0, n); bytes[0] += n;
                    }
                }
                zip.closeEntry();
                checksums.put(entry.substring(("payload/" + rootId + "/").length()), hex(digest.digest()));
            }
        }
    }

    public static RestoreReport restore(Context context, Uri source, String password) throws Exception {
        if (password == null || password.length() < 8) throw new Exception("أدخل كلمة مرور النسخة");
        File stage = new File(context.getCacheDir(), "mimax_restore_stage_" + System.currentTimeMillis());
        if (!stage.mkdirs()) throw new Exception("تعذر إنشاء مجلد الاستعادة المؤقت");
        RestoreReport report = new RestoreReport();
        try {
            InputStream raw = context.getContentResolver().openInputStream(source);
            if (raw == null) throw new Exception("تعذر فتح ملف النسخة");
            DataInputStream header = new DataInputStream(new BufferedInputStream(raw));
            byte[] magic = new byte[MAGIC.length], salt = new byte[16], iv = new byte[12];
            header.readFully(magic); header.readFully(salt); header.readFully(iv);
            for (int i=0;i<MAGIC.length;i++) if (magic[i] != MAGIC[i]) throw new Exception("هذا ليس ملف Mi_Max Ai المشفر");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv);
            JSONObject manifest = null;
            long[] total = {0L}; int[] entries = {0};
            try (ZipInputStream zip = new ZipInputStream(new CipherInputStream(header, cipher))) {
                ZipEntry entry;
                byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries[0] > MAX_ENTRIES + 1000) throw new Exception("النسخة تحتوي عدداً غير آمن من الملفات");
                    String name = entry.getName().replace('\\', '/');
                    if (name.equals(MANIFEST)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        int n; while ((n=zip.read(buffer))!=-1) out.write(buffer,0,n);
                        manifest = new JSONObject(new String(out.toByteArray(), "UTF-8"));
                    } else if (name.startsWith("payload/")) {
                        String rest = name.substring("payload/".length());
                        int slash = rest.indexOf('/');
                        if (slash < 1) throw new Exception("مسار داخل النسخة غير صالح");
                        String rootId = rest.substring(0, slash);
                        if (!rootId.matches("[A-Za-z0-9_-]{1,64}")) throw new Exception("معرّف مجلد غير صالح");
                        String relative = rest.substring(slash + 1);
                        File root = new File(stage, rootId);
                        File out = relative.isEmpty() ? root : new File(root, relative);
                        String canonical = out.getCanonicalPath();
                        String rootCanonical = root.getCanonicalPath();
                        if (!canonical.equals(rootCanonical) && !canonical.startsWith(rootCanonical + File.separator)) throw new Exception("مسار ملف غير آمن");
                        if (entry.isDirectory() || name.endsWith("/")) out.mkdirs();
                        else {
                            File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
                            try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out))) {
                                int n; while ((n=zip.read(buffer))!=-1) {
                                    total[0] += n;
                                    if (total[0] > MAX_RESTORE_BYTES) throw new Exception("حجم النسخة يتجاوز الحد الآمن");
                                    file.write(buffer,0,n);
                                }
                            }
                            report.files++;
                        }
                    } else {
                        throw new Exception("النسخة تحتوي مساراً غير معروف");
                    }
                    zip.closeEntry();
                }
            } catch (java.io.IOException cryptoOrZip) {
                throw new Exception("تعذر فك النسخة. تحقق من كلمة المرور وسلامة الملف");
            }
            if (manifest == null || !"MiMaxAiFullBackup".equals(manifest.optString("format"))
                    || manifest.optInt("version") != VERSION) throw new Exception("إصدار النسخة غير مدعوم أو ملفها غير مكتمل");
            JSONObject checksums = manifest.optJSONObject("files");
            if (checksums == null) throw new Exception("قائمة التحقق غير موجودة");
            Iterator<String> keys = checksums.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int slash = key.indexOf('/');
                if (slash < 1) throw new Exception("بيانات التحقق غير صالحة");
                File file = new File(new File(stage, key.substring(0, slash)), key.substring(slash+1));
                if (!file.isFile() || !hex(sha256(file)).equalsIgnoreCase(checksums.getString(key)))
                    throw new Exception("فشل التحقق من سلامة الملف: " + key);
            }
            report.bytes = total[0];
            LinkedHashMap<String, Root> destinations = roots(context);
            JSONArray savedRoots = manifest.optJSONArray("roots");
            if (savedRoots == null) throw new Exception("قائمة مجلدات النسخة غير موجودة");
            for (int i=0;i<savedRoots.length();i++) {
                String id = savedRoots.getJSONObject(i).optString("id");
                File from = new File(stage, id);
                Root dest = destinations.get(id);
                if (!from.isDirectory()) continue;
                if (dest == null || dest.dir == null) { report.skippedRoots++; continue; }
                replaceDirectory(from, dest.dir);
                report.restoredRoots++;
            }
            return report;
        } finally {
            deleteTree(stage);
        }
    }

    private static Cipher cipher(int mode, String password, byte[] salt, byte[] iv) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        SecretKey key = new SecretKeySpec(encoded, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        return cipher;
    }

    private static LinkedHashMap<String, Root> roots(Context c) {
        LinkedHashMap<String, Root> out = new LinkedHashMap<>();
        File data = new File(c.getApplicationInfo().dataDir);
        out.put("files", new Root("files", c.getFilesDir()));
        out.put("databases", new Root("databases", c.getDatabasePath("_probe_").getParentFile()));
        out.put("shared_prefs", new Root("shared_prefs", new File(data, "shared_prefs")));
        out.put("no_backup", new Root("no_backup", c.getNoBackupFilesDir()));
        out.put("cache", new Root("cache", c.getCacheDir()));
        if (Build.VERSION.SDK_INT >= 21) out.put("code_cache", new Root("code_cache", c.getCodeCacheDir()));
        File[] ext = c.getExternalFilesDirs(null);
        if (ext != null) for (int i=0;i<ext.length;i++) out.put("external_files_" + i, new Root("external_files_" + i, ext[i]));
        File[] extCache = c.getExternalCacheDirs();
        if (extCache != null) for (int i=0;i<extCache.length;i++) out.put("external_cache_" + i, new Root("external_cache_" + i, extCache[i]));
        return out;
    }

    private static void replaceDirectory(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new Exception("تعذر تجهيز مجلد الاستعادة");
        File fresh = new File(parent, target.getName() + ".mimax_new");
        File old = new File(parent, target.getName() + ".mimax_old");
        deleteTree(fresh); deleteTree(old);
        copyTree(source, fresh);
        if (target.exists() && !target.renameTo(old)) { deleteTree(fresh); throw new Exception("تعذر حفظ نسخة الرجوع للمجلد: " + target.getName()); }
        if (!fresh.renameTo(target)) {
            if (old.exists()) old.renameTo(target);
            deleteTree(fresh);
            throw new Exception("تعذر تطبيق الاستعادة للمجلد: " + target.getName());
        }
        deleteTree(old);
    }

    private static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) throw new Exception("تعذر إنشاء " + to.getName());
            File[] list = from.listFiles();
            if (list != null) for (File child : list) copyTree(child, new File(to, child.getName()));
        } else {
            File parent = to.getParentFile(); if (parent != null) parent.mkdirs();
            try (InputStream in = new BufferedInputStream(new FileInputStream(from));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
                byte[] b = new byte[32768]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n);
            }
        }
    }

    private static byte[] sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[32768]; int n; while ((n=in.read(b))!=-1) md.update(b,0,n);
        }
        return md.digest();
    }
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length*2);
        for (byte x:b) s.append(String.format(java.util.Locale.US,"%02x",x&255));
        return s.toString();
    }
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] list=f.listFiles(); if(list!=null) for(File c:list) deleteTree(c); }
        f.delete();
    }
}
