package com.mimax.ai.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * مدير تنزيل متعدد الاتصالات (حتى 16): يقسم الملف إلى أجزاء صغيرة تسحبها الاتصالات تباعاً (تقسيم ديناميكي)،
 * مع استئناف بعد الإيقاف أو الانقطاع (ملف .mmdl)، وإعادة محاولة لكل جزء، وتحقق من اكتمال الحجم.
 */
public final class Fetch {
    private Fetch() { }

    public static class Opts {
        public int connections = 16, connectTimeout = 15000, readTimeout = 30000, retries = 6, maxRedirects = 10;
        public long minSplit = 1L << 20;
        public Map<String, String> headers = new LinkedHashMap<>();
    }

    public static class Info {
        public String finalUrl, name, etag, lastModified, mime;
        public long total = -1;
        public boolean ranges;
    }

    static class NoRange extends IOException { NoRange() { super("الخادم تجاهل طلب المجال (Range)"); } }
    static class Changed extends IOException { Changed() { super("الملف تغيّر على الخادم أثناء التنزيل"); } }

    static HttpURLConnection open(String url, Opts o, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(o.connectTimeout);
        c.setReadTimeout(o.readTimeout);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) MiMaxAi");
        c.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> e : o.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (from >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        return c;
    }

    static long totalFrom(String cr) {
        if (cr == null) return -1;
        int i = cr.lastIndexOf('/');
        if (i < 0) return -1;
        try { return Long.parseLong(cr.substring(i + 1).trim()); } catch (NumberFormatException e) { return -1; }
    }

    static String nameOf(String cd, String url) {
        String n = null;
        if (cd != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*\\s*=\\s*[^']*''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) { try { n = URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8"); } catch (Exception e) { n = null; } }
            if (n == null) {
                m = java.util.regex.Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m.find()) n = m.group(1).trim();
            }
        }
        if (n == null || n.isEmpty()) {
            try {
                String p = new URL(url).getPath();
                n = URLDecoder.decode(p.substring(p.lastIndexOf('/') + 1).replace("+", "%2B"), "UTF-8");
            } catch (Exception e) { n = ""; }
        }
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        return n.isEmpty() ? "download" : n;
    }

    /** يفحص الرابط (يتبع التحويلات يدوياً) ويعيد الحجم ودعم المجال واسم الملف. */
    public static Info probe(String url, Opts o) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= o.maxRedirects; hop++) {
            HttpURLConnection c = open(cur, o, 0, 0);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("تحويل بلا عنوان وجهة (HTTP " + code + ")");
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                Info i = new Info();
                i.finalUrl = cur;
                i.etag = c.getHeaderField("ETag");
                i.lastModified = c.getHeaderField("Last-Modified");
                i.mime = c.getContentType();
                i.name = nameOf(c.getHeaderField("Content-Disposition"), cur);
                if (code == 206) { i.ranges = true; i.total = totalFrom(c.getHeaderField("Content-Range")); }
                else if (code == 200) { long l = c.getContentLengthLong(); i.total = l < 0 ? -1 : l; }
                else if (code == 416 && totalFrom(c.getHeaderField("Content-Range")) == 0) { i.total = 0; i.ranges = true; }
                else throw new IOException("استجاب الخادم بالخطأ HTTP " + code + (c.getResponseMessage() == null ? "" : " " + c.getResponseMessage()));
                return i;
            } finally { c.disconnect(); }
        }
        throw new IOException("تحويلات كثيرة جداً (أكثر من " + o.maxRedirects + ")");
    }

    /** ينزّل إلى dest. عند الإلغاء يبقى dest.part وdest.mmdl للاستئناف (استدعِ discard لحذفهما). */
    public static File download(String url, File dest, Opts o, Io.Progress p, Io.Cancel c) throws IOException {
        Info info = probe(url, o);
        File dir = dest.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        File part = new File(dest.getPath() + ".part"), st = new File(dest.getPath() + ".mmdl");
        if (info.total == 0) {
            if (dest.exists()) dest.delete();
            if (!dest.createNewFile()) throw new IOException("تعذر إنشاء الملف");
            return dest;
        }
        if (info.total < 0 || !info.ranges || info.total < o.minSplit || o.connections <= 1) return single(info, dest, part, st, o, p, c);
        try { return multi(url, info, dest, part, st, o, p, c); }
        catch (NoRange e) { part.delete(); st.delete(); return single(info, dest, part, st, o, p, c); }
    }

    public static void discard(File dest) {
        new File(dest.getPath() + ".part").delete();
        new File(dest.getPath() + ".mmdl").delete();
    }

    static File finish(File part, File dest) throws IOException {
        if (dest.exists() && !dest.delete()) throw new IOException("تعذر استبدال الملف الموجود");
        try { Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) { throw new IOException("تعذر إنهاء الملف: " + e.getMessage()); }
        return dest;
    }

    static File single(Info info, File dest, File part, File st, Opts o, Io.Progress p, Io.Cancel c) throws IOException {
        st.delete();
        File dir = dest.getAbsoluteFile().getParentFile();
        if (info.total > 0) Io.checkSpace(dir, info.total);
        boolean ok = false;
        HttpURLConnection conn = open(info.finalUrl, o, -1, -1);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("استجاب الخادم بالخطأ HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 1 << 16)) {
                byte[] b = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    if (c != null) c.check();
                    out.write(b, 0, n);
                    done += n;
                    if (p != null) p.on(done, total);
                }
            }
            if (total >= 0 && part.length() != total) throw new IOException("اكتمل التنزيل ناقصاً: " + part.length() + " من " + total + " بايت");
            ok = true;
        } finally {
            conn.disconnect();
            if (!ok) part.delete();
        }
        return finish(part, dest);
    }

    // ---------------- الحالة (للاستئناف) ----------------
    static long loadState(File st, File part, String url, Info info, long cs, boolean[] done) {
        try {
            if (!st.isFile() || !part.isFile()) throw new IOException();
            List<String> l = Files.readAllLines(st.toPath(), StandardCharsets.UTF_8);
            if (l.size() < 7 || !l.get(0).equals("MMDL1") || !l.get(1).equals(url) || Long.parseLong(l.get(2)) != info.total
                    || Long.parseLong(l.get(3)) != cs || !l.get(4).equals(String.valueOf(info.etag)) || !l.get(5).equals(String.valueOf(info.lastModified))
                    || part.length() != info.total || l.get(6).length() != done.length) throw new IOException();
            long have = 0;
            for (int i = 0; i < done.length; i++) if (l.get(6).charAt(i) == '1') { done[i] = true; have += Math.min(cs, info.total - i * cs); }
            return have;
        } catch (Exception e) {
            st.delete();
            part.delete();
            return 0;
        }
    }

    static synchronized void saveState(File st, String url, Info info, long cs, boolean[] done) {
        try {
            StringBuilder bits = new StringBuilder();
            for (boolean b : done) bits.append(b ? '1' : '0');
            String txt = "MMDL1\n" + url + "\n" + info.total + "\n" + cs + "\n" + info.etag + "\n" + info.lastModified + "\n" + bits + "\n";
            File tmp = new File(st.getPath() + ".tmp");
            Files.write(tmp.toPath(), txt.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), st.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { /* الحفظ اختياري */ }
    }

    // ---------------- متعدد الاتصالات ----------------
    static File multi(final String origUrl, final Info info, File dest, final File part, final File st, final Opts o, Io.Progress p, Io.Cancel c) throws IOException {
        final long total = info.total;
        final long cs = Math.max(256L << 10, Math.min(8L << 20, (total + o.connections * 4L - 1) / (o.connections * 4L)));
        final int chunks = (int) ((total + cs - 1) / cs);
        final boolean[] done = new boolean[chunks];
        long have = loadState(st, part, origUrl, info, cs, done);
        File dir = dest.getAbsoluteFile().getParentFile();
        Io.checkSpace(dir, total - have);
        final RandomAccessFile raf = new RandomAccessFile(part, "rw");
        final List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < chunks; i++) if (!done[i]) pending.add(i);
        final AtomicInteger next = new AtomicInteger();
        final AtomicLong doneBytes = new AtomicLong(have);
        final AtomicReference<IOException> fail = new AtomicReference<>();
        final boolean[] stop = {false};
        final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
        boolean finished = false;
        try {
            if (raf.length() != total) raf.setLength(total);
            final FileChannel ch = raf.getChannel();
            int nThreads = Math.min(o.connections, Math.max(1, pending.size()));
            Thread[] ts = new Thread[pending.isEmpty() ? 0 : nThreads];
            for (int t = 0; t < ts.length; t++) {
                ts[t] = new Thread(() -> {
                    byte[] buf = new byte[1 << 16];
                    while (!stop[0]) {
                        int k = next.getAndIncrement();
                        if (k >= pending.size()) return;
                        int idx = pending.get(k);
                        long s = idx * cs, e = Math.min(total, s + cs) - 1, pos = s;
                        int attempts = 0;
                        while (pos <= e && !stop[0]) {
                            HttpURLConnection conn = null;
                            try {
                                conn = open(info.finalUrl, o, pos, e);
                                active.add(conn);
                                int code = conn.getResponseCode();
                                if (code == 200) throw new NoRange();
                                if (code != 206) throw new IOException("استجاب الخادم بالخطأ HTTP " + code);
                                long t2 = totalFrom(conn.getHeaderField("Content-Range"));
                                String et = conn.getHeaderField("ETag");
                                if ((t2 >= 0 && t2 != total) || (info.etag != null && et != null && !et.equals(info.etag))) throw new Changed();
                                try (InputStream in = conn.getInputStream()) {
                                    int r;
                                    while (pos <= e && (r = in.read(buf, 0, (int) Math.min(buf.length, e - pos + 1))) > 0) {
                                        if (stop[0]) return;
                                        ch.write(ByteBuffer.wrap(buf, 0, r), pos);
                                        pos += r;
                                        doneBytes.addAndGet(r);
                                        attempts = 0;
                                    }
                                }
                                if (pos <= e) throw new IOException("اتصال مبتور قبل نهاية الجزء");
                            } catch (NoRange | Changed ex) {
                                fail.compareAndSet(null, ex);
                                stop[0] = true;
                                return;
                            } catch (IOException ex) {
                                if (stop[0]) return;
                                if (++attempts > o.retries) { fail.compareAndSet(null, ex); stop[0] = true; return; }
                                try { Thread.sleep(Math.min(3000L, 250L * attempts)); } catch (InterruptedException ie) { return; }
                            } finally {
                                if (conn != null) { active.remove(conn); conn.disconnect(); }
                            }
                        }
                        if (stop[0]) return;
                        synchronized (done) { done[idx] = true; }
                    }
                });
                ts[t].setDaemon(true);
                ts[t].start();
            }
            long lastSave = System.currentTimeMillis();
            for (;;) {
                boolean alive = false;
                for (Thread t : ts) if (t.isAlive()) { alive = true; break; }
                if (p != null) p.on(Math.min(doneBytes.get(), total), total);
                if (c != null && c.cancelled && !stop[0]) { stop[0] = true; for (HttpURLConnection h : active) h.disconnect(); }
                if (!alive) break;
                if (stop[0]) for (HttpURLConnection h : active) h.disconnect();
                long now = System.currentTimeMillis();
                if (now - lastSave > 1000) { synchronized (done) { saveState(st, origUrl, info, cs, done.clone()); } lastSave = now; }
                try { Thread.sleep(120); } catch (InterruptedException ie) { stop[0] = true; Thread.currentThread().interrupt(); break; }
            }
            for (Thread t : ts) { try { t.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
            synchronized (done) { saveState(st, origUrl, info, cs, done.clone()); }
            if (c != null && c.cancelled) throw new Io.CancelledException();
            IOException f = fail.get();
            if (f instanceof NoRange) { part.delete(); st.delete(); throw f; }
            if (f instanceof Changed) { part.delete(); st.delete(); throw f; }
            if (f != null) throw new IOException("فشل التنزيل بعد إعادة المحاولة: " + f.getMessage() + " (يمكن الاستئناف لاحقاً)");
            for (int i = 0; i < chunks; i++) if (!done[i]) throw new IOException("تنزيل ناقص: الجزء " + i + " لم يكتمل");
            ch.force(false);
            finished = true;
        } finally {
            raf.close();
        }
        if (part.length() != total) throw new IOException("اكتمل التنزيل بحجم مختلف: " + part.length() + " من " + total);
        if (p != null) p.on(total, total);
        File out = finish(part, dest);
        st.delete();
        return out;
    }
}
