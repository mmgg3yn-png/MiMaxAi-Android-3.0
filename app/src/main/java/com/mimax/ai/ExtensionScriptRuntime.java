package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sandboxed JavaScript extension runner.
 * Scripts receive only the documented PluginApi object; Java class access is denied.
 */
public final class ExtensionScriptRuntime {
    private ExtensionScriptRuntime() {}

    public static void execute(Activity activity, String extensionId, File selectedFile) throws Exception {
        File root = new File(ExtensionRuntime.root(activity), extensionId);
        File script = new File(root, "main.js");
        if (!script.isFile() || script.length() > 512 * 1024) throw new Exception("main.js missing or too large");
        byte[] bytes = read(script, 512 * 1024);
        String source = new String(bytes, StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
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
        byte[] data = new byte[(int)file.length()];
        int off=0,n;
        while(off<data.length && (n=in.read(data,off,data.length-off))>0) off+=n;
        in.close();
        if(off != data.length) throw new Exception("Incomplete file read");
        return data;
    }

    public static final class PluginApi {
        private final Activity activity;
        private final String id;
        private final File selected;
        private final File data;

        PluginApi(Activity a, String extensionId, File selectedFile) throws Exception {
            activity=a; id=extensionId; selected=selectedFile;
            data=new File(new File(ExtensionRuntime.root(a), id), "data");
            if(!data.exists() && !data.mkdirs()) throw new Exception("Cannot create plugin data directory");
        }

        public String getApiVersion() { return "1"; }
        public String getSelectedFileName() { return selected != null ? selected.getName() : ""; }
        public boolean hasSelectedFile() { return selected != null && selected.isFile(); }

        public String readSelectedText() throws Exception {
            if(!hasSelectedFile()) throw new Exception("No selected file");
            if(selected.length() > 1024*1024) throw new Exception("Selected text file is over 1 MB");
            return new String(read(selected, 1024*1024), StandardCharsets.UTF_8);
        }

        public String readData(String name) throws Exception {
            File f=dataFile(name);
            if(!f.isFile()) return "";
            return new String(read(f, 1024*1024), StandardCharsets.UTF_8);
        }

        public void writeData(String name, String value) throws Exception {
            if(value == null || value.getBytes(StandardCharsets.UTF_8).length > 1024*1024)
                throw new Exception("Data exceeds 1 MB");
            File f=dataFile(name);
            File parent=f.getParentFile();
            if(parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Cannot create data folder");
            FileOutputStream out=new FileOutputStream(f);
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.close();
        }

        public void toast(String message) {
            final String safe=limit(message, 300);
            activity.runOnUiThread(() -> Toast.makeText(activity, safe, Toast.LENGTH_LONG).show());
        }

        public void showMessage(String title, String message) {
            final String t=limit(title, 100), m=limit(message, 4000);
            activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                    .setTitle(t).setMessage(m).setPositiveButton("OK", null).show());
        }

        public void log(String message) { ExtensionState.appendLog(activity, id, limit(message, 1000)); }

        private File dataFile(String name) throws Exception {
            if(name == null || !name.matches("[A-Za-z0-9._-]{1,80}") || name.equals(".") || name.equals(".."))
                throw new Exception("Invalid data filename");
            File f=new File(data, name);
            if(!f.getCanonicalPath().startsWith(data.getCanonicalPath()+File.separator))
                throw new Exception("Invalid data path");
            return f;
        }

        private static String limit(String s, int max) {
            if(s == null) return "";
            return s.length() > max ? s.substring(0,max) : s;
        }
    }
}
