package com.mimax.ai;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;

public class BrowserActivity extends Activity {
    private WebView web;
    private EditText address;
    private ProgressBar progress;
    private LinearLayout root, toolbar;
    private boolean desktop=false, immersive=false;
    private static final String PREF="mimax_browser";
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF17212D);getWindow().setNavigationBarColor(0xFF17212D);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xFFF6F7F9);
        toolbar=new LinearLayout(this);toolbar.setOrientation(LinearLayout.VERTICAL);toolbar.setPadding(dp(5),dp(4),dp(5),dp(2));
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
        address=new EditText(this);address.setSingleLine(true);address.setTextSize(14);address.setHint("ابحث أو أدخل رابطاً");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        line.addView(address,new LinearLayout.LayoutParams(0,dp(46),1));
        addButton(line,"فتح",()->loadAddress());toolbar.addView(line);
        LinearLayout actions=new LinearLayout(this);
        addButton(actions,"رجوع",()->{if(web.canGoBack())web.goBack();});
        addButton(actions,"تقدم",()->{if(web.canGoForward())web.goForward();});
        addButton(actions,"تحديث",()->web.reload());
        addButton(actions,"الرئيسية",()->web.loadUrl("https://www.google.com"));
        addButton(actions,"⋮",this::showMenu);
        toolbar.addView(actions);root.addView(toolbar);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        web=new WebView(this);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(true);s.setAllowFileAccess(false);s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(true);
        s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setMediaPlaybackRequiresUserGesture(true);
        if(Build.VERSION.SDK_INT>=26)s.setSafeBrowsingEnabled(true);
        if(Build.VERSION.SDK_INT>=21)s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return handleUrl(r.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return handleUrl(u);}
            @Override public void onPageFinished(WebView v,String u){address.setText(u);saveHistory(u);}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}
            @Override public void onReceivedTitle(WebView v,String t){setTitle(t==null?"Mi_Max Browser":t);}
            @Override public boolean onCreateWindow(WebView v,boolean dialog,boolean gesture,android.os.Message resultMsg){
                WebView.HitTestResult hit=v.getHitTestResult();String u=hit==null?null:hit.getExtra();
                if(u!=null)web.loadUrl(u);else toast("فتح النوافذ الجديدة غير مدعوم بعد");
                return false;
            }
        });
        web.setDownloadListener((u,ua,cd,mime,len)->download(u,ua,cd,mime));
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        Button back=new Button(this);back.setText("العودة إلى Mi_Max Ai");back.setAllCaps(false);back.setOnClickListener(v->finish());
        root.addView(back,new LinearLayout.LayoutParams(-1,dp(42)));setContentView(root);AppPalette.apply(this,root);
        if(state==null)web.loadUrl("https://www.google.com");else if(state.getString("url")!=null)web.loadUrl(state.getString("url"));
    }
    private void addButton(LinearLayout row,String label,Runnable run){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(11);row.addView(b,new LinearLayout.LayoutParams(0,dp(42),1));b.setOnClickListener(v->run.run());}
    private boolean handleUrl(String u){
        if(u==null)return true;
        if(u.startsWith("http://")||u.startsWith("https://"))return false;
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));}catch(Exception e){toast("لا يمكن فتح الرابط");}
        return true;
    }
    private void loadAddress(){
        String q=address.getText().toString().trim();if(q.isEmpty())return;
        if(!q.matches("(?i)^https?://.*")){
            if(q.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(:[0-9]+)?(/.*)?$"))q="https://"+q;
            else q="https://www.google.com/search?q="+Uri.encode(q);
        }web.loadUrl(q);
    }
    private void showMenu(){
        String[] items={"وضع سطح المكتب: "+(desktop?"مفعّل":"متوقف"),"ملء الشاشة: "+(immersive?"مفعّل":"متوقف"),
            "إضافة للمفضلة","المفضلة","السجل","التنزيلات","بحث داخل الصفحة","مشاركة الرابط","مسح بيانات التصفح"};
        new AlertDialog.Builder(this).setTitle("Mi_Max Browser").setItems(items,(d,w)->{
            switch(w){
                case 0: desktop=!desktop;applyDesktop();toast(desktop?"تم تفعيل وضع سطح المكتب":"تم إيقاف وضع سطح المكتب");break;
                case 1: immersive=!immersive;applyImmersive();break;
                case 2: saveBookmark();break;
                case 3: showSaved("bookmarks","المفضلة");break;
                case 4: showSaved("history","السجل");break;
                case 5: showDownloads();break;
                case 6: findOnPage();break;
                case 7: sharePage();break;
                case 8: clearBrowsingData();break;
            }
        }).show();
    }
    private void applyDesktop(){
        WebSettings s=web.getSettings();
        String ua=s.getUserAgentString();
        if(desktop){
            ua=ua.replace("Mobile","").replace("Android","X11; Linux x86_64");
            s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);
        }else{
            ua=WebSettings.getDefaultUserAgent(this);s.setUseWideViewPort(false);s.setLoadWithOverviewMode(false);
        }
        s.setUserAgentString(ua);web.reload();
    }
    private void applyImmersive(){
        if(immersive)getWindow().getDecorView().setSystemUiVisibility( View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        toolbar.setVisibility(immersive?View.GONE:View.VISIBLE);
        toast(immersive?"ملء الشاشة مفعّل":"ملء الشاشة متوقف");
    }
    private void saveBookmark(){
        String u=web.getUrl();if(u==null)return;
        android.content.SharedPreferences p=getSharedPreferences(PREF,0);
        JSONArray a;try{a=new JSONArray(p.getString("bookmarks","[]"));}catch(Exception e){a=new JSONArray();}
        for(int i=0;i<a.length();i++)if(u.equals(a.optString(i))){toast("الرابط محفوظ مسبقاً");return;}
        a.put(u);p.edit().putString("bookmarks",a.toString()).apply();toast("تمت إضافة الصفحة للمفضلة");
    }
    private void saveHistory(String u){
        if(u==null||(!u.startsWith("http://")&&!u.startsWith("https://")))return;
        android.content.SharedPreferences p=getSharedPreferences(PREF,0);JSONArray a;
        try{a=new JSONArray(p.getString("history","[]"));}catch(Exception e){a=new JSONArray();}
        if(a.length()>0&&u.equals(a.optString(a.length()-1)))return;
        a.put(u);while(a.length()>100) a.remove(0);
        p.edit().putString("history",a.toString()).apply();
    }
    private void showSaved(String key,String title){
        android.content.SharedPreferences p=getSharedPreferences(PREF,0);JSONArray a;
        try{a=new JSONArray(p.getString(key,"[]"));}catch(Exception e){a=new JSONArray();}
        ArrayList<String> values=new ArrayList<>();for(int i=a.length()-1;i>=0;i--)values.add(a.optString(i));
        if(values.isEmpty()){toast("لا توجد عناصر");return;}
        String[] rows=values.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(title).setItems(rows,(d,w)->web.loadUrl(rows[w]))
            .setNeutralButton("مسح", (d,w)->{p.edit().remove(key).apply();toast("تم المسح");}).show();
    }
    private void showDownloads(){
        DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query q=new DownloadManager.Query();Cursor c=dm.query(q);
        ArrayList<String> rows=new ArrayList<>();ArrayList<Long> ids=new ArrayList<>();
        if(c!=null){while(c.moveToNext()){
            String name=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
            int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            String state=status==DownloadManager.STATUS_SUCCESSFUL?"مكتمل":status==DownloadManager.STATUS_FAILED?"فشل": "قيد التنزيل";
            rows.add(name+" — "+state);ids.add(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
        }c.close();}
        if(rows.isEmpty()){toast("لا توجد تنزيلات");return;}
        new AlertDialog.Builder(this).setTitle("التنزيلات").setItems(rows.toArray(new String[0]),(d,w)->{
            DownloadManager.Query one=new DownloadManager.Query().setFilterById(ids.get(w));Cursor x=dm.query(one);
            if(x!=null&&x.moveToFirst()){
                int st=x.getInt(x.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                String path=x.getString(x.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));x.close();
                if(st==DownloadManager.STATUS_SUCCESSFUL&&path!=null)try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(path),"*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast("لا يوجد تطبيق لفتح الملف");}
                else toast("التنزيل لم يكتمل بعد");
            }else if(x!=null)x.close();
        }).show();
    }
    private void findOnPage(){
        EditText q=new EditText(this);q.setSingleLine(true);q.setHint("النص المطلوب");
        new AlertDialog.Builder(this).setTitle("بحث داخل الصفحة").setView(q).setPositiveButton("بحث",(d,w)->web.findAllAsync(q.getText().toString())).setNegativeButton("إلغاء",null).show();
    }
    private void sharePage(){
        String u=web.getUrl();if(u==null)return;
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,u);
        startActivity(Intent.createChooser(i,"مشاركة الرابط"));
    }
    private void clearBrowsingData(){
        new AlertDialog.Builder(this).setTitle("مسح بيانات التصفح").setMessage("سيتم مسح السجل والكوكيز وذاكرة الصفحات المؤقتة. لن تُحذف الملفات التي نزّلتها.")
        .setPositiveButton("مسح",(d,w)->{
            web.clearCache(true);web.clearHistory();android.webkit.CookieManager.getInstance().removeAllCookies(null);
            getSharedPreferences(PREF,0).edit().remove("history").apply();toast("تم مسح بيانات التصفح");
        }).setNegativeButton("إلغاء",null).show();
    }
    private void download(String url,String ua,String disposition,String mime){
        try{
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url));
            String name=android.webkit.URLUtil.guessFileName(url,disposition,mime);
            r.setTitle(name);r.setDescription("Mi_Max Ai Browser");if(mime!=null)r.setMimeType(mime);
            if(ua!=null)r.addRequestHeader("User-Agent",ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"MiMaxAi/"+name);
            ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);toast("بدأ التنزيل");
        }catch(Exception e){toast("تعذر بدء التنزيل");}
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(web!=null)b.putString("url",web.getUrl());}
    @Override public void onBackPressed(){if(immersive){immersive=false;applyImmersive();}else if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
