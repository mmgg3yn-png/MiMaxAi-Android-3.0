package com.mimax.ai;

import android.app.Activity;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;

public class BrowserActivity extends Activity {
    private WebView web;
    private EditText address;
    private ProgressBar progress;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF17212D);
        getWindow().setNavigationBarColor(0xFF17212D);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F7F9);

        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(6), dp(6), dp(6), dp(6));
        top.setGravity(Gravity.CENTER_VERTICAL);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setHint("ابحث أو أدخل رابطاً");
        address.setText("https://www.google.com");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        top.addView(address, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button go = new Button(this); go.setText("اذهب"); go.setAllCaps(false);
        top.addView(go, new LinearLayout.LayoutParams(dp(66), dp(46)));
        go.setOnClickListener(v -> loadAddress());
        address.setOnEditorActionListener((v, action, event) -> { loadAddress(); return true; });
        root.addView(top);

        LinearLayout nav = new LinearLayout(this);
        String[] labels = {"رجوع", "تقدم", "تحديث", "الرئيسية"};
        for (String label : labels) {
            Button b = new Button(this); b.setText(label); b.setAllCaps(false);
            nav.addView(b, new LinearLayout.LayoutParams(0, dp(42), 1));
            b.setOnClickListener(v -> {
                if (label.equals("رجوع") && web.canGoBack()) web.goBack();
                else if (label.equals("تقدم") && web.canGoForward()) web.goForward();
                else if (label.equals("تحديث")) web.reload();
                else if (label.equals("الرئيسية")) web.loadUrl("https://www.google.com");
            });
        }
        root.addView(nav);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handleUrl(url); }
            @Override public void onPageFinished(WebView view, String url) { address.setText(url); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) { progress.setProgress(p); progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE); }
            @Override public void onReceivedTitle(WebView view, String title) { setTitle(title); }
        });
        web.setDownloadListener((url, ua, disposition, mime, length) -> download(url, ua, disposition, mime));
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        Button done = new Button(this);
        done.setText("العودة إلى Mi_Max Ai"); done.setAllCaps(false);
        done.setOnClickListener(v -> finish());
        root.addView(done, new LinearLayout.LayoutParams(-1, dp(42)));
        setContentView(root);
        if (state == null) web.loadUrl("https://www.google.com");
    }

    private boolean handleUrl(String url) {
        if (url == null) return true;
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "لا يمكن فتح هذا الرابط", Toast.LENGTH_SHORT).show(); }
        return true;
    }

    private void loadAddress() {
        String q = address.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!q.matches("(?i)^https?://.*")) {
            if (q.contains(".") && !q.contains(" ")) q = "https://" + q;
            else q = "https://www.google.com/search?q=" + Uri.encode(q);
        }
        web.loadUrl(q);
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(name);
            r.setMimeType(mime);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MiMaxAi/" + name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "بدأ التنزيل", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
