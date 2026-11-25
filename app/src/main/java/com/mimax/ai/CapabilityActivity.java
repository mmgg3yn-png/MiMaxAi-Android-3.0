package com.mimax.ai;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Device capability screen; Root is probed only after an explicit user action. */
public class CapabilityActivity extends Activity {
    private LinearLayout content;
    private TextView rootStatus;
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme theme = Theme.get(Store.theme());
        boolean dark = theme.dark;
        int bg = theme.base;
        int fg = theme.tx;
        int muted = theme.mut;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(20));
        content.setBackgroundColor(bg);
        scroll.addView(content);

        TextView title = text("Device Capability Manager", fg, 21);
        content.addView(title);
        content.addView(text("يفحص قدرات Android الفعلية ويعرض الأدوات المتوافقة مع الجهاز.", muted, 13));

        DeviceCapabilities c = DeviceCapabilities.detect();
        card("معمارية التطبيق", c.appIs64Bit ? "64-bit" : "32-bit", fg, muted);
        card("قدرات النظام", c.architectureLabel(), fg, muted);
        card("ABI المدعومة", c.abiList(), fg, muted);
        card("إصدار Android", c.release + " (API " + c.sdkInt + ")", fg, muted);
        card("ADB Debugging", c.adbEnabled(this) ? "مفعّل في إعدادات النظام" : "غير مفعّل أو غير قابل للقراءة", fg, muted);
        card("وجود ملف su", c.suBinaryPresent() ? "تم العثور على ملف su — لا يعني ذلك أن Root ممنوح" : "لم يُعثر على ملف su في المسارات الشائعة", fg, muted);

        rootStatus = text("حالة Root: لم يتم طلب فحص الصلاحية بعد.", muted, 14);
        rootStatus.setPadding(0, dp(14), 0, dp(8));
        content.addView(rootStatus);

        Button check = new Button(this);
        check.setText("فحص صلاحية Root الآن");
        check.setAllCaps(false);
        content.addView(check, new LinearLayout.LayoutParams(-1, dp(48)));
        check.setOnClickListener(v -> {
            check.setEnabled(false);
            rootStatus.setText("جارٍ طلب التحقق من مدير Root...");
            new Thread(() -> {
                String result = DeviceCapabilities.requestRootCheck();
                runOnUiThread(() -> {
                    rootStatus.setText("نتيجة الفحص: " + result);
                    check.setEnabled(true);
                });
            }).start();
        });

        TextView note = text("ملاحظة: خيارات المطور لا تمنح Root. يمكن إظهار أقسام ADB يدوياً، لكن تنفيذها يحتاج تفعيل USB/Wireless Debugging وإتمام اقتران Android عند الطلب.", muted, 12);
        note.setPadding(0, dp(14), 0, dp(8));
        content.addView(note);

        Button back = new Button(this);
        back.setText("العودة");
        back.setAllCaps(false);
        content.addView(back, new LinearLayout.LayoutParams(-1, dp(46)));
        back.setOnClickListener(v -> finish());
        setContentView(scroll);
    }

    private TextView text(String value, int color, float size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private void card(String label, String value, int fg, int muted) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(9));
        box.setBackgroundColor(Theme.mix(Theme.get(Store.theme()).surface, 0xFFFFFFFF, 0.06f));
        TextView l = text(label, muted, 12);
        TextView v = text(value, fg, 15);
        box.addView(l);
        box.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        content.addView(box, p);
    }
}
