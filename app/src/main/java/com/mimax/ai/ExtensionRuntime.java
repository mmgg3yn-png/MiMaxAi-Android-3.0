package com.mimax.ai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

/**
 * Safe declarative extension host.
 * Extensions describe actions in JSON; only app-owned, allow-listed handlers run.
 * No downloaded Java, DEX, shell, or native code is executed.
 */
final class ExtensionRuntime {
    private ExtensionRuntime() {}

    static File root(Context context) {
        return new File(context.getFilesDir(), "extensions");
    }

    static ArrayList<JSONObject> fileActions(Context context, File selected) {
        ArrayList<JSONObject> result = new ArrayList<>();
        File[] dirs = root(context).listFiles();
        if (dirs == null || selected == null || !selected.isFile()) return result;
        String filename = selected.getName().toLowerCase(java.util.Locale.US);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File manifest = new File(dir, "manifest.json");
            try {
                JSONObject m = readManifest(manifest);
                String id = m.optString("id", dir.getName());
                if (!ExtensionState.enabled(context, id)) continue;
                if (!ExtensionState.permissionGranted(context, id, "read_selected_file")) continue;
                JSONObject action = m.optJSONObject("file_action");
                if (action == null) continue;
                String exactName = action.optString("filename", "");
                if (!exactName.isEmpty() && !exactName.equalsIgnoreCase(selected.getName())) continue;
                JSONArray extensions = action.optJSONArray("extensions");
                if (extensions == null) continue;
                for (int i = 0; i < extensions.length(); i++) {
                    String ext = extensions.optString(i, "").toLowerCase(java.util.Locale.US);
                    if (!ext.isEmpty() && filename.endsWith(ext)) {
                        JSONObject copy = new JSONObject(m.toString());
                        copy.put("_file_action_label", action.optString("label", "تشغيل " + m.optString("name", "الإضافة")));
                        copy.put("_file_action_handler", action.optString("handler", ""));
                        result.add(copy);
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    static JSONObject readManifest(File file) throws Exception {
        if (!file.isFile() || file.length() > 128 * 1024) throw new Exception("ملف تعريف الإضافة غير صالح");
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int off = 0, n;
        while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        in.close();
        return new JSONObject(new String(data, "UTF-8"));
    }

    static void run(Activity activity, JSONObject manifest, File selectedFile) {
        String extensionId = manifest.optString("id", "");
        if (!extensionId.isEmpty() && !ExtensionState.enabled(activity, extensionId)) {
            Toast.makeText(activity, "هذه الإضافة معطلة. افتح وضع الصيانة لإعادة تفعيلها.", Toast.LENGTH_LONG).show();
            return;
        }
        JSONArray required = manifest.optJSONArray("permissions");
        if (!extensionId.isEmpty() && required != null) {
            for (int i = 0; i < required.length(); i++) {
                String permission = required.optString(i, "");
                if (!permission.isEmpty() && !ExtensionState.permissionGranted(activity, extensionId, permission)) {
                    Toast.makeText(activity, "تحتاج الإضافة إلى تفعيل الصلاحية: " + permission + " من مدير الإضافات.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        if (!extensionId.isEmpty()) ExtensionState.begin(activity, extensionId);
        JSONObject action = manifest.optJSONObject("action");
        String handler = action == null
                ? manifest.optString("_file_action_handler", "")
                : action.optString("handler", "");
        runHandler(activity, handler, selectedFile);
    }

    static void runHandler(Activity activity, String handler, File selectedFile) {
        try {
            Intent intent;
            switch (handler) {
                case "logo_bin_extractor":
                    intent = new Intent(activity, LogoBinExtractorActivity.class);
                    if (selectedFile != null) intent.putExtra("input_path", selectedFile.getAbsolutePath());
                    activity.startActivity(intent);
                    return;
                case "open_terminal":
                    activity.startActivity(new Intent(activity, TerminalActivity.class));
                    return;
                case "open_browser":
                    activity.startActivity(new Intent(activity, BrowserActivity.class));
                    return;
                case "open_agent":
                    activity.startActivity(new Intent(activity, AgentActivity.class));
                    return;
                case "open_capabilities":
                    activity.startActivity(new Intent(activity, CapabilityActivity.class));
                    return;
                case "open_backup":
                    activity.startActivity(new Intent(activity, BackupActivity.class));
                    return;
                case "open_pc_bridge":
                    activity.startActivity(new Intent(activity, RemotePcActivity.class));
                    return;
                default:
                    ExtensionState.clearActive(activity);
                    Toast.makeText(activity, "هذا الإجراء غير مدعوم في إصدار محرك الإضافات الحالي.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            ExtensionState.clearActive(activity);
            Toast.makeText(activity, "تعذر تشغيل الإضافة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
