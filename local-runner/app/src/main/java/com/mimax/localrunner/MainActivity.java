package com.mimax.localrunner;

import android.app.Activity;\nimport android.content.Intent;\nimport android.content.pm.ShortcutInfo;\nimport android.content.pm.ShortcutManager;\nimport android.graphics.Bitmap;\nimport android.graphics.BitmapFactory;\nimport android.graphics.drawable.Icon;
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
}
