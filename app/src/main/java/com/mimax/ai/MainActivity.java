package com.mimax.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.res.Configuration;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ExifInterface;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final int REQ_IMG = 1, REQ_AUD = 2, REQ_STO = 3;
    static boolean toSettings = false;
    static final String DEFAULT_PROMPT =
            "أنت Mi_Max Ai، مساعد ذكي ودود وعملي. أجب بلغة المستخدم (والعربية افتراضياً) بوضوح وإيجاز، "
                    + "وقدّم خطوات عملية عند الحاجة. عند كتابة كود ضعه داخل كتل كود. "
                    + "إن لم تكن متأكداً من معلومة فقل ذلك بصراحة.";

    interface Txt { void on(String s); }

    // ---- الألوان ----
    boolean dark;
    Theme th;
    Backdrop backdrop;
    int style;                      // 0 عادي، 1 زجاج سائل، 2 Material 3
    int ON = 0xFFFFFFFF;            // لون النص فوق لون التمييز
    int curNav = 0;
    boolean lensReady = false;
    View lens;
    FrameLayout navFrame;
    LinearLayout navRow;
    FileScreen files;
    MediaUi media;
    static boolean reopenLook = false, reopenDev = false;
    static boolean extensionThemeChanged = false;
    int oBubU, oBubA, oTop, oNav, oGlass;
    Typeface appFont;
    ApkInstaller installer;
    static final java.util.LinkedHashMap<String, Integer> pend = new java.util.LinkedHashMap<>();
    static int devTaps = 0;
    static long devTapT = 0;

    static class Scr {
        final String title;
        final Runnable build;
        Scr(String t, Runnable b) { title = t; build = b; }
    }
    final java.util.ArrayDeque<Scr> screens = new java.util.ArrayDeque<>();

    /** يفتح شاشة جديدة فوق الحالية؛ زر الرجوع يعود للسابقة. */
    void openScreen(String title, Runnable build) {
        screens.push(new Scr(title, build));
        build.run();
    }
    int BG, SF, SF2, LN, TX, MUT, AC, ER;
    final Handler ui = new Handler(Looper.getMainLooper());

    // ---- هيكل الشاشة ----
    FrameLayout content;
    TextView title;
    Button backBtn, newBtn;
    Button[] navBtns = new Button[5];
    String tab = "chat";
    String sub = null;

    // ---- البيانات ----
    ArrayList<Store.Chat> chats = new ArrayList<>();
    Store.Chat cur;
    ArrayList<String> mem = new ArrayList<>();
    String prompt = "";
    boolean busy;
    boolean[] cancel = new boolean[]{false};

    // ---- المرفقات والتسجيل ----
    String attImg, attAud;
    Bitmap attThumb;
    int attDur;
    Rec rec;
    Runnable recTick;

    // ---- عناصر المحادثة ----
    LinearLayout msgBox;
    ScrollView msgScroll;
    EditText input;
    Button sendBtn, micBtn;
    TextView attInfo, lastTv;
    String draft = "";

    TextToSpeech tts;
    String pendingSpeak;
    EditText fModel, fAudio;

    // =====================================================================
    @Override
    protected void attachBaseContext(Context base) {
        Store.init(base);
        int pct = 100;
        try { pct = Integer.parseInt(Store.get("fontScale", "100")); } catch (Exception e) { /* تجاهل */ }
        if (pct != 100) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.fontScale = c.fontScale * pct / 100f;
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle b) {
        Store.init(this);
        new ExtensionEngine(this).applyEnabledThemes();
        th = Theme.get(Store.theme());
        if (Store.flag("ccOn", false)) th = Theme.custom(th);
        dark = th.dark;
        style = Store.style();
        loadFont();
        setTheme(dark ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(b);
        colors();
        reload();
        media = new MediaUi(this);
        files = new FileScreen(this);
        installer = new ApkInstaller(this);
        installer.register();
        buildRoot();
        if (toSettings) {
            toSettings = false;
            showSettings();
            if (reopenLook) { reopenLook = false; openSub("look"); }
            if (reopenDev) { reopenDev = false; openSub("devopts"); }
        } else showChat();
        if (getIntent() != null && getIntent().getBooleanExtra("ask_widget", false)) {
            if (input != null) {
                input.setHint("اكتب سؤالك للذكاء الاصطناعي...");
                input.requestFocus();
                ui.postDelayed(() -> {
                    try {
                        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                            .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    } catch (Exception ignored) { }
                }, 350);
            }
            getIntent().removeExtra("ask_widget");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHomeWidgets();
    }

    private void refreshHomeWidgets() {
        try {
            android.appwidget.AppWidgetManager manager = android.appwidget.AppWidgetManager.getInstance(this);
            android.content.ComponentName search = new android.content.ComponentName(this, SearchWidgetProvider.class);
            android.content.ComponentName ask = new android.content.ComponentName(this, AskAiWidgetProvider.class);
            int[] searchIds = manager.getAppWidgetIds(search);
            int[] askIds = manager.getAppWidgetIds(ask);
            Intent si = new Intent(this, SearchWidgetProvider.class);
            si.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            si.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, searchIds);
            sendBroadcast(si);
            Intent ai = new Intent(this, AskAiWidgetProvider.class);
            ai.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            ai.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, askIds);
            sendBroadcast(ai);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) tts.shutdown();
        if (media != null) media.release();
        if (installer != null) installer.unregister();
        if (rec != null) { try { rec.stop(); } catch (Exception e) { /* تجاهل */ } }
    }

    void colors() {
        BG = th.base; TX = th.tx; MUT = th.mut; AC = th.ac; ER = th.er;
        if (style == 1) {                       // زجاج سائل: أسطح شفافة
            SF = th.dark ? 0x1FFFFFFF : 0x99FFFFFF;
            SF2 = th.dark ? 0x33FFFFFF : 0xCCFFFFFF;
            LN = th.dark ? 0x55FFFFFF : 0xFFFFFFFF;
        } else if (style == 2) {                // Material 3: أسطح لونية (tonal)
            int base = th.base, acc = th.ac, tx = th.tx, mu = th.mut;
            if (Build.VERSION.SDK_INT >= 31 && Store.flag("dynamic", true)) {
                try {
                    acc = getColor(th.dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
                    base = getColor(th.dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
                    tx = getColor(th.dark ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
                    mu = getColor(th.dark ? android.R.color.system_neutral2_300 : android.R.color.system_neutral2_700);
                } catch (Throwable e) { /* ألوان النظام غير متاحة */ }
            }
            BG = base; AC = acc; TX = tx; MUT = mu;
            SF = Theme.mix(base, acc, th.dark ? 0.10f : 0.07f);
            SF2 = Theme.mix(base, acc, th.dark ? 0.20f : 0.16f);
            LN = 0;
        } else {                                // عادي (مثل النسخة الأولى)
            SF = th.surface; SF2 = th.surface2; LN = th.line;
        }
        ON = Theme.lum(AC) > 0.58f ? 0xFF10131A : 0xFFFFFFFF;
        oBubU = 0; oBubA = 0; oTop = 0; oNav = 0; oGlass = 0;
        if (Store.flag("ccOn", false)) {
            oBubU = Store.cc("bubU", 0); oBubA = Store.cc("bubA", 0); oTop = Store.cc("top", 0); oNav = Store.cc("nav", 0); oGlass = Store.cc("glass", 0);
            if (style == 1 && oGlass != 0) {
                SF = oGlass;
                SF2 = (Math.min(255, (oGlass >>> 24) * 8 / 5) << 24) | (oGlass & 0xFFFFFF);
            }
        }
    }

    // ---------------- الخطوط ----------------
    void loadFont() {
        appFont = null;
        String f = Store.get("fontFam", "");
        try {
            if (f.startsWith("file:")) appFont = Typeface.createFromFile(f.substring(5));
            else if (!f.isEmpty()) appFont = Typeface.create(f, Typeface.NORMAL);
        } catch (Exception e) { appFont = null; }
    }

    void applyFonts(View v) {
        if (appFont == null || v == null) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getTag(R.id.mm_font) == null) {
                Typeface cur = t.getTypeface();
                t.setTypeface(Typeface.create(appFont, cur != null ? cur.getStyle() : Typeface.NORMAL));
                t.setTag(R.id.mm_font, Boolean.TRUE);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFonts(g.getChildAt(i));
        }
    }

    void reload() {
        chats = Store.loadChats();
        mem = Store.loadMem();
        prompt = Store.prompt();
        cur = null;
    }

    // ---- مساعدات الواجهة ----
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    Drawable rr(int fill, int r, int stroke) {
        float d = getResources().getDisplayMetrics().density;
        boolean acc = fill == AC || fill == ER;
        if (style == 1) return new Glass(dp(r), fill, acc, th.dark, d);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (style == 2) {
            g.setCornerRadius(dp(acc ? 100 : Math.min(r + 8, 28)));
        } else {
            g.setCornerRadius(dp(r));
            if (stroke != 0) g.setStroke(dp(1), stroke);
        }
        return g;
    }

    int dialogBg() { return Theme.mix(BG, 0xFFFFFFFF, th.dark ? 0.12f : 0f); }

    /** غلاف لـ AlertDialog.Builder يطبّق خلفية متناسقة مع نمط التصميم. */
    class GB {
        final AlertDialog.Builder b;
        GB(android.content.Context c) { b = new AlertDialog.Builder(c); }
        GB setMessage(CharSequence m) { b.setMessage(m); return this; }
        GB setTitle(CharSequence t) { b.setTitle(t); return this; }
        GB setView(View v) { b.setView(v); return this; }
        GB setCancelable(boolean c) { b.setCancelable(c); return this; }
        GB setItems(CharSequence[] i, android.content.DialogInterface.OnClickListener l) { b.setItems(i, l); return this; }
        GB setPositiveButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { b.setPositiveButton(t, l); return this; }
        GB setNegativeButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { b.setNegativeButton(t, l); return this; }
        AlertDialog show() {
            AlertDialog d = b.create();
            d.show();
            applyFonts(d.getWindow().getDecorView());
            if (d.getWindow() != null)
                d.getWindow().setBackgroundDrawable(style == 1
                        ? new Glass(dp(26), dialogBg(), false, th.dark, getResources().getDisplayMetrics().density)
                        : rr(SF2, 22, LN));
            return d;
        }
    }

    TextView tv(String t, int color, float sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(sp);
        return v;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    Button flat(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(TX);
        b.setTextSize(20);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(48));
        b.setOnClickListener(l);
        return b;
    }

    Button btn(String t, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setBackground(rr(bg, 12, 0));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    TextView note(String t) {
        TextView v = tv(t, MUT, 13);
        v.setLineSpacing(0, 1.3f);
        v.setPadding(dp(4), dp(4), dp(4), dp(8));
        return v;
    }

    TextView box(String t) {
        TextView v = tv(t, TX, 13.5f);
        v.setLineSpacing(0, 1.3f);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(rr((AC & 0x00FFFFFF) | 0x38000000, 14, AC));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        v.setLayoutParams(p);
        return v;
    }

    View row(String badge, String t, String s, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        r.setBackground(rr(SF, 12, LN));
        TextView b = tv(badge, AC, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rr(SF2, 10, 0));
        r.addView(b, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        TextView tt = tv(t, TX, 15);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        m.addView(tt);
        if (s != null && !s.isEmpty()) m.addView(tv(s, MUT, 12.5f));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.setMarginStart(dp(12));
        r.addView(m, mp);
        if (l != null) r.setOnClickListener(l);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = dp(8);
        r.setLayoutParams(rp);
        return r;
    }

    TextWatcher watcher(final Txt t) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { t.on(e.toString()); }
        };
    }

    EditText field(LinearLayout p, String label, String val, boolean pwd, boolean ltr) {
        TextView l = tv(label, MUT, 13);
        l.setPadding(0, dp(8), 0, dp(4));
        p.addView(l);
        EditText e = new EditText(this);
        e.setText(val);
        e.setTextColor(TX);
        e.setHintTextColor(MUT);
        e.setBackground(rr(SF, 10, LN));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setInputType(pwd ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : InputType.TYPE_CLASS_TEXT);
        if (ltr) {
            e.setTextDirection(View.TEXT_DIRECTION_LTR);
            e.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        }
        p.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    View toggle(String t, boolean v, final String key) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        r.setBackground(rr(SF, 12, LN));
        TextView l = tv(t, TX, 14);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(v);
        int[][] sts = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(sts, new int[]{Color.WHITE, 0xFFE6E6E6}));
        sw.setTrackTintList(new ColorStateList(sts, new int[]{AC, 0x66888888}));
        sw.setOnCheckedChangeListener((cb, c) -> Store.setFlag(key, c));
        r.addView(sw);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    GB gb() { return new GB(this); }

    interface Job { void run() throws Exception; }

    void showError(String m) { gb().setMessage(m).setPositiveButton("حسناً", null).show(); }

    /** ينفّذ عملاً ثقيلاً في خيط خلفي مع نافذة تقدم وزر إلغاء اختياري. */
    void runBg(String msg, final com.mimax.ai.engine.Io.Cancel cancel, final Job job, final Runnable ok) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(10));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        box.addView(pb, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = tv(msg, TX, 14);
        t.setPadding(dp(16), 0, 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        GB b = gb().setView(box).setCancelable(false);
        if (cancel != null) b.setNegativeButton("إلغاء", (d0, w0) -> cancel.cancelled = true);
        final AlertDialog dlg = b.show();
        new Thread(() -> {
            String err = null;
            try { job.run(); }
            catch (com.mimax.ai.engine.Io.CancelledException e) { err = "تم إلغاء العملية"; }
            catch (Throwable e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                try { dlg.dismiss(); } catch (Exception e) { /* تجاهل */ }
                if (fe != null) showError(fe); else if (ok != null) ok.run();
            });
        }).start();
    }

    interface PJob { void run(com.mimax.ai.engine.Io.Progress p) throws Exception; }

    /** مثل runBg لكن مع نسبة تقدم تُعرض في النافذة. */
    void runBgP(final String msg, final com.mimax.ai.engine.Io.Cancel cancel, final PJob job, final Runnable ok) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(10));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        box.addView(pb, new LinearLayout.LayoutParams(dp(36), dp(36)));
        final TextView t = tv(msg, TX, 14);
        t.setPadding(dp(16), 0, 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        GB b = gb().setView(box).setCancelable(false);
        if (cancel != null) b.setNegativeButton("إلغاء", (d0, w0) -> cancel.cancelled = true);
        final AlertDialog dlg = b.show();
        final long[] last = {0};
        new Thread(() -> {
            String err = null;
            try {
                job.run((d, tot) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] < 300) return;
                    last[0] = now;
                    final int pct = tot > 0 ? (int) Math.min(100, d * 100 / tot) : -1;
                    ui.post(() -> t.setText(pct >= 0 ? msg + " — " + pct + "%" : msg));
                });
            } catch (com.mimax.ai.engine.Io.CancelledException e) { err = "تم إلغاء العملية (لم يُترك أي ناتج ناقص)"; }
            catch (Throwable e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                try { dlg.dismiss(); } catch (Exception e) { /* تجاهل */ }
                if (fe != null) showError(fe); else if (ok != null) ok.run();
            });
        }).start();
    }

    /** نافذة إدخال نص. */
    void input(String title, String initial, final Txt cb) {
        final EditText e = new EditText(this);
        e.setText(initial);
        e.setTextColor(TX);
        e.setBackground(rr(SF, 12, LN));
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout w = new LinearLayout(this);
        w.setPadding(dp(20), dp(8), dp(20), 0);
        w.addView(e, new LinearLayout.LayoutParams(-1, -2));
        gb().setTitle(title).setView(w)
                .setPositiveButton("موافق", (d0, w0) -> cb.on(e.getText().toString()))
                .setNegativeButton("إلغاء", null).show();
    }

    Button small(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(TX);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(rr(SF2, 14, LN));
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (extensionThemeChanged) { extensionThemeChanged = false; recreate(); return; }
        if (tab.equals("files") && files != null && files.pendingPermission) {
            files.pendingPermission = false;
            showFiles();
        }
    }

    View toggleCb(String t, boolean v, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        r.setBackground(rr(SF, 12, LN));
        r.addView(tv(t, TX, 14), new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(v);
        int[][] sts = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(sts, new int[]{Color.WHITE, 0xFFE6E6E6}));
        sw.setTrackTintList(new ColorStateList(sts, new int[]{AC, 0x66888888}));
        sw.setOnCheckedChangeListener(l);
        r.addView(sw);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        r.setLayoutParams(p);
        return r;
    }

    void confirm(String msg, final Runnable yes) {
        new GB(this).setMessage(msg)
                .setPositiveButton("تأكيد", (d, w) -> yes.run())
                .setNegativeButton("إلغاء", null).show();
    }

    LinearLayout page() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), dp(12), dp(12), dp(24));
        return p;
    }

    void setPage(LinearLayout p) {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.addView(p);
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        applyFonts(p);
    }

    void header(String t, boolean back, boolean showNew) {
        title.setText(t);
        backBtn.setVisibility(back ? View.VISIBLE : View.GONE);
        newBtn.setVisibility(showNew ? View.VISIBLE : View.INVISIBLE);
    }

    void selNav(final int idx) {
        curNav = idx;
        for (int i = 0; i < navBtns.length; i++) {
            navBtns[i].setTextColor(i == idx ? AC : MUT);
            navBtns[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            navBtns[i].setBackground(style == 1 ? null : (i == idx ? rr(SF2, 22, 0) : null));
        }
        if (style == 1 && navFrame != null) navFrame.post(() -> lensTo(idx, lensReady));
    }

    /** يحرّك «عدسة الزجاج» إلى التبويب المحدد. */
    void lensTo(int idx, boolean animate) {
        if (lens == null || navBtns[idx].getWidth() == 0) return;
        Button b = navBtns[idx];
        int w = b.getWidth() - dp(6), h = navRow.getHeight() - dp(4);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lens.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lens.setLayoutParams(lp); }
        float tx = navRow.getLeft() + b.getLeft() + dp(3);
        lens.animate().cancel();
        if (animate) {
            lens.animate().translationX(tx).scaleX(1f).scaleY(1f).setDuration(340)
                    .setInterpolator(new OvershootInterpolator(1.15f)).start();
        } else {
            lens.setTranslationX(tx); lens.setScaleX(1f); lens.setScaleY(1f);
        }
        lensReady = true;
    }

    int nearestTab(float x) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < navBtns.length; i++) {
            float c = navRow.getLeft() + navBtns[i].getLeft() + navBtns[i].getWidth() / 2f;
            float d = Math.abs(c - x);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    void lensGrab() {
        if (lens == null) return;
        lens.animate().cancel();
        lens.animate().scaleX(1.18f).scaleY(1.12f).setDuration(160).start();
    }

    void lensDrag(float x) {
        if (lens == null || navBtns[0].getWidth() == 0) return;
        float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
        for (Button b : navBtns) {
            float l = navRow.getLeft() + b.getLeft() + dp(3);
            minL = Math.min(minL, l); maxL = Math.max(maxL, l);
        }
        lens.setTranslationX(Math.max(minL, Math.min(maxL, x - lens.getWidth() / 2f)));
        int n = nearestTab(x);
        for (int i = 0; i < navBtns.length; i++) navBtns[i].setTextColor(i == n ? AC : MUT);
    }

    void lensRelease(float x) {
        int n = nearestTab(x);
        if (n != curNav) goTab(n); else lensTo(curNav, true);
    }

    void buildRoot() {
        float dens = getResources().getDisplayMetrics().density;
        final boolean fl = style == 1;
        FrameLayout outer = new FrameLayout(this);
        if (fl) {
            backdrop = new Backdrop(this, th, Store.flag("motion", true));
            outer.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        } else {
            outer.setBackgroundColor(BG);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (fl) top.setBackground(rr(oTop != 0 ? oTop : SF2, 26, LN));
        else if (style == 0) top.setBackgroundColor(oTop != 0 ? oTop : SF);
        else if (oTop != 0) top.setBackgroundColor(oTop);
        backBtn = flat("→", v -> onBackPressed());
        title = tv("Mi_Max Ai", TX, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        newBtn = flat("+", v -> {
            cur = null; attImg = null; attAud = null; attThumb = null;
            showChat();
        });
        top.addView(backBtn, new LinearLayout.LayoutParams(-2, dp(52)));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(newBtn, new LinearLayout.LayoutParams(-2, dp(52)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(52));
        if (fl) tp.setMargins(dp(10), dp(8), dp(10), 0);
        root.addView(top, tp);
        if (style == 0) {
            View d1 = new View(this);
            d1.setBackgroundColor(LN);
            root.addView(d1, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout.LayoutParams mbp = new LinearLayout.LayoutParams(-1, -2);
        if (fl) mbp.setMargins(dp(10), dp(4), dp(10), 0);
        root.addView(media.bar(), mbp);

        navRow = new LinearLayout(this);
        navRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        String[] names = {"المحادثة", "الملفات", "السجل", "التنزيل", "الإعدادات"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setStateListAnimator(null);
            b.setOnClickListener(v -> goTab(idx));
            navBtns[i] = b;
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(46), 1f);
            np.setMargins(dp(1), 0, dp(1), 0);
            navRow.addView(b, np);
        }
        LinearLayout.LayoutParams navp = new LinearLayout.LayoutParams(-1, -2);
        if (fl) {
            navFrame = new FrameLayout(this) {
                float downX;
                boolean dragging;

                @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: downX = e.getX(); dragging = false; break;
                        case MotionEvent.ACTION_MOVE:
                            if (!dragging && Math.abs(e.getX() - downX) > dp(10)) {
                                dragging = true;
                                getParent().requestDisallowInterceptTouchEvent(true);
                                lensGrab();
                            }
                            return dragging;
                        default: break;
                    }
                    return false;
                }

                @Override public boolean onTouchEvent(MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_MOVE: lensDrag(e.getX()); return true;
                        case MotionEvent.ACTION_UP: dragging = false; lensRelease(e.getX()); return true;
                        case MotionEvent.ACTION_CANCEL: dragging = false; lensTo(curNav, true); return true;
                        default: return dragging;
                    }
                }
            };
            navFrame.setBackground(rr(oNav != 0 ? oNav : SF2, 30, LN));
            lens = new View(this);
            lens.setBackground(new Glass(dp(24), th.dark ? 0x3AFFFFFF : 0xE6FFFFFF, false, th.dark, dens, 1.9f));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(10), dp(10));
            lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
            navFrame.addView(lens, lp);
            navFrame.addView(navRow, new FrameLayout.LayoutParams(-1, -2));
            navp.setMargins(dp(10), dp(4), dp(10), dp(8));
            root.addView(navFrame, navp);
        } else {
            navRow.setBackgroundColor(oNav != 0 ? oNav : SF);
            if (style == 0) {
                View d2 = new View(this);
                d2.setBackgroundColor(LN);
                root.addView(d2, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            root.addView(navRow, navp);
        }
        outer.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(outer);
        if (appFont != null) outer.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyFonts(outer));

        int f = 0;
        if (fl) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        } else {
            getWindow().setStatusBarColor(oTop != 0 ? oTop : (style == 0 ? SF : BG));
            getWindow().setNavigationBarColor(oNav != 0 ? oNav : SF);
        }
        if (!dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    void goTab(int i) {
        sub = null;
        screens.clear();
        if (i == 0) showChat();
        else if (i == 1) showFiles();
        else if (i == 2) showHist();
        else if (i == 3) showDl();
        else showSettings();
    }

    void showFiles() {
        tab = "files";
        sub = null;
        selNav(1);
        screens.clear();
        files.openRoot();
    }

    @Override
    public void onBackPressed() {
        if (screens.size() > 1) {
            screens.pop();
            screens.peek().build.run();
            return;
        }
        if (sub != null) showSettings();
        else if (!tab.equals("chat")) showChat();
        else super.onBackPressed();
    }

    // =====================================================================
    //                              المحادثة
    // =====================================================================
    void showChat() {
        if (input != null) draft = input.getText().toString();
        tab = "chat";
        sub = null;
        selNav(0);
        header(cur != null ? cur.title : "Mi_Max Ai", false, true);
        content.removeAllViews();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        msgScroll = new ScrollView(this);
        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(dp(12), dp(12), dp(12), dp(8));
        msgScroll.addView(msgBox);
        col.addView(msgScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        attInfo = tv("", TX, 13);
        attInfo.setPadding(dp(14), dp(8), dp(14), dp(8));
        attInfo.setBackground(rr(SF2, 10, 0));
        attInfo.setVisibility(View.GONE);
        attInfo.setOnClickListener(v -> {
            if (rec != null && rec.on) return;
            attImg = null; attAud = null; attThumb = null;
            updateAtt();
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.setMargins(dp(10), dp(4), dp(10), 0);
        col.addView(attInfo, ap);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button pic = new Button(this);
        pic.setText("📷");
        pic.setTextSize(18);
        pic.setBackground(rr(SF, 22, LN));
        pic.setStateListAnimator(null);
        pic.setOnClickListener(v -> pickImage());
        bar.addView(pic, new LinearLayout.LayoutParams(dp(46), dp(46)));
        micBtn = new Button(this);
        micBtn.setTextSize(18);
        micBtn.setStateListAnimator(null);
        micBtn.setOnClickListener(v -> toggleMic());
        boolean recording = rec != null && rec.on;
        micBtn.setText(recording ? "■" : "🎤");
        micBtn.setTextColor(recording ? ON : TX);
        micBtn.setBackground(rr(recording ? ER : SF, 22, recording ? 0 : LN));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(46), dp(46));
        mp.setMarginStart(dp(6));
        bar.addView(micBtn, mp);
        input = new EditText(this);
        input.setHint("اكتب رسالتك…");
        input.setHintTextColor(MUT);
        input.setTextColor(TX);
        input.setTextSize(15.5f);
        input.setBackground(rr(SF, 22, LN));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setMaxLines(5);
        input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(draft);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(input, ip);
        sendBtn = new Button(this);
        sendBtn.setAllCaps(false);
        sendBtn.setTextColor(ON);
        sendBtn.setText(busy ? "إيقاف" : "إرسال");
        sendBtn.setBackground(rr(AC, 22, 0));
        sendBtn.setStateListAnimator(null);
        sendBtn.setOnClickListener(v -> send(false));
        bar.addView(sendBtn, new LinearLayout.LayoutParams(-2, dp(46)));
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        content.addView(col, new FrameLayout.LayoutParams(-1, -1));
        fillMsgs();
        updateAtt();
    }

    void scrollEnd() {
        if (msgScroll != null) msgScroll.post(() -> msgScroll.fullScroll(View.FOCUS_DOWN));
    }

    void fillMsgs() {
        msgBox.removeAllViews();
        lastTv = null;
        if (cur == null || cur.msgs.isEmpty()) {
            TextView h = tv("", TX, 26);
            SpannableString ss = new SpannableString("Mi_Max Ai");
            ss.setSpan(new ForegroundColorSpan(AC), 7, 9, 0);
            h.setText(ss);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setGravity(Gravity.CENTER);
            h.setPadding(0, dp(40), 0, dp(6));
            msgBox.addView(h);
            TextView s = tv("اسألني أي شيء، أرسل صورة لأصفها، أو سجّل رسالة صوتية.", MUT, 14);
            s.setGravity(Gravity.CENTER);
            s.setPadding(dp(10), 0, dp(10), dp(20));
            msgBox.addView(s);
            String[] chips = {"لخّص لي نصاً", "اشرح لي هذه الصورة", "اكتب لي كوداً", "ترجم إلى الإنجليزية"};
            for (final String c : chips) {
                Button b = new Button(this);
                b.setText(c);
                b.setAllCaps(false);
                b.setTextColor(TX);
                b.setTextSize(14);
                b.setBackground(rr(SF, 20, LN));
                b.setStateListAnimator(null);
                b.setOnClickListener(v -> { input.setText(c + ": "); input.setSelection(input.length()); input.requestFocus(); });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
                p.bottomMargin = dp(8);
                msgBox.addView(b, p);
            }
            return;
        }
        for (int i = 0; i < cur.msgs.size(); i++) bubble(cur.msgs.get(i), i == cur.msgs.size() - 1);
        scrollEnd();
    }

    void bubble(final Store.Msg m, final boolean last) {
        final boolean u = m.r.equals("user");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(10), dp(13), dp(10));
        box.setBackground(u ? rr(oBubU != 0 ? oBubU : AC, 16, 0) : rr(oBubA != 0 ? oBubA : SF, 16, LN));
        if (m.img != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(m.img);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(150));
            iv.setMaxWidth(dp(150));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.bottomMargin = dp(6);
            box.addView(iv, lp);
        }
        TextView t = new TextView(this);
        t.setTextSize(15.5f);
        t.setLineSpacing(0, 1.25f);
        t.setTextColor(u ? ON : TX);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.85f));
        t.setText(u ? m.t : Md.render(m.t, SF2));
        box.addView(t);
        box.setOnLongClickListener(v -> { msgMenu(m, last); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = u ? Gravity.START : Gravity.END;
        lp.bottomMargin = dp(10);
        msgBox.addView(box, lp);
        lastTv = t;
    }

    void msgMenu(final Store.Msg m, boolean last) {
        final ArrayList<String> items = new ArrayList<>();
        items.add("نسخ");
        if (m.r.equals("assistant")) {
            items.add("استماع");
            if (last) items.add("إعادة الرد");
        }
        final String[] arr = items.toArray(new String[0]);
        new GB(this).setItems(arr, (d, w) -> {
            String s = arr[w];
            if (s.equals("نسخ")) copy(m.t);
            else if (s.equals("استماع")) speak(m.t);
            else send(true);
        }).show();
    }

    void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("mimax", s));
        toast("تم النسخ");
    }

    void speak(String t) {
        pendingSpeak = t.replaceAll("```[\\s\\S]*?```", " ");
        if (tts == null) {
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("ar"));
                    tts.speak(pendingSpeak, TextToSpeech.QUEUE_FLUSH, null, "mm");
                } else toast("الاستماع غير متاح على هذا الجهاز");
            });
        } else {
            tts.speak(pendingSpeak, TextToSpeech.QUEUE_FLUSH, null, "mm");
        }
    }

    // ---- الذاكرة والاستدعاء ----
    HashSet<String> toks(String s) {
        HashSet<String> h = new HashSet<>();
        Matcher m = Pattern.compile("[\\p{L}\\p{N}]{3,}").matcher(s.toLowerCase());
        while (m.find()) h.add(m.group());
        return h;
    }

    ArrayList<String> recall(String q) {
        ArrayList<String> out = new ArrayList<>();
        if (!Store.flag("recall", true)) return out;
        HashSet<String> qt = toks(q);
        if (qt.isEmpty()) return out;
        ArrayList<Object[]> sc = new ArrayList<>();
        for (Store.Chat c : chats) {
            if (cur != null && c.id.equals(cur.id)) continue;
            for (Store.Msg m : c.msgs) {
                HashSet<String> mt = toks(m.t);
                int n = 0;
                for (String w : qt) if (mt.contains(w)) n++;
                if (n >= 2 || (qt.size() <= 2 && n >= 1)) {
                    String tx = m.t.length() > 220 ? m.t.substring(0, 220) : m.t;
                    sc.add(new Object[]{n, "[" + c.title + "] " + (m.r.equals("user") ? "المستخدم" : "المساعد") + ": " + tx});
                }
            }
        }
        Collections.sort(sc, (a, b) -> (Integer) b[0] - (Integer) a[0]);
        for (int i = 0; i < Math.min(4, sc.size()); i++) out.add((String) sc.get(i)[1]);
        return out;
    }

    String buildSystem(String q) {
        StringBuilder s = new StringBuilder(prompt.isEmpty() ? DEFAULT_PROMPT : prompt);
        s.append("\nالتاريخ الحالي: ")
                .append(new SimpleDateFormat("EEEE d MMMM yyyy", new Locale("ar")).format(new Date())).append(".");
        String u = Store.profileName();
        if (!u.isEmpty()) s.append("\nاسم المستخدم: ").append(u).append(".");
        if (!mem.isEmpty()) {
            s.append("\n\nمعلومات محفوظة عن المستخدم (استخدمها عند الصلة فقط ولا تذكرها بلا داعٍ):");
            for (String m : mem) s.append("\n- ").append(m);
        }
        ArrayList<String> r = recall(q);
        if (!r.isEmpty()) {
            s.append("\n\nمقتطفات من محادثات سابقة قد تفيد:");
            for (String x : r) s.append("\n- ").append(x);
        }
        return s.toString();
    }

    JSONObject jm(String role, Object content) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("content", content);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    void extractMem(final String u, final String a) {
        if (!Store.flag("autoMem", true)) return;
        new Thread(() -> {
            try {
                JSONArray ms = new JSONArray();
                ms.put(jm("system", "استخرج من الحوار حقائق ثابتة ومفيدة عن المستخدم (اسمه، اهتماماته، تفضيلاته، مشاريعه، لغته). "
                        + "أعد مصفوفة JSON فقط من جمل عربية قصيرة، أو [] إن لم يوجد شيء. "
                        + "لا تُدرج معلومات حساسة مثل الصحة أو المال أو كلمات السر."));
                ms.put(jm("user", "المستخدم: " + cut(u, 800) + "\nالمساعد: " + cut(a, 800)));
                String r = Api.chat(Store.key(), Models.resolve(Store.model()), ms, 0.2, false, null, null);
                int st = r.indexOf('['), en = r.lastIndexOf(']');
                if (st < 0 || en < st) return;
                final JSONArray arr = new JSONArray(r.substring(st, en + 1));
                ui.post(() -> {
                    boolean ch = false;
                    for (int i = 0; i < arr.length() && i < 3; i++) {
                        String t = arr.optString(i, "").trim();
                        if (t.length() > 3 && !mem.contains(t) && mem.size() < 100) { mem.add(t); ch = true; }
                    }
                    if (ch) Store.saveMem(mem);
                });
            } catch (Exception e) { /* تجاهل */ }
        }).start();
    }

    // ---- الإرسال ----
    void send(final boolean regen) {
        if (busy) { cancel[0] = true; return; }
        if (Store.key().isEmpty()) {
            toast("أضف مفتاح API أولاً: الإعدادات ← الذكاء الاصطناعي");
            return;
        }
        final Store.Msg um;
        final String text;
        if (regen) {
            if (cur == null || cur.msgs.isEmpty()) return;
            if (cur.msgs.get(cur.msgs.size() - 1).r.equals("assistant")) cur.msgs.remove(cur.msgs.size() - 1);
            if (cur.msgs.isEmpty()) return;
            um = cur.msgs.get(cur.msgs.size() - 1);
            text = um.t;
        } else {
            String tx = input.getText().toString().trim();
            if (tx.isEmpty() && attImg == null && attAud == null) return;
            if (cur == null) {
                cur = new Store.Chat();
                chats.add(0, cur);
                cur.title = tx.isEmpty() ? "محادثة" : (tx.length() > 40 ? tx.substring(0, 40) : tx);
            }
            um = new Store.Msg("user", tx.isEmpty() ? (attAud != null ? "[رسالة صوتية]" : "[صورة]") : tx);
            um.img = attThumb;
            text = tx;
        }
        final String img = regen ? null : attImg;
        final String aud = regen ? null : attAud;
        final String model = aud != null ? (Store.audioModel().isEmpty() ? Store.model() : Store.audioModel()) : Store.model();

        JSONArray msgs = new JSONArray();
        msgs.put(jm("system", buildSystem(um.t)));
        ArrayList<Store.Msg> hist = new ArrayList<>();
        for (Store.Msg m : cur.msgs) if (m != um) hist.add(m);
        for (int i = Math.max(0, hist.size() - 16); i < hist.size(); i++) msgs.put(jm(hist.get(i).r, hist.get(i).t));
        Object content;
        if (img == null && aud == null) {
            content = um.t;
        } else {
            JSONArray parts = new JSONArray();
            try {
                JSONObject t = new JSONObject();
                t.put("type", "text");
                t.put("text", text.isEmpty() ? (aud != null ? "استمع إلى الرسالة الصوتية وأجب عليها." : "صف هذه الصورة بالتفصيل.") : text);
                parts.put(t);
                if (img != null) {
                    JSONObject p = new JSONObject();
                    p.put("type", "image_url");
                    p.put("image_url", new JSONObject().put("url", img));
                    parts.put(p);
                }
                if (aud != null) {
                    JSONObject p = new JSONObject();
                    p.put("type", "input_audio");
                    p.put("input_audio", new JSONObject().put("data", aud).put("format", "wav"));
                    parts.put(p);
                }
            } catch (Exception e) { /* تجاهل */ }
            content = parts;
        }
        msgs.put(jm("user", content));

        if (!regen) {
            cur.msgs.add(um);
            input.setText("");
            draft = "";
            attImg = null; attAud = null; attThumb = null;
        }
        final Store.Msg am = new Store.Msg("assistant", "");
        cur.msgs.add(am);
        cur.ts = System.currentTimeMillis();
        fillMsgs();
        updateAtt();
        title.setText(cur.title);
        final TextView tvv = lastTv;
        tvv.setText("…");
        busy = true;
        cancel = new boolean[]{false};
        final boolean[] cn = cancel;
        sendBtn.setText("إيقاف");
        final boolean stream = Store.flag("stream", true);
        final String key = Store.key();
        final double temp = Store.temp();
        final JSONArray fm = msgs;

        new Thread(() -> {
            String res = "";
            String err = null;
            try {
                res = Api.chat(key, Models.resolve(model), fm, temp, stream,
                        full -> ui.post(() -> { am.t = full; tvv.setText(Md.render(full, SF2)); scrollEnd(); }), cn);
            } catch (Exception e) {
                err = e.getMessage();
                if (err == null) err = "خطأ غير معروف";
                if (err.contains("النموذج غير موجود")) Store.put("autoModel", "");
            }
            final String fr = res, fe = err;
            ui.post(() -> {
                if (fe != null) am.t = (am.t.isEmpty() ? "" : am.t + "\n\n") + "⚠ " + fe;
                else am.t = fr.isEmpty() ? (cn[0] ? "(تم الإيقاف)" : "(لا يوجد رد)") : fr;
                busy = false;
                Store.saveChats(chats);
                if (tab.equals("chat") && sub == null) showChat();
                if (fe == null && !fr.isEmpty() && !regen) extractMem(um.t, am.t);
            });
        }).start();
    }

    // =====================================================================
    //                         الصور والصوت
    // =====================================================================
    void updateAtt() {
        if (attInfo == null) return;
        ArrayList<String> p = new ArrayList<>();
        if (attImg != null) p.add("صورة");
        if (attAud != null) p.add("رسالة صوتية (" + attDur + " ث)");
        if (rec != null && rec.on) {
            attInfo.setText("جارٍ التسجيل… " + ((System.currentTimeMillis() - rec.t0) / 1000) + " ث");
            attInfo.setVisibility(View.VISIBLE);
        } else if (p.isEmpty()) {
            attInfo.setVisibility(View.GONE);
        } else {
            attInfo.setText("المرفقات: " + TextUtils.join(" + ", p) + "  — اضغط للإزالة");
            attInfo.setVisibility(View.VISIBLE);
        }
    }

    void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "اختر صورة"), REQ_IMG);
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent d) {
        super.onActivityResult(rq, rs, d);
        if (rq == REQ_IMG && rs == RESULT_OK && d != null && d.getData() != null) {
            final Uri u = d.getData();
            new Thread(() -> {
                try {
                    final Object[] r = loadImage(u);
                    ui.post(() -> { attImg = (String) r[0]; attThumb = (Bitmap) r[1]; updateAtt(); });
                } catch (Exception e) {
                    ui.post(() -> toast("تعذر قراءة الصورة"));
                }
            }).start();
        }
    }

    Bitmap scale(Bitmap b, int max) {
        float r = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
        if (r >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * r), Math.round(b.getHeight() * r), true);
    }

    Object[] loadImage(Uri u) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(u);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / s > 2560) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        in = getContentResolver().openInputStream(u);
        Bitmap b = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        if (b == null) throw new Exception("bad image");
        int rot = 0;
        try {
            in = getContentResolver().openInputStream(u);
            ExifInterface ex = new ExifInterface(in);
            int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            in.close();
            if (ori == 6) rot = 90; else if (ori == 3) rot = 180; else if (ori == 8) rot = 270;
        } catch (Exception e) { /* تجاهل */ }
        if (rot != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(rot);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
        }
        Bitmap big = scale(b, 1280);
        Bitmap th = scale(b, 180);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        big.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        return new Object[]{"data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP), th};
    }

    /** تسجيل صوتي PCM 16kHz أحادي ويُحوَّل إلى WAV. */
    static class Rec {
        AudioRecord ar;
        Thread th;
        volatile boolean on;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long t0;

        boolean start() {
            try {
                int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) return false;
                ar.startRecording();
            } catch (Exception e) {
                return false;
            }
            on = true;
            t0 = System.currentTimeMillis();
            th = new Thread(() -> {
                byte[] b = new byte[4096];
                while (on) {
                    int n = ar.read(b, 0, b.length);
                    if (n > 0) { synchronized (pcm) { pcm.write(b, 0, n); } }
                    if (System.currentTimeMillis() - t0 > 120000) on = false;
                }
            });
            th.start();
            return true;
        }

        byte[] stop() {
            on = false;
            try { th.join(1500); } catch (Exception e) { /* تجاهل */ }
            try { ar.stop(); ar.release(); } catch (Exception e) { /* تجاهل */ }
            byte[] d;
            synchronized (pcm) { d = pcm.toByteArray(); }
            ByteBuffer bb = ByteBuffer.allocate(44 + d.length).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes());
            bb.putInt(36 + d.length);
            bb.put("WAVE".getBytes());
            bb.put("fmt ".getBytes());
            bb.putInt(16);
            bb.putShort((short) 1);
            bb.putShort((short) 1);
            bb.putInt(16000);
            bb.putInt(32000);
            bb.putShort((short) 2);
            bb.putShort((short) 16);
            bb.put("data".getBytes());
            bb.putInt(d.length);
            bb.put(d);
            return bb.array();
        }
    }

    void toggleMic() {
        if (rec != null && rec.on) { stopRec(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUD);
            return;
        }
        startRec();
    }

    void startRec() {
        rec = new Rec();
        if (!rec.start()) { toast("تعذر بدء التسجيل"); rec = null; return; }
        attAud = null;
        micBtn.setText("■");
        micBtn.setTextColor(ON);
        micBtn.setBackground(rr(ER, 22, 0));
        recTick = new Runnable() {
            public void run() {
                if (rec == null) return;
                if (!rec.on) { stopRec(); return; }
                updateAtt();
                ui.postDelayed(this, 500);
            }
        };
        ui.post(recTick);
    }

    void stopRec() {
        if (rec == null) return;
        Rec r = rec;
        rec = null;
        ui.removeCallbacks(recTick);
        byte[] wav = r.stop();
        attDur = (int) ((System.currentTimeMillis() - r.t0) / 1000);
        if (micBtn != null) {
            micBtn.setText("🎤");
            micBtn.setTextColor(TX);
            micBtn.setBackground(rr(SF, 22, LN));
        }
        if (wav != null && wav.length > 44 + 16000) attAud = Base64.encodeToString(wav, Base64.NO_WRAP);
        else toast("التسجيل قصير جداً");
        updateAtt();
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] p, int[] r) {
        super.onRequestPermissionsResult(rq, p, r);
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (rq == REQ_AUD) {
            if (ok) startRec(); else toast("لم يتم السماح باستخدام الميكروفون");
        }
        if (rq == 4) showFiles();
    }

    // =====================================================================
    //                               السجل
    // =====================================================================
    void showHist() {
        tab = "hist";
        sub = null;
        selNav(2);
        header("سجل المحادثات", false, false);
        LinearLayout p = page();
        final EditText s = new EditText(this);
        s.setHint("بحث في المحادثات");
        s.setHintTextColor(MUT);
        s.setTextColor(TX);
        s.setBackground(rr(SF, 10, LN));
        s.setPadding(dp(12), dp(10), dp(12), dp(10));
        s.setSingleLine(true);
        p.addView(s, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        p.addView(list, lp);
        s.addTextChangedListener(watcher(q -> drawHist(list, q)));
        drawHist(list, "");
        setPage(p);
    }

    void drawHist(LinearLayout list, String q) {
        list.removeAllViews();
        q = q.trim().toLowerCase();
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy، h:mm a", new Locale("ar"));
        int n = 0;
        for (final Store.Chat c : chats) {
            boolean ok = q.isEmpty() || c.title.toLowerCase().contains(q);
            if (!ok) for (Store.Msg m : c.msgs) if (m.t.toLowerCase().contains(q)) { ok = true; break; }
            if (!ok) continue;
            n++;
            View r = row("💬", c.title, c.msgs.size() + " رسالة — " + df.format(new Date(c.ts)), v -> { cur = c; showChat(); });
            r.setOnLongClickListener(v -> { chatMenu(c); return true; });
            list.addView(r);
        }
        if (n == 0) list.addView(note(q.isEmpty() ? "لا توجد محادثات بعد. اضغط مطولاً على أي محادثة لخيارات الحذف والتصدير." : "لا نتائج"));
        else list.addView(note("اضغط مطولاً على المحادثة لفتح قائمة الخيارات."));
    }

    void chatMenu(final Store.Chat c) {
        final String[] it = {"فتح", "مشاركة كنص", "حذف"};
        new GB(this).setTitle(c.title).setItems(it, (d, w) -> {
            if (w == 0) { cur = c; showChat(); }
            else if (w == 1) {
                StringBuilder sb = new StringBuilder(c.title).append("\n\n");
                for (Store.Msg m : c.msgs) sb.append(m.r.equals("user") ? "أنا" : "Mi_Max Ai").append(":\n").append(m.t).append("\n\n");
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, sb.toString());
                startActivity(Intent.createChooser(i, "مشاركة"));
            } else {
                confirm("حذف هذه المحادثة؟", () -> {
                    Store.markChatDeleted(c.id);
                    chats.remove(c);
                    if (cur == c) cur = null;
                    Store.saveChats(chats);
                    showHist();
                });
            }
        }).show();
    }

    // =====================================================================
    //                              التنزيل
    // =====================================================================
    void showDl() {
        tab = "dl";
        sub = null;
        selNav(3);
        header("تنزيل الفيديو", false, false);
        LinearLayout p = page();
        p.addView(note("الصق رابط فيديو من تيك توك" + (Store.get("dlApi", "").isEmpty() ? "" : " أو أي موقع يدعمه خادم التنزيل الذي أضفته")
                + ". حمّل فقط ما يحق لك تنزيله."));
        final EditText u = field(p, "رابط الفيديو", "", false, true);
        final LinearLayout res = new LinearLayout(this);
        res.setOrientation(LinearLayout.VERTICAL);
        p.addView(btn("جلب الفيديو", AC, ON, v -> {
            final String url = u.getText().toString().trim();
            if (!url.startsWith("http")) { toast("الصق رابطاً صحيحاً"); return; }
            res.removeAllViews();
            res.addView(note("جارٍ الجلب…"));
            new Thread(() -> {
                try {
                    final Api.Video vd = Api.resolve(url, Store.get("dlApi", ""), Store.get("dlKey", ""));
                    ui.post(() -> showVideo(res, vd));
                } catch (Exception e) {
                    final String m = e.getMessage() == null ? "فشل الجلب" : e.getMessage();
                    ui.post(() -> { res.removeAllViews(); res.addView(box(m)); });
                }
            }).start();
        }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(12);
        p.addView(res, rp);
        setPage(p);
    }

    void showVideo(LinearLayout res, Api.Video vd) {
        res.removeAllViews();
        if (vd.cover != null) {
            final ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(240));
            final String cu = vd.cover;
            new Thread(() -> {
                try {
                    InputStream in = new URL(cu).openStream();
                    final Bitmap b = BitmapFactory.decodeStream(in);
                    in.close();
                    if (b != null) ui.post(() -> iv.setImageBitmap(b));
                } catch (Exception e) { /* تجاهل */ }
            }).start();
            res.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView t = tv(vd.title == null ? "" : vd.title, TX, 15);
        t.setPadding(0, dp(8), 0, dp(4));
        res.addView(t);
        for (final Api.Item it : vd.items) {
            res.addView(btn("تنزيل: " + it.label, AC, ON, v -> download(it)));
            res.addView(btn("فتح في المتصفح: " + it.label, SF2, TX, v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.url))); }
                catch (Exception e) { toast("تعذر فتح الرابط"); }
            }));
        }
    }

    void download(Api.Item it) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STO);
            toast("اسمح بالتخزين ثم أعد الضغط");
            return;
        }
        try {
            String name = it.file.replaceAll("[\\\\/:*?\"<>|]", "_");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(it.url));
            r.setTitle(name);
            r.addRequestHeader("User-Agent", "Mozilla/5.0");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MiMaxAi/" + name);
            dm.enqueue(r);
            toast("بدأ التنزيل — تجده في Download/MiMaxAi");
        } catch (Exception e) {
            toast("تعذر التنزيل. جرّب فتح الرابط في المتصفح.");
        }
    }

    // =====================================================================
    //                             الإعدادات
    // =====================================================================
    void showSettings() {
        tab = "set";
        sub = null;
        selNav(4);
        header("الإعدادات", false, false);
        LinearLayout p = page();
        p.addView(row("USR", "الملف الشخصي", Store.profileName().isEmpty() ? "اسم محلي اختياري" : Store.profileName(), v -> openSub("profile")));
        p.addView(row("API", "الذكاء الاصطناعي", Store.hasCustomKey() ? "تستخدم مفتاحك الخاص" : "يعمل بالمفتاح المدمج", v -> openSub("ai")));
        p.addView(row("MDL", "النماذج المجانية", Store.model().isEmpty() ? "اختيار تلقائي" : Store.model(), v -> openSub("models")));
        p.addView(row("TXT", "البرمبت", "تعليمات تحسّن أسلوب الذكاء الاصطناعي", v -> openSub("prompt")));
        p.addView(row("MEM", "الذاكرة", "ما يتذكره عنك (" + mem.size() + ")", v -> openSub("mem")));
        p.addView(row("DL", "إعدادات التنزيل", "خادم تنزيل لمواقع مثل يوتيوب", v -> openSub("dl")));
        p.addView(row("WEB", "المتصفح المدمج", "تصفح المواقع والتنزيلات داخل التطبيق", v -> startActivity(new Intent(MainActivity.this, BrowserActivity.class))));
        p.addView(row("WEB+", "اختصارات المواقع", "عرض وإدارة أسماء المواقع المحفوظة في المتصفح", v -> { Intent bi = new Intent(MainActivity.this, BrowserActivity.class); bi.putExtra("manage_shortcuts", true); startActivity(bi); }));
        p.addView(row("TERM", "Terminal محلي", "أوامر Android داخل مساحة عمل آمنة", v -> startActivity(new Intent(MainActivity.this, TerminalActivity.class))));
        p.addView(row("AGENT", "AI Agent", "تحليل ملفات مساحة العمل وإنشاء تعديلات بموافقتك", v -> startActivity(new Intent(MainActivity.this, AgentActivity.class))));
        p.addView(row("MT1", "الإضافات وAI Extension Creator", "تثبيت حزم .mt1 وإنشاء إضافات بالذكاء الاصطناعي", v -> startActivity(new Intent(MainActivity.this, ExtensionActivity.class))));
        p.addView(row("IDE", "Developer Studio", "محررات HTML وPython وتحويل المشاريع إلى APK", v -> startActivity(new Intent(MainActivity.this, DeveloperStudioActivity.class))));
        p.addView(row("THEME", "Theme Studio", "استيراد وإنشاء ثيمات مستقلة بصيغة .mt_1", v -> startActivity(new Intent(MainActivity.this, ThemeStudioActivity.class))));
        p.addView(row("PC", "اتصال الكمبيوتر", "ADB وFastboot عبر الكمبيوتر على نفس الشبكة", v -> startActivity(new Intent(MainActivity.this, RemotePcActivity.class))));
        p.addView(row("BACKUP", "النسخ الاحتياطي", "تصدير واستعادة المحادثات والذاكرة والإعدادات", v -> startActivity(new Intent(MainActivity.this, BackupActivity.class))));
        p.addView(row("UI", "المظهر", (style == 1 ? "زجاج سائل" : style == 2 ? "Material 3" : "عادي") + " — " + th.nameAr, v -> openSub("look")));
        p.addView(row("DEV", "المطور والشكاوى", "محمد السعيد علي", v -> openSub("dev")));
        if (Store.flag("devOpts", false)) p.addView(row("DEV+", "خيارات المطور", "ألوان مخصصة لكل عنصر", v -> openSub("devopts")));
        setPage(p);
    }

    void openSub(String s) {
        sub = s;
        LinearLayout p = page();
        String t = "";
        switch (s) {
            case "profile": t = "الملف الشخصي"; pProfile(p); break;
            case "ai": t = "الذكاء الاصطناعي"; pAi(p); break;
            case "prompt": t = "البرمبت"; pPrompt(p); break;
            case "mem": t = "الذاكرة"; pMem(p); break;
            case "dl": t = "إعدادات التنزيل"; pDl(p); break;
            case "models": t = "النماذج المجانية"; pModels(p); break;
            case "look": t = "المظهر"; pLook(p); break;
            case "devopts": t = "خيارات المطور"; pDevOpts(p); break;
            default: t = "المطور والشكاوى"; pDev(p); break;
        }
        header(t, true, false);
        setPage(p);
    }

    void pProfile(final LinearLayout p) {
        p.removeAllViews();
        p.addView(note("هذا ملف شخصي محلي اختياري. لا يوجد تسجيل دخول أو حساب سحابي."));
        EditText displayName = field(p, "الاسم الذي يظهر داخل التطبيق", Store.profileName(), false, false);
        p.addView(btn("حفظ الاسم", AC, ON, v -> {
            String chosen = displayName.getText().toString().trim();
            Store.setProfileName(chosen);
            toast("تم حفظ الاسم على هذا الجهاز");
        }));
        p.addView(note(chats.size() + " محادثة — " + mem.size() + " عنصر في الذاكرة"));
        p.addView(note("المحادثات والذاكرة والإعدادات محفوظة محلياً على جهازك."));
        p.addView(btn("مسح بيانات التطبيق المحلية", SF, ER, v -> confirm(
                "سيتم حذف المحادثات والذاكرة والبرمبت والاسم المحلي فقط. هل تريد المتابعة؟",
                () -> { Store.clearLocalData(); reload(); showSettings(); })));
    }

    void pAi(final LinearLayout p) {
        p.addView(box(Store.hasCustomKey()
                ? "تستخدم مفتاحك الخاص حالياً. امسحه من الخانة للعودة إلى المفتاح المدمج."
                : "التطبيق يعمل مباشرة بمفتاح مدمج ومشفّر بداخله، فلا تحتاج لإدخال شيء. يمكنك اختيارياً إضافة مفتاحك الخاص ليُستخدم بدلاً منه."));
        EditText k = field(p, "مفتاحك الخاص (اختياري)", Store.get("key", ""), true, true);
        k.setHint("sk-or-…");
        k.addTextChangedListener(watcher(s -> Store.put("key", s.trim())));
        final TextView res = note("");
        p.addView(btn("اختبار الاتصال", SF2, TX, v -> {
            res.setText("جارٍ الاختبار…");
            new Thread(() -> {
                String m;
                try { m = Api.keyInfo(Store.key()); } catch (Exception e) { m = "فشل: " + e.getMessage(); }
                final String fm = m;
                ui.post(() -> res.setText(fm));
            }).start();
        }));
        p.addView(res);
        p.addView(row("MDL", "النموذج الحالي", Store.model().isEmpty() ? "اختيار تلقائي من النماذج المجانية" : Store.model(), v -> openSub("models")));
        fAudio = field(p, "نموذج الرسائل الصوتية (فارغ = نفس نموذج المحادثة)", Store.audioModel(), false, true);
        fAudio.addTextChangedListener(watcher(s -> Store.put("audioModel", s.trim())));
        final TextView tl = tv("الإبداع (الحرارة): " + Store.temp(), MUT, 13);
        tl.setPadding(0, dp(12), 0, dp(4));
        p.addView(tl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(15);
        sb.setProgress((int) Math.round(Store.temp() * 10));
        sb.getProgressDrawable().setTint(AC);
        sb.getThumb().setTint(AC);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                Store.put("temp", String.valueOf(v / 10.0));
                tl.setText("الإبداع (الحرارة): " + (v / 10.0));
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        p.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        p.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));
        p.addView(toggle("عرض الرد تدريجياً أثناء الكتابة", Store.flag("stream", true), "stream"));
        p.addView(toggle("حفظ المعلومات تلقائياً في الذاكرة", Store.flag("autoMem", true), "autoMem"));
        p.addView(toggle("تذكّر المحادثات السابقة", Store.flag("recall", true), "recall"));
        p.addView(toggle("حركة الخلفية (تُطبَّق عند إعادة فتح التطبيق)", Store.flag("motion", true), "motion"));
        p.addView(note("اختيار نموذج غير مجاني يستهلك من رصيد المفتاح. الحفظ التلقائي للذاكرة يرسل مقطعاً قصيراً من كل حوار إلى النموذج."));
    }

    // ---- النماذج المجانية ----
    boolean fetchingModels = false;

    void pModels(final LinearLayout p) {
        p.removeAllViews();
        p.addView(note("هذه النماذج المجانية المتاحة حالياً على OpenRouter، ومميزات كل نموذج مأخوذة من بيانات الموقع. اختر نموذجاً ويبقى مختاراً حتى تغيّره. قد تفرض النماذج المجانية حدّاً على عدد الطلبات."));
        final List<Models.M> all = Models.load();
        final long ts;
        long t0 = 0;
        try { t0 = Long.parseLong(Store.get("freeModelsTs", "0")); } catch (Exception e) { /* تجاهل */ }
        ts = t0;
        final Runnable refresh = () -> {
            if (fetchingModels) return;
            fetchingModels = true;
            toast("جارٍ جلب القائمة…");
            new Thread(() -> {
                try {
                    List<Models.M> l = Models.parseFree(Api.models());
                    if (l.isEmpty()) throw new Exception("لم يرجع الموقع أي نموذج مجاني");
                    Models.save(l);
                    ui.post(() -> { fetchingModels = false; if ("models".equals(sub)) pModels(p); });
                } catch (Exception e) {
                    final String m = String.valueOf(e.getMessage());
                    ui.post(() -> { fetchingModels = false; toast("تعذر جلب القائمة: " + m); });
                }
            }).start();
        };
        p.addView(btn(ts == 0 ? "جلب القائمة من الموقع" : "تحديث القائمة (" + all.size() + " نموذج)", AC, ON, v -> refresh.run()));
        if (all.isEmpty()) {
            p.addView(note("لم تُجلب القائمة بعد. اضغط الزر أعلاه (يلزم اتصال بالإنترنت)."));
            if (ts == 0) refresh.run();
        }
        boolean auto = Store.model().isEmpty();
        p.addView(modelCard("اختيار تلقائي", "يختار التطبيق أفضل نموذج مجاني يدعم الأدوات وبأكبر سياق"
                + (Store.get("autoModel", "").isEmpty() ? "" : "\nالحالي: " + Store.get("autoModel", "")), "", auto, v -> {
            Store.put("model", "");
            Store.put("autoModel", "");
            toast("تم: اختيار تلقائي");
            pModels(p);
        }));
        final EditText f = new EditText(this);
        f.setHint("بحث في النماذج");
        f.setHintTextColor(MUT);
        f.setTextColor(TX);
        f.setBackground(rr(SF, 14, LN));
        f.setPadding(dp(14), dp(10), dp(14), dp(10));
        f.setSingleLine(true);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(0, dp(6), 0, dp(10));
        if (!all.isEmpty()) p.addView(f, fp);
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        p.addView(listBox);
        final Txt draw = q -> {
            listBox.removeAllViews();
            String qq = q.trim().toLowerCase();
            int shown = 0;
            for (final Models.M m : all) {
                if (!qq.isEmpty() && !m.id.toLowerCase().contains(qq) && !m.name.toLowerCase().contains(qq)) continue;
                shown++;
                final boolean sel = m.id.equals(Store.model());
                listBox.addView(modelCard(m.name, m.id + "\n" + m.desc, Models.features(m), sel, v -> {
                    Store.put("model", m.id);
                    Store.setFlag("modelChosen", true);
                    toast("تم اختيار: " + m.name);
                    pModels(p);
                }));
            }
            if (shown == 0 && !all.isEmpty()) listBox.addView(note("لا نتائج"));
        };
        f.addTextChangedListener(watcher(draw));
        draw.on("");
    }

    View modelCard(String name, String sub1, String feats, boolean selected, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rr(selected ? SF2 : SF, 18, LN));
        TextView n = tv((selected ? "✓ " : "") + name, selected ? AC : TX, 15);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        c.addView(n);
        if (!feats.isEmpty()) {
            TextView ft = tv(feats, AC, 12.5f);
            ft.setPadding(0, dp(4), 0, dp(2));
            ft.setLineSpacing(0, 1.2f);
            c.addView(ft);
        }
        TextView d = tv(sub1, MUT, 12);
        d.setLineSpacing(0, 1.2f);
        d.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        c.addView(d);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------------- الخط ----------------
    static final String[][] FONTS = {{"الافتراضي", ""}, {"Sans Serif", "sans-serif"}, {"Serif", "serif"}, {"Monospace", "monospace"},
            {"Casual", "casual"}, {"Condensed", "sans-serif-condensed"}, {"Light", "sans-serif-light"}, {"Medium", "sans-serif-medium"}};

    void pFont(final LinearLayout p) {
        p.addView(note("الخط"));
        String cur = Store.get("fontFam", "");
        String curName = cur.startsWith("file:") ? "خط مخصص من ملف" : "الافتراضي";
        for (String[] f : FONTS) if (f[1].equals(cur)) curName = f[0];
        p.addView(row("Aa", "نوع الخط", curName, v -> {
            final List<String> labels = new ArrayList<>();
            for (String[] f : FONTS) labels.add(f[0]);
            labels.add("خط من ملف (ttf / otf)…");
            gb().setTitle("نوع الخط").setItems(labels.toArray(new String[0]), (d, w) -> {
                if (w < FONTS.length) { Store.put("fontFam", FONTS[w][1]); applyLook(); }
                else pickFontFile();
            }).show();
        }));
        int pct = 100;
        try { pct = Integer.parseInt(Store.get("fontScale", "100")); } catch (Exception e) { /* تجاهل */ }
        final TextView tl = tv("حجم الخط: " + pct + "%", MUT, 13);
        tl.setPadding(0, dp(10), 0, dp(4));
        p.addView(tl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(26);                       // 70% .. 200% بخطوة 5%
        sb.setProgress((pct - 70) / 5);
        sb.getProgressDrawable().setTint(AC);
        sb.getThumb().setTint(AC);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) { tl.setText("حجم الخط: " + (70 + v * 5) + "%"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { Store.put("fontScale", String.valueOf(70 + s.getProgress() * 5)); applyLook(); }
        });
        p.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        p.addView(note("يُطبَّق الحجم والنوع على كل التطبيق عند ترك المؤشر."));
    }

    void pickFontFile() {
        final List<File> found = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        File[] dirs = {new File(root, "Download"), new File(root, "Download/Telegram"), new File(root, "Fonts"), new File(root, "Documents"), root};
        for (File d : dirs) {
            File[] l = d.listFiles();
            if (l != null) for (File f : l) { String n = f.getName().toLowerCase(); if (f.isFile() && (n.endsWith(".ttf") || n.endsWith(".otf"))) found.add(f); }
        }
        if (found.isEmpty()) { input("مسار ملف الخط (ttf/otf)", root.getPath() + "/Download/", s -> useFont(new File(s.trim()))); return; }
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).getName();
        gb().setTitle("اختر خطاً").setItems(names, (d, w) -> useFont(found.get(w))).show();
    }

    void useFont(File f) {
        try {
            Typeface t = Typeface.createFromFile(f);
            if (t == null) throw new Exception("ملف خط غير صالح");
            File dst = new File(getFilesDir(), "font_" + System.currentTimeMillis() + ".ttf");
            ZipScreen.copy(f, dst);
            Store.put("fontFam", "file:" + dst.getPath());
            applyLook();
        } catch (Throwable e) {
            showError("تعذر استخدام الخط: " + (e.getMessage() == null ? "الملف غير صالح أو لا يمكن قراءته" : e.getMessage()));
        }
    }

    // ---------------- خيارات المطور: تخصيص الألوان ----------------
    interface IntCb { void on(int c); }

    static String hexOf(int c) { return String.format("#%08X", c); }

    Drawable swatch(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0x88888888);
        return g;
    }

    void pickColor(String title, int initial, final IntCb cb) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        final int[] al = {Color.alpha(initial)};
        final boolean[] lock = {false};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final View prev = new View(this);
        box.addView(prev, new LinearLayout.LayoutParams(-1, dp(56)));
        final EditText hex = new EditText(this);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setSingleLine(true);
        hex.setTextColor(TX);
        hex.setTextDirection(View.TEXT_DIRECTION_LTR);
        hex.setTag(R.id.mm_font, Boolean.TRUE);
        box.addView(hex, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar[] bars = new SeekBar[4];
        final String[] labs = {"الصبغة", "التشبّع", "السطوع", "الشفافية"};
        final int[] max = {360, 100, 100, 255};
        final Runnable upd = () -> {
            int c = Color.HSVToColor(al[0], hsv);
            prev.setBackground(swatch(c));
            if (!lock[0]) { lock[0] = true; hex.setText(hexOf(c)); lock[0] = false; }
        };
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView l = tv(labs[i], MUT, 12);
            l.setPadding(0, dp(6), 0, 0);
            box.addView(l);
            SeekBar sb = new SeekBar(this);
            sb.setMax(max[i]);
            sb.getProgressDrawable().setTint(AC);
            sb.getThumb().setTint(AC);
            sb.setProgress(i == 0 ? (int) hsv[0] : i == 1 ? (int) (hsv[1] * 100) : i == 2 ? (int) (hsv[2] * 100) : al[0]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean u) {
                    if (!u) return;
                    if (k == 0) hsv[0] = v; else if (k == 1) hsv[1] = v / 100f; else if (k == 2) hsv[2] = v / 100f; else al[0] = v;
                    upd.run();
                }
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = sb;
            box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        }
        hex.addTextChangedListener(watcher(s -> {
            if (lock[0]) return;
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            try {
                long v = Long.parseLong(t, 16);
                int c = t.length() <= 6 ? (0xFF000000 | (int) v) : (int) v;
                if (t.length() != 6 && t.length() != 8) return;
                Color.colorToHSV(c, hsv);
                al[0] = Color.alpha(c);
                lock[0] = true;
                bars[0].setProgress((int) hsv[0]); bars[1].setProgress((int) (hsv[1] * 100)); bars[2].setProgress((int) (hsv[2] * 100)); bars[3].setProgress(al[0]);
                prev.setBackground(swatch(c));
                lock[0] = false;
            } catch (Exception e) { /* تجاهل إدخالاً غير مكتمل */ }
        }));
        upd.run();
        gb().setTitle(title).setView(box).setPositiveButton("تطبيق", (d, w) -> cb.on(Color.HSVToColor(al[0], hsv)))
                .setNegativeButton("إلغاء", null).show();
    }

    View colorRow(final String key, String label, int color, final boolean elementOverride) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(rr(SF, 12, LN));
        final View sw = new View(this);
        sw.setBackground(swatch(color == 0 ? 0x00000000 : color));
        r.addView(sw, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.addView(tv(label, TX, 14));
        final TextView hv = tv(color == 0 ? "افتراضي" : hexOf(color), MUT, 12);
        m.addView(hv);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.setMarginStart(dp(12));
        r.addView(m, mp);
        r.setOnClickListener(v -> pickColor(label, pend.containsKey(key) ? pend.get(key) : (color == 0 ? AC : color), c -> {
            pend.put(key, c);
            sw.setBackground(swatch(c));
            hv.setText(hexOf(c) + " (غير مطبَّق)");
        }));
        if (elementOverride) r.setOnLongClickListener(v -> {
            pend.put(key, 0);
            sw.setBackground(swatch(0));
            hv.setText("افتراضي (غير مطبَّق)");
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    void pDevOpts(final LinearLayout p) {
        p.addView(note("خيارات متقدمة للمطور. اضغط على أي لون لتغييره ثم «تطبيق وإعادة تحميل». الضغط المطوّل على لون عنصر يعيده للافتراضي."));
        p.addView(toggleCb("تفعيل الألوان المخصصة", Store.flag("ccOn", false), (cb, c) -> { Store.setFlag("ccOn", c); applyDev(); }));
        p.addView(note("ألوان الثيم"));
        String[] keys = {"base", "surface", "surface2", "line", "tx", "mut", "ac", "er", "b0", "b1", "b2", "b3"};
        String[] labels = {"الخلفية", "السطح (البطاقات)", "السطح القوي (الأشرطة والنوافذ)", "الحدود والخطوط", "لون النص", "النص الثانوي", "لون التمييز", "لون الخطأ",
                "بقعة الضوء 1 (زجاج)", "بقعة الضوء 2", "بقعة الضوء 3", "بقعة الضوء 4"};
        int[] cur = {th.base, th.surface, th.surface2, th.line, th.tx, th.mut, th.ac, th.er, th.blobs[0], th.blobs[1], th.blobs[2], th.blobs[3]};
        for (int i = 0; i < keys.length; i++) p.addView(colorRow(keys[i], labels[i], pend.containsKey(keys[i]) ? pend.get(keys[i]) : cur[i], false));
        p.addView(note("ألوان عناصر محددة"));
        String[] k2 = {"bubU", "bubA", "top", "nav", "glass"};
        String[] l2 = {"فقاعة رسائلي", "فقاعة ردود الذكاء الاصطناعي", "الشريط العلوي", "الشريط السفلي", "لون الزجاج (للنمط الزجاجي)"};
        for (int i = 0; i < k2.length; i++) p.addView(colorRow(k2[i], l2[i], pend.containsKey(k2[i]) ? pend.get(k2[i]) : Store.cc(k2[i], 0), true));
        p.addView(btn("تطبيق وإعادة تحميل", AC, ON, v -> {
            for (java.util.Map.Entry<String, Integer> e : pend.entrySet()) Store.setCc(e.getKey(), e.getValue());
            pend.clear();
            Store.setFlag("ccOn", true);
            applyDev();
        }));
        p.addView(btn("إعادة ضبط كل الألوان", SF2, TX, v -> confirm("حذف كل الألوان المخصصة والعودة للثيم؟", () -> { pend.clear(); Store.clearCc(); applyDev(); })));
    }

    void applyDev() {
        toSettings = true;
        reopenDev = true;
        recreate();
    }

    void applyLook() {
        toSettings = true;
        reopenLook = true;
        recreate();
    }

    void pLook(final LinearLayout p) {
        p.addView(note("اختر نمط التصميم ثم الثيم. التغيير يُطبَّق فوراً ويبقى محفوظاً."));
        p.addView(toggleCb("الزجاج السائل", style == 1, (cb, c) -> {
            if (c) Store.setStyle(1); else if (style == 1) Store.setStyle(0);
            applyLook();
        }));
        p.addView(toggleCb("Material 3", style == 2, (cb, c) -> {
            if (c) Store.setStyle(2); else if (style == 2) Store.setStyle(0);
            applyLook();
        }));
        if (style == 2 && Build.VERSION.SDK_INT >= 31)
            p.addView(toggleCb("ألوان ديناميكية من خلفية الجهاز", Store.flag("dynamic", true), (cb, c) -> {
                Store.setFlag("dynamic", c);
                applyLook();
            }));
        if (style == 1) {
            p.addView(toggle("حركة الخلفية السائلة (عند إعادة فتح التطبيق)", Store.flag("motion", true), "motion"));
            p.addView(note("في الزجاج السائل: اسحب العدسة اللامعة في الشريط السفلي بين التبويبات."));
        }
        p.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(6)));
        pTheme(p);
        pFont(p);
    }

    // ---- الثيمات ----
    void pTheme(final LinearLayout p) {
        p.addView(note("اختر الثيم. يُطبَّق فوراً على كل التطبيق ويبقى محفوظاً."));
        for (int i = 0; i < Theme.ALL.length; i++) {
            final int idx = i;
            Theme t = Theme.ALL[i];
            LinearLayout c = new LinearLayout(this);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(12), dp(12), dp(12), dp(12));
            c.setBackground(rr(i == Store.theme() ? SF2 : SF, 20, LN));
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{t.blobs[0], t.blobs[1], t.blobs[2], t.blobs[3]});
            g.setCornerRadius(dp(16));
            g.setStroke(dp(2), t.base);
            sw.setBackground(g);
            c.addView(sw, new LinearLayout.LayoutParams(dp(60), dp(60)));
            LinearLayout m = new LinearLayout(this);
            m.setOrientation(LinearLayout.VERTICAL);
            TextView n = tv((i == Store.theme() ? "✓ " : "") + t.nameAr, i == Store.theme() ? AC : TX, 16);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            m.addView(n);
            m.addView(tv(t.descAr + (t.dark ? " — داكن" : " — فاتح"), MUT, 12.5f));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
            mp.setMarginStart(dp(14));
            c.addView(m, mp);
            c.setOnClickListener(v -> {
                Store.setTheme(idx);
                toSettings = true;
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            p.addView(c, lp);
        }
    }

    void pPrompt(LinearLayout p) {
        p.addView(note("اكتب هنا تعليمات تُرسل مع كل محادثة لتحدد شخصية الذكاء الاصطناعي وأسلوبه. اتركها فارغة لاستخدام الافتراضي."));
        final String[] names = {"مساعد عام", "مبرمج", "مترجم", "مدرّس"};
        final String[] texts = {
                DEFAULT_PROMPT,
                "أنت Mi_Max Ai، مهندس برمجيات خبير. اشرح بإيجاز ثم أعطِ كوداً كاملاً قابلاً للتشغيل داخل كتل كود، ونبّه على الأخطاء الشائعة. أجب بلغة المستخدم.",
                "أنت Mi_Max Ai، مترجم محترف. ترجم النص بدقة وبأسلوب طبيعي بين العربية والإنجليزية (أو اللغة المطلوبة) دون شرح إلا إذا طُلب.",
                "أنت Mi_Max Ai، مدرّس صبور. اشرح بخطوات بسيطة مع أمثلة، ثم اطرح سؤالاً قصيراً للتأكد من الفهم. أجب بلغة المستخدم."};
        final EditText e = field(p, "البرمبت", prompt, false, false);
        e.setMinLines(8);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setHint(DEFAULT_PROMPT);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            p.addView(btn("قالب: " + names[i], SF2, TX, v -> e.setText(texts[idx])));
        }
        p.addView(btn("حفظ", AC, ON, v -> {
            prompt = e.getText().toString().trim();
            Store.setPrompt(prompt);
            toast("تم الحفظ");
        }));
        p.addView(btn("استعادة الافتراضي", SF2, TX, v -> {
            prompt = "";
            Store.setPrompt("");
            e.setText("");
            toast("تمت الاستعادة");
        }));
    }

    void pMem(final LinearLayout p) {
        p.removeAllViews();
        p.addView(note("هذه المعلومات تُرسل مع المحادثات ليعرف الذكاء الاصطناعي من أنت. يمكنك إضافتها أو حذفها."));
        final EditText e = field(p, "أضف معلومة", "", false, false);
        e.setHint("مثال: اسمي محمد وأحب البرمجة");
        p.addView(btn("إضافة", AC, ON, v -> {
            String t = e.getText().toString().trim();
            if (t.isEmpty()) return;
            mem.add(t);
            Store.saveMem(mem);
            pMem(p);
        }));
        if (mem.isEmpty()) {
            p.addView(note("الذاكرة فارغة."));
            return;
        }
        for (final String m : new ArrayList<>(mem)) {
            View r = row("•", m, "اضغط للحذف", v -> {
                mem.remove(m);
                Store.saveMem(mem);
                pMem(p);
            });
            p.addView(r);
        }
        p.addView(btn("مسح كل الذاكرة", SF, ER, v -> confirm("مسح كل الذاكرة؟", () -> {
            mem.clear();
            Store.saveMem(mem);
            pMem(p);
        })));
    }

    void pDl(LinearLayout p) {
        p.addView(box("تيك توك يعمل مباشرة. يوتيوب ومواقع أخرى تحتاج خادم تنزيل خارجياً (متوافق مع Cobalt API) لأن الهاتف وحده لا يستطيع تنزيلها."));
        EditText a = field(p, "رابط خادم التنزيل (اختياري)", Store.get("dlApi", ""), false, true);
        a.setHint("https://your-server.example/");
        a.addTextChangedListener(watcher(s -> Store.put("dlApi", s.trim())));
        EditText k = field(p, "مفتاح الخادم (إن وُجد)", Store.get("dlKey", ""), true, true);
        k.addTextChangedListener(watcher(s -> Store.put("dlKey", s.trim())));
    }

    void pDev(LinearLayout p) {
        TextView av = tv("M", ON, 34);
        av.setGravity(Gravity.CENTER);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        av.setBackground(rr(AC, 42, 0));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(84), dp(84));
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = dp(12);
        p.addView(av, ap);
        TextView n = tv("محمد السعيد علي", TX, 20);
        n.setGravity(Gravity.CENTER);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setPadding(0, dp(10), 0, dp(2));
        p.addView(n);
        TextView s = note("مطوّر Mi_Max Ai");
        s.setGravity(Gravity.CENTER);
        p.addView(s);
        p.addView(row("MAIL", "للشكاوى والاقتراحات", "mmgg3yn@gmail.com", v -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mmgg3yn@gmail.com"));
            try { startActivity(i); } catch (Exception e) { toast("لا يوجد تطبيق بريد"); }
        }));
        p.addView(row("TT", "قناة تيك توك", "@xiaomi_12o", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@xiaomi_12o"))); }
            catch (Exception e) { toast("تعذر فتح الرابط"); }
        }));
        TextView ver = note("الإصدار 3.0.0");
        ver.setGravity(Gravity.CENTER);
        ver.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - devTapT > 1500) devTaps = 0;
            devTapT = now;
            if (++devTaps >= 7 && !Store.flag("devOpts", false)) {
                Store.setFlag("devOpts", true);
                toast("خيارات المطور مفعّلة");
            }
        });
        p.addView(ver);
    }
}
