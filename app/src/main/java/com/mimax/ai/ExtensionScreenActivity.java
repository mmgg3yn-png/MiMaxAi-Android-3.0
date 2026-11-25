package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

public class ExtensionScreenActivity extends Activity {
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);Store.init(this);
        String id=getIntent().getStringExtra("extension_id");
        ExtensionEngine.Item item=new ExtensionEngine(this).find(id==null?"":id);
        if(item==null||!item.enabled||!"ui".equals(item.manifest.optString("type",""))){finish();return;}
        try{
            File f=new File(item.dir,"app/screen.json");if(!f.isFile()||f.length()>256*1024)throw new Exception("ملف الشاشة غير صالح");
            byte[] bytes=new byte[(int)f.length()];try(FileInputStream in=new FileInputStream(f)){int p=0,n;while(p<bytes.length&&(n=in.read(bytes,p,bytes.length-p))>0)p+=n;}
            JSONObject spec=new JSONObject(new String(bytes,"UTF-8"));JSONArray components=spec.optJSONArray("components");
            if(components==null||components.length()>40)throw new Exception("عدد العناصر غير صالح");
            getWindow().setStatusBarColor(0xFF17212D);getWindow().setNavigationBarColor(0xFF17212D);
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(10),dp(14),dp(10));root.setBackgroundColor(0xFFF2F4F7);
            TextView title=new TextView(this);title.setText(spec.optString("title",item.name));title.setTextSize(21);title.setTextColor(0xFF17212D);title.setTypeface(null,1);root.addView(title);
            ScrollView sc=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,dp(10),0,dp(10));sc.addView(body);
            for(int i=0;i<components.length();i++)addComponent(body,components.getJSONObject(i));
            root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
            Button close=new Button(this);close.setText("إغلاق");close.setOnClickListener(v->finish());root.addView(close);
            setContentView(root);
        AppPalette.apply(this, root);
        }catch(Exception e){toast("تعذر فتح الإضافة: "+e.getMessage());finish();}
    }
    private void addComponent(LinearLayout parent,JSONObject c)throws Exception{
        String type=c.optString("type"),value=c.optString("text","");
        if(value.length()>500)throw new Exception("نص عنصر طويل");
        if("text".equals(type)){
            TextView t=new TextView(this);t.setText(value);t.setTextSize("title".equals(c.optString("style"))?18:14);t.setTextColor(0xFF17212D);t.setPadding(dp(4),dp(8),dp(4),dp(8));parent.addView(t);
        }else if("button".equals(type)){
            Button b=new Button(this);b.setText(value);b.setAllCaps(false);parent.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));
            b.setOnClickListener(v->runAction(c.optString("action"),c.optString("value")));
        }else throw new Exception("نوع عنصر غير مدعوم");
    }
    private void runAction(String action,String value){
        try{
            if("toast".equals(action)){toast(value);return;}
            if("openUrl".equals(action)){if(!value.startsWith("https://")){toast("يسمح بروابط HTTPS فقط");return;}startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(value)));return;}
            if("openTool".equals(action)){
                Class<?> target;
                switch(value){
                    case "terminal":target=TerminalActivity.class;break;
                    case "browser":target=BrowserActivity.class;break;
                    case "agent":target=AgentActivity.class;break;
                    case "developer_studio":target=DeveloperStudioActivity.class;break;
                    case "theme_studio":target=ThemeStudioActivity.class;break;
                    case "backup":target=BackupActivity.class;break;
                    case "extensions":target=ExtensionActivity.class;break;
                    default:toast("الأداة غير متاحة");return;
                }
                startActivity(new Intent(this,target));return;
            }
            toast("إجراء غير مدعوم");
        }catch(Exception e){toast("تعذر تنفيذ الإجراء: "+e.getMessage());}
    }
}
