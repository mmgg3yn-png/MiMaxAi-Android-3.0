package com.mimax.ai;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Translation provider for installed declarative language-pack extensions. */
final class L10n {
    private static final String PACK_ID = "language_ar_en";
    private static String cachedLanguage = "";
    private static long cachedStamp = -1;
    private static Map<String,String> cached = new HashMap<>();
    private L10n() {}

    static String language(Context c) {
        return Store.get("app_language", "ar");
    }

    static String text(Context c, String source) {
        if (source == null || source.isEmpty() || "ar".equals(language(c))) return source;
        try {
            File file = new File(new File(ExtensionRuntime.root(c), PACK_ID), "translations/en.json");
            if (!file.isFile() || file.length() > 1024 * 1024) return source;
            long stamp = file.lastModified();
            if (!"en".equals(cachedLanguage) || cachedStamp != stamp) {
                FileInputStream in = new FileInputStream(file);
                byte[] bytes = new byte[(int) file.length()];
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
                in.close();
                JSONObject json = new JSONObject(new String(bytes, "UTF-8"));
                HashMap<String,String> map = new HashMap<>();
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = json.optString(key, "");
                    if (!key.isEmpty() && !value.isEmpty()) map.put(key, value);
                }
                cached = map; cachedLanguage = "en"; cachedStamp = stamp;
            }
            String value = cached.get(source);
            return value == null ? source : value;
        } catch (Exception ignored) { return source; }
    }

    static boolean packInstalled(Context c) {
        File manifest = new File(new File(ExtensionRuntime.root(c), PACK_ID), "manifest.json");
        return manifest.isFile();
    }

    static void clearCache() { cachedLanguage = ""; cachedStamp = -1; cached.clear(); }
}
