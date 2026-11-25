package com.mimax.ai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mimax.ai.engine.Io;
import com.mimax.ai.engine.Shell;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent terminal workspaces. Session metadata, history, environment, cwd and transcript
 * survive app restarts. Running child processes are recreated after Android kills the app.
 */
public class TerminalActivity extends Activity {
    private static final int REQUEST_IMPORT = 7310;
    private final List<TerminalSessionStore.Record> records = new ArrayList<>();
    private final List<Shell> shells = new ArrayList<>();
    private TerminalSessionStore store;
    private File workspace;
    private int selected = -1;
    private LinearLayout root, tabs, log;
    private ScrollView scroll;
    private EditText input;
    private TextView title;
    private volatile boolean commandRunning;

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 18, 24));
        getWindow().setNavigationBarColor(Color.rgb(14, 18, 24));
        workspace = new File(getFilesDir(), "terminal/workspace");
        File imports = new File(workspace, "imports");
        imports.mkdirs();
        store = new TerminalSessionStore(this);
        records.addAll(store.load());
        for (TerminalSessionStore.Record r : records) shells.add(restoreShell(r));
        build();
        if (records.isEmpty()) addSession();
        else select(Math.max(0, Math.min(getPreferences(0).getInt("active_session", 0), records.size() - 1)));
    }

    private Shell restoreShell(TerminalSessionStore.Record r) {
        File cwd = r.cwd == null || r.cwd.isEmpty() ? workspace : new File(r.cwd);
        if (!cwd.isDirectory()) cwd = workspace;
        Shell s = new Shell(cwd, workspace, new File(getFilesDir(), "terminal/bin"), new File(getFilesDir(), "terminal/tmp"));
        s.env.putAll(r.env);
        s.history.addAll(r.history);
        return s;
    }

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(9), dp(7), dp(9), dp(7));
        root.setBackgroundColor(Color.rgb(14, 18, 24));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("Mi_Max Ai • Terminal");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button add = button("+ جلسة");
        add.setOnClickListener(v -> addSession());
        header.addView(add);
        Button importer = button("استيراد");
        importer.setOnClickListener(v -> openImporter());
        header.addView(importer);
        Button delete = button("حذف");
        delete.setOnClickListener(v -> deleteSession());
        header.addView(delete);
        root.addView(header);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabs);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(42)));

        scroll = new ScrollView(this);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        log.setPadding(dp(4), dp(6), dp(4), dp(10));
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout commandBar = new LinearLayout(this);
        commandBar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF8995A5);
        input.setHint("اكتب الأمر...");
        input.setTextSize(14);
        input.setBackgroundColor(0xFF202936);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        commandBar.addView(input, new LinearLayout.LayoutParams(0, dp(47), 1));
        Button run = button("تنفيذ");
        run.setOnClickListener(v -> execute());
        commandBar.addView(run, new LinearLayout.LayoutParams(dp(78), dp(44)));
        input.setOnEditorActionListener((v, action, event) -> { execute(); return true; });
        root.addView(commandBar);

        Button back = button("العودة إلى Mi_Max Ai");
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(39)));
        setContentView(root);
        AppPalette.apply(this, root);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setMinWidth(dp(46));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private void addSession() {
        if (records.size() >= 12) {
            Toast.makeText(this, "الحد الأقصى 12 جلسة محفوظة", Toast.LENGTH_SHORT).show();
            return;
        }
        TerminalSessionStore.Record r = TerminalSessionStore.create("جلسة " + (records.size() + 1), workspace);
        records.add(r);
        shells.add(restoreShell(r));
        persist();
        select(records.size() - 1);
    }

    private void select(int index) {
        if (index < 0 || index >= records.size()) return;
        selected = index;
        getPreferences(0).edit().putInt("active_session", selected).apply();
        refreshTabs();
        renderTranscript();
        title.setText("Mi_Max Ai • " + records.get(selected).name);
    }

    private void refreshTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < records.size(); i++) {
            final int at = i;
            Button tab = button(records.get(i).name);
            tab.setTextColor(i == selected ? Color.WHITE : 0xFFB8C2CF);
            tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(i == selected ? 0xFF315A4A : 0xFF202936));
            tab.setOnClickListener(v -> select(at));
            tabs.addView(tab);
        }
    }

    private void renderTranscript() {
        log.removeAllViews();
        if (selected < 0 || selected >= records.size()) return;
        for (String entry : records.get(selected).transcript) appendLine(entry, entry.startsWith("$ ") ? 0xFF7CDBA5 : 0xFFE6EDF3, false);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void appendLine(String text, int color, boolean save) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(12.5f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(2), 0, dp(2));
        log.addView(t);
        while (log.getChildCount() > 500) log.removeViewAt(0);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        if (save && selected >= 0 && selected < records.size()) {
            TerminalSessionStore.Record r = records.get(selected);
            r.transcript.add(text);
            if (r.transcript.size() > 500) r.transcript.remove(0);
        }
    }

    private void execute() {
        if (selected < 0 || commandRunning) return;
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText("");
        final int sessionIndex = selected;
        final Shell shell = shells.get(sessionIndex);
        final TerminalSessionStore.Record record = records.get(sessionIndex);
        appendLine("$ " + command, 0xFF7CDBA5, true);
        persist();
        commandRunning = true;
        new Thread(() -> {
            StringBuilder output = new StringBuilder();
            Io.Cancel cancel = new Io.Cancel();
            try {
                Shell.Result result = shell.run(command, chunk -> {
                    synchronized (output) { output.append(chunk); }
                }, cancel);
                if (result.exit != 0) synchronized (output) { output.append("\n[exit ").append(result.exit).append("]"); }
            } catch (Exception e) {
                synchronized (output) { output.append("خطأ: ").append(e.getMessage()); }
            }
            final String out;
            synchronized (output) { out = output.toString(); }
            runOnUiThread(() -> {
                commandRunning = false;
                record.cwd = shell.cwd.getAbsolutePath();
                record.history.clear();
                record.history.addAll(shell.history);
                record.env.clear();
                record.env.putAll(shell.env);
                if (!out.isEmpty()) {
                    record.transcript.add(out);
                    if (sessionIndex == selected) appendLine(out, 0xFFE6EDF3, false);
                }
                persist();
                if (sessionIndex == selected) refreshTabs();
            });
        }, "MiMax-Terminal-Command").start();
    }

    private void deleteSession() {
        if (selected < 0 || selected >= records.size()) return;
        records.remove(selected);
        shells.remove(selected);
        if (records.isEmpty()) {
            selected = -1;
            addSession();
        } else select(Math.min(selected, records.size() - 1));
        persist();
    }

    private void openImporter() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            String result;
            try {
                String name = displayName(uri).replaceAll("[^A-Za-z0-9._ -]", "_");
                File dir = new File(workspace, "imports");
                dir.mkdirs();
                File target = new File(dir, System.currentTimeMillis() + "_" + name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new java.io.IOException("تعذر فتح الملف");
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                result = "تم استيراد الملف: " + target.getAbsolutePath();
            } catch (Exception e) { result = "فشل الاستيراد: " + e.getMessage(); }
            final String message = result;
            runOnUiThread(() -> {
                if (selected >= 0) {
                    records.get(selected).transcript.add(message);
                    appendLine(message, 0xFF7CDBA5, false);
                    persist();
                }
            });
        }, "MiMax-Terminal-Import").start();
    }

    private String displayName(Uri uri) {
        String name = "imported_file";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) name = c.getString(col);
            }
        } catch (Exception ignored) { }
        return name;
    }

    private void persist() {
        for (int i = 0; i < records.size(); i++) {
            Shell s = shells.get(i);
            TerminalSessionStore.Record r = records.get(i);
            r.cwd = s.cwd.getAbsolutePath();
            r.history.clear();
            r.history.addAll(s.history);
            r.env.clear();
            r.env.putAll(s.env);
        }
        store.save(records);
    }

    @Override protected void onPause() {
        persist();
        super.onPause();
    }

    @Override protected void onDestroy() {
        persist();
        super.onDestroy();
    }
}
