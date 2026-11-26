package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Runs declarative .mt1 tools through a small, allow-listed host API. */
public final class ExtensionToolActivity extends Activity {
    private static final int PICK_FILE = 910;
    private ExtensionEngine engine;
    private ExtensionEngine.Item item;
    private JSONObject selectedAction;
    private LinearLayout body;
    private TextView output;

    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private TextView label(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setPadding(dp(5),dp(7),dp(5),dp(7));return t;
    }
    private Button button(String s,Runnable r){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        engine=new ExtensionEngine(this);
        String id=getIntent().getStringExtra("extension_id");
        item=engine.find(id==null?"":id);
        if(item==null || !item.enabled || !"tool".equals(item.manifest.optString("type"))){
            toast("الإضافة غير موجودة أو غير مفعّلة");finish();return;
        }
        try{
            JSONObject spec=readToolSpec();
            getWindow().setStatusBarColor(0xFF17212D);
            getWindow().setNavigationBarColor(0xFF17212D);
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14),dp(10),dp(14),dp(12));root.setBackgroundColor(0xFFF2F4F7);
            TextView title=label(spec.optString("title",item.name),21,0xFF17212D);title.setTypeface(null,1);root.addView(title);
            root.addView(label(item.manifest.optString("description","أداة إضافية .mt1"),13,0xFF536273));
            ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            JSONArray actions=spec.getJSONArray("actions");
            for(int i=0;i<actions.length();i++){
                JSONObject action=actions.getJSONObject(i);
                body.addView(button(action.optString("title","تشغيل الأداة"),()->runAction(action)));
            }
            output=label("جاهز. اختر عملية لبدء استخدامها.",13,0xFF334155);
            output.setTextIsSelectable(true);body.addView(output);
            root.addView(button("إغلاق",this::finish));
            setContentView(root);AppPalette.apply(this,root);
        }catch(Exception e){toast("ملف الأداة غير صالح: "+e.getMessage());finish();}
    }

    private JSONObject readToolSpec() throws Exception {
        java.io.File f=new java.io.File(item.dir,"app/tool.json");
        if(!f.isFile() || f.length()>128*1024)throw new Exception("app/tool.json غير موجود أو كبير");
        byte[] b=new byte[(int)f.length()];
        try(java.io.FileInputStream in=new java.io.FileInputStream(f)){
            int p=0,n;while(p<b.length&&(n=in.read(b,p,b.length-p))>0)p+=n;
        }
        return new JSONObject(new String(b,"UTF-8"));
    }

    private void runAction(JSONObject action){
        selectedAction=action;
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(pick,PICK_FILE);}catch(Exception e){toast("تعذر فتح منتقي الملفات");}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_FILE || result!=RESULT_OK || data==null || data.getData()==null)return;
        final Uri uri=data.getData();
        final String kind=selectedAction==null?"":selectedAction.optString("kind","");
        output.setText("جارٍ تنفيذ العملية...");
        new Thread(()->{
            String resultText;
            try{
                if("file_info".equals(kind)) resultText=fileInfo(uri);
                else if("sha256".equals(kind)) resultText=sha256(uri);
                else if("hex_preview".equals(kind)) resultText=hexPreview(uri);
                else if("text_preview".equals(kind)) resultText=textPreview(uri);
                else if("detect_image".equals(kind)) resultText=detectImage(uri);
                else throw new Exception("نوع العملية غير مدعوم");
            }catch(Exception e){resultText="فشلت العملية: "+(e.getMessage()==null?"خطأ":e.getMessage());}
            final String shown=resultText;
            runOnUiThread(()->output.setText(shown));
        }).start();
    }

    private String fileInfo(Uri uri)throws Exception{
        android.database.Cursor c=getContentResolver().query(uri,null,null,null,null);
        String name="ملف محدد";long size=-1;
        if(c!=null){try{
            int ni=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            int si=c.getColumnIndex(android.provider.OpenableColumns.SIZE);
            if(ni>=0&&c.moveToFirst())name=c.getString(ni);
            if(si>=0&&c.moveToFirst())size=c.getLong(si);
        }finally{c.close();}}
        String mime=getContentResolver().getType(uri);
        return "معلومات الملف\nالاسم: "+name+"\nالحجم: "+(size<0?"غير معروف":size+" bytes")+"\nMIME: "+(mime==null?"غير معروف":mime);
    }
    private String sha256(Uri uri)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("تعذر فتح الملف");
            byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){md.update(b,0,n);count+=n;}
        }
        StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));
        return "SHA-256\n"+h+"\nالحجم المقروء: "+count+" bytes";
    }
    private String hexPreview(Uri uri)throws Exception{
        StringBuilder s=new StringBuilder("Hex Preview — أول 4096 بايت\n");
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("تعذر فتح الملف");
            byte[] b=new byte[4096];int n=in.read(b);if(n<0)n=0;
            for(int i=0;i<n;i++){
                if(i%16==0)s.append(String.format(Locale.ROOT,"\n%08X  ",i));
                s.append(String.format(Locale.ROOT,"%02X ",b[i]&255));
            }
            s.append("\n\nتم عرض ").append(n).append(" بايت فقط.");
        }
        return s.toString();
    }
    private String textPreview(Uri uri)throws Exception{
        byte[] b=new byte[16384];int n;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("تعذر فتح الملف");n=in.read(b);
        }
        if(n<0)n=0;
        return "معاينة نصية — حتى 16KB\n"+new String(b,0,n,"UTF-8");
    }
    private String detectImage(Uri uri)throws Exception{
        byte[] b=new byte[64];int n;
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new Exception("تعذر فتح الملف");n=in.read(b);
        }
        if(n<0)n=0;
        String type="غير معروف";
        if(n>=4 && (b[0]&255)==0x3A && (b[1]&255)==0xFF && (b[2]&255)==0x26 && (b[3]&255)==0xED)
            type="Android Sparse Image";
        else if(n>=4 && (b[0]&255)==0xE2 && (b[1]&255)==0xE1 && (b[2]&255)==0xF5 && (b[3]&255)==0xE0)
            type="EROFS (magic)";
        else if(n>=4 && (b[0]&255)==0x28 && (b[1]&255)==0xB5 && (b[2]&255)==0x2F && (b[3]&255)==0xFD)
            type="Zstandard";
        else if(n>=4 && (b[0]&255)==0x04 && (b[1]&255)==0x22 && (b[2]&255)==0x4D && (b[3]&255)==0x18)
            type="LZ4 frame";
        else if(n>=4 && (b[0]&255)==0x50 && (b[1]&255)==0x4B)
            type="ZIP";
        return "نتيجة التعرف من الترويسة\nالنوع المحتمل: "+type+"\nملاحظة: هذا فحص توقيع أولي وليس تحققاً كاملاً من سلامة الصورة.";
    }
}
