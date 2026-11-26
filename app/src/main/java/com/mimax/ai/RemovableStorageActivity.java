package com.mimax.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/** SAF browser for USB OTG and removable SD volumes. */
public final class RemovableStorageActivity extends Activity {
    private static final int PICK_TREE = 7411;
    private Uri treeUri;
    private String currentDocumentId;
    private final ArrayDeque<String> parents = new ArrayDeque<>();
    private LinearLayout root, list;
    private TextView location;
    private int bg, surface, text, muted, accent;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Store.init(this);
        Theme theme = Theme.get(Store.theme());
        bg = theme.base; surface = theme.surface; text = theme.tx; muted = theme.mut; accent = theme.ac;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        String saved = getSharedPreferences("mm_removable_storage", MODE_PRIVATE).getString("tree_uri", "");
        if (!saved.isEmpty()) {
            try {
                treeUri = Uri.parse(saved);
                currentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                render();
                return;
            } catch (Exception ignored) { treeUri = null; }
        }
        chooseTree();
    }

    private void chooseTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_TREE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (treeUri == null) finish();
            return;
        }
        treeUri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
            currentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            parents.clear();
            getSharedPreferences("mm_removable_storage", MODE_PRIVATE).edit().putString("tree_uri", treeUri.toString()).apply();
            render();
        } catch (Exception e) {
            toast("تعذر حفظ إذن وحدة التخزين. اختر المجلد مرة أخرى.");
            treeUri = null;
            chooseTree();
        }
    }

    private Uri documentUri(String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private String currentName() {
        try (Cursor c = getContentResolver().query(documentUri(currentDocumentId),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "وحدة التخزين";
    }

    private void render() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("رجوع");
        back.setOnClickListener(v -> navigateUp());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("USB / SD");
        title.setTextColor(text);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button choose = button("تغيير");
        choose.setOnClickListener(v -> chooseTree());
        bar.addView(choose);
        root.addView(bar);

        location = new TextView(this);
        location.setText(currentName());
        location.setTextColor(muted);
        location.setTextSize(12);
        location.setPadding(dp(8), dp(6), dp(8), dp(8));
        root.addView(location);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button folder = button("+ مجلد");
        folder.setOnClickListener(v -> createFolder());
        actions.addView(folder);
        Button refresh = button("تحديث");
        refresh.setOnClickListener(v -> render());
        actions.addView(refresh);
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button done = button("العودة إلى مدير الملفات");
        done.setOnClickListener(v -> finish());
        root.addView(done);
        setContentView(root);
        AppPalette.apply(this, root);
        loadChildren();
    }

    private void loadChildren() {
        list.removeAllViews();
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocumentId);
            String[] projection = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            };
            try (Cursor c = getContentResolver().query(childrenUri, projection, null, null,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                if (c == null) throw new Exception("لم يتمكن Android من قراءة هذه الوحدة");
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0 : c.getLong(3);
                    boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    addItem(id, name, directory, size);
                }
            }
            if (list.getChildCount() == 0) addNote("هذا المجلد فارغ.");
        } catch (Exception e) {
            addNote("تعذر قراءة المجلد. قد تكون الوحدة مفصولة أو أن إذن الوصول انتهى. أعد توصيلها واضغط تغيير.");
        }
    }

    private void addItem(String id, String name, boolean directory, long size) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(round(surface, 12));
        TextView icon = new TextView(this);
        icon.setText(directory ? "📁" : "📄");
        icon.setTextSize(20);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(38)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(text);
        nameView.setTextSize(14);
        nameView.setMaxLines(2);
        labels.addView(nameView);
        TextView sub = new TextView(this);
        sub.setText(directory ? "مجلد" : human(size));
        sub.setTextColor(muted);
        sub.setTextSize(11);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> {
            if (directory) {
                parents.push(currentDocumentId);
                currentDocumentId = id;
                render();
            } else openDocument(id, name);
        });
        row.setOnLongClickListener(v -> {
            showItemActions(id, name, directory);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        list.addView(row, lp);
    }

    private void showItemActions(String id, String name, boolean directory) {
        String[] options = directory
                ? new String[]{"فتح", "إعادة تسمية", "حذف"}
                : new String[]{"فتح", "نسخ إلى ملفات التطبيق", "إعادة تسمية", "حذف"};
        new AlertDialog.Builder(this).setTitle(name).setItems(options, (d, which) -> {
            if (which == 0) {
                if (directory) { parents.push(currentDocumentId); currentDocumentId = id; render(); }
                else openDocument(id, name);
            } else if (!directory && which == 1) copyIntoApp(id, name);
            else if (directory ? which == 1 : which == 2) renameDocument(id, name);
            else confirmDelete(id, name);
        }).setNegativeButton("إغلاق", null).show();
    }

    private void openDocument(String id, String name) {
        try {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < name.length()) ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime == null) mime = "*/*";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(documentUri(id), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { toast("لا يوجد تطبيق لفتح هذا الملف."); }
    }

    private void createFolder() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("اسم المجلد");
        new AlertDialog.Builder(this).setTitle("إنشاء مجلد").setView(input)
                .setPositiveButton("إنشاء", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    try {
                        DocumentsContract.createDocument(getContentResolver(), documentUri(currentDocumentId),
                                DocumentsContract.Document.MIME_TYPE_DIR, name);
                        render();
                    } catch (Exception e) { toast("تعذر إنشاء المجلد: " + e.getMessage()); }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void renameDocument(String id, String oldName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(oldName);
        new AlertDialog.Builder(this).setTitle("إعادة تسمية").setView(input)
                .setPositiveButton("حفظ", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    try { DocumentsContract.renameDocument(getContentResolver(), documentUri(id), name); render(); }
                    catch (Exception e) { toast("تعذرت إعادة التسمية: " + e.getMessage()); }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void confirmDelete(String id, String name) {
        new AlertDialog.Builder(this).setTitle("حذف " + name + "؟")
                .setMessage("سيتم حذف العنصر من وحدة التخزين الخارجية.")
                .setPositiveButton("حذف", (d, w) -> {
                    try { DocumentsContract.deleteDocument(getContentResolver(), documentUri(id)); render(); }
                    catch (Exception e) { toast("تعذر الحذف: " + e.getMessage()); }
                }).setNegativeButton("إلغاء", null).show();
    }

    private void copyIntoApp(String id, String name) {
        new Thread(() -> {
            String result;
            try {
                File dir = new File(getFilesDir(), "removable-imports");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد الاستيراد");
                String safe = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
                File outFile = new File(dir, System.currentTimeMillis() + "_" + safe);
                try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(documentUri(id)));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    if (in == null) throw new Exception("تعذر قراءة الملف");
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                }
                result = "تم نسخ الملف إلى: " + outFile.getAbsolutePath();
            } catch (Exception e) { result = "فشل النسخ: " + e.getMessage(); }
            final String message = result;
            runOnUiThread(() -> toast(message));
        }, "MiMax-OTG-Copy").start();
    }

    private void navigateUp() {
        if (!parents.isEmpty()) {
            currentDocumentId = parents.pop();
            render();
        } else finish();
    }

    @Override public void onBackPressed() { navigateUp(); }

    private void addNote(String message) {
        TextView note = new TextView(this);
        note.setText(message);
        note.setTextColor(muted);
        note.setPadding(dp(12), dp(18), dp(12), dp(18));
        list.addView(note);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(text);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface));
        return b;
    }

    private android.graphics.drawable.Drawable round(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
