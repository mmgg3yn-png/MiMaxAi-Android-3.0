package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Separate .mt_1 theme packages; intentionally not the .mt1 extension format. */
public class ThemeStudioActivity extends Activity {
    private static final int IMPORT_THEME=940, EXPORT_THEME=941;
    private File themesDir, pendingExport;
    private LinearLayout list;
    private TextView status;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(5),dp(4),dp(5));return t;}
    private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}

    @Override public void onCreate(Bundle b){
        super.onCreate(b); Store.init(this);
        themesDir=new File(getFilesDir(),"themes");themesDir.mkdirs();
        getWindow().setStatusBarColor(0xFF17212D);getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackgroundColor(0xFFF2F4F7);
        TextView title=text("Mi_Max Theme Studio • .mt_1",20,0xFF17212D);title.setTypeface(null,1);root.addView(title);
        root.addView(text("حزم الثيمات مستقلة عن إضافات .mt1. يمكن استيرادها ومعاينتها وتطبيقها وتصديرها.",13,0xFF536273));
        root.addView(button("استيراد ثيم .mt_1",this::pickImport));
        root.addView(button("إنشاء ثيم بالذكاء الاصطناعي",this::createAi));
        status=text("جاهز",12,0xFF536273);root.addView(status);
        ScrollView sc=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(button("العودة",this::finish));setContentView(root);
        AppPalette.apply(this, root);refresh();
    }

    private void pickImport(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(i,IMPORT_THEME);}catch(Exception e){toast("تعذر فتح مدير الملفات");}
    }

    private void createAi(){
        EditText prompt=new EditText(this);prompt.setMinLines(3);prompt.setGravity(Gravity.TOP|Gravity.START);prompt.setHint("صف الثيم: مثلاً زجاج داكن أزرق...");
        new AlertDialog.Builder(this).setTitle("إنشاء ثيم .mt_1").setView(prompt).setNegativeButton("إلغاء",null).setPositiveButton("إنشاء",(d,w)->generate(prompt.getText().toString().trim())).show();
    }

    private void generate(String description){
        if(description.isEmpty()){toast("اكتب وصف الثيم");return;}
        if(Store.key().isEmpty()){toast("مفتاح OpenRouter غير متاح");return;}
        status.setText("يتم تصميم الثيم...");
        new Thread(()->{
            try{
                JSONArray messages=new JSONArray();
                JSONObject sys=new JSONObject();sys.put("role","system");
                sys.put("content","صمم لوحة ألوان لتطبيق Android. أعد JSON فقط بهذا الشكل {\"name\":\"Theme\",\"description\":\"...\",\"colors\":{\"base\":\"#AARRGGBB\",\"surface\":\"#AARRGGBB\",\"surface2\":\"#AARRGGBB\",\"line\":\"#AARRGGBB\",\"tx\":\"#AARRGGBB\",\"mut\":\"#AARRGGBB\",\"ac\":\"#AARRGGBB\",\"er\":\"#AARRGGBB\",\"b0\":\"#AARRGGBB\",\"b1\":\"#AARRGGBB\",\"b2\":\"#AARRGGBB\",\"b3\":\"#AARRGGBB\"}}. كل لون 8 خانات hex بعد #. لا Markdown.");
                messages.put(sys);JSONObject user=new JSONObject();user.put("role","user");user.put("content",description);messages.put(user);
                String answer=Api.chat(Store.key(),Models.resolve(Store.model()),messages,0.25,false,null,null);
                JSONObject spec=parse(answer);File f=makeTheme(spec);
                runOnUiThread(()->{status.setText("تم إنشاء "+spec.optString("name")+" بصيغة .mt_1");refresh();toast("تم حفظ الثيم");});
            }catch(Exception e){runOnUiThread(()->status.setText("فشل إنشاء الثيم: "+e.getMessage()));}
        }).start();
    }

    private JSONObject parse(String raw)throws Exception{
        int a=raw.indexOf('{'),b=raw.lastIndexOf('}');if(a<0||b<=a)throw new Exception("لم يصل JSON صالح");
        JSONObject o=new JSONObject(raw.substring(a,b+1));JSONObject colors=o.optJSONObject("colors");if(colors==null)throw new Exception("الألوان غير موجودة");
        validateColors(colors);return o;
    }

    private void validateColors(JSONObject c)throws Exception{
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3"};
        for(String k:keys)if(!c.optString(k,"").matches("#[0-9a-fA-F]{8}"))throw new Exception("لون غير صالح: "+k);
    }

    private File makeTheme(JSONObject spec)throws Exception{
        String name=spec.optString("name","Custom Theme");
        String id=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","_");
        if(id.length()<2)id="theme_"+Long.toString(System.currentTimeMillis(),36);
        JSONObject manifest=new JSONObject();manifest.put("format","MiMaxTheme");manifest.put("formatVersion",1);manifest.put("id",id+"_"+Long.toString(System.currentTimeMillis(),36));manifest.put("name",name);manifest.put("description",spec.optString("description",""));manifest.put("colors",spec.getJSONObject("colors"));
        File out=new File(themesDir,manifest.optString("id")+".mt_1");
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){put(z,"theme.json",manifest.toString(2));put(z,"README.txt","Mi_Max Ai Theme Package (.mt_1)\nFormat version 1\n");}
        return out;
    }

    private void importTheme(Uri uri)throws Exception{
        File temp=new File(getCacheDir(),"theme-import-"+System.currentTimeMillis());temp.mkdirs();
        JSONObject manifest=null;long total=0;int count=0;
        try(InputStream raw=getContentResolver().openInputStream(uri);ZipInputStream zip=new ZipInputStream(raw)){
            if(raw==null)throw new Exception("تعذر قراءة الملف");
            ZipEntry e;byte[] buf=new byte[4096];
            while((e=zip.getNextEntry())!=null){
                if(e.isDirectory())continue;
                String n=e.getName().replace('\\','/');
                if(n.startsWith("/")||n.contains("../")||n.indexOf(':')>=0)throw new Exception("مسار غير آمن");
                if(++count>20)throw new Exception("ملفات كثيرة داخل الثيم");
                if(!n.equals("theme.json")&&!n.equals("README.txt"))throw new Exception("ملف غير معروف داخل الثيم: "+n);
                File f=new File(temp,n);f.getParentFile().mkdirs();
                try(FileOutputStream out=new FileOutputStream(f)){int k;while((k=zip.read(buf))!=-1){total+=k;if(total>1024*1024)throw new Exception("حجم الثيم أكبر من 1MB");out.write(buf,0,k);}}
                zip.closeEntry();
            }
        }
        File mf=new File(temp,"theme.json");if(!mf.isFile())throw new Exception("theme.json غير موجود");
        byte[] bytes=new byte[(int)mf.length()];try(FileInputStream in=new FileInputStream(mf)){in.read(bytes);}
        manifest=new JSONObject(new String(bytes,"UTF-8"));
        if(!"MiMaxTheme".equals(manifest.optString("format"))||manifest.optInt("formatVersion")!=1)throw new Exception("صيغة الثيم غير مدعومة");
        validateColors(manifest.getJSONObject("colors"));
        String id=manifest.optString("id","").replaceAll("[^A-Za-z0-9._-]","_");if(id.length()<3)throw new Exception("معرّف الثيم غير صالح");
        File out=new File(themesDir,id+".mt_1");if(out.exists())throw new Exception("الثيم مستورد مسبقاً");
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){put(z,"theme.json",manifest.toString(2));put(z,"README.txt","Imported Mi_Max Ai theme package.");}
        deleteTree(temp);
    }

    private void refresh(){
        list.removeAllViews();File[] files=themesDir.listFiles();int n=0;
        if(files!=null)for(File f:files){if(!f.isFile()||!f.getName().endsWith(".mt_1"))continue;n++;
            try{
                JSONObject m=readManifest(f);String name=m.optString("name",f.getName());
                LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackgroundColor(0xFFFFFFFF);
                TextView t=text(name+" • .mt_1",16,0xFF17212D);t.setTypeface(null,1);card.addView(t);
                card.addView(text(m.optString("description","ثيم مستورد"),12,0xFF64748B));
                card.addView(button("معاينة الألوان",()->preview(m)));
                card.addView(button("تطبيق الثيم",()->apply(m)));
                card.addView(button("تصدير .mt_1",()->export(f)));
                card.addView(button("حذف",()->new AlertDialog.Builder(this).setMessage("حذف الثيم؟").setNegativeButton("إلغاء",null).setPositiveButton("حذف",(d,w)->{f.delete();refresh();}).show()));
                list.addView(card);
            }catch(Exception e){list.addView(text("ثيم تالف: "+f.getName(),13,0xFFB91C1C));}
        }
        if(n==0)list.addView(text("لا توجد ثيمات مستوردة بعد.",13,0xFF64748B));
    }

    private JSONObject readManifest(File archive)throws Exception{
        try(ZipInputStream z=new ZipInputStream(new FileInputStream(archive))){
            ZipEntry e;while((e=z.getNextEntry())!=null)if("theme.json".equals(e.getName())){
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=z.read(b))!=-1){if(out.size()+n>1024*1024)throw new Exception("ملف كبير");out.write(b,0,n);}
                JSONObject m=new JSONObject(out.toString("UTF-8"));if(!"MiMaxTheme".equals(m.optString("format"))||m.optInt("formatVersion")!=1)throw new Exception("صيغة غير مدعومة");validateColors(m.getJSONObject("colors"));return m;
            }
        }
        throw new Exception("theme.json غير موجود");
    }

    private void preview(JSONObject m){
        JSONObject c=m.optJSONObject("colors");if(c==null)return;
        StringBuilder b=new StringBuilder(m.optString("name")+"\n");
        java.util.Iterator<String> it=c.keys();while(it.hasNext()){String k=it.next();b.append(k).append("  ").append(c.optString(k)).append("\n");}
        new AlertDialog.Builder(this).setTitle("معاينة لوحة الألوان").setMessage(b.toString()).setPositiveButton("إغلاق",null).show();
    }

    private void apply(JSONObject m){
        try{
            JSONObject c=m.getJSONObject("colors");String[] keys={"base","surface","surface2","line","tx","mut","ac","er","b0","b1","b2","b3"};
            for(String k:keys)Store.setCc(k,(int)Long.parseLong(c.getString(k).substring(1),16));
            Store.setFlag("ccOn",true);MainActivity.extensionThemeChanged=true;
            toast("تم تطبيق الثيم. ستُحدّث واجهة التطبيق عند العودة.");finish();
        }catch(Exception e){toast("تعذر تطبيق الثيم: "+e.getMessage());}
    }

    private void export(File f){
        pendingExport=f;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,f.getName());
        try{startActivityForResult(i,EXPORT_THEME);}catch(Exception e){toast("تعذر فتح حفظ الملفات");}
    }

    private void put(ZipOutputStream z,String name,String data)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(data.getBytes("UTF-8"));z.closeEntry();}
    private void deleteTree(File f){if(f==null||!f.exists())return;File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);f.delete();}

    @Override protected void onActivityResult(int rq,int rs,Intent data){
        super.onActivityResult(rq,rs,data);if(rs!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            if(rq==IMPORT_THEME){importTheme(data.getData());refresh();toast("تم استيراد الثيم");}
            else if(rq==EXPORT_THEME&&pendingExport!=null){try(InputStream in=new FileInputStream(pendingExport);OutputStream out=getContentResolver().openOutputStream(data.getData())){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}toast("تم تصدير الثيم");}
        }catch(Exception e){toast("فشلت العملية: "+e.getMessage());}
    }
}
