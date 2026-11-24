package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mi_Max Ai extension host API.
 * Extensions can control documented app capabilities, but cannot load arbitrary
 * Java/DEX/native code, execute shell commands, or access Android internals.
 */
public final class ExtensionScriptRuntime {
    private ExtensionScriptRuntime() {}

    public static void execute(Activity activity, String extensionId, File selectedFile) throws Exception {
        File root = new File(ExtensionRuntime.root(activity), extensionId);
        File script = new File(root, "main.js");
        if (!script.isFile() || script.length() > 512 * 1024) throw new Exception("main.js missing or too large");
        String source = new String(read(script, 512 * 1024), StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(100000);
            cx.setGenerateObserverCount(true);
            cx.setClassShutter(new ClassShutter() {
                @Override public boolean visibleToScripts(String className) { return false; }
            });
            Scriptable scope = cx.initSafeStandardObjects();
            ScriptableObject.putProperty(scope, "api", Context.javaToJS(new PluginApi(activity, extensionId, selectedFile), scope));
            cx.evaluateString(scope, source, extensionId + "/main.js", 1, null);
        } finally {
            Context.exit();
        }
    }

    private static byte[] read(File file, int max) throws Exception {
        if (!file.isFile() || file.length() > max) throw new Exception("File exceeds size limit");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            if (off != data.length) throw new Exception("Incomplete file read");
            return data;
        } finally { in.close(); }
    }

    public static final class PluginApi {
        private final Activity activity;
        private final String id;
        private final File selected;
        private final File data;

        PluginApi(Activity a, String extensionId, File selectedFile) throws Exception {
            activity = a; id = extensionId; selected = selectedFile;
            data = new File(new File(ExtensionRuntime.root(a), id), "data");
            if (!data.exists() && !data.mkdirs()) throw new Exception("Cannot create extension data directory");
        }

        public String getApiVersion() { return "2.0"; }
        public String getAppVersion() { return "3.0.2"; }
        public String getSelectedFileName() { return selected != null ? selected.getName() : ""; }
        public boolean hasSelectedFile() { return selected != null && selected.isFile(); }

        public String getAppInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", "Mi_Max Ai");
                o.put("version", getAppVersion());
                o.put("androidSdk", android.os.Build.VERSION.SDK_INT);
                o.put("api", getApiVersion());
                o.put("extensionId", id);
            } catch (Exception ignored) {}
            return o.toString();
        }

        public String readSelectedText() throws Exception {
            require("read_selected_file");
            if (!hasSelectedFile()) throw new Exception("No selected file");
            return new String(read(selected, 1024 * 1024), StandardCharsets.UTF_8);
        }

        /** Replaces only the selected file, keeping a timestamped rollback copy. */
        public void writeSelectedText(String value) throws Exception {
            require("write_selected_file");
            if (!hasSelectedFile()) throw new Exception("No selected file");
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new Exception("Selected file content exceeds 1 MB");
            File backup = new File(selected.getParentFile(), selected.getName() + ".mimax-backup-" + System.currentTimeMillis());
            copy(selected, backup);
            File temp = new File(selected.getParentFile(), selected.getName() + ".mimax-tmp");
            FileOutputStream out = new FileOutputStream(temp);
            try { out.write(value.getBytes(StandardCharsets.UTF_8)); out.flush(); }
            finally { out.close(); }
            if (!temp.renameTo(selected)) {
                temp.delete();
                throw new Exception("Could not replace selected file; backup kept at " + backup.getName());
            }
            log("Updated selected file; backup: " + backup.getName());
        }

        public void applyTheme(String json) throws Exception {
            require("theme_control");
            if (json == null || json.length() > 4096) throw new Exception("Invalid theme data");
            JSONObject colors = new JSONObject(json);
            String[] keys = {"base", "surface", "surface2", "line", "tx", "mut", "ac", "er", "b0", "b1", "b2", "b3"};
            int applied = 0;
            for (String key : keys) {
                if (!colors.has(key)) continue;
                String value = colors.optString(key, "");
                if (!value.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))
                    throw new Exception("Invalid color for " + key);
                long parsed = Long.parseLong(value.substring(1), 16);
                int color = value.length() == 7 ? (int) (0xFF000000L | parsed) : (int) parsed;
                Store.setCc(key, color);
                applied++;
            }
            if (applied < 8) throw new Exception("Theme must include the 8 core colors");
            Store.setFlag("ccOn", true);
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) activity.recreate();
            });
            log("Applied custom theme (" + applied + " colors)");
        }

        public void resetTheme() throws Exception {
            require("theme_control");
            Store.clearCc();
            activity.runOnUiThread(() -> { if (!activity.isFinishing()) activity.recreate(); });
            log("Reset custom theme");
        }

        /** Reads/writes settings through a strict allow-list, never arbitrary SharedPreferences keys. */
        public String getAppSetting(String key) throws Exception {
            require("app_settings");
            if ("model".equals(key)) return Store.model();
            if ("audioModel".equals(key)) return Store.audioModel();
            if ("temp".equals(key)) return String.valueOf(Store.temp());
            if ("prompt".equals(key)) return Store.prompt();
            if ("style".equals(key)) return String.valueOf(Store.style());
            if ("theme".equals(key)) return String.valueOf(Store.theme());
            throw new Exception("Setting is not exposed: " + key);
        }

        public void setAppSetting(String key, String value) throws Exception {
            require("app_settings");
            if (value == null || value.length() > 12000) throw new Exception("Invalid setting value");
            if ("model".equals(key) || "audioModel".equals(key)) {
                if (value.length() > 180) throw new Exception("Model name too long");
                Store.put(key, value.trim());
            } else if ("temp".equals(key)) {
                double v = Double.parseDouble(value);
                if (v < 0 || v > 2) throw new Exception("Temperature must be between 0 and 2");
                Store.put("temp", String.valueOf(v));
            } else if ("prompt".equals(key)) {
                Store.setPrompt(value);
            } else if ("style".equals(key)) {
                int v = Integer.parseInt(value);
                if (v < 0 || v > 2) throw new Exception("Invalid style");
                Store.setStyle(v);
            } else if ("theme".equals(key)) {
                int v = Integer.parseInt(value);
                if (v < 0 || v >= Theme.ALL.length) throw new Exception("Invalid theme index");
                Store.setTheme(v);
            } else throw new Exception("Setting is not exposed: " + key);
            log("Updated app setting: " + key);
        }

        /** Creates a native in-app panel from JSON: {title,items:[{type:'text'|'button',text,action}]}. */
        public void showUi(String json) throws Exception {
            require("ui_control");
            if (json == null || json.length() > 16000) throw new Exception("UI description too large");
            JSONObject spec = new JSONObject(json);
            JSONArray items = spec.optJSONArray("items");
            if (items == null || items.length() > 40) throw new Exception("UI items missing or too many");
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                int[] colors = currentColors();
                LinearLayout body = new LinearLayout(activity);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setPadding(dp(18), dp(12), dp(18), dp(12));
                body.setBackgroundColor(colors[0]);
                ScrollView scroll = new ScrollView(activity);
                scroll.addView(body);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String type = item.optString("type", "text");
                    String label = limit(item.optString("text", ""), 1200);
                    if ("button".equals(type)) {
                        Button button = new Button(activity);
                        button.setText(label);
                        button.setAllCaps(false);
                        body.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
                        String action = item.optString("action", "");
                        button.setOnClickListener(v -> runUiAction(action));
                    } else {
                        TextView text = new TextView(activity);
                        text.setText(label);
                        text.setTextColor(colors[1]);
                        text.setTextSize(15);
                        text.setPadding(dp(4), dp(8), dp(4), dp(8));
                        body.addView(text);
                    }
                }
                new AlertDialog.Builder(activity)
                        .setTitle(limit(spec.optString("title", "إضافة Mi_Max"), 80))
                        .setView(scroll).setPositiveButton("إغلاق", null).show();
            });
        }

        private void runUiAction(String action) {
            if (action == null) return;
            if (action.startsWith("toast:")) {
                toast(action.substring(6));
            } else if (action.startsWith("tool:")) {
                try { openTool(action.substring(5)); }
                catch (Exception e) { showMessage("تعذر تنفيذ الإجراء", e.getMessage()); }
            } else if (action.startsWith("message:")) {
                showMessage("رسالة الإضافة", action.substring(8));
            } else {
                toast("إجراء غير معروف");
            }
        }

        public void openTool(String tool) throws Exception {
            require("app_navigation");
            String[] allowed = {"settings", "extensions", "logo_bin", "terminal", "browser", "agent", "capabilities", "backup", "pc_bridge"};
            boolean ok = false;
            for (String name : allowed) if (name.equals(tool)) ok = true;
            if (!ok) throw new Exception("Tool is not available: " + tool);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                Class<?> target = null;
                switch (tool) {
                    case "settings": target = SettingsActivity.class; break;
                    case "extensions": target = ExtensionManagerActivity.class; break;
                    case "logo_bin": target = LogoBinExtractorActivity.class; break;
                    case "terminal": target = TerminalActivity.class; break;
                    case "browser": target = BrowserActivity.class; break;
                    case "agent": target = AgentActivity.class; break;
                    case "capabilities": target = CapabilityActivity.class; break;
                    case "backup": target = BackupActivity.class; break;
                    case "pc_bridge": target = RemotePcActivity.class; break;
                }
                if (target != null) activity.startActivity(new android.content.Intent(activity, target));
            });
        }

        public String listExtensions() throws Exception {
            require("manage_extensions");
            JSONArray out = new JSONArray();
            File[] dirs = ExtensionRuntime.root(activity).listFiles();
            if (dirs != null) for (File dir : dirs) {
                if (!dir.isDirectory()) continue;
                File mf = new File(dir, "manifest.json");
                if (!mf.isFile() || mf.length() > 128 * 1024) continue;
                try {
                    JSONObject m = new JSONObject(new String(read(mf, 128 * 1024), StandardCharsets.UTF_8));
                    JSONObject item = new JSONObject();
                    item.put("id", m.optString("id", dir.getName()));
                    item.put("name", m.optString("name", dir.getName()));
                    item.put("version", m.optString("version", ""));
                    item.put("enabled", ExtensionState.enabled(activity, m.optString("id", dir.getName())));
                    out.put(item);
                } catch (Exception ignored) {}
            }
            return out.toString();
        }

        public void setExtensionEnabled(String extensionId, boolean enabled) throws Exception {
            require("manage_extensions");
            if (extensionId == null || !extensionId.matches("[A-Za-z0-9._-]{2,64}"))
                throw new Exception("Invalid extension id");
            File target = new File(ExtensionRuntime.root(activity), extensionId);
            if (!target.isDirectory()) throw new Exception("Extension not found");
            ExtensionState.setEnabled(activity, extensionId, enabled);
            ExtensionState.appendLog(activity, id, "Changed extension " + extensionId + " enabled=" + enabled);
        }

        public String readData(String name) throws Exception {
            File f = dataFile(name);
            if (!f.isFile()) return "";
            return new String(read(f, 1024 * 1024), StandardCharsets.UTF_8);
        }

        public void writeData(String name, String value) throws Exception {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new Exception("Data exceeds 1 MB");
            File f = dataFile(name);
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(value.getBytes(StandardCharsets.UTF_8)); }
            finally { out.close(); }
        }

        public void toast(String message) {
            final String safe = limit(message, 300);
            activity.runOnUiThread(() -> Toast.makeText(activity, safe, Toast.LENGTH_LONG).show());
        }

        public void showMessage(String title, String message) {
            final String t = limit(title, 100), m = limit(message, 4000);
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) new AlertDialog.Builder(activity)
                        .setTitle(t).setMessage(m).setPositiveButton("OK", null).show();
            });
        }

        public void log(String message) {
            ExtensionState.appendLog(activity, id, limit(message, 1000));
        }

        private void require(String permission) throws Exception {
            if (!ExtensionState.permissionGranted(activity, id, permission))
                throw new Exception("Permission required: " + permission + " (enable it in Extension Manager)");
        }

        private File dataFile(String name) throws Exception {
            if (name == null || !name.matches("[A-Za-z0-9._-]{1,80}") || name.equals(".") || name.equals(".."))
                throw new Exception("Invalid data filename");
            File f = new File(data, name);
            if (!f.getCanonicalPath().startsWith(data.getCanonicalPath() + File.separator))
                throw new Exception("Invalid data path");
            return f;
        }

        private int[] currentColors() {
            Theme t = Theme.get(Store.theme());
            return new int[]{t.surface, t.tx};
        }

        private int dp(int n) {
            return Math.round(n * activity.getResources().getDisplayMetrics().density);
        }

        private static void copy(File from, File to) throws Exception {
            FileInputStream in = new FileInputStream(from);
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.flush();
            } finally { in.close(); out.close(); }
        }

        private static String limit(String s, int max) {
            if (s == null) return "";
            return s.length() > max ? s.substring(0, max) : s;
        }
    }
}
