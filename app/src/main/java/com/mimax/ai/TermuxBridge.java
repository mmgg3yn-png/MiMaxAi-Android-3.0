package com.mimax.ai;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the user's existing Termux installation as the package/runtime backend.
 * No root access and no copied Termux binaries are required.
 */
public final class TermuxBridge {
    public static final String ACTION_RESULT = "com.mimax.ai.TERMUX_COMMAND_RESULT";
    static final String EXTRA_SESSION_ID = "mimax_session_id";
    static final String EXTRA_COMMAND_ID = "mimax_command_id";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final AtomicInteger REQUESTS = new AtomicInteger((int) (System.currentTimeMillis() & 0x3fffffff));

    public static final class Result {
        public String sessionId;
        public String output;
        public int exitCode;
    }

    public static java.util.List<Result> consumeResults(Context context) {
        java.util.List<Result> results = new java.util.ArrayList<>();
        android.content.SharedPreferences prefs = context.getSharedPreferences("mimax_termux_bridge_results", Context.MODE_PRIVATE);
        try {
            org.json.JSONArray queue = new org.json.JSONArray(prefs.getString("queue", "[]"));
            for (int i = 0; i < queue.length(); i++) {
                org.json.JSONObject item = queue.optJSONObject(i);
                if (item == null) continue;
                Result result = new Result();
                result.sessionId = item.optString("sessionId", "");
                result.output = item.optString("output", "");
                result.exitCode = item.optInt("exitCode", -1);
                results.add(result);
            }
        } catch (Exception ignored) { }
        prefs.edit().remove("queue").commit();
        return results;
    }

    private TermuxBridge() { }

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasRunPermission(Context context) {
        return Build.VERSION.SDK_INT < 23 ||
                context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED;
    }

    public static String home() { return TERMUX_HOME; }

    public static boolean isTermuxPath(String path) {
        return path != null && (path.startsWith("/data/data/com.termux/files/") ||
                path.startsWith("/data/user/0/com.termux/files/"));
    }

    private static String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    public static void run(Context context, String sessionId, String cwd,
                           String command, Map<String, String> environment) throws Exception {
        if (!isInstalled(context)) throw new IllegalStateException("Termux غير مثبت على الجهاز");
        if (!hasRunPermission(context)) throw new SecurityException("إذن RUN_COMMAND غير ممنوح لـ Mi_Max Ai");

        String work = isTermuxPath(cwd) ? cwd : TERMUX_HOME;
        StringBuilder script = new StringBuilder();
        if (environment != null) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                if ("PATH".equals(key) || "HOME".equals(key) || "PREFIX".equals(key) ||
                        "TMPDIR".equals(key) || "TERM".equals(key) || "LANG".equals(key)) continue;
                script.append("export ").append(key).append("=").append(quote(entry.getValue())).append("\n");
            }
        }
        script.append("cd ").append(quote(work)).append(" || exit 125\n");
        script.append(command).append("\n");
        script.append("__mimax_status=$?\n");
        script.append("printf '\\n__MIMAX_CWD__%s\\n' \"$PWD\"\n");
        script.append("exit \"$__mimax_status\"\n");

        int request = REQUESTS.incrementAndGet();
        Intent callback = new Intent(context, TermuxResultReceiver.class);
        callback.setAction(ACTION_RESULT);
        callback.putExtra(EXTRA_SESSION_ID, sessionId);
        callback.putExtra(EXTRA_COMMAND_ID, request);
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, request, callback, flags);

        Intent run = new Intent();
        run.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService");
        run.setAction("com.termux.RUN_COMMAND");
        run.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        run.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script.toString()});
        run.putExtra("com.termux.RUN_COMMAND_WORKDIR", work);
        run.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        run.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Mi_Max Ai Terminal");
        run.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "أمر أدخله المستخدم من الطرفية داخل Mi_Max Ai");
        run.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending);
        context.startService(run);
    }
}
