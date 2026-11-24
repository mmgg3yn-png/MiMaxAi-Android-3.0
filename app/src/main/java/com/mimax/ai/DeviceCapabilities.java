package com.mimax.ai;

import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Discovers capabilities without assuming that a CPU ABI implies the Android userspace ABI. */
public final class DeviceCapabilities {
    public final String[] supportedAbis;
    public final boolean appIs64Bit;
    public final boolean systemHas64BitAbi;
    public final int sdkInt;
    public final String release;
    public final String primaryAbi;

    private DeviceCapabilities() {
        supportedAbis = Build.VERSION.SDK_INT >= 21 ? Build.SUPPORTED_ABIS : new String[]{Build.CPU_ABI};
        appIs64Bit = Build.VERSION.SDK_INT >= 23 && Process.is64Bit();
        systemHas64BitAbi = Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_64_BIT_ABIS != null
                && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        sdkInt = Build.VERSION.SDK_INT;
        release = Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE;
        primaryAbi = supportedAbis.length == 0 ? "unknown" : supportedAbis[0];
    }

    public static DeviceCapabilities detect() { return new DeviceCapabilities(); }

    public String architectureLabel() {
        if (appIs64Bit) return "64-bit (تطبيق 64 بت)";
        if (systemHas64BitAbi) return "نظام 32 بت على جهاز يدعم 64 بت";
        if (primaryAbi.startsWith("armeabi") || primaryAbi.equals("arm")) return "ARM32";
        if (primaryAbi.startsWith("arm64")) return "ARM64";
        if (primaryAbi.equals("x86_64")) return "x86_64";
        if (primaryAbi.equals("x86")) return "x86";
        return primaryAbi;
    }

    public String abiList() {
        StringBuilder b = new StringBuilder();
        for (String abi : supportedAbis) {
            if (b.length() > 0) b.append(", ");
            b.append(abi);
        }
        return b.length() == 0 ? "غير معروف" : b.toString();
    }

    public boolean adbEnabled(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1; }
        catch (Exception ignored) { return false; }
    }

    public boolean suBinaryPresent() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su"};
        for (String path : paths) if (new java.io.File(path).canExecute()) return true;
        return false;
    }

    /** Must only be called after the user explicitly taps the Root check button. */
    public static String requestRootCheck() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            boolean ended = process.waitFor(3, TimeUnit.SECONDS);
            if (!ended) {
                process.destroy();
                return "لم يصل رد من مدير Root خلال المهلة.";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            reader.close();
            if (process.exitValue() == 0 && output.toString().contains("uid=0"))
                return "Root متاح فعلياً: " + output.toString().trim();
            return "لم تُمنح صلاحية Root أو أنها غير متاحة.";
        } catch (Exception e) {
            return "Root غير متاح: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }
}
