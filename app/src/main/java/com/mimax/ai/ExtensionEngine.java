package com.mimax.ai;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safe .mt1 extension package manager.
 * Version 1 packages are ZIP containers with a root manifest.json.
 * Only declarative theme values are applied by this first host API.
 */
public final class ExtensionEngine {
    public static final int API_VERSION = 1;
    private static final long MAX_TOTAL = 20L * 1024L * 1024L;
    private static final int MAX_FILES = 300;
    private final Context app;
    private final File root;

    public static final class Item {
        public final File dir;
        public final JSONObject manifest;
        public final String id, name, version;
        public final boolean enabled;
        Item(File d, JSONObject m, boolean e) {
            dir=d; manifest=m; id=m.optString("id", d.getName());
            name=m.optString("name", id); version=m.optString("version", "1.0");
            enabled=e;
        }
    }

    public ExtensionEngine(Context c) {
        app=c.getApplicationContext();
        root=new File(app.getFilesDir(), "extensions");
        if(!root.exists()) root.mkdirs();
    }

    public File getRoot(){ return root; }

    public List<Item> list() {
        ArrayList<Item> out=new ArrayList<>();
        File[] dirs=root.listFiles();
        if(dirs==null) return out;
        for(File d:dirs) {
            if(!d.isDirectory()) continue;
            File mf=new File(d,"manifest.json");
            try {
                JSONObject m=readJson(mf);
                if(m.optInt("apiVersion",0)>API_VERSION) continue;
                out.add(new Item(d,m,app.getSharedPreferences("mm_extensions",0).getBoolean("enabled_"+m.optString("id"),false)));
            } catch(Exception ignored) { }
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b){ return a.name.compareToIgnoreCase(b.name); }
        });
        return out;
    }

    public Item install(Uri uri) throws Exception {
        File stage=new File(root,".install_"+System.currentTimeMillis());
        if(!stage.mkdirs()) throw new Exception("تعذر إنشاء مجلد التثبيت");
        long total=0; int count=0;
        try(InputStream raw=app.getContentResolver().openInputStream(uri);
            ZipInputStream zip=new ZipInputStream(raw)) {
            if(raw==null) throw new Exception("تعذر قراءة ملف الإضافة");
            ZipEntry e;
            byte[] buf=new byte[8192];
            while((e=zip.getNextEntry())!=null) {
                if(e.isDirectory()) continue;
                String name=e.getName().replace('\\','/');
                if(name.startsWith("/") || name.contains("../") || name.equals("..") || name.indexOf(':')>=0)
                    throw new Exception("مسار غير آمن داخل الحزمة");
                if(++count>MAX_FILES) throw new Exception("عدد ملفات الإضافة أكبر من الحد");
                File out=new File(stage,name);
                String canonical=out.getCanonicalPath();
                if(!canonical.startsWith(stage.getCanonicalPath()+File.separator))
                    throw new Exception("مسار ملف غير آمن");
                File parent=out.getParentFile(); if(parent!=null) parent.mkdirs();
                try(FileOutputStream fos=new FileOutputStream(out)) {
                    int n; while((n=zip.read(buf))!=-1) {
                        total+=n; if(total>MAX_TOTAL) throw new Exception("حجم الإضافة يتجاوز 20 ميغابايت");
                        fos.write(buf,0,n);
                    }
                }
                zip.closeEntry();
            }
            JSONObject m=readJson(new File(stage,"manifest.json"));
            String id=m.optString("id","").trim();
            if(!id.matches("[a-zA-Z0-9._-]{3,64}")) throw new Exception("معرّف الإضافة غير صالح");
            if(m.optInt("apiVersion",0)>API_VERSION) throw new Exception("الإضافة تحتاج إصدار API أحدث");
            if(m.optInt("apiVersion",0)<1) throw new Exception("apiVersion مفقود أو غير صالح");
            String type=m.optString("type","theme");
            if(!"theme".equals(type) && !"ui".equals(type)) throw new Exception("نوع الإضافة غير مدعوم");
            if("ui".equals(type)) {
                File screen=new File(stage,"app/screen.json");
                if(!screen.isFile() || screen.length()>256*1024) throw new Exception("إضافة الواجهة تحتاج app/screen.json صالحاً");
                JSONObject spec=readJson(screen);
                org.json.JSONArray components=spec.optJSONArray("components");
                if(components==null || components.length()>40) throw new Exception("مكونات شاشة الإضافة غير صالحة");
            }
            File target=new File(root,id);
            if(target.exists()) throw new Exception("هذه الإضافة مثبتة بالفعل. احذف النسخة الحالية أو استخدم معرّفاً مختلفاً.");
            if(!stage.renameTo(target)) throw new Exception("تعذر اعتماد ملفات الإضافة");
            app.getSharedPreferences("mm_extensions",0).edit().putBoolean("enabled_"+id,false).apply();
            return find(id);
        } catch(Exception ex) {
            deleteTree(stage); throw ex;
        }
    }

    public Item find(String id) {
        for(Item i:list()) if(i.id.equals(id)) return i;
        return null;
    }

    public void setEnabled(String id, boolean enabled) throws Exception {
        Item item=find(id); if(item==null) throw new Exception("الإضافة غير موجودة");
        android.content.SharedPreferences prefs=app.getSharedPreferences("mm_extensions",0);
        if(enabled && "theme".equals(item.manifest.optString("type","theme"))) {
            saveThemeSnapshot(prefs);
            for(Item other:list()) if(!other.id.equals(id) && "theme".equals(other.manifest.optString("type","theme")))
                prefs.edit().putBoolean("enabled_"+other.id,false).apply();
        }
        prefs.edit().putBoolean("enabled_"+id,enabled).apply();
        if(enabled && "theme".equals(item.manifest.optString("type","theme"))) applyTheme(item);
        else if(!enabled && "theme".equals(item.manifest.optString("type","theme")) && !hasEnabledTheme()) resetTheme(prefs);
    }

    public void delete(String id) throws Exception {
        Item item=find(id); if(item==null) return;
        android.content.SharedPreferences prefs=app.getSharedPreferences("mm_extensions",0);
        prefs.edit().remove("enabled_"+id).apply();
        deleteTree(item.dir);
        if("theme".equals(item.manifest.optString("type","theme")) && !hasEnabledTheme()) resetTheme(prefs);
    }

    public void applyEnabledThemes() {
        List<Item> all=list();
        for(Item i:all) if(i.enabled && "theme".equals(i.manifest.optString("type","theme"))) { try { applyTheme(i); } catch(Exception ignored){} }
    }

    private void applyTheme(Item item) throws Exception {
        File f=new File(item.dir,"resources/theme.json");
        if(!f.isFile()) f=new File(item.dir,"theme.json");
        if(!f.isFile()) return;
        JSONObject colors=readJson(f);
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3"};
        for(String k:keys) if(colors.has(k)) {
            String v=colors.optString(k,"");
            if(v.matches("#[0-9a-fA-F]{6,8}")) {
                long parsed=Long.parseLong(v.substring(1),16);
                int color=v.length()==7?(int)(0xFF000000L|parsed):(int)parsed;
                Store.setCc(k,color);
            }
        }
        Store.setFlag("ccOn",true);
    }

    private boolean hasEnabledTheme() {
        for(Item i:list()) if(i.enabled && "theme".equals(i.manifest.optString("type","theme"))) return true;
        return false;
    }

    private void saveThemeSnapshot(android.content.SharedPreferences prefs) {
        if(prefs.getBoolean("snapshot_valid",false)) return;
        android.content.SharedPreferences.Editor edit=prefs.edit();
        edit.putBoolean("snapshot_ccOn",Store.flag("ccOn",false));
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3","bubU","bubA","top","nav","glass"};
        for(String k:keys) {
            String value=Store.get("cc_"+k,null);
            edit.putString("snapshot_cc_"+k,value==null?"__NULL__":value);
        }
        edit.putBoolean("snapshot_valid",true).apply();
    }

    private void resetTheme(android.content.SharedPreferences prefs) {
        if(!prefs.getBoolean("snapshot_valid",false)) { Store.clearCc(); return; }
        Store.clearCc();
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3","bubU","bubA","top","nav","glass"};
        for(String k:keys) {
            String value=prefs.getString("snapshot_cc_"+k,"__NULL__");
            if(!"__NULL__".equals(value)) Store.put("cc_"+k,value);
        }
        Store.setFlag("ccOn",prefs.getBoolean("snapshot_ccOn",false));
        android.content.SharedPreferences.Editor edit=prefs.edit().remove("snapshot_valid").remove("snapshot_ccOn");
        for(String k:keys) edit.remove("snapshot_cc_"+k);
        edit.apply();
    }

    private static JSONObject readJson(File f) throws Exception {
        if(!f.isFile()) throw new Exception("manifest.json غير موجود");
        if(f.length()>256*1024) throw new Exception("ملف JSON كبير جداً");
        byte[] b=new byte[(int)f.length()];
        try(FileInputStream in=new FileInputStream(f)) {
            int p=0,n; while(p<b.length && (n=in.read(b,p,b.length-p))>0) p+=n;
        }
        return new JSONObject(new String(b,"UTF-8"));
    }

    private static void deleteTree(File f) {
        if(f==null || !f.exists()) return;
        File[] children=f.listFiles();
        if(children!=null) for(File c:children) deleteTree(c);
        try { f.delete(); } catch(Exception ignored) { }
    }
}
