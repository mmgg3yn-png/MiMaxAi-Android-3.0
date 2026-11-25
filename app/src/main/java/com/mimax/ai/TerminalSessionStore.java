package com.mimax.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent terminal workspaces. Shell processes are recreated after app process death. */
public final class TerminalSessionStore {
    private static final String PREFS = "mimax_terminal_sessions_v1";
    private static final String KEY = "sessions";
    private final SharedPreferences prefs;

    public static final class Record {
        public String id;
        public String name;
        public String cwd;
        public final List<String> history = new ArrayList<>();
        public final List<String> transcript = new ArrayList<>();
        public final Map<String, String> env = new LinkedHashMap<>();
    }

    public TerminalSessionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Record> load() {
        List<Record> result = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                Record r = new Record();
                r.id = o.optString("id", UUID.randomUUID().toString());
                r.name = o.optString("name", "جلسة " + (i + 1));
                r.cwd = o.optString("cwd", "");
                JSONArray h = o.optJSONArray("history");
                if (h != null) for (int j = 0; j < h.length(); j++) r.history.add(h.optString(j));
                JSONArray t = o.optJSONArray("transcript");
                if (t != null) for (int j = 0; j < t.length(); j++) r.transcript.add(t.optString(j));
                JSONObject e = o.optJSONObject("env");
                if (e != null) {
                    java.util.Iterator<String> keys = e.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        r.env.put(k, e.optString(k, ""));
                    }
                }
                result.add(r);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void save(List<Record> records) {
        JSONArray all = new JSONArray();
        try {
            for (Record r : records) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name);
                o.put("cwd", r.cwd);
                JSONArray h = new JSONArray();
                for (String s : r.history) h.put(s);
                o.put("history", h);
                JSONArray t = new JSONArray();
                int start = Math.max(0, r.transcript.size() - 500);
                for (int i = start; i < r.transcript.size(); i++) t.put(r.transcript.get(i));
                o.put("transcript", t);
                JSONObject e = new JSONObject();
                for (Map.Entry<String, String> entry : r.env.entrySet()) e.put(entry.getKey(), entry.getValue());
                o.put("env", e);
                all.put(o);
            }
            prefs.edit().putString(KEY, all.toString()).commit();
        } catch (Exception ignored) { }
    }

    public static Record create(String name, File workspace) {
        Record r = new Record();
        r.id = UUID.randomUUID().toString();
        r.name = name;
        r.cwd = workspace.getAbsolutePath();
        r.transcript.add("جلسة جديدة — تم حفظ بياناتها محلياً.");
        return r;
    }
}
