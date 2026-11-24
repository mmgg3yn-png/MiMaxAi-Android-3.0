package com.mimax.ai.engine;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** جلسة طرفية: تشغّل الأوامر عبر sh مع مجلد عمل وبيئة محفوظتين، وتبث الإخراج، وتدعم الإلغاء وإدخال stdin. */
public final class Shell {
    public interface Out { void on(String chunk); }

    public static class Result { public int exit; public boolean cancelled, clear, exitSession; }

    public File cwd;
    public final Map<String, String> env = new LinkedHashMap<>();
    public final List<String> history = new ArrayList<>();
    public String shell = "/system/bin/sh";
    private volatile Process proc;
    private volatile OutputStream stdin;

    public Shell(File cwd, File home, File toolsBin, File tmp) {
        this.cwd = cwd;
        String path = System.getenv("PATH");
        if (path == null) path = "/system/bin:/system/xbin";
        env.put("PATH", (toolsBin != null ? toolsBin.getAbsolutePath() + ":" : "") + path);
        if (home != null) env.put("HOME", home.getAbsolutePath());
        if (tmp != null) env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("TERM", "dumb");
        env.put("LANG", "en_US.UTF-8");
    }

    static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007");

    public static String strip(String s) { return ANSI.matcher(s).replaceAll(""); }

    public boolean running() { return proc != null; }

    /** يرسل نصاً إلى stdin للأمر الجاري. */
    public void sendInput(String s) throws IOException {
        OutputStream o = stdin;
        if (o == null) throw new IOException("لا يوجد أمر قيد التشغيل");
        o.write(s.getBytes(StandardCharsets.UTF_8));
        o.flush();
    }

    /** يغلق stdin (مثل Ctrl+D). */
    public void closeInput() { try { OutputStream o = stdin; if (o != null) o.close(); } catch (IOException e) { /* تجاهل */ } }

    static String[] words(String cmd) { return cmd.trim().split("\\s+", 2); }

    public Result run(String cmdRaw, Out out, Io.Cancel c) throws IOException {
        String cmd = cmdRaw.trim();
        Result r = new Result();
        if (cmd.isEmpty()) return r;
        history.add(cmd);
        String[] w = words(cmd);
        String arg = w.length > 1 ? w[1].trim() : "";
        switch (w[0]) {
            case "cd": {
                File t;
                String a = arg.isEmpty() ? env.getOrDefault("HOME", "/") : arg;
                if (a.equals("-")) a = cwd.getParent() == null ? "/" : cwd.getParent();
                a = a.replaceFirst("^~", env.getOrDefault("HOME", "/"));
                t = a.startsWith("/") ? new File(a) : new File(cwd, a);
                try { t = t.getCanonicalFile(); } catch (IOException e) { /* يبقى كما هو */ }
                if (!t.isDirectory()) { out.on("cd: " + arg + ": لا يوجد مجلد بهذا الاسم\n"); r.exit = 1; }
                else cwd = t;
                return r;
            }
            case "pwd": out.on(cwd.getPath() + "\n"); return r;
            case "clear": r.clear = true; return r;
            case "exit": if (arg.isEmpty()) { r.exitSession = true; return r; } break;
            case "history": for (int i = 0; i < history.size(); i++) out.on((i + 1) + "  " + history.get(i) + "\n"); return r;
            case "export": {
                int eq = arg.indexOf('=');
                if (eq <= 0) { for (Map.Entry<String, String> e : env.entrySet()) out.on("export " + e.getKey() + "=" + e.getValue() + "\n"); return r; }
                String v = arg.substring(eq + 1).trim();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
                env.put(arg.substring(0, eq).trim(), v);
                return r;
            }
            case "unset": env.remove(arg); return r;
            default: break;
        }
        ProcessBuilder pb = new ProcessBuilder(shell, "-c", cmd);
        pb.directory(cwd.isDirectory() ? cwd : new File("/"));
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p;
        try { p = pb.start(); } catch (IOException e) { out.on("تعذر تشغيل الأمر: " + e.getMessage() + "\n"); r.exit = 127; return r; }
        proc = p;
        stdin = p.getOutputStream();
        final Process fp = p;
        Thread rd = new Thread(() -> {
            try (Reader in = new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8)) {
                char[] b = new char[4096];
                int n;
                while ((n = in.read(b)) > 0) out.on(strip(new String(b, 0, n)));
            } catch (IOException e) { /* انتهى الأمر */ }
        });
        rd.setDaemon(true);
        rd.start();
        try {
            for (;;) {
                if (p.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
                if (c != null && c.cancelled) {
                    p.destroy();
                    if (!p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly();
                    r.cancelled = true;
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    break;
                }
            }
            rd.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        } finally {
            proc = null;
            stdin = null;
        }
        r.exit = r.cancelled ? 130 : p.exitValue();
        return r;
    }
}
