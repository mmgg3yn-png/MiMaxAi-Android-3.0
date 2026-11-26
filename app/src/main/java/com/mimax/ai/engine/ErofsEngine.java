package com.mimax.ai.engine;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Real EROFS extractor backed by the bundled, ABI-matched extract.erofs executable. */
public final class ErofsEngine {
    private ErofsEngine() { }

    private static String supportedAbi() throws Exception {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) return abi;
        }
        throw new Exception("محرك EROFS غير متوفر لمعمارية هذا الهاتف: " + Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private static File executable(Context context) throws Exception {
        String abi = supportedAbi();
        File dir = new File(context.getFilesDir(), "engines/erofs/" + abi);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد محرك EROFS");
        String assetDir = "engines/erofs/" + abi;
        String[] bundled = context.getAssets().list(assetDir);
        if (bundled == null || bundled.length == 0)
            throw new Exception("لم تُضمّن ملفات محرك EROFS لمعمارية " + abi + " في هذه النسخة");
        for (String name : bundled) {
            File target = new File(dir, name);
            boolean copy = !target.isFile();
            if (!copy) {
                try (InputStream probe = context.getAssets().open(assetDir + "/" + name)) {
                    copy = target.length() != probe.available();
                }
            }
            if (copy) {
                try (InputStream in = context.getAssets().open(assetDir + "/" + name);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    out.getFD().sync();
                }
            }
        }
        File exe = new File(dir, "extract.erofs");
        if (!exe.isFile() || exe.length() == 0)
            throw new Exception("ملف extract.erofs غير موجود داخل المحرك المضمّن");
        if (!exe.setExecutable(true, true) && !exe.canExecute())
            throw new Exception("تعذر تفعيل صلاحية تشغيل محرك EROFS");
        return exe;
    }

    private static String dirOf(File file) { return file.getParentFile().getAbsolutePath(); }

    private static String imageBaseName(File image) {
        String name = image.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Removes a temporary tree without following symbolic links. */
    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    /** Extracts the EROFS tree into exactly the requested output directory. */
    public static File extractAll(Context context, File image, boolean sparse, File output) throws Exception {
        if (!image.isFile() || image.length() == 0) throw new Exception("ملف الصورة غير موجود أو فارغ");
        if (output.getCanonicalFile().equals(image.getCanonicalFile()))
            throw new Exception("مجلد الاستخراج لا يمكن أن يكون ملف الصورة نفسه");
        if (output.exists()) throw new Exception("مجلد الإخراج موجود مسبقاً؛ اختر اسماً آخر حتى لا تُستبدل ملفاتك");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent == null) throw new Exception("تعذر تحديد المجلد الأب لنتيجة الاستخراج");
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new Exception("تعذر إنشاء المجلد الأب لنتيجة الاستخراج");

        File raw = image;
        File temp = null;
        File stage = new File(parent, "." + output.getName() + ".erofs-stage-" + System.nanoTime());
        if (!stage.mkdirs()) throw new Exception("تعذر إنشاء مجلد العمل المؤقت لمحرك EROFS");
        try {
            if (sparse) {
                temp = new File(context.getCacheDir(), "erofs_raw_" + System.nanoTime() + ".img");
                Sparse.toRaw(image, temp, null, null);
                raw = temp;
            }

            File exe = executable(context);
            File log = new File(context.getCacheDir(), "erofs_extract_" + System.nanoTime() + ".log");
            ProcessBuilder builder = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    "-i", raw.getAbsolutePath(),
                    "-x", "-f", "-T2",
                    "-o", stage.getAbsolutePath());
            builder.redirectErrorStream(true).redirectOutput(log);
            String previousLibPath = builder.environment().get("LD_LIBRARY_PATH");
            builder.environment().put("LD_LIBRARY_PATH", dirOf(exe)
                    + (previousLibPath == null || previousLibPath.isEmpty() ? "" : ":" + previousLibPath));

            Process process = builder.start();
            int exit = process.waitFor();
            StringBuilder detail = new StringBuilder();
            if (log.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(log), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && detail.length() < 6000)
                        detail.append(line).append('\n');
                } finally {
                    log.delete();
                }
            }
            if (exit != 0) {
                throw new Exception("فشل محرك EROFS (رمز " + exit + "): " + detail.toString().trim());
            }

            // extract.erofs appends the image basename to --outdir; normalize that
            // generated child directory into the exact destination selected by the user.
            File extractedRoot = new File(stage, imageBaseName(raw));
            if (!extractedRoot.isDirectory()) {
                throw new Exception("انتهى محرك EROFS دون إنشاء شجرة الملفات المتوقعة. سجل المحرك: "
                        + detail.toString().trim());
            }

            // Keep Android fs_config / file_contexts / mkfs metadata with the extracted tree.
            File config = new File(stage, "config");
            if (config.isDirectory()) {
                File metadata = new File(extractedRoot, ".erofs_metadata");
                if (metadata.exists() || !config.renameTo(metadata)) {
                    throw new Exception("تم استخراج الملفات لكن تعذر حفظ بيانات EROFS الوصفية");
                }
            }

            if (!extractedRoot.renameTo(output)) {
                throw new Exception("اكتمل الاستخراج لكن تعذر نقل مجلد الملفات إلى المسار النهائي");
            }
            if (!output.isDirectory())
                throw new Exception("لم يتم إنشاء مجلد الملفات النهائي بعد الاستخراج");
            return output;
        } finally {
            deleteTree(stage);
            if (temp != null) temp.delete();
        }
    }
}
