package com.mimax.ai.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * مفتاح الخدمة المدمج مخزَّن مشفَّراً (AES-256-GCM) ولا يظهر كنص داخل الشيفرة.
 * ملاحظة صريحة: هذا تعتيم يصعّب القراءة العابرة فقط؛ أي مفتاح داخل تطبيق يمكن
 * استخراجه من قِبل شخص متمرّس. لا تضع هنا مفتاحاً بلا حد إنفاق.
 */
final class Vault {
    private Vault() { }
    private static String cached;

    private static byte[] p0() { return new byte[]{-128,87,-7,-69,112,-76,104,125}; }
    private static byte[] p1() { return new byte[]{-125,125,-26,51,-99,-89,-63,-20}; }
    private static byte[] p2() { return new byte[]{69,43,-24,13,-95,84,52,127}; }
    private static byte[] p3() { return new byte[]{-29,28,34,-86,-44,-46,87,36}; }
    private static byte[] iv() { return new byte[]{73,-73,-106,-6,70,-61,-29,37,89,82,-102,-50}; }
    private static byte[] ct() { return new byte[]{-77,-92,107,121,95,-71,-102,-16,7,-77,14,-86,-50,20,-108,73,74,-28,58,-4,49,32,60,-68,101,-6,111,4,-124,-19,83,2,-111,117,-13,24,0,10,83,49,-81,-107,35,-55,-26,-76,-127,-23,-72,3,-63,-28,-107,107,69,24,-95,-38,107,-78,-127,4,-128,-78,36,50,33,-107,86,59,41,-94,34,-69,28,121,-37,93,-36,-123,-49,37,79,-108,80,39,-67,-67,22}; }

    private static byte mask(int i) { return (byte) (((i * 73 + 19) ^ (i << 3) ^ 0xA7) & 0xFF); }

    static synchronized String key() {
        if (cached != null) return cached;
        try {
            byte[] r = new byte[32];
            byte[][] ps = {p0(), p1(), p2(), p3()};
            for (int i = 0; i < 32; i++) r[i] = (byte) (ps[i / 8][i % 8] ^ mask(i));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(r);
            sha.update("com.mimax.ai.v1".getBytes(StandardCharsets.UTF_8));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha.digest(), "AES"), new GCMParameterSpec(128, iv()));
            cached = new String(c.doFinal(ct()), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            cached = "";
        }
        return cached;
    }
}
