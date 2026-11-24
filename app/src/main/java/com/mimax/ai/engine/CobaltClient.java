package com.mimax.ai.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** عميل Cobalt API: فحص حالة الخادم، وطلب تنزيل رابط، مع ترجمة رموز الأخطاء إلى رسائل عربية مفهومة. */
public final class CobaltClient {
    private CobaltClient() { }

    public static final String DEFAULT_SERVER = "https://rue-cobalt.xenon.zone";

    public static class Status {
        public boolean online;
        public String version, url, error;
        public List<String> services = new ArrayList<>();
        public long ms;
    }

    public static class Item { public String type, url; }

    public static class Result {
        public String kind, url, filename, audio;
        public List<Item> picker = new ArrayList<>();
    }

    public static class Err extends IOException {
        public final String code;
        public Err(String code, String msg) { super(msg); this.code = code; }
    }

    public static String normalize(String base) throws IOException {
        String b = base == null ? "" : base.trim();
        if (b.isEmpty()) throw new IOException("لم يُحدَّد رابط خادم التنزيل");
        if (!b.matches("(?i)https?://.+")) b = "https://" + b;
        try { new URL(b); } catch (Exception e) { throw new IOException("رابط الخادم غير صالح: " + base); }
        return b.endsWith("/") ? b : b + "/";
    }

    static String read(HttpURLConnection c, int code) throws IOException {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = i.read(b)) > 0 && o.size() < (4 << 20)) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** يفحص الخادم: GET على الجذر يعيد JSON فيه كائن cobalt. */
    public static Status status(String base, int timeoutMs) {
        Status s = new Status();
        long t0 = System.currentTimeMillis();
        try {
            String url = normalize(base);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "MiMaxAi");
            try {
                int code = c.getResponseCode();
                String body = read(c, code);
                s.ms = System.currentTimeMillis() - t0;
                if (code == 401 || code == 403) { s.error = "الخادم يرفض الطلب (HTTP " + code + "): قد يتطلب مفتاحاً أو يمنع هذا الاتصال"; return s; }
                if (code == 429) { s.error = "الخادم يحدّ الطلبات حالياً (HTTP 429)، أعد المحاولة بعد قليل"; return s; }
                if (code >= 500) { s.error = "خطأ في الخادم (HTTP " + code + ")"; return s; }
                if (code != 200) { s.error = "ردّ غير متوقع (HTTP " + code + ")"; return s; }
                Map<String, Object> m;
                try { m = MiniJson.map(MiniJson.parse(body)); } catch (IOException e) { m = null; }
                Map<String, Object> cb = m == null ? null : MiniJson.map(m.get("cobalt"));
                if (cb == null) { s.error = "الرد ليس من خادم Cobalt (لا يحتوي كائن cobalt)"; return s; }
                s.online = true;
                s.version = MiniJson.str(cb, "version");
                s.url = MiniJson.str(cb, "url");
                List<Object> sv = MiniJson.list(cb.get("services"));
                if (sv != null) for (Object o : sv) s.services.add(MiniJson.str(o));
                return s;
            } finally { c.disconnect(); }
        } catch (java.net.UnknownHostException e) { s.error = "تعذر العثور على الخادم (تحقق من الرابط والإنترنت)"; }
        catch (java.net.SocketTimeoutException e) { s.error = "انتهت مهلة الاتصال بالخادم"; }
        catch (javax.net.ssl.SSLException e) { s.error = "فشل الاتصال الآمن (SSL): " + e.getMessage(); }
        catch (IOException e) { s.error = e.getMessage() == null ? "تعذر الاتصال بالخادم" : "تعذر الاتصال: " + e.getMessage(); }
        s.ms = System.currentTimeMillis() - t0;
        return s;
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** طلب تنزيل. mode: auto | audio | mute. quality مثل 1080 أو max. */
    public static Result resolve(String base, String link, String apiKey, String quality, String mode) throws IOException {
        String url = normalize(base);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "MiMaxAi");
        if (apiKey != null && !apiKey.isEmpty()) c.setRequestProperty("Authorization", "Api-Key " + apiKey);
        String body = "{\"url\":" + esc(link) + ",\"videoQuality\":" + esc(quality == null ? "1080" : quality)
                + ",\"downloadMode\":" + esc(mode == null ? "auto" : mode) + ",\"filenameStyle\":\"basic\"}";
        int code;
        String resp;
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            code = c.getResponseCode();
            resp = read(c, code);
        } catch (java.net.UnknownHostException e) { throw new Err("net", "تعذر العثور على الخادم (تحقق من الرابط والإنترنت)"); }
        catch (java.net.SocketTimeoutException e) { throw new Err("timeout", "انتهت مهلة الخادم. قد يكون الفيديو طويلاً أو الخادم مزدحماً"); }
        catch (IOException e) { throw new Err("net", "تعذر الاتصال بالخادم: " + e.getMessage()); }
        finally { c.disconnect(); }
        Map<String, Object> m;
        try { m = MiniJson.map(MiniJson.parse(resp)); } catch (IOException e) { m = null; }
        if (m == null) {
            if (code == 401 || code == 403) throw new Err("auth", "الخادم يرفض الطلب (HTTP " + code + "): قد يتطلب مفتاح API من الإعدادات");
            if (code == 429) throw new Err("rate", "الخادم يحدّ الطلبات حالياً، أعد المحاولة بعد قليل");
            throw new Err("bad_response", "ردّ غير مفهوم من الخادم (HTTP " + code + ")");
        }
        String st = MiniJson.str(m, "status");
        Result r = new Result();
        if ("tunnel".equals(st) || "redirect".equals(st)) {
            r.kind = st; r.url = MiniJson.str(m, "url"); r.filename = MiniJson.str(m, "filename");
            if (r.url == null) throw new Err("bad_response", "ردّ الخادم بلا رابط تنزيل");
            return r;
        }
        if ("picker".equals(st)) {
            r.kind = "picker"; r.audio = MiniJson.str(m, "audio");
            List<Object> pl = MiniJson.list(m.get("picker"));
            if (pl != null) for (Object o : pl) {
                Map<String, Object> im = MiniJson.map(o);
                if (im == null || MiniJson.str(im, "url") == null) continue;
                Item it = new Item();
                it.type = MiniJson.str(im, "type"); it.url = MiniJson.str(im, "url");
                r.picker.add(it);
            }
            if (r.picker.isEmpty() && r.audio == null) throw new Err("bad_response", "ردّ الخادم بقائمة اختيار فارغة");
            return r;
        }
        if ("local-processing".equals(st))
            throw new Err("local-processing", "هذا الرابط يتطلب معالجة محلية على جهازك (دمج صوت وفيديو) وهي غير مدعومة في هذا الإصدار. جرّب جودة أقل أو وضع «صوت فقط».");
        if ("error".equals(st)) {
            Map<String, Object> em = MiniJson.map(m.get("error"));
            String ec = em == null ? "unknown" : MiniJson.str(em, "code");
            throw new Err(ec == null ? "unknown" : ec, arabic(ec == null ? "" : ec));
        }
        throw new Err("bad_response", "ردّ الخادم بحالة غير معروفة: " + st);
    }

    /** ترجمة رموز أخطاء Cobalt (error.api.*) إلى رسائل مفهومة. */
    public static String arabic(String code) {
        if (code == null) code = "";
        if (code.startsWith("error.api.link.invalid")) return "الرابط غير صالح. تأكد أنه رابط كامل لفيديو أو منشور";
        if (code.startsWith("error.api.link.unsupported") || code.startsWith("error.api.service.unsupported")) return "هذا الموقع أو نوع الرابط غير مدعوم في هذا الخادم";
        if (code.startsWith("error.api.service.disabled")) return "تم تعطيل هذه الخدمة مؤقتاً في الخادم";
        if (code.startsWith("error.api.content.video.live")) return "هذا بث مباشر ولا يمكن تنزيله";
        if (code.startsWith("error.api.content.video.private")) return "الفيديو خاص ولا يمكن الوصول إليه";
        if (code.startsWith("error.api.content.video.age")) return "الفيديو مقيّد بالعمر ويحتاج تسجيل دخول";
        if (code.startsWith("error.api.content.video.region")) return "الفيديو غير متاح في منطقة الخادم";
        if (code.startsWith("error.api.content.video.unavailable")) return "الفيديو غير متاح (محذوف أو مقيّد)";
        if (code.startsWith("error.api.content.too_long")) return "الفيديو أطول من الحد الذي يسمح به الخادم";
        if (code.startsWith("error.api.content.post.private")) return "المنشور خاص";
        if (code.startsWith("error.api.content.post.age")) return "المنشور مقيّد بالعمر";
        if (code.startsWith("error.api.content.post.unavailable")) return "المنشور غير متاح";
        if (code.startsWith("error.api.fetch.empty")) return "لم يجد الخادم أي وسائط قابلة للتنزيل في هذا الرابط";
        if (code.startsWith("error.api.fetch.rate")) return "الخدمة المصدر تحدّ طلبات الخادم حالياً، أعد المحاولة بعد قليل";
        if (code.startsWith("error.api.fetch.critical") || code.startsWith("error.api.fetch.fail")) return "فشل الخادم في جلب الوسائط من الموقع المصدر. جرّب لاحقاً";
        if (code.startsWith("error.api.rate_exceeded")) return "تجاوزت حد الطلبات المسموح. انتظر قليلاً ثم أعد المحاولة";
        if (code.startsWith("error.api.capacity")) return "الخادم ممتلئ حالياً. أعد المحاولة بعد قليل";
        if (code.startsWith("error.api.auth.key.missing") || code.startsWith("error.api.auth.jwt.missing")) return "هذا الخادم يتطلب مفتاح API. أضفه من الإعدادات ← خادم التنزيل";
        if (code.startsWith("error.api.auth")) return "فشل التحقق من الهوية على الخادم (المفتاح غير صالح أو غير مسموح)";
        if (code.startsWith("error.api.youtube.login")) return "يوتيوب طلب تسجيل دخول من الخادم لهذا الفيديو";
        if (code.startsWith("error.api.youtube")) return "تعذر على الخادم معالجة هذا الفيديو من يوتيوب حالياً";
        if (code.startsWith("error.api.invalid_body") || code.startsWith("error.api.header")) return "طلب غير متوافق مع إصدار الخادم. حدّث التطبيق أو غيّر الخادم";
        if (code.startsWith("error.api.generic")) return "حدث خطأ عام في الخادم";
        if (code.startsWith("error.api.unknown_response")) return "الخدمة المصدر أعادت ردّاً غير متوقع";
        return "رفض الخادم الطلب: " + (code.isEmpty() ? "بلا رمز خطأ" : code);
    }
}
