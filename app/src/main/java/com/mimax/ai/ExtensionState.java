package com.mimax.ai;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ExtensionState {
    private static final String PREF = "extension_runtime_state";
    private ExtensionState() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    static boolean enabled(Context c, String id) { return prefs(c).getBoolean("enabled." + id, true); }
    static void setEnabled(Context c, String id, boolean enabled) { prefs(c).edit().putBoolean("enabled." + id, enabled).apply(); }
    static void begin(Context c, String id) { prefs(c).edit().putString("active_id", id).putLong("active_at", System.currentTimeMillis()).apply(); }
    static void clearActive(Context c) { prefs(c).edit().remove("active_id").remove("active_at").apply(); }
    static String recoveryId(Context c) { return prefs(c).getString("recovery_id", ""); }
    static void clearRecovery(Context c) { prefs(c).edit().remove("recovery_id").apply(); }
    static void recordCrash(Context c, Throwable error) {
        String id = prefs(c).getString("active_id", "");
        if (id == null || id.isEmpty()) return;
        int failures = prefs(c).getInt("failures." + id, 0) + 1;
        prefs(c).edit().putInt("failures." + id, failures).putBoolean("enabled." + id, false)
                .putString("recovery_id", id).remove("active_id").remove("active_at").apply();
        appendLog(c, id, "تعطيل تلقائي بعد تعطل التطبيق: " + String.valueOf(error));
    }
    static void appendLog(Context c, String id, String message) {
        try {
            File dir = new File(c.getFilesDir(), "extension_logs");
            if (!dir.exists()) dir.mkdirs();
            File log = new File(dir, id + ".log");
            FileOutputStream out = new FileOutputStream(log, true);
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            out.write(("[" + stamp + "] " + message + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {}
    }
    static String readLog(Context c, String id) {
        try {
            File f = new File(new File(c.getFilesDir(), "extension_logs"), id + ".log");
            if (!f.isFile() || f.length() > 256 * 1024) return "لا توجد سجلات.";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b); in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) { return "تعذر قراءة السجل: " + e.getMessage(); }
    }
    static boolean permissionGranted(Context c, String id, String permission) {
        return prefs(c).getBoolean("permission." + id + "." + permission, false);
    }
    static void setPermission(Context c, String id, String permission, boolean allowed) {
        prefs(c).edit().putBoolean("permission." + id + "." + permission, allowed).apply();
    }
}
