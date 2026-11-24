package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LanguagePackActivity extends Activity {
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme theme = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(16),dp(18),dp(16));
        page.setBackgroundColor(theme.base);
        TextView title = new TextView(this);
        title.setText("لغة التطبيق / App language");
        title.setTextColor(theme.tx); title.setTextSize(22);
        page.addView(title);
        TextView info = new TextView(this);
        info.setText("اختر العربية أو English. يتطلب هذا الخيار تثبيت إضافة حزمة اللغات.");
        info.setTextColor(theme.mut); info.setPadding(0,dp(8),0,dp(12));
        page.addView(info);
        Button ar = new Button(this); ar.setText("العربية"); page.addView(ar);
        ar.setOnClickListener(v -> choose("ar"));
        Button en = new Button(this); en.setText("English"); page.addView(en);
        en.setOnClickListener(v -> choose("en"));
        Button back = new Button(this); back.setText("رجوع / Back"); page.addView(back);
        back.setOnClickListener(v -> finish());
        if (!L10n.packInstalled(this)) {
            ar.setEnabled(false); en.setEnabled(false);
            info.setText("حزمة اللغات غير مثبتة. ثبّت language_ar_en.mt1 أولاً.");
        }
        setContentView(page);
    }
    private void choose(String language) {
        Store.put("app_language", language);
        L10n.clearCache();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
