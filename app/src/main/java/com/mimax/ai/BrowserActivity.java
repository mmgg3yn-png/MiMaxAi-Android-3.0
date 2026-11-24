package com.mimax.ai;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;

public class BrowserActivity extends Activity {
    private static final String HOME = "https://www.google.com";
    private final ArrayList<WebView> tabs = new ArrayList<>();
    private final ArrayList<String> titles = new ArrayList<>();
    private LinearLayout tabStrip;
    private FrameLayout webHost;
    private EditText address;
    private ProgressBar progress;
    private Button desktopButton;
    private boolean desktop;
    private int active = -1;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme th = Theme.get(Store.theme());
        desktop = Store.flag("browserDesktop", false);
        getWindow().setStatusBarColor(th.base);
        getWindow().setNavigationBarColor(th.base);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.base);

        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(7), dp(5), dp(7), dp(5));
        top.setGravity(Gravity.CENTER_VERTICAL);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setTextColor(th.tx);
        address.setHintTextColor(th.mut);
        address.setHint("ابحث أو أدخل رابطاً");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setBackgroundColor(th.surface);
        top.addView(address, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button go = button("فتح", th);
        top.addView(go, new LinearLayout.LayoutParams(dp(58), dp(44)));
        go.setOnClickListener(v -> loadAddress());
        address.setOnEditorActionListener((v, action, event) -> { loadAddress(); return true; });
        root.addView(top);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        addNav(nav, "رجوع", th, () -> { if (current() != null && current().canGoBack()) current().goBack(); });
        addNav(nav, "تقدم", th, () -> { if (current() != null && current().canGoForward()) current().goForward(); });
        addNav(nav, "تحديث", th, () -> { if (current()!=null) current().reload(); });
        addNav(nav, "الرئيسية", th, () -> { if (current()!=null) current().loadUrl(HOME); });
        addNav(nav, "تبويب +", th, () -> newTab(HOME));
        desktopButton = button(desktop ? "سطح المكتب: ON" : "سطح المكتب: OFF", th);
        nav.addView(desktopButton, new LinearLayout.LayoutParams(0, dp(40), 1));
        desktopButton.setOnClickListener(v -> toggleDesktop());
        root.addView(nav);

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabsScroll.addView(tabStrip);
        root.addView(tabsScroll, new LinearLayout.LayoutParams(-1, dp(42)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        webHost = new FrameLayout(this);
        root.addView(webHost, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        Button bookmark = button("حفظ المفضلة", th);
        bottom.addView(bookmark, new LinearLayout.LayoutParams(0, dp(42), 1));
        bookmark.setOnClickListener(v -> saveBookmark());
        Button bookmarks = button("المفضلة", th);
        bottom.addView(bookmarks, new LinearLayout.LayoutParams(0, dp(42), 1));
        bookmarks.setOnClickListener(v -> showBookmarks());
        Button history = button("السجل", th);
        bottom.addView(history, new LinearLayout.LayoutParams(0, dp(42), 1));
        history.setOnClickListener(v -> showHistory());
        Button back = button("إغلاق", th);
        bottom.addView(back, new LinearLayout.LayoutParams(0, dp(42), 1));
        back.setOnClickListener(v -> finish());
        root.addView(bottom);
        setContentView(root);

        if (state != null) {
            String url = state.getString("activeUrl", HOME);
            newTab(url);
        } else newTab(HOME);
    }

    private interface Action { void run(); }
    private void addNav(LinearLayout row, String label, Theme th, Action action) {
        Button b = button(label, th);
        row.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
        b.setOnClickListener(v -> action.run());
    }

    private Button button(String label, Theme th) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(th.tx);
        b.setBackgroundColor(th.surface);
        b.setPadding(dp(3), 0, dp(3), 0);
        return b;
    }

    private WebView current() { return active >= 0 && active < tabs.size() ? tabs.get(active) : null; }

    private void newTab(String url) {
        Theme th = Theme.get(Store.theme());
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(desktop);
        settings.setLoadWithOverviewMode(desktop);
        applyDesktop(web, desktop);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        final int index = tabs.size();
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String target) { return handleUrl(target); }
            @Override public void onPageFinished(WebView view, String target) {
                int currentIndex = tabs.indexOf(view);
                if (currentIndex < 0) return;
                if (active == currentIndex) address.setText(target);
                saveHistory(target);
                updateTabLabel(currentIndex, view.getTitle());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                int currentIndex = tabs.indexOf(view);
                if (currentIndex >= 0 && active == currentIndex) {
                    progress.setProgress(value);
                    progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
                }
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                int currentIndex = tabs.indexOf(view);
                if (currentIndex >= 0) updateTabLabel(currentIndex, title);
            }
        });
        web.setDownloadListener((target, ua, disposition, mime, length) -> download(target, ua, disposition, mime));
        tabs.add(web);
        titles.add("تبويب " + (index + 1));
        webHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        selectTab(index);
        web.loadUrl(url);
        renderTabs();
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        active = index;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        WebView web = current();
        address.setText(web.getUrl() == null ? "" : web.getUrl());
        progress.setProgress(0);
        renderTabs();
    }

    private void renderTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        Theme th = Theme.get(Store.theme());
        for (int i = 0; i < tabs.size(); i++) {
            final int at = i;
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundColor(i == active ? th.surface2 : th.surface);
            Button select = button((i == active ? "● " : "") + titles.get(i), th);
            chip.addView(select, new LinearLayout.LayoutParams(dp(118), dp(38)));
            select.setOnClickListener(v -> selectTab(at));
            Button close = button("×", th);
            chip.addView(close, new LinearLayout.LayoutParams(dp(34), dp(38)));
            close.setOnClickListener(v -> closeTab(at));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(38));
            cp.rightMargin = dp(4);
            tabStrip.addView(chip, cp);
        }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        WebView web = tabs.remove(index);
        titles.remove(index);
        web.stopLoading();
        webHost.removeView(web);
        web.destroy();
        if (tabs.isEmpty()) { active = -1; newTab(HOME); return; }
        if (active >= tabs.size()) active = tabs.size() - 1;
        else if (index < active) active--;
        selectTab(active);
    }

    private void updateTabLabel(int index, String title) {
        if (index < 0 || index >= titles.size()) return;
        titles.set(index, title == null || title.trim().isEmpty() ? "تبويب " + (index + 1) : title);
        if (active == index) renderTabs();
    }

    private void applyDesktop(WebView web, boolean enabled) {
        WebSettings s = web.getSettings();
        s.setUseWideViewPort(enabled);
        s.setLoadWithOverviewMode(enabled);
        if (enabled) {
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        } else {
            s.setUserAgentString(null);
        }
    }

    private void toggleDesktop() {
        desktop = !desktop;
        Store.setFlag("browserDesktop", desktop);
        desktopButton.setText(desktop ? "سطح المكتب: ON" : "سطح المكتب: OFF");
        for (WebView web : tabs) {
            String url = web.getUrl();
            applyDesktop(web, desktop);
            if (url != null) web.reload();
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) return true;
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "لا يمكن فتح هذا الرابط", Toast.LENGTH_SHORT).show(); }
        return true;
    }

    private void loadAddress() {
        String q = address.getText().toString().trim();
        if (q.isEmpty() || current() == null) return;
        if (!q.matches("(?i)^https?://.*")) {
            if (q.contains(".") && !q.contains(" ")) q = "https://" + q;
            else q = "https://www.google.com/search?q=" + Uri.encode(q);
        }
        current().loadUrl(q);
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

    private void saveBookmark() {
        if (current() == null || current().getUrl() == null) return;
        try {
            JSONArray a = new JSONArray(Store.get("browserBookmarks", "[]"));
            String url = current().getUrl();
            for (int i=0;i<a.length();i++) {
                org.json.JSONObject existing = a.optJSONObject(i);
                if (existing != null && url.equals(existing.optString("url"))) {
                    Toast.makeText(this,"الرابط محفوظ مسبقاً",Toast.LENGTH_SHORT).show(); return;
                }
            }
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("title", current().getTitle() == null ? url : current().getTitle());
            item.put("url", url);
            a.put(item);
            Store.put("browserBookmarks", a.toString());
            Toast.makeText(this, "تمت إضافة المفضلة", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "تعذر حفظ المفضلة", Toast.LENGTH_SHORT).show(); }
    }

    private void showBookmarks() { showSavedList("browserBookmarks", "المفضلة"); }
    private void showHistory() { showSavedList("browserHistory", "السجل"); }

    private void saveHistory(String url) {
        try {
            JSONArray a = new JSONArray(Store.get("browserHistory", "[]"));
            if (a.length() > 0 && url.equals(a.optJSONObject(a.length()-1).optString("url"))) return;
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("title", url);
            item.put("url", url);
            a.put(item);
            while (a.length() > 200) a.remove(0);
            Store.put("browserHistory", a.toString());
        } catch (Exception ignored) { }
    }

    private void showSavedList(String key, String title) {
        try {
            JSONArray a = new JSONArray(Store.get(key, "[]"));
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<String> urls = new ArrayList<>();
            for (int i=a.length()-1;i>=0;i--) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null) { labels.add(o.optString("title", o.optString("url"))); urls.add(o.optString("url")); }
            }
            if (labels.isEmpty()) { Toast.makeText(this, "لا توجد عناصر", Toast.LENGTH_SHORT).show(); return; }
            new AlertDialog.Builder(this).setTitle(title).setItems(labels.toArray(new String[0]), (d, which) -> {
                if (current()!=null) current().loadUrl(urls.get(which));
            }).setNegativeButton("إغلاق", null).show();
        } catch (Exception e) { Toast.makeText(this, "تعذر قراءة القائمة", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (current()!=null) out.putString("activeUrl", current().getUrl());
    }

    @Override public void onBackPressed() {
        if (current() != null && current().canGoBack()) current().goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        for (WebView web : tabs) { web.stopLoading(); web.destroy(); }
        tabs.clear();
        super.onDestroy();
    }
}
