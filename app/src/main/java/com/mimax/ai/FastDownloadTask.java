package com.mimax.ai;

import android.content.Context;
import android.os.Environment;
import android.webkit.URLUtil;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Range-based downloader with safe single-stream fallback when the server rejects byte ranges. */
public final class FastDownloadTask {
    public interface Listener {
        void onProgress(long downloaded, long total, int connections);
        void onComplete(File file);
        void onError(String message);
    }
    private final Context context;
    private final String url, userAgent, cookie, referer, fileName;
    private final Listener listener;
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean failed;
    private volatile RandomAccessFile output;
    private ExecutorService pool;

    public FastDownloadTask(Context c, String u, String ua, String ck, String ref, String name, Listener l) {
        context=c.getApplicationContext();url=u;userAgent=ua;cookie=ck;referer=ref;fileName=name;listener=l;
    }
    public void start() { new Thread(this::prepare,"MiMax-FastPrepare").start(); }
    public void cancel() { cancelled.set(true); if(pool!=null)pool.shutdownNow(); try{if(output!=null)output.close();}catch(Exception ignored){} }

    private HttpURLConnection connect(long start,long end,boolean range) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding","identity");
        if(userAgent!=null)c.setRequestProperty("User-Agent",userAgent);
        if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
        if(referer!=null&&!referer.isEmpty())c.setRequestProperty("Referer",referer);
        if(range)c.setRequestProperty("Range","bytes="+start+"-"+end);
        return c;
    }
    private void prepare() {
        try {
            HttpURLConnection probe=connect(0,0,true);
            int code=probe.getResponseCode();
            String cr=probe.getHeaderField("Content-Range");
            long total=parseTotal(cr);
            boolean ranges=code==206 && total>1;
            probe.disconnect();
            File dir=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(dir==null)dir=new File(context.getFilesDir(),"downloads");
            if(!dir.exists()&&!dir.mkdirs())throw new Exception("تعذر إنشاء مجلد التنزيلات");
            String safe=fileName==null||fileName.trim().isEmpty()?"download.bin":fileName.replaceAll("[\\\\/:*?\"<>|]","_");
            File file=new File(dir,safe);
            if(file.exists())file=new File(dir,System.currentTimeMillis()+"_"+safe);
            output=new RandomAccessFile(file,"rw");
            if(ranges) {
                output.setLength(total);
                runRanges(file,total,4);
            } else {
                output.setLength(0);
                runSingle(file);
            }
            if(!cancelled.get()&&!failed)listener.onComplete(file);
        } catch(Exception e) {
            if(!cancelled.get())listener.onError(e.getMessage()==null?"فشل التنزيل":e.getMessage());
        } finally {
            try{if(output!=null)output.close();}catch(Exception ignored){}
            if(pool!=null)pool.shutdownNow();
        }
    }
    private long parseTotal(String value) {
        if(value==null)return -1;
        try{return Long.parseLong(value.substring(value.lastIndexOf('/')+1).trim());}
        catch(Exception e){return -1;}
    }
    private void runRanges(File file,long total,int parts) throws Exception {
        pool=Executors.newFixedThreadPool(parts);
        long chunk=(total+parts-1)/parts;
        for(int i=0;i<parts;i++){
            final long start=i*chunk, end=Math.min(total-1,start+chunk-1);
            if(start>=total)break;
            pool.submit(()->{
                active.incrementAndGet();
                HttpURLConnection c=null;
                try{
                    c=connect(start,end,true);
                    int code=c.getResponseCode();
                    String rangeHeader=c.getHeaderField("Content-Range");
                    if(code!=206 || rangeHeader==null || !rangeHeader.startsWith("bytes "+start+"-"+end+"/"))
                        throw new Exception("الخادم لم يؤكد نطاق الجزء المطلوب");
                    try(InputStream in=c.getInputStream()){
                        byte[] b=new byte[64*1024];long pos=start;int n;
                        while(!cancelled.get()&&(n=in.read(b))!=-1){
                            synchronized(output){output.seek(pos);output.write(b,0,n);}
                            pos+=n;long now=downloaded.addAndGet(n);
                            listener.onProgress(now,total,active.get());
                        }
                        if(pos!=end+1)throw new Exception("استجابة غير مكتملة من الخادم");
                    }
                }catch(Exception e){
                    if(!cancelled.get()){
                        failed=true;cancelled.set(true);
                        listener.onError(e.getMessage()==null?"فشل جزء من التنزيل":e.getMessage());
                    }
                }
                finally{if(c!=null)c.disconnect();active.decrementAndGet();}
            });
        }
        pool.shutdown();
        while(!pool.isTerminated()){try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();pool.shutdownNow();break;}}
        if(cancelled.get()||failed)throw new Exception("تم إيقاف التنزيل أو فشل أحد أجزائه");
        if(downloaded.get()!=total)throw new Exception("حجم الملف المستلم غير مكتمل");
    }
    private void runSingle(File file) throws Exception {
        HttpURLConnection c=connect(0,0,false);
        try{
            int code=c.getResponseCode();if(code<200||code>=300)throw new Exception("HTTP "+code);
            long total=c.getContentLengthLong();
            try(InputStream in=c.getInputStream()){
                byte[] b=new byte[64*1024];int n;
                while(!cancelled.get()&&(n=in.read(b))!=-1){
                    output.write(b,0,n);long now=downloaded.addAndGet(n);
                    listener.onProgress(now,total,1);
                }
            }
            if(cancelled.get())throw new Exception("أُلغي التنزيل");
        }finally{c.disconnect();}
    }
}
