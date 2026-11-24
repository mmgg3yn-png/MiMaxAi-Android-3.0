package com.mimax.ai;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

public class ExtensionRecoveryActivity extends Activity {
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme t = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.setBackgroundColor(t.base);
        TextView title = new TextView(this);
        title.setText("Mi_Max Extension Recovery");
        title.setTextSize(22); title.setTextColor(t.tx);
        page.addView(title);
        String id = ExtensionState.recoveryId(this);
        TextView detail = new TextView(this);
        detail.setText(id.isEmpty() ? "وضع الصيانة: يمكنك تعطيل الإضافات أو استعادة نسخة سابقة." :
                "تم تعطيل الإضافة تلقائياً بعد تعطل التطبيق:\n" + id + "\nيمكنك مراجعة سجلها أو حذفها.");
        detail.setTextColor(t.mut); detail.setPadding(0, dp(12), 0, dp(12));
        page.addView(detail);
        Button disableAll = new Button(this);
        disableAll.setText("تعطيل جميع الإضافات");
        page.addView(disableAll);
        disableAll.setOnClickListener(v -> {
            File root = ExtensionRuntime.root(this);
            File[] dirs = root.listFiles();
            if (dirs != null) for (File d : dirs) if (d.isDirectory()) ExtensionState.setEnabled(this, d.getName(), false);
            ExtensionState.clearRecovery(this);
            detail.setText("تم تعطيل جميع الإضافات. يمكنك العودة إلى التطبيق.");
        });
        Button enableAll = new Button(this);
        enableAll.setText("إعادة تفعيل جميع الإضافات");
        page.addView(enableAll);
        enableAll.setOnClickListener(v -> {
            File root = ExtensionRuntime.root(this);
            File[] dirs = root.listFiles();
            if (dirs != null) for (File d : dirs) if (d.isDirectory()) ExtensionState.setEnabled(this, d.getName(), true);
            ExtensionState.clearRecovery(this);
            detail.setText("تمت إعادة تفعيل الإضافات.");
        });
        Button logs = new Button(this);
        logs.setText("عرض سجل الإضافة المتسببة");
        page.addView(logs);
        logs.setOnClickListener(v -> {
            String log = id.isEmpty() ? "لا توجد إضافة محددة." : ExtensionState.readLog(this, id);
            new android.app.AlertDialog.Builder(this).setTitle("سجل الأعطال").setMessage(log).setPositiveButton("إغلاق", null).show();
        });
        Button back = new Button(this);
        back.setText("العودة إلى التطبيق");
        page.addView(back);
        back.setOnClickListener(v -> { ExtensionState.clearRecovery(this); finish(); });
        setContentView(page);
    }
}
