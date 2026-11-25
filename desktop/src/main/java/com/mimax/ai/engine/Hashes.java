package com.mimax.ai.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** MD5 / SHA-1 / SHA-256 / SHA-512 بقراءة متدفقة. */
public final class Hashes {
    private Hashes() { }
    public static final String[] ALGOS = {"MD5", "SHA-1", "SHA-256", "SHA-512"};

    /** يحسب كل الخوارزميات في مرور واحد على الملف. */
    public static String[] all(File f, Io.Progress p, Io.Cancel c) throws IOException {
        MessageDigest[] md = new MessageDigest[ALGOS.length];
        try { for (int i = 0; i < md.length; i++) md[i] = MessageDigest.getInstance(ALGOS[i]); }
        catch (Exception e) { throw new IOException("خوارزمية غير مدعومة"); }
        long total = f.length(), done = 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 18];
            int n;
            while ((n = in.read(b)) > 0) {
                if (c != null) c.check();
                for (MessageDigest m : md) m.update(b, 0, n);
                done += n;
                if (p != null) p.on(done, total);
            }
        }
        String[] out = new String[md.length];
        for (int i = 0; i < md.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (byte x : md[i].digest()) sb.append(String.format("%02x", x));
            out[i] = sb.toString();
        }
        return out;
    }
}
