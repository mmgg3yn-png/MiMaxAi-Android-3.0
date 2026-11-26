package com.mimax.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** التخزين المحلي لإعدادات التطبيق والمحادثات والذاكرة والملف الشخصي. */
public class Store {

    public static class Msg {
        public String r;      // user | assistant
        public String t;
        public Bitmap img;    // صورة مصغرة تبقى في الجلسة فقط
        public Msg(String r, String t) { this.r = r; this.t = t; }
    }

    public static class Chat {
        public String id = newId();
        public String title = "محادثة جديدة";
        public long ts = System.currentTimeMillis();
        public ArrayList<Msg> msgs = new ArrayList<>();
    }

    static SharedPreferences sp;

    public static void init(Context c) {
        if (sp == null) {
            Context app = c.getApplicationContext();
            sp = (app != null ? app : c).getSharedPreferences("mm", Context.MODE_PRIVATE);
            migrateLegacyAccountData();
        }
    }

    /**
     * ينقل بيانات الجلسة القديمة إلى مساحة محلية واحدة قبل إزالة تسجيل الدخول.
     * لا يحذف بيانات المستخدم القديمة، ويحتفظ بنسخة محلية عند عدم وجودها.
     */
    private static void migrateLegacyAccountData() {
        String uid = sp.getString("session", "");
        if (uid == null || uid.trim().isEmpty()) return;
        SharedPreferences.Editor e = sp.edit();
        String[] keys = {"chats", "mem", "prompt", "profileName", "publicId", "deletedChats"};
        for (String key : keys) {
            String oldValue = sp.getString(key + ":" + uid, null);
            String localKey = key + ":guest";
            if (oldValue != null && sp.getString(localKey, null) == null) {
                e.putString(localKey, oldValue);
            }
        }
        e.remove("session").remove("googleName").remove("googleEmail");
        e.apply();
    }

    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String get(String k, String d) { return sp.getString(k, d); }
    public static void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    public static boolean flag(String k, boolean d) { return sp.getBoolean(k, d); }
    public static void setFlag(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }

    // ---- الإعدادات ----
    /** مفتاح المستخدم الخاص إن وُجد، وإلا المفتاح المدمج المشفّر. */
    public static String key() {
        String k = get("key", "").trim();
        return k.isEmpty() ? Vault.key() : k;
    }
    public static boolean hasCustomKey() { return !get("key", "").trim().isEmpty(); }
    public static int theme() {
        try { return Integer.parseInt(get("theme", "0")); } catch (Exception e) { return 0; }
    }
    public static void setTheme(int i) { put("theme", String.valueOf(i)); }
    /** ألوان مخصصة: تُخزَّن بصيغة سداسية AARRGGBB. */
    public static int cc(String key, int def) {
        String v = get("cc_" + key, null);
        if (v == null) return def;
        try { return (int) Long.parseLong(v, 16); } catch (Exception e) { return def; }
    }
    public static void setCc(String key, int color) { put("cc_" + key, String.format("%08X", color)); }
    public static void clearCc() {
        android.content.SharedPreferences.Editor e = sp.edit();
        for (String k : new String[]{"base", "surface", "surface2", "line", "tx", "mut", "ac", "er", "b0", "b1", "b2", "b3", "bubU", "bubA", "top", "nav", "glass"}) e.remove("cc_" + k);
        e.putBoolean("ccOn", false);
        e.apply();
    }
    /** نمط التصميم: 0 عادي، 1 زجاج سائل، 2 Material 3 */
    public static int style() {
        try { return Integer.parseInt(get("style", "0")); } catch (Exception e) { return 0; }
    }
    public static void setStyle(int i) { put("style", String.valueOf(i)); }
    public static String model() { return get("model", "").trim(); }
    public static String audioModel() { return get("audioModel", "").trim(); }
    public static String mediaModel() { return get("mediaModel", audioModel()).trim(); }
    public static double temp() {
        try { return Double.parseDouble(get("temp", "0.7")); } catch (Exception e) { return 0.7; }
    }

    // ---- الملف الشخصي المحلي ----
    public static String ns() { return "guest"; }

    public static String profileName() { return get("profileName:guest", ""); }
    public static void setProfileName(String name) {
        put("profileName:guest", name == null ? "" : name.trim());
    }

    private static String deletedChatsKey() { return "deletedChats:guest"; }
    public static void markChatDeleted(String id) {
        if (id == null || id.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(get(deletedChatsKey(), "[]"));
            for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return;
            a.put(id);
            put(deletedChatsKey(), a.toString());
        } catch (Exception ignored) { }
    }

    /** يمسح بيانات المحادثات والذاكرة والبرمبت والملف الشخصي المحلية فقط. */
    public static void clearLocalData() {
        sp.edit()
                .remove("chats:guest")
                .remove("mem:guest")
                .remove("prompt:guest")
                .remove("profileName:guest")
                .remove("deletedChats:guest")
                .apply();
    }

    // ---- المحادثات ----
    public static ArrayList<Chat> loadChats() {
        ArrayList<Chat> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(get("chats:" + ns(), "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Chat c = new Chat();
                c.id = o.getString("id");
                c.title = o.getString("title");
                c.ts = o.getLong("ts");
                JSONArray m = o.getJSONArray("msgs");
                for (int j = 0; j < m.length(); j++) {
                    JSONObject mo = m.getJSONObject(j);
                    c.msgs.add(new Msg(mo.getString("r"), mo.getString("t")));
                }
                out.add(c);
            }
        } catch (Exception e) { /* تجاهل */ }
        return out;
    }

    public static void saveChats(List<Chat> l) {
        try {
            JSONArray a = new JSONArray();
            for (Chat c : l) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("title", c.title);
                o.put("ts", c.ts);
                JSONArray m = new JSONArray();
                for (Msg x : c.msgs) {
                    JSONObject mo = new JSONObject();
                    mo.put("r", x.r);
                    mo.put("t", x.t);
                    m.put(mo);
                }
                o.put("msgs", m);
                a.put(o);
            }
            put("chats:" + ns(), a.toString());
        } catch (Exception e) { /* تجاهل */ }
    }

    // ---- الذاكرة ----
    public static ArrayList<String> loadMem() {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(get("mem:" + ns(), "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) { /* تجاهل */ }
        return out;
    }

    public static void saveMem(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        put("mem:" + ns(), a.toString());
    }

    public static String prompt() { return get("prompt:" + ns(), ""); }
    public static void setPrompt(String p) { put("prompt:" + ns(), p); }
}
