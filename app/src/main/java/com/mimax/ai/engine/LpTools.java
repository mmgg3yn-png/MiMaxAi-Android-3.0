package com.mimax.ai.engine;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs the bundled AOSP-derived logical-partition command-line tools.
 * Binaries are packaged for both Android ARM ABIs and run offline.
 */
public final class LpTools {
    private LpTools() { }

    public static String abi() throws IOException {
        if (Build.VERSION.SDK_INT >= 23 && Process.is64Bit()) return "arm64-v8a";
        for (String supported : Build.SUPPORTED_ABIS) {
            if ("armeabi-v7a".equals(supported)) return "armeabi-v7a";
        }
        throw new IOException("هذه النسخة لا تحتوي محرك LP لمعمارية هذا الهاتف.");
    }

    private static File executable(Context context, String name) throws IOException {
        if (!name.matches("[a-z0-9_]+")) throw new IOException("اسم أداة غير صالح");
        String abi = abi();
        File dir = new File(context.getFilesDir(), "lp-tools/" + abi);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد أدوات LP");
        File out = new File(dir, name);
        String asset = "lp-tools/" + abi + "/" + name;
        try (InputStream in = context.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) fos.write(buffer, 0, n);
            fos.getFD().sync();
        } catch (IOException e) {
            out.delete();
            throw new IOException("الأداة " + name + " غير مضمّنة في هذا الإصدار. يلزم تجهيز ملفات AOSP للـ " + abi + " ثم إعادة بناء APK.", e);
        }
        verifyElf(out, abi);
        if (!out.setReadable(true, true) || !out.setExecutable(true, true)) {
            out.delete();
            throw new IOException("تعذر تجهيز صلاحية تشغيل أداة " + name);
        }
        return out;
    }

    private static void verifyElf(File file, String abi) throws IOException {
        byte[] h = new byte[20];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(h) != h.length) throw new IOException("ملف الأداة ناقص: " + file.getName());
        }
        int machine = (h[18] & 255) | ((h[19] & 255) << 8);
        int expected = "arm64-v8a".equals(abi) ? 183 : 40;
        if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' || machine != expected) {
            throw new IOException("معمارية ملف " + file.getName() + " لا تطابق " + abi);
        }
    }

    public static String run(Context context, String tool, List<String> args,
                             Io.Cancel cancel, Io.Progress progress) throws IOException {
        File exe = executable(context.getApplicationContext(), tool);
        List<String> command = new ArrayList<>();
        command.add(exe.getAbsolutePath());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(context.getFilesDir());
        builder.environment().put("LC_ALL", "C");
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    synchronized (captured) {
                        if (captured.size() < 128 * 1024) captured.write(bytes, 0,
                                Math.min(bytes.length, 128 * 1024 - captured.size()));
                    }
                }
            } catch (IOException ignored) { }
        }, "lp-tool-output");
        reader.start();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.cancelled) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                    throw new Io.CancelledException();
                }
                if (progress != null) progress.on(0, 0);
            }
            reader.join(2000);
            if (cancel != null) cancel.check();
            String output;
            synchronized (captured) {
                output = new String(captured.toByteArray(), StandardCharsets.UTF_8).trim();
            }
            if (process.exitValue() != 0) {
                throw new IOException(tool + " انتهى برمز " + process.exitValue()
                        + (output.isEmpty() ? "" : ":\n" + output));
            }
            if (progress != null) progress.on(1, 1);
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("تمت مقاطعة تشغيل " + tool, e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static List<String> lpmakeArgs(Lp.Meta meta, java.util.Map<String, File> images,
                                           File output, boolean sparse) throws IOException {
        if (meta.devices.size() != 1) {
            throw new Io.Unsupported("هذه النسخة من واجهة إعادة التجميع تتطلب super بجهاز كتل واحد؛ الصورة متعددة الأجهزة لم تُعدّل.");
        }
        Lp.Device device = meta.devices.get(0);
        if (device.name.isEmpty() || device.size <= 0) throw new Io.Corrupt("بيانات جهاز super غير مكتملة.");
        boolean suffix = (device.flags & 1) != 0;
        for (Lp.Group group : meta.groups) {
            if (((group.flags & 1) != 0) != suffix) {
                throw new Io.Unsupported("مجموعات LP تستخدم إعدادات Slot Suffix مختلفة؛ أُوقف إعادة التجميع لتجنب تغيير Metadata.");
            }
        }
        for (Lp.Part part : meta.parts) {
            if (((part.attrs & 2) != 0) != suffix) {
                throw new Io.Unsupported("الأقسام تستخدم إعدادات Slot Suffix مختلفة؛ أُوقف إعادة التجميع لتجنب تغيير Metadata.");
            }
            if ((part.attrs & ~3) != 0) {
                throw new Io.Unsupported("القسم " + part.name + " يحتوي سمات LP لا يدعمها lpmake بهذه الواجهة؛ لن تُحذف سماته بصمت.");
            }
            File image = images.get(part.name);
            if (image == null || !image.isFile()) throw new IOException("ملف القسم المطلوب غير موجود: " + part.name);
        }

        List<String> args = new ArrayList<>();
        add(args, "--metadata-size", Long.toString(meta.maxSize));
        add(args, "--metadata-slots", Integer.toString(meta.slotCount));
        add(args, "--device", device.name + ":" + device.size + ":"
                + Math.max(1, device.alignment) + ":" + device.alignmentOffset);
        add(args, "--super-name", device.name);
        if (suffix) args.add("--auto-slot-suffixing");
        if (meta.virtualAb) args.add("--virtual-ab");
        for (Lp.Group group : meta.groups) {
            add(args, "--group", group.name + ":" + group.maxSize);
        }
        long alignment = Math.max(1, device.alignment);
        for (Lp.Part part : meta.parts) {
            File image = images.get(part.name);
            long required = image.length();
            long size = Math.max(part.size, align(required, alignment));
            String attr = (part.attrs & 1) != 0 ? "readonly" : "none";
            String group = part.group >= 0 && part.group < meta.groups.size()
                    ? meta.groups.get(part.group).name : "default";
            add(args, "--partition", part.name + ":" + attr + ":" + size + ":" + group);
            add(args, "--image", part.name + "=" + image.getAbsolutePath());
        }
        if (sparse) {
            args.add("--sparse");
            add(args, "--block-size", Integer.toString(meta.logicalBlockSize));
        } else {
            args.add("--force-full-image");
        }
        add(args, "--output", output.getAbsolutePath());
        return args;
    }

    private static void add(List<String> args, String key, String value) {
        args.add(key);
        args.add(value);
    }

    private static long align(long value, long alignment) throws IOException {
        if (alignment <= 0) return value;
        if (value > Long.MAX_VALUE - alignment) throw new IOException("حجم القسم أكبر من المجال المدعوم");
        return ((value + alignment - 1) / alignment) * alignment;
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[1024 * 1024];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }
}
