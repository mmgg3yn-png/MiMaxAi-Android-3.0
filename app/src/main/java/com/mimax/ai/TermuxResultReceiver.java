package com.mimax.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/** Persists Termux command results so they are not lost if the terminal screen is closed. */
public class TermuxResultReceiver extends BroadcastReceiver {
    private static final String PREFS = "mimax_termux_bridge_results";
    private static final String KEY = "queue";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String sessionId = intent.getStringExtra(TermuxBridge.EXTRA_SESSION_ID);
        if (sessionId == null) return;

        Bundle result = intent.getBundleExtra("result");
        String stdout = result == null ? "" : result.getString("stdout", "");
        String stderr = result == null ? "" : result.getString("stderr", "");
        String error = result == null ? "لم يُرجع Termux بيانات التنفيذ" : result.getString("errmsg", "");
        int exit = result == null ? -1 : result.getInt("exitCode", -1);
        int internalError = result == null ? -1 : result.getInt("err", -1);

        StringBuilder output = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) output.append(stdout);
        if (stderr != null && !stderr.isEmpty()) {
            if (output.length() > 0) output.append('\n');
            output.append(stderr);
        }
        if (error != null && !error.isEmpty()) {
            if (output.length() > 0) output.append('\n');
            output.append("Termux: ").append(error);
        }
        if (internalError != 0 && internalError != -1) {
            if (output.length() > 0) output.append('\n');
            output.append("Termux internal error: ").append(internalError);
        }

        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray queue = new JSONArray(prefs.getString(KEY, "[]"));
            JSONObject item = new JSONObject();
            item.put("sessionId", sessionId);
            item.put("output", output.toString());
            item.put("exitCode", exit);
            queue.put(item);
            while (queue.length() > 20) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < queue.length(); i++) trimmed.put(queue.get(i));
                queue = trimmed;
            }
            prefs.edit().putString(KEY, queue.toString()).commit();
        } catch (Exception ignored) { }

        Intent update = new Intent(TermuxBridge.ACTION_RESULT);
        update.setPackage(context.getPackageName());
        context.sendBroadcast(update);
    }
}
