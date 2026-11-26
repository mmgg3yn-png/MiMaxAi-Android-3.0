package com.mimax.ai;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * Minimal language-pack registry.
 * A language pack is installed as an .mt1 extension under files/extensions
 * and must declare the id "language_ar_en" in its manifest.json.
 */
final class L10n {
    private static final String PACK_ID = "language_ar_en";

    private L10n() {}

    static boolean packInstalled(Context context) {
        if (context == null) return false;
        File manifest = new File(
                new File(new File(context.getFilesDir(), "extensions"), PACK_ID),
                "manifest.json"
        );
        if (!manifest.isFile() || manifest.length() <= 0 || manifest.length() > 128 * 1024) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(manifest)) {
            byte[] bytes = new byte[(int) manifest.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) return false;
            JSONObject json = new JSONObject(new String(bytes, "UTF-8"));
            return PACK_ID.equals(json.optString("id", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** No in-memory translation cache is used by this lightweight registry yet. */
    static void clearCache() {
        // Reserved for invalidating translated-resource caches when pack rendering is wired in.
    }
}
