package com.mimax.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** كتالوج النماذج المجانية من OpenRouter. المميزات مأخوذة من بيانات الموقع نفسها (لا تخمين). */
public final class Models {
    private Models() { }

    public static class M {
        public String id, name, desc;
        public long ctx;
        public boolean image, audio, video, file, tools, reasoning;
    }

    static boolean has(JSONArray a, String v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    /** يحتفظ بالنماذج التي سعر مدخلها ومخرجها صفر وتُنتج نصاً. */
    public static ArrayList<M> parseFree(JSONArray d) {
        ArrayList<M> out = new ArrayList<>();
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (o == null) continue;
            JSONObject pr = o.optJSONObject("pricing");
            if (pr == null) continue;
            double pp = pr.optDouble("prompt", -1), pc = pr.optDouble("completion", -1);
            if (pp != 0 || pc != 0) continue;
            JSONObject ar = o.optJSONObject("architecture");
            JSONArray in = ar != null ? ar.optJSONArray("input_modalities") : null;
            JSONArray outm = ar != null ? ar.optJSONArray("output_modalities") : null;
            if (outm != null && !has(outm, "text")) continue;
            JSONArray sp = o.optJSONArray("supported_parameters");
            M m = new M();
            m.id = o.optString("id");
            if (m.id.isEmpty()) continue;
            m.name = o.optString("name", m.id);
            String ds = o.isNull("description") ? "" : o.optString("description", "");
            ds = ds.replace('\n', ' ').trim();
            m.desc = ds.length() > 190 ? ds.substring(0, 190) + "…" : ds;
            m.ctx = o.optLong("context_length", 0);
            m.image = has(in, "image");
            m.audio = has(in, "audio");
            m.video = has(in, "video");
            m.file = has(in, "file");
            m.tools = has(sp, "tools");
            m.reasoning = has(sp, "reasoning") || has(sp, "include_reasoning");
            out.add(m);
        }
        Collections.sort(out, (a, b) -> {
            int sa = (a.tools ? 4 : 0) + (a.image ? 2 : 0) + (a.reasoning ? 1 : 0);
            int sb = (b.tools ? 4 : 0) + (b.image ? 2 : 0) + (b.reasoning ? 1 : 0);
            if (sa != sb) return sb - sa;
            return Long.compare(b.ctx, a.ctx);
        });
        return out;
    }

    public static String ctxStr(long n) {
        if (n <= 0) return "";
        if (n >= 1000000) return String.format(java.util.Locale.US, "%.1fM", n / 1000000.0);
        return (n / 1000) + "K";
    }

    /** مميزات النموذج بالعربية، مستخرجة من بيانات الموقع. */
    public static String features(M m) {
        StringBuilder s = new StringBuilder("نصوص");
        if (m.image) s.append(" • يفهم الصور");
        if (m.audio) s.append(" • يفهم الصوت");
        if (m.video) s.append(" • يفهم الفيديو");
        if (m.file) s.append(" • يقرأ الملفات");
        if (m.tools) s.append(" • يدعم الأدوات");
        if (m.reasoning) s.append(" • استدلال");
        if (m.ctx > 0) s.append(" • سياق ").append(ctxStr(m.ctx));
        return s.toString();
    }

    public static void save(List<M> l) {
        try {
            JSONArray a = new JSONArray();
            for (M m : l) {
                JSONObject o = new JSONObject();
                o.put("id", m.id); o.put("n", m.name); o.put("d", m.desc); o.put("c", m.ctx);
                int f = (m.image ? 1 : 0) | (m.audio ? 2 : 0) | (m.video ? 4 : 0) | (m.file ? 8 : 0) | (m.tools ? 16 : 0) | (m.reasoning ? 32 : 0);
                o.put("f", f);
                a.put(o);
            }
            Store.put("freeModels", a.toString());
            Store.put("freeModelsTs", String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) { /* تجاهل */ }
    }

    public static ArrayList<M> load() {
        ArrayList<M> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("freeModels", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                M m = new M();
                m.id = o.getString("id"); m.name = o.optString("n", m.id); m.desc = o.optString("d", ""); m.ctx = o.optLong("c", 0);
                int f = o.optInt("f", 0);
                m.image = (f & 1) != 0; m.audio = (f & 2) != 0; m.video = (f & 4) != 0; m.file = (f & 8) != 0;
                m.tools = (f & 16) != 0; m.reasoning = (f & 32) != 0;
                out.add(m);
            }
        } catch (Exception e) { /* تجاهل */ }
        return out;
    }

    /** أفضل نموذج مجاني متاح: يدعم الأدوات وبأكبر سياق. */
    public static M best(List<M> l) {
        M best = null;
        for (M m : l) {
            if (!m.tools) continue;
            if (best == null || m.ctx > best.ctx) best = m;
        }
        if (best == null && !l.isEmpty()) best = l.get(0);
        return best;
    }

    /** يحدد النموذج الفعلي: المختار يدوياً، وإلا الاختيار التلقائي من القائمة المجانية. */
    public static String resolve(String pref) throws Exception {
        if (pref != null && !pref.isEmpty()) return pref;
        String auto = Store.get("autoModel", "");
        if (!auto.isEmpty()) return auto;
        List<M> l = load();
        if (l.isEmpty()) {
            l = parseFree(Api.models());
            save(l);
        }
        M b = best(l);
        if (b == null) throw new Exception("تعذر العثور على نموذج مجاني. اخترته يدوياً من الإعدادات ← النماذج المجانية.");
        Store.put("autoModel", b.id);
        return b.id;
    }
}
