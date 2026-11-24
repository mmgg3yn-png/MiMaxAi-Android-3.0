package com.mimax.ai;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.nio.charset.Charset;

/** محرر نصوص: بحث، ترميزات، تحذير للملفات الثنائية، وحفظ عبر دالة يحددها المستدعي. */
class EditorScreen {
    interface Saver { void save(byte[] data) throws Exception; }

    static final String[] CS = {"UTF-8", "windows-1256", "ISO-8859-1", "UTF-16LE"};
    static final int MAX = 2 << 20;

    static void open(final MainActivity a, final String title, final byte[] data, final boolean editable, final Saver saver) {
        if (data.length > MAX) { a.showError("الملف كبير (" + com.mimax.ai.engine.Io.human(data.length) + ") للتحرير النصي. الحد 2MB."); return; }
        boolean bin = false;
        for (int i = 0; i < Math.min(data.length, 8000); i++) if (data[i] == 0) { bin = true; break; }
        if (bin) {
            a.confirm("الملف يبدو ثنائياً وليس نصاً عادياً. فتحه كنص قد يعرض رموزاً غريبة، وحفظه قد يفسده. المتابعة؟",
                    () -> a.openScreen(title, () -> build(a, title, data, editable, saver)));
            return;
        }
        a.openScreen(title, () -> build(a, title, data, editable, saver));
    }

    static String decode(byte[] d, String cs) {
        try { return new String(d, Charset.forName(cs)); } catch (Exception e) { return new String(d); }
    }

    static void build(final MainActivity a, final String title, final byte[] data, final boolean editable, final Saver saver) {
        a.header(title, true, false);
        a.content.removeAllViews();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        final String[] cs = {"UTF-8"};
        final EditText ed = new EditText(a);
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setTag(R.id.mm_font, Boolean.TRUE);
        ed.setTextSize(13);
        ed.setTextColor(a.TX);
        ed.setGravity(Gravity.TOP | Gravity.LEFT);
        ed.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ed.setBackground(a.rr(a.SF, 14, a.LN));
        ed.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        ed.setText(decode(data, cs[0]));
        if (!editable) { ed.setKeyListener(null); ed.setTextIsSelectable(true); }

        LinearLayout tool = new LinearLayout(a);
        tool.setGravity(Gravity.CENTER_VERTICAL);
        final EditText q = new EditText(a);
        q.setHint("بحث");
        q.setHintTextColor(a.MUT);
        q.setTextColor(a.TX);
        q.setSingleLine(true);
        q.setTextSize(13);
        q.setBackground(a.rr(a.SF, 14, a.LN));
        q.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
        tool.addView(q, new LinearLayout.LayoutParams(0, a.dp(40), 1f));
        tool.addView(a.small("التالي", v -> {
            String t = ed.getText().toString(), s = q.getText().toString();
            if (s.isEmpty()) return;
            int i = t.indexOf(s, ed.getSelectionEnd());
            if (i < 0) i = t.indexOf(s);
            if (i < 0) { a.toast("لا توجد نتائج"); return; }
            ed.requestFocus();
            ed.setSelection(i, i + s.length());
        }), new LinearLayout.LayoutParams(-2, a.dp(40)));
        tool.addView(a.small("الترميز", v -> a.gb().setTitle("ترميز النص").setItems(CS, (d0, w) -> {
            cs[0] = CS[w];
            ed.setText(decode(data, cs[0]));
            a.toast("إعادة قراءة الأصل بترميز " + cs[0] + " (تُفقد التعديلات غير المحفوظة)");
        }).show()), new LinearLayout.LayoutParams(-2, a.dp(40)));
        if (editable && saver != null) {
            tool.addView(a.small("حفظ", v -> {
                final byte[] out = ed.getText().toString().getBytes(Charset.forName(cs[0]));
                a.runBg("جارٍ الحفظ…", null, () -> saver.save(out), () -> a.toast("تم الحفظ"));
            }), new LinearLayout.LayoutParams(-2, a.dp(40)));
        }
        col.addView(tool, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, 0, 1f);
        ep.topMargin = a.dp(8);
        col.addView(ed, ep);
        a.content.addView(col, new FrameLayout.LayoutParams(-1, -1));
    }
}
