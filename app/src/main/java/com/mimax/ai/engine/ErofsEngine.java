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
        File exe = new File(dir, "extract.erofs");
        if (!exe.isFile() || exe.length() == 0) {
            try (InputStream in = context.getAssets().open("engines/erofs/" + abi + "/extract.erofs");
                 FileOutputStream out = new FileOutputStream(exe)) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                out.getFD().sync();
            }
        }
        if (!exe.setExecutable(true, true) && !exe.canExecute())
            throw new Exception("تعذر تفعيل صلاحية تشغيل محرك EROFS");
        return exe;
    }

    /** Extracts a complete EROFS filesystem. Sparse images are expanded to a private temporary raw image first. */
    public static File extractAll(Context context, File image, boolean sparse, File output) throws Exception {
        if (!image.isFile() || image.length() == 0) throw new Exception("ملف الصورة غير موجود أو فارغ");
        if (output.getCanonicalFile().equals(image.getCanonicalFile()))
            throw new Exception("مجلد الاستخراج لا يمكن أن يكون ملف الصورة نفسه");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new Exception("تعذر إنشاء مجلد الإخراج");
        File raw = image;
        File temp = null;
        try {
            if (sparse) {
                temp = new File(context.getCacheDir(), "erofs_raw_" + System.nanoTime() + ".img");
                Sparse.toRaw(image, temp, null, null);
                raw = temp;
            }
            File exe = executable(context);
            File log = new File(context.getCacheDir(), "erofs_extract_" + System.nanoTime() + ".log");
            Process process = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    "-i=" + raw.getAbsolutePath(),
                    "-x", "-f", "-s", "-T2",
                    "-o=" + output.getAbsolutePath())
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start();
            int exit = process.waitFor();
            StringBuilder detail = new StringBuilder();
            if (log.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(log), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && detail.length() < 6000)
                        detail.append(line).append('\n');
                }
                log.delete();
            }
            if (exit != 0) {
                deleteTree(output);
                throw new Exception("فشل محرك EROFS (رمز " + exit + "): " + detail.toString().trim());
            }
            if (!output.isDirectory()) throw new Exception("انتهى محرك EROFS دون إنشاء مجلد الإخراج");
            return output;
        } finally {
            if (temp != null) temp.delete();
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
