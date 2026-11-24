package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Manager and AI creator for Mi_Max .mt1 packages. */
public class ExtensionActivity extends Activity {
    private static final int PICK_MT1=810, SAVE_MT1=811;
    private ExtensionEngine engine;
    private LinearLayout list;
    private EditText request;
    private TextView status;
    private File pendingExport;

    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String s,int size,int color){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(dp(4),dp(5),dp(4),dp(5)); return v;
    }
    private Button button(String s,Runnable r){
        Button b=new Button(this); b.setText(s); b.setAllCaps(false);
        b.setOnClickListener(v->r.run()); return b;
    }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        engine=new ExtensionEngine(this);
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(8),dp(14),dp(10)); root.setBackgroundColor(0xFFF2F4F7);
        TextView title=text("Mi_Max Extension Control",21,0xFF17212D);
        title.setTypeface(null,1); root.addView(title);
        root.addView(text("إدارة حزم .mt1 وإنشاء إضافات بالذكاء الاصطناعي. الإصدار الأول يطبق إضافات الثيمات المعلنة فقط؛ دعم تعديل الشاشات والوظائف سيضاف عبر واجهات API موثقة.",13,0xFF536273));
        root.addView(button("تثبيت إضافة .mt1",this::pickPackage));
        root.addView(button("إنشاء إضافة بالذكاء الاصطناعي",this::createWithAi));
        request=new EditText(this); request.setMinLines(3); request.setGravity(Gravity.TOP|Gravity.START);
        request.setHint("صف الإضافة المطلوبة: مثلاً ثيم زجاجي أزرق داكن مع تباين واضح...");
        request.setTextSize(14); request.setPadding(dp(10),dp(8),dp(10),dp(8));
        root.addView(request,new LinearLayout.LayoutParams(-1,-2));
        status=text("جاهز. اكتب وصف الإضافة ثم أنشئها.",13,0xFF334155);
        root.addView(status);
        ScrollView sc=new ScrollView(this); list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(button("تحديث القائمة",this::refresh));
        root.addView(button("العودة",this::finish));
        setContentView(root); refresh();
    }

    private void pickPackage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try{ startActivityForResult(i,PICK_MT1); }catch(Exception e){ toast("تعذر فتح الملفات"); }
    }

    @Override protected void onActivityResult(int rq,int rs,Intent data){
        super.onActivityResult(rq,rs,data);
        if(rs!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(rq==PICK_MT1){
                ExtensionEngine.Item item=engine.install(uri);
                toast("تم تثبيت: "+item.name); refresh();
            }else if(rq==SAVE_MT1 && pendingExport!=null){
                try(InputStreamCopy copy=new InputStreamCopy(new FileInputStream(pendingExport),getContentResolver().openOutputStream(uri))){
                    copy.copy();
                }
                toast("تم حفظ حزمة .mt1");
            }
        }catch(Exception e){ toast("فشلت العملية: "+message(e)); }
    }

    private void refresh(){
        list.removeAllViews();
        List<ExtensionEngine.Item> items=engine.list();
        if(items.isEmpty()) list.addView(text("لا توجد إضافات مثبتة حالياً.",14,0xFF64748B));
        for(ExtensionEngine.Item item:items){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10),dp(8),dp(10),dp(8)); card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.bottomMargin=dp(8); card.setLayoutParams(cp);
            TextView name=text(item.name+"  •  "+item.version,16,0xFF17212D); name.setTypeface(null,1); card.addView(name);
            card.addView(text("ID: "+item.id+" | API: "+item.manifest.optInt("apiVersion"),12,0xFF64748B));
            card.addView(text(item.manifest.optString("description","إضافة Mi_Max"),12,0xFF475569));
            card.addView(button(item.enabled?"تعطيل الإضافة":"تفعيل الإضافة",()->{
                try{ engine.setEnabled(item.id,!item.enabled); refresh(); toast(item.enabled?"تم تعطيل الإضافة":"تم تفعيل الإضافة"); }
                catch(Exception e){ toast("تعذر التفعيل: "+message(e)); }
            }));
            card.addView(button("تصدير نسخة .mt1",()->exportPackage(item)));
            card.addView(button("حذف الإضافة",()->new AlertDialog.Builder(this)
                    .setTitle("حذف "+item.name+"؟").setMessage("سيتم حذف ملفات هذه الإضافة.")
                    .setNegativeButton("إلغاء",null).setPositiveButton("حذف",(d,w)->{
                        try{engine.delete(item.id);refresh();toast("تم الحذف");}
                        catch(Exception e){toast(message(e));}
                    }).show()));
            list.addView(card);
        }
    }

    private void createWithAi(){
        final String task=request.getText().toString().trim();
        if(task.isEmpty()){toast("اكتب وصف الإضافة أولاً");return;}
        if(Store.key().isEmpty()){toast("أضف مفتاح OpenRouter من الإعدادات أولاً");return;}
        status.setText("الذكاء الاصطناعي يصمم الإضافة ويتحقق من ألوانها...");
        new Thread(()->{
            try{
                JSONArray msgs=new JSONArray();
                JSONObject sys=new JSONObject();
                sys.put("role","system");
                sys.put("content","أنت مصمم إضافات Mi_Max Ai بصيغة mt1 API v1. حالياً النوع المدعوم فعلياً هو theme فقط. حوّل وصف المستخدم إلى لوحة ألوان متناسقة قابلة للتطبيق. أعد JSON فقط بهذا الشكل: {\"id\":\"lowercase_id_3_to_48_chars\",\"name\":\"اسم عربي\",\"description\":\"وصف\",\"colors\":{\"base\":\"#AARRGGBB\",\"surface\":\"#AARRGGBB\",\"surface2\":\"#AARRGGBB\",\"line\":\"#AARRGGBB\",\"tx\":\"#AARRGGBB\",\"mut\":\"#AARRGGBB\",\"ac\":\"#AARRGGBB\",\"er\":\"#AARRGGBB\",\"b0\":\"#AARRGGBB\",\"b1\":\"#AARRGGBB\",\"b2\":\"#AARRGGBB\",\"b3\":\"#AARRGGBB\"}}. كل لون يجب أن يكون # متبوعاً بثمانية أرقام hex. لا تدّع إنشاء وظائف أو شاشات لأنها غير مدعومة في API v1. لا تضف Markdown.");
                msgs.put(sys);
                JSONObject user=new JSONObject(); user.put("role","user"); user.put("content",task); msgs.put(user);
                String model=Models.resolve(Store.model());
                String answer=Api.chat(Store.key(),model,msgs,0.25,false,null,null);
                JSONObject spec=parseJson(answer);
                File archive=buildPackage(spec);
                runOnUiThread(()->{
                    pendingExport=archive;
                    status.setText("تم إنشاء إضافة ثيم وفحص ملفها. ثبّتها الآن أو صدّر ملف .mt1.");
                    new AlertDialog.Builder(this).setTitle("الإضافة جاهزة")
                        .setMessage("تم إنشاء "+spec.optString("name")+" بصيغة .mt1. هل تريد تثبيتها الآن؟")
                        .setNegativeButton("لاحقاً",(d,w)->exportPackageFile(archive))
                        .setPositiveButton("تثبيت",(d,w)->{
                            try{ engine.install(Uri.fromFile(archive)); refresh(); toast("تم تثبيت الإضافة"); }
                            catch(Exception e){toast("فشل التثبيت: "+message(e));}
                        }).show();
                });
            }catch(Exception e){
                String m="تعذر إنشاء الإضافة: "+message(e);
                runOnUiThread(()->status.setText(m));
            }
        }).start();
    }

    private JSONObject parseJson(String raw)throws Exception{
        int a=raw.indexOf('{'), b=raw.lastIndexOf('}');
        if(a<0||b<=a)throw new Exception("لم يُرجع النموذج JSON صالحاً");
        JSONObject o=new JSONObject(raw.substring(a,b+1));
        JSONObject colors=o.optJSONObject("colors");
        if(colors==null)throw new Exception("ملف الألوان غير موجود");
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3"};
        for(String k:keys) if(!colors.optString(k,"").matches("#[0-9a-fA-F]{8}"))
            throw new Exception("قيمة اللون غير صالحة: "+k);
        String id=o.optString("id","").toLowerCase(Locale.ROOT);
        if(!id.matches("[a-z0-9._-]{3,48}")) throw new Exception("معرّف الإضافة غير صالح");
        return o;
    }

    private File buildPackage(JSONObject spec)throws Exception{
        File out=new File(getCacheDir(),spec.optString("id")+".mt1");
        JSONObject manifest=new JSONObject();
        manifest.put("format","MiMaxExtension"); manifest.put("formatVersion",1);
        manifest.put("apiVersion",1); manifest.put("id",spec.getString("id"));
        manifest.put("name",spec.optString("name",spec.getString("id")));
        manifest.put("version","1.0.0"); manifest.put("type","theme");
        manifest.put("description",spec.optString("description","AI generated theme"));
        manifest.put("permissions",new JSONArray());
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(out))){
            addEntry(zip,"manifest.json",manifest.toString(2));
            addEntry(zip,"resources/theme.json",spec.getJSONObject("colors").toString(2));
            addEntry(zip,"README.txt","Mi_Max Ai .mt1 theme package\nAPI version: 1\nGenerated by AI Extension Creator.");
        }
        return out;
    }
    private void addEntry(ZipOutputStream z,String name,String data)throws Exception{
        z.putNextEntry(new ZipEntry(name)); z.write(data.getBytes("UTF-8")); z.closeEntry();
    }
    private void exportPackage(ExtensionEngine.Item item){
        File f=new File(getCacheDir(),item.id+".mt1");
        try{
            java.io.FileOutputStream out=new FileOutputStream(f);
            ZipOutputStream zip=new ZipOutputStream(out);
            addFile(zip,item.dir,new File(item.dir,"manifest.json"));
            addFolder(zip,item.dir,new File(item.dir,"resources"));
            addFolder(zip,item.dir,new File(item.dir,"app"));
            zip.close(); pendingExport=f; exportPackageFile(f);
        }catch(Exception e){toast("تعذر تجهيز الحزمة: "+message(e));}
    }
    private void addFile(ZipOutputStream z,File root,File f)throws Exception{
        if(!f.isFile())return;
        String rel=root.toURI().relativize(f.toURI()).getPath();
        z.putNextEntry(new ZipEntry(rel)); FileInputStream in=new FileInputStream(f);
        byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1)z.write(b,0,n);
        in.close(); z.closeEntry();
    }
    private void addFolder(ZipOutputStream z,File root,File folder)throws Exception{
        if(!folder.isDirectory())return;
        File[] files=folder.listFiles(); if(files==null)return;
        for(File f:files) if(f.isDirectory())addFolder(z,root,f); else addFile(z,root,f);
    }
    private void exportPackageFile(File f){
        pendingExport=f;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream"); i.putExtra(Intent.EXTRA_TITLE,f.getName());
        try{startActivityForResult(i,SAVE_MT1);}catch(Exception e){toast("تعذر فتح نافذة الحفظ");}
    }
    private String message(Exception e){return e.getMessage()==null?"خطأ غير معروف":e.getMessage();}

    private static final class InputStreamCopy implements AutoCloseable{
        final java.io.InputStream in; final OutputStream out;
        InputStreamCopy(java.io.InputStream i,OutputStream o)throws Exception{if(i==null||o==null)throw new Exception("تعذر فتح ملف النسخ");in=i;out=o;}
        void copy()throws Exception{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b);out.flush();}
        public void close(){try{in.close();}catch(Exception ignored){}try{out.close();}catch(Exception ignored){}}
    }
}
