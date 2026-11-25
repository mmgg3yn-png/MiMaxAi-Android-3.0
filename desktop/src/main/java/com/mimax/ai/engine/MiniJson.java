package com.mimax.ai.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** محلّل JSON صغير (Java خالص) كي يعمل المحرك على JVM وعلى Android بلا اعتماد على org.json. */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) throws IOException {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.err("نص زائد بعد JSON");
        return v;
    }

    IOException err(String m) { return new IOException("JSON غير صالح: " + m + " عند " + i); }

    void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    Object value() throws IOException {
        if (i >= s.length()) throw err("نهاية مفاجئة");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("قيمة غير متوقعة");
        try { return Double.valueOf(s.substring(st, i)); } catch (NumberFormatException e) { throw err("رقم غير صالح"); }
    }

    Map<String, Object> obj() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        for (;;) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("مفتاح متوقع");
            String k = str();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("':' متوقعة");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("نهاية مفاجئة");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("',' أو '}' متوقعة");
        }
    }

    List<Object> arr() throws IOException {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        for (;;) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("نهاية مفاجئة");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("',' أو ']' متوقعة");
        }
    }

    String str() throws IOException {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("\\u ناقصة");
                        try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException x) { throw err("\\u غير صالحة"); }
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
        throw err("نص غير منتهٍ");
    }

    // ---- مساعدات ----
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : null; }
    public static String str(Object o) { return o instanceof String ? (String) o : o == null ? null : String.valueOf(o); }
    public static String str(Map<String, Object> m, String k) { return m == null ? null : str(m.get(k)); }
    public static long num(Object o, long def) { return o instanceof Double ? ((Double) o).longValue() : def; }
}
