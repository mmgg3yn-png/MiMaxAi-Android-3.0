package com.mimax.localrunner;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.InputStream;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String type = "html";
        String title = "MiMax Local Project";
        try (InputStream in = getAssets().open("app-config.json")) {
            byte[] data = new byte[in.available()];
            int count = in.read(data);
            if (count > 0) {
                JSONObject config = new JSONObject(new String(data, 0, count, "UTF-8"));
                type = config.optString("type", type);
                title = config.optString("title", title);
            }
        } catch (Exception ignored) { }
        setTitle(title);
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/" + ("python".equals(type) ? "python.html" : "site/main.html"));
        createProjectShortcut(title);
    }

    private void createProjectShortcut(String title) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            if (getPreferences(MODE_PRIVATE).getBoolean("project_shortcut_requested", false)) return;
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            Bitmap bitmap;
            try (InputStream icon = getAssets().open("app-icon.png")) {
                bitmap = BitmapFactory.decodeStream(icon);
            } catch (Exception missingIcon) { return; }
            if (bitmap == null) return;
            Intent launch = new Intent(this, MainActivity.class);
            launch.setAction(Intent.ACTION_VIEW);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "mimax-project-shortcut")
                    .setShortLabel(title.length() > 25 ? title.substring(0, 25) : title)
                    .setLongLabel(title)
                    .setIcon(Icon.createWithBitmap(bitmap))
                    .setIntent(launch)
                    .build();
            if (manager.requestPinShortcut(shortcut, null)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("project_shortcut_requested", true).apply();
            }
        } catch (Exception ignored) { }
    }
}
