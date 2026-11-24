package com.mimax.ai.engine;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** تحقق محلي من Google ID Token: توقيع RS256 مقابل مفاتيح Google العامة، والمُصدِر، والجمهور (Client ID)، والانتهاء، وnonce. */
public final class GoogleIdToken {
    private GoogleIdToken() { }

    public static class Claims {
        public String iss, aud, sub, email, name, picture, nonce;
        public long exp, iat;
        public boolean emailVerified;
    }

    public static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";

    static byte[] b64(String s) throws IOException {
        try { return Base64.getUrlDecoder().decode(s); } catch (IllegalArgumentException e) { throw new IOException("ترميز base64 غير صالح في الرمز"); }
    }

    public static String fetchJwks(int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(JWKS_URL).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        try {
            if (c.getResponseCode() != 200) throw new IOException("تعذر جلب مفاتيح Google (HTTP " + c.getResponseCode() + ")");
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return new String(o.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { c.disconnect(); }
    }

    public static Claims verify(String token, String expectedAud, String jwksJson, long nowSec, String expectedNonce) throws IOException {
        if (token == null) throw new IOException("لا يوجد رمز");
        String[] p = token.split("\\.");
        if (p.length != 3) throw new IOException("الرمز ليس JWT صالحاً");
        Map<String, Object> hdr = MiniJson.map(MiniJson.parse(new String(b64(p[0]), StandardCharsets.UTF_8)));
        if (hdr == null) throw new IOException("ترويسة الرمز غير صالحة");
        String alg = MiniJson.str(hdr, "alg"), kid = MiniJson.str(hdr, "kid");
        if (!"RS256".equals(alg)) throw new IOException("خوارزمية التوقيع غير مقبولة: " + alg);
        Map<String, Object> jwks = MiniJson.map(MiniJson.parse(jwksJson));
        List<Object> keys = jwks == null ? null : MiniJson.list(jwks.get("keys"));
        Map<String, Object> key = null;
        if (keys != null) for (Object k : keys) {
            Map<String, Object> m = MiniJson.map(k);
            if (m != null && "RSA".equals(MiniJson.str(m, "kty")) && kid != null && kid.equals(MiniJson.str(m, "kid"))) { key = m; break; }
        }
        if (key == null) throw new IOException("مفتاح التوقيع (kid) غير موجود في قائمة Google");
        try {
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, b64(MiniJson.str(key, "n"))), new BigInteger(1, b64(MiniJson.str(key, "e")))));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pk);
            sig.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(b64(p[2]))) throw new IOException("توقيع الرمز غير صحيح");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("تعذر التحقق من التوقيع: " + e.getMessage()); }
        Map<String, Object> c = MiniJson.map(MiniJson.parse(new String(b64(p[1]), StandardCharsets.UTF_8)));
        if (c == null) throw new IOException("بيانات الرمز غير صالحة");
        Claims r = new Claims();
        r.iss = MiniJson.str(c, "iss"); r.sub = MiniJson.str(c, "sub"); r.email = MiniJson.str(c, "email"); r.name = MiniJson.str(c, "name");
        r.picture = MiniJson.str(c, "picture"); r.nonce = MiniJson.str(c, "nonce");
        r.exp = MiniJson.num(c.get("exp"), 0); r.iat = MiniJson.num(c.get("iat"), 0);
        Object ev = c.get("email_verified");
        r.emailVerified = Boolean.TRUE.equals(ev) || "true".equals(MiniJson.str(ev));
        Object aud = c.get("aud");
        boolean audOk = false;
        if (aud instanceof List) { for (Object a : (List<?>) aud) if (expectedAud.equals(MiniJson.str(a))) audOk = true; r.aud = String.valueOf(aud); }
        else { r.aud = MiniJson.str(aud); audOk = expectedAud.equals(r.aud); }
        if (!"https://accounts.google.com".equals(r.iss) && !"accounts.google.com".equals(r.iss)) throw new IOException("مُصدِر الرمز غير موثوق: " + r.iss);
        if (!audOk) throw new IOException("الرمز صادر لتطبيق آخر (Client ID لا يطابق)");
        if (r.exp <= nowSec - 60) throw new IOException("انتهت صلاحية الرمز");
        if (r.iat > nowSec + 300) throw new IOException("وقت إصدار الرمز في المستقبل (تحقق من ساعة الجهاز)");
        if (r.sub == null || r.sub.isEmpty()) throw new IOException("الرمز بلا معرّف مستخدم (sub)");
        if (expectedNonce != null && !expectedNonce.equals(r.nonce)) throw new IOException("nonce لا يطابق (محاولة إعادة استخدام الرمز؟)");
        return r;
    }
}
