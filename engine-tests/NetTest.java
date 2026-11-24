import com.mimax.ai.engine.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** اختبارات: التنزيل متعدد الاتصالات، الطرفية، الأدوات، Hex، البصمات، تحقق Google ID Token، عميل Cobalt. */
public class NetTest {
    interface T { String run() throws Exception; }
    static List<String[]> rows = new ArrayList<>();
    static int pass, fail;
    static File W;

    static void t(String name, String data, String expected, T body) {
        try { String a = body.run(); rows.add(new String[]{name, data, expected, a, "نجح"}); pass++; }
        catch (Throwable e) { e.printStackTrace(System.err); rows.add(new String[]{name, data, expected, String.valueOf(e), "فشل"}); fail++; }
    }
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " => " + out);
        return out;
    }
    static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
    static byte[] all(File f) throws IOException { return Files.readAllBytes(f.toPath()); }
    static <E extends Throwable> String thrown(Class<E> c, T body) throws Exception {
        try { body.run(); } catch (Throwable e) {
            if (c.isInstance(e)) return e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new AssertionError("استثناء مختلف: " + e);
        }
        throw new AssertionError("لم يُرمَ أي استثناء");
    }
    static void del(File f) { File[] l = f.listFiles(); if (l != null) for (File x : l) del(x); f.delete(); }
    static String sha(byte[] b) throws Exception { StringBuilder s = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x)); return s.toString(); }

    // ================= خادم اختبار =================
    static class Srv {
        HttpServer s; int port;
        final Map<String, byte[]> files = new HashMap<>();
        final AtomicInteger conc = new AtomicInteger(), maxConc = new AtomicInteger(), reqs = new AtomicInteger();
        final AtomicLong served = new AtomicLong();
        volatile int throttleBps = 0, failEvery = 0;
        volatile boolean noRange = false, etagRotates = false;
        volatile String etag = "\"v1\"";
        final AtomicInteger etagN = new AtomicInteger();

        Srv() throws IOException {
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(Executors.newCachedThreadPool());
            s.createContext("/f/", this::file);
            s.createContext("/redir/", ex -> {
                String n = ex.getRequestURI().getPath().substring("/redir/".length());
                int k = Integer.parseInt(n);
                ex.getResponseHeaders().add("Location", k > 1 ? "/redir/" + (k - 1) : "http://127.0.0.1:" + port + "/f/big.bin");
                ex.sendResponseHeaders(302, -1); ex.close();
            });
            s.start();
            port = s.getAddress().getPort();
        }
        String url(String p) { return "http://127.0.0.1:" + port + p; }
        void stop() { s.stop(0); }

        void file(HttpExchange ex) throws IOException {
            int c = conc.incrementAndGet();
            maxConc.accumulateAndGet(c, Math::max);
            int rq = reqs.incrementAndGet();
            try {
                String name = java.net.URLDecoder.decode(ex.getRequestURI().getRawPath().substring(3), "UTF-8");
                if (name.equals("huge")) {
                    ex.getResponseHeaders().add("Content-Range", "bytes 0-0/54975581388800");
                    ex.sendResponseHeaders(206, 1); ex.getResponseBody().write(0); ex.close(); return;
                }
                byte[] d = files.get(name);
                if (d == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                String tag = etagRotates ? "\"r" + etagN.incrementAndGet() + "\"" : etag;
                ex.getResponseHeaders().add("ETag", tag);
                ex.getResponseHeaders().add("Accept-Ranges", "bytes");
                if (name.equals("cd.bin")) ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report final.bin\"");
                String rg = ex.getRequestHeaders().getFirst("Range");
                long from = 0, to = d.length - 1;
                int status = 200;
                if (rg != null && !noRange) {
                    if (d.length == 0) { ex.getResponseHeaders().add("Content-Range", "bytes */0"); ex.sendResponseHeaders(416, -1); ex.close(); return; }
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(rg);
                    if (m.matches()) {
                        from = Long.parseLong(m.group(1));
                        if (!m.group(2).isEmpty()) to = Math.min(d.length - 1, Long.parseLong(m.group(2)));
                        status = 206;
                        ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + d.length);
                    }
                }
                long len = status == 206 ? to - from + 1 : d.length;
                ex.sendResponseHeaders(status, len == 0 ? -1 : len);
                if (len == 0) { ex.close(); return; }
                boolean die = failEvery > 0 && rq % failEvery == 0 && len > 120_000 && status == 206;
                try (OutputStream o = ex.getResponseBody()) {
                    long sent = 0;
                    long t0 = System.nanoTime();
                    while (sent < len) {
                        int n = (int) Math.min(16384, len - sent);
                        o.write(d, (int) (from + sent), n);
                        sent += n;
                        served.addAndGet(n);
                        if (die && sent >= 50_000) { ex.close(); return; }
                        if (throttleBps > 0) {
                            long want = sent * 1_000_000_000L / throttleBps, have = System.nanoTime() - t0;
                            if (want > have) try { Thread.sleep((want - have) / 1_000_000); } catch (InterruptedException ie) { return; }
                        }
                    }
                }
            } catch (IOException e) { /* العميل أغلق الاتصال */ }
            finally { conc.decrementAndGet(); try { ex.close(); } catch (Exception e) { } }
        }
    }

    static Fetch.Opts opts(int conns) { Fetch.Opts o = new Fetch.Opts(); o.connections = conns; o.readTimeout = 8000; o.retries = 8; return o; }

    // ================= JWT مساعدات =================
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String jwt(KeyPair kp, String kid, String alg, String payload) throws Exception {
        String h = b64(("{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}").getBytes());
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(kp.getPrivate()); s.update((h + "." + p).getBytes());
        return h + "." + p + "." + b64(s.sign());
    }
    static String jwks(KeyPair kp, String kid) {
        RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + kid + "\",\"n\":\"" + b64(unsigned(pk.getModulus())) + "\",\"e\":\"" + b64(unsigned(pk.getPublicExponent())) + "\"}]}";
    }
    static byte[] unsigned(BigInteger v) { byte[] b = v.toByteArray(); return b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b; }

    public static void main(String[] a) throws Exception {
        W = new File(a[0]); del(W); W.mkdirs();
        Srv srv = new Srv();
        byte[] big = rnd(24 << 20, 1);
        srv.files.put("big.bin", big); srv.files.put("small.bin", rnd(300_000, 2)); srv.files.put("one.bin", rnd(1 << 20, 3));
        srv.files.put("empty.bin", new byte[0]); srv.files.put("cd.bin", rnd(2_000_000, 4)); srv.files.put("عربي.txt", rnd(1_500_000, 5));
        srv.files.put("mid.bin", rnd(6 << 20, 6)); srv.files.put("res.bin", rnd(16 << 20, 7)); srv.files.put("pkg.tar.gz", new byte[0]);

        // ============ Fetch ============
        t("Fetch: 24MB بـ16 اتصالاً — سلامة SHA-256 وتوازي حقيقي", "ملف عشوائي 24MB من خادم محلي يدعم Range", "المحتوى مطابق، 8–16 اتصالاً متزامناً، ولا بقايا .part/.mmdl", () -> {
            srv.maxConc.set(0); srv.conc.set(0); File d = new File(W, "big.out");
            long[] last = {0, 0};
            Fetch.download(srv.url("/f/big.bin"), d, opts(16), (x, y) -> { last[0] = x; last[1] = y; }, null);
            check(sha(all(d)).equals(sha(big)), "المحتوى مختلف");
            check(last[0] == big.length && last[1] == big.length, "التقدم النهائي " + last[0] + "/" + last[1]);
            check(srv.maxConc.get() >= 8 && srv.maxConc.get() <= 17, "التزامن " + srv.maxConc.get());
            check(!new File(d.getPath() + ".part").exists() && !new File(d.getPath() + ".mmdl").exists(), "بقايا");
            return "تزامن أقصى = " + srv.maxConc.get() + "، " + srv.reqs.get() + " طلباً";
        });
        t("Fetch: ملف صغير (تيار واحد) وحد 1MB بالضبط", "300KB و1,048,576 بايت", "كلاهما مطابق", () -> {
            File d1 = new File(W, "small.out"), d2 = new File(W, "one.out");
            Fetch.download(srv.url("/f/small.bin"), d1, opts(16), null, null);
            Fetch.download(srv.url("/f/one.bin"), d2, opts(16), null, null);
            check(Arrays.equals(all(d1), srv.files.get("small.bin")) && Arrays.equals(all(d2), srv.files.get("one.bin")), "المحتوى");
            return "مطابقان";
        });
        t("Fetch: التسريع الفعلي مقابل اتصال واحد", "ملف 6MB، كل اتصال محدود بـ1MB/ث", "16 اتصالاً أسرع بـ3.5× على الأقل", () -> {
            srv.throttleBps = 1 << 20;
            try {
                long t0 = System.nanoTime(); Fetch.download(srv.url("/f/mid.bin"), new File(W, "mid1.out"), opts(1), null, null); long one = System.nanoTime() - t0;
                t0 = System.nanoTime(); Fetch.download(srv.url("/f/mid.bin"), new File(W, "mid16.out"), opts(16), null, null); long sixteen = System.nanoTime() - t0;
                check(Arrays.equals(all(new File(W, "mid16.out")), srv.files.get("mid.bin")), "المحتوى");
                double ratio = (double) one / sixteen;
                check(ratio >= 3.5, "النسبة " + ratio);
                return String.format("اتصال واحد %.1fث، 16 اتصالاً %.1fث، التسريع %.1f×", one / 1e9, sixteen / 1e9, ratio);
            } finally { srv.throttleBps = 0; }
        });
        t("Fetch: الاستئناف بعد الإيقاف", "إلغاء عند ~35% ثم إعادة التشغيل", "يكتمل بنفس المحتوى دون إعادة تنزيل كل شيء", () -> {
            srv.throttleBps = 3 << 20; srv.served.set(0);
            try {
                File d = new File(W, "res.out"); Io.Cancel c = new Io.Cancel();
                thrown(Io.CancelledException.class, () -> { Fetch.download(srv.url("/f/res.bin"), d, opts(16), (x, y) -> { if (x * 100 / y >= 35) c.cancelled = true; }, c); return ""; });
                check(new File(d.getPath() + ".part").exists() && new File(d.getPath() + ".mmdl").exists(), "ملفات الاستئناف غير موجودة");
                long s1 = srv.served.get(); srv.served.set(0);
                long[] first = {-1};
                Fetch.download(srv.url("/f/res.bin"), d, opts(16), (x, y) -> { if (first[0] < 0) first[0] = x; }, null);
                long s2 = srv.served.get();
                check(Arrays.equals(all(d), srv.files.get("res.bin")), "المحتوى");
                check(first[0] > 0, "لم يبدأ الاستئناف من تقدم سابق: " + first[0]);
                check(s1 + s2 < srv.files.get("res.bin").length * 1.6, "أُعيد تنزيل الكثير: " + (s1 + s2));
                return "الجولة 1 أرسلت " + s1 / 1024 + "KB، الجولة 2 " + s2 / 1024 + "KB من " + srv.files.get("res.bin").length / 1024 + "KB؛ بدأ الاستئناف من " + first[0] / 1024 + "KB";
            } finally { srv.throttleBps = 0; }
        });
        t("Fetch: إعادة محاولة الأجزاء عند انقطاع الاتصال", "الخادم يقطع كل ثالث طلب بعد 50KB", "المحتوى مطابق رغم الانقطاعات", () -> {
            srv.failEvery = 3; srv.reqs.set(0);
            try {
                File d = new File(W, "fail.out");
                Fetch.download(srv.url("/f/big.bin"), d, opts(8), null, null);
                check(Arrays.equals(all(d), big), "المحتوى");
                return "اكتمل بعد " + srv.reqs.get() + " طلباً (مع قطع متعمد)";
            } finally { srv.failEvery = 0; }
        });
        t("Fetch: تحويلات متسلسلة (نسبي ومطلق)", "/redir/3 → /redir/2 → /redir/1 → /f/big.bin", "ينتهي إلى الملف الصحيح", () -> {
            File d = new File(W, "redir.out"); Fetch.download(srv.url("/redir/3"), d, opts(16), null, null);
            check(Arrays.equals(all(d), big), "المحتوى");
            return "تمّ عبر 3 تحويلات";
        });
        t("Fetch: خادم يتجاهل Range → تيار واحد", "noRange=true، ملف 6MB", "تنزيل صحيح", () -> {
            srv.noRange = true;
            try { File d = new File(W, "norange.out"); Fetch.download(srv.url("/f/mid.bin"), d, opts(16), null, null); check(Arrays.equals(all(d), srv.files.get("mid.bin")), "المحتوى"); return "مطابق"; }
            finally { srv.noRange = false; }
        });
        t("Fetch: 404 وملف فارغ ونقص المساحة", "ملف غير موجود، empty.bin، وخادم يعلن 50TB", "رسالة 404، ملف فارغ، «مساحة التخزين غير كافية» بلا بقايا", () -> {
            File d1 = new File(W, "nf.out");
            String m1 = thrown(IOException.class, () -> { Fetch.download(srv.url("/f/nope.bin"), d1, opts(4), null, null); return ""; });
            check(m1.contains("404") && !d1.exists() && !new File(d1.getPath() + ".part").exists(), m1);
            File d2 = new File(W, "empty.out"); Fetch.download(srv.url("/f/empty.bin"), d2, opts(4), null, null);
            check(d2.exists() && d2.length() == 0, "الفارغ");
            File d3 = new File(W, "huge.out");
            String m3 = thrown(IOException.class, () -> { Fetch.download(srv.url("/f/huge"), d3, opts(16), null, null); return ""; });
            check(m3.contains("مساحة التخزين غير كافية") && !new File(d3.getPath() + ".part").exists(), m3);
            return m1 + " | فارغ سليم | " + m3;
        });
        t("Fetch: اسم الملف من Content-Disposition ومن مسار عربي", "cd.bin و/f/عربي.txt", "«report final.bin» و«عربي.txt»", () -> {
            Fetch.Info i1 = Fetch.probe(srv.url("/f/cd.bin"), opts(4));
            Fetch.Info i2 = Fetch.probe(srv.url("/f/" + java.net.URLEncoder.encode("عربي.txt", "UTF-8").replace("+", "%20")), opts(4));
            check(i1.name.equals("report final.bin") && i1.total == 2_000_000 && i1.ranges, i1.name);
            check(i2.name.equals("عربي.txt"), i2.name);
            File d = new File(W, "ar.out"); Fetch.download(srv.url("/f/" + java.net.URLEncoder.encode("عربي.txt", "UTF-8").replace("+", "%20")), d, opts(16), null, null);
            check(Arrays.equals(all(d), srv.files.get("عربي.txt")), "المحتوى");
            return i1.name + " | " + i2.name;
        });
        t("Fetch: تغيّر الملف على الخادم بين المحاولات / أثناء التنزيل", "تبديل المحتوى وETag؛ ثم ETag يتغير مع كل طلب", "إعادة تنزيل نظيفة بالمحتوى الجديد؛ وخطأ «تغيّر» مع تنظيف", () -> {
            File d = new File(W, "chg.out"); Io.Cancel c = new Io.Cancel(); srv.throttleBps = 3 << 20;
            try {
                thrown(Io.CancelledException.class, () -> { Fetch.download(srv.url("/f/res.bin"), d, opts(16), (x, y) -> { if (x * 100 / y >= 30) c.cancelled = true; }, c); return ""; });
                byte[] nu = rnd(16 << 20, 99); srv.files.put("res.bin", nu); srv.etag = "\"v2\"";
                srv.throttleBps = 0;
                Fetch.download(srv.url("/f/res.bin"), d, opts(16), null, null);
                check(Arrays.equals(all(d), nu), "لم تُعد التنزيل بالمحتوى الجديد");
                srv.etagRotates = true;
                File d2 = new File(W, "chg2.out");
                String m = thrown(IOException.class, () -> { Fetch.download(srv.url("/f/res.bin"), d2, opts(16), null, null); return ""; });
                check(m.contains("تغيّر") && !new File(d2.getPath() + ".part").exists(), m);
                return "إعادة نظيفة ✓ | " + m;
            } finally { srv.etagRotates = false; srv.throttleBps = 0; }
        });
        t("Fetch: discard بعد الإلغاء", "إلغاء ثم discard", "تُحذف .part و.mmdl", () -> {
            File d = new File(W, "dis.out"); Io.Cancel c = new Io.Cancel(); srv.throttleBps = 3 << 20;
            try {
                srv.files.put("res.bin", rnd(16 << 20, 7));
                thrown(Io.CancelledException.class, () -> { Fetch.download(srv.url("/f/res.bin"), d, opts(16), (x, y) -> { if (x * 100 / y >= 20) c.cancelled = true; }, c); return ""; });
                Fetch.discard(d);
                check(!new File(d.getPath() + ".part").exists() && !new File(d.getPath() + ".mmdl").exists(), "بقايا");
                return "نُظّفت";
            } finally { srv.throttleBps = 0; }
        });

        // ============ Shell ============
        File home = new File(W, "home"); home.mkdirs(); File tb = new File(W, "tools/bin"); tb.mkdirs();
        t("Shell: أوامر أساسية (stdout/stderr/exit/UTF-8/ANSI)", "echo، exit 3، stderr، عربي، printf بألوان", "المخرجات والرموز صحيحة", () -> {
            Shell s = new Shell(home, home, tb, W); s.shell = "/bin/sh";
            StringBuilder o = new StringBuilder();
            Shell.Result r = s.run("echo hello", o::append, null); check(r.exit == 0 && o.toString().equals("hello\n"), o + "");
            check(s.run("exit 3", o::append, null).exit == 3, "exit 3");
            o.setLength(0); s.run("echo err 1>&2", o::append, null); check(o.toString().equals("err\n"), "stderr: " + o);
            o.setLength(0); s.run("echo مرحبا", o::append, null); check(o.toString().equals("مرحبا\n"), "عربي: " + o);
            o.setLength(0); s.run("printf '\\033[31mred\\033[0m'", o::append, null); check(o.toString().equals("red"), "ANSI: " + o);
            o.setLength(0); Shell.Result r2 = s.run("no_such_cmd_xyz", o::append, null); check(r2.exit == 127, "غير موجود: " + r2.exit);
            return "كلها صحيحة";
        });
        t("Shell: cd وpwd وexport والتاريخ", "cd إلى مجلد فيه ملف ثم ls وexport FOO", "الحالة تُحفظ بين الأوامر", () -> {
            File d = new File(W, "wd"); d.mkdirs(); Files.write(new File(d, "x.txt").toPath(), "x".getBytes());
            Shell s = new Shell(home, home, tb, W); s.shell = "/bin/sh"; StringBuilder o = new StringBuilder();
            s.run("cd " + d.getPath(), o::append, null); s.run("pwd", o::append, null); check(o.toString().trim().equals(d.getCanonicalPath()), "pwd: " + o);
            o.setLength(0); s.run("ls", o::append, null); check(o.toString().contains("x.txt"), "ls: " + o);
            o.setLength(0); s.run("export FOO=\"a b\"", o::append, null); s.run("echo $FOO", o::append, null); check(o.toString().equals("a b\n"), "FOO: " + o);
            o.setLength(0); Shell.Result r = s.run("cd /no/such/dir", o::append, null); check(r.exit == 1 && o.toString().contains("cd:"), "cd خاطئ");
            check(s.history.size() == 6, "التاريخ " + s.history.size());
            return "pwd/ls/export/cd صحيحة";
        });
        t("Shell: إلغاء أمر طويل وإدخال stdin", "sleep 30 ثم إلغاء؛ cat مع sendInput", "الإلغاء < 3ث؛ stdin يصل", () -> {
            Shell s = new Shell(home, home, tb, W); s.shell = "/bin/sh"; StringBuilder o = new StringBuilder(); Io.Cancel c = new Io.Cancel();
            new Thread(() -> { try { Thread.sleep(500); } catch (Exception e) { } c.cancelled = true; }).start();
            long t0 = System.currentTimeMillis(); Shell.Result r = s.run("sleep 30", o::append, c);
            long dt = System.currentTimeMillis() - t0;
            check(r.cancelled && dt < 3500, "الإلغاء " + dt + "ms");
            StringBuilder o2 = new StringBuilder();
            new Thread(() -> { try { Thread.sleep(400); s.sendInput("abc\n"); Thread.sleep(200); s.closeInput(); } catch (Exception e) { } }).start();
            Shell.Result r2 = s.run("cat", o2::append, null);
            check(o2.toString().equals("abc\n") && r2.exit == 0, "stdin: " + o2);
            return "الإلغاء في " + dt + "ms، وstdin يعمل";
        });
        t("Shell: إخراج كبير (2MB) متدفق", "head -c 2097152 /dev/zero | tr", "كل البايتات تصل", () -> {
            Shell s = new Shell(home, home, tb, W); s.shell = "/bin/sh"; long[] n = {0};
            s.run("head -c 2097152 /dev/zero | tr '\\0' 'a'", ch -> n[0] += ch.length(), null);
            check(n[0] == 2097152, "وصل " + n[0]);
            return n[0] + " حرفاً";
        });

        // ============ Tools ============
        Tools tools = new Tools(new File(W, "tools"));
        t("Tools: تثبيت سكربت وتشغيله عبر PATH", "سكربت hello.sh", "يظهر في bin ويعمل من الطرفية", () -> {
            File f = new File(W, "hello.sh"); Files.write(f.toPath(), "#!/bin/sh\necho tool-says $1\n".getBytes());
            String sum = tools.installFile(f, "hello", "ملف", new String[]{"arm64-v8a"}, null);
            Shell s = new Shell(home, home, tools.bin(), W); s.shell = "/bin/sh"; StringBuilder o = new StringBuilder();
            s.run("hello world", o::append, null);
            check(o.toString().equals("tool-says world\n"), "المخرجات: " + o);
            check(tools.list().size() == 1 && tools.list().get(0).name.equals("hello"), "السجل");
            return sum;
        });
        t("Tools: حزم tar.gz وzip (روابط للأوامر في bin/)", "حزمتان بسكربتات تنفيذية وملف بيانات", "تُضاف الأوامر وتعمل، وبت التنفيذ محفوظ", () -> {
            File src = new File(W, "pk/mypkg"); new File(src, "bin").mkdirs(); new File(src, "share").mkdirs();
            Files.write(new File(src, "bin/pk1").toPath(), "#!/bin/sh\necho pk1-ok\n".getBytes()); new File(src, "bin/pk1").setExecutable(true);
            Files.write(new File(src, "bin/pk2").toPath(), "#!/bin/sh\ncat $(dirname $0)/../share/data.txt\n".getBytes()); new File(src, "bin/pk2").setExecutable(true);
            Files.write(new File(src, "share/data.txt").toPath(), "DATA\n".getBytes());
            File tgz = new File(W, "pk.tar.gz"); sh("/usr/bin/tar", "-C", new File(W, "pk").getPath(), "-czf", tgz.getPath(), "mypkg");
            String s1 = tools.installFile(tgz, "mypkg", null, null, null);
            File zip = new File(W, "pk2.zip"); ProcessBuilder pb = new ProcessBuilder("/usr/bin/zip", "-qr", zip.getPath(), "."); pb.directory(src); pb.start().waitFor();
            String s2 = tools.installFile(zip, "zpkg", null, null, null);
            Shell s = new Shell(home, home, tools.bin(), W); s.shell = "/bin/sh"; StringBuilder o = new StringBuilder();
            s.run("pk1", o::append, null); s.run("pk2", o::append, null);
            check(o.toString().equals("pk1-ok\nDATA\n"), "المخرجات: " + o);
            return s1 + " | " + s2;
        });
        t("Tools: فحص معمارية ELF، حذف أداة، ملف غير مدعوم", "ELF لـarm64 على جهاز armeabi-v7a وبالعكس", "رفض برسالة واضحة / قبول / حذف نظيف", () -> {
            byte[] elf = new byte[64]; elf[0] = 0x7F; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F'; elf[18] = (byte) 0xB7;
            File f = new File(W, "fake.elf"); Files.write(f.toPath(), elf);
            String m = thrown(IOException.class, () -> { tools.installFile(f, "fk", null, new String[]{"armeabi-v7a"}, null); return ""; });
            check(m.contains("arm64-v8a"), m);
            tools.installFile(f, "fk", null, new String[]{"arm64-v8a", "armeabi-v7a"}, null);
            check(new File(tools.bin(), "fk").canExecute(), "بت التنفيذ");
            tools.remove("fk"); check(!new File(tools.bin(), "fk").exists(), "الحذف");
            File junk = new File(W, "junk.dat"); Files.write(junk.toPath(), rnd(1000, 3));
            String m2 = thrown(IOException.class, () -> { tools.installFile(junk, "j", null, null, null); return ""; });
            return m + " | " + m2;
        });
        t("Tools: تثبيت من رابط + رفض tar يحاول الخروج من المجلد", "tar.gz من الخادم المحلي؛ وtar فيه ../evil", "يُثبَّت؛ والآخر يُرفض بلا كتابة خارجية", () -> {
            File pk = new File(W, "pk.tar.gz"); srv.files.put("pkg.tar.gz", all(pk));
            String s = tools.installUrl(srv.url("/f/pkg.tar.gz"), "viaurl", null, opts(4), null, null);
            check(tools.list().stream().anyMatch(i -> i.name.equals("viaurl")), "السجل");
            // tar خبيث
            File evil = new File(W, "evil.tar");
            sh("python3", "-c", "import tarfile,io\nt=tarfile.open('" + evil.getPath() + "','w')\ni=tarfile.TarInfo('../evil_tool.txt');d=b'x';i.size=1;t.addfile(i,io.BytesIO(d));t.close()");
            String m = thrown(IOException.class, () -> { tools.installFile(evil, "evil", null, null, null); return ""; });
            check(!new File(W, "evil_tool.txt").exists() && !new File(tools.root, "evil_tool.txt").exists(), "كُتب ملف خارجي!");
            return s + " | " + m;
        });

        // ============ HexFile / Hashes ============
        t("Hex: قراءة صفحات وبحث عبر حدود الكتل و>2GB", "ملف 2.2GB ممتد بنمط في منتصفه ونهايته", "الإزاحات صحيحة (long)", () -> {
            File f = new File(W, "hex.bin"); long size = 2200L << 20;
            byte[] pat = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x01, 0x02};
            long at1 = (1 << 20) - 3, at2 = size - 100;
            try (RandomAccessFile r = new RandomAccessFile(f, "rw")) { r.setLength(size); r.seek(at1); r.write(pat); r.seek(at2); r.write(pat); r.seek(0); r.write("HEADER".getBytes()); }
            try (HexFile h = new HexFile(f)) {
                check(new String(h.read(0, 6)).equals("HEADER") && h.size == size, "القراءة");
                check(h.find(pat, 0, null, null) == at1, "البحث عبر حد الكتلة: " + h.find(pat, 0, null, null));
                check(h.find(pat, at1 + 1, null, null) == at2, "البحث البعيد");
                check(h.find(pat, at2 + 1, null, null) == -1, "لا نتيجة بعد الأخيرة");
                check(h.read(size - 3, 100).length == 3, "قراءة الذيل");
            }
            f.delete();
            return "الإزاحات " + at1 + " و" + at2 + " صحيحة";
        });
        t("Hex: تحليل السداسي + نسخة معدّلة + تعديل مباشر مع .bak + حدود", "تعديل 3 بايتات", "نسخة جديدة بالتعديل فقط والأصل سليم، و.bak مطابق للأصل", () -> {
            check(Arrays.equals(HexFile.parseHex("DE ad,BE:ef-0x01"), new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 1}), "parseHex");
            thrown(IOException.class, () -> HexFile.parseHex("ABC").toString());
            File f = new File(W, "patch.bin"); byte[] orig = rnd(3 << 20, 8); Files.write(f.toPath(), orig);
            TreeMap<Long, Byte> ch = new TreeMap<>(); ch.put(5L, (byte) 0x11); ch.put((1L << 20) + 7, (byte) 0x22); ch.put(orig.length - 1L, (byte) 0x33);
            File out = new File(W, "patch_new.bin"); HexFile.saveCopy(f, out, ch, null, null);
            byte[] nb = all(out); int diff = 0; for (int i = 0; i < orig.length; i++) if (orig[i] != nb[i]) diff++;
            check(nb[5] == 0x11 && nb[(1 << 20) + 7] == 0x22 && nb[nb.length - 1] == 0x33 && diff <= 3, "diff=" + diff);
            check(Arrays.equals(all(f), orig), "الأصل تغيّر");
            HexFile.applyInPlace(f, ch, true);
            check(all(f)[5] == 0x11 && Arrays.equals(all(new File(f.getPath() + ".bak")), orig), "المباشر/.bak");
            TreeMap<Long, Byte> bad = new TreeMap<>(); bad.put(orig.length + 5L, (byte) 1);
            String m = thrown(IOException.class, () -> { HexFile.saveCopy(f, new File(W, "x.bin"), bad, null, null); return ""; });
            thrown(IOException.class, () -> { HexFile.saveCopy(f, f, ch, null, null); return ""; });
            return "نسخة/مباشر/.bak صحيحة | " + m;
        });
        t("Hashes: MD5/SHA-1/SHA-256/SHA-512 مقابل أدوات النظام", "ملف 3MB", "مطابقة الخوارزميات الأربع", () -> {
            File f = new File(W, "h.bin"); Files.write(f.toPath(), rnd(3 << 20, 9));
            String[] mine = Hashes.all(f, null, null); String[] tool = {"md5sum", "sha1sum", "sha256sum", "sha512sum"};
            for (int i = 0; i < 4; i++) check(mine[i].equals(sh("/usr/bin/" + tool[i], f.getPath()).split(" ")[0]), Hashes.ALGOS[i]);
            return "الأربع مطابقة";
        });

        // ============ MiniJson / Google ID Token ============
        t("MiniJson: تحليل صحيح وأخطاء", "كائنات متداخلة وUnicode وإدخالات تالفة", "يقبل الصحيح ويرفض التالف", () -> {
            Map<String, Object> m = MiniJson.map(MiniJson.parse("{\"a\":[1,2.5,{\"b\":\"\\u0645\\n\"}],\"t\":true,\"n\":null}"));
            check(MiniJson.list(m.get("a")).size() == 3 && Boolean.TRUE.equals(m.get("t")) && m.get("n") == null, "البنية");
            check(MiniJson.str(MiniJson.map(MiniJson.list(m.get("a")).get(2)), "b").equals("م\n"), "Unicode");
            for (String bad : new String[]{"{", "{\"a\":}", "[1,2", "{\"a\" 1}", "\"abc", "{} x"}) thrown(IOException.class, () -> { MiniJson.parse(bad); return ""; });
            return "6 حالات تالفة رُفضت";
        });
        KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA"); kg.initialize(2048);
        KeyPair kp = kg.generateKeyPair(), other = kg.generateKeyPair();
        String J = jwks(kp, "k1"), AUD = "123-abc.apps.googleusercontent.com";
        long now = 1_800_000_000L;
        String good = "{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + AUD + "\",\"sub\":\"1122\",\"email\":\"u@x.com\",\"email_verified\":true,\"name\":\"مستخدم\",\"picture\":\"http://p\",\"exp\":" + (now + 3600) + ",\"iat\":" + now + ",\"nonce\":\"n1\"}";
        t("GoogleIdToken: رمز صالح", "RS256 موقّع بمفتاح اختبار، JWKS مطابق", "تُرجَع المطالبات الصحيحة", () -> {
            GoogleIdToken.Claims c = GoogleIdToken.verify(jwt(kp, "k1", "RS256", good), AUD, J, now, "n1");
            check(c.sub.equals("1122") && c.email.equals("u@x.com") && c.name.equals("مستخدم") && c.emailVerified, "المطالبات");
            return c.email + " / " + c.name;
        });
        t("GoogleIdToken: رفض الرموز غير الصالحة", "منتهٍ، aud خاطئ، iss خاطئ، توقيع مزوّر، kid مجهول، HS256/none، nonce خاطئ، iat مستقبلي", "كلها مرفوضة برسائل واضحة", () -> {
            List<String> msgs = new ArrayList<>();
            String[][] cases = {
                    {"منتهٍ", jwt(kp, "k1", "RS256", good.replace("\"exp\":" + (now + 3600), "\"exp\":" + (now - 7200))), AUD, "n1", "انتهت"},
                    {"aud", jwt(kp, "k1", "RS256", good), "other-client", "n1", "تطبيق آخر"},
                    {"iss", jwt(kp, "k1", "RS256", good.replace("https://accounts.google.com", "https://evil.com")), AUD, "n1", "مُصدِر"},
                    {"توقيع بمفتاح آخر", jwt(other, "k1", "RS256", good), AUD, "n1", "توقيع الرمز غير صحيح"},
                    {"kid", jwt(kp, "zzz", "RS256", good), AUD, "n1", "kid"},
                    {"HS256", jwt(kp, "k1", "HS256", good), AUD, "n1", "خوارزمية"},
                    {"none", jwt(kp, "k1", "none", good), AUD, "n1", "خوارزمية"},
                    {"nonce", jwt(kp, "k1", "RS256", good), AUD, "WRONG", "nonce"},
                    {"iat", jwt(kp, "k1", "RS256", good.replace("\"iat\":" + now, "\"iat\":" + (now + 99999))), AUD, "n1", "المستقبل"},
                    {"ليس JWT", "abc.def", AUD, "n1", "JWT"}};
            for (String[] c : cases) { String m = thrown(IOException.class, () -> { GoogleIdToken.verify(c[1], c[2], J, now, c[3]); return ""; }); check(m.contains(c[4]), c[0] + ": " + m); msgs.add(c[0]); }
            String tam = jwt(kp, "k1", "RS256", good); String[] p = tam.split("\\.");
            String forged = p[0] + "." + b64(good.replace("u@x.com", "admin@x.com").getBytes(StandardCharsets.UTF_8)) + "." + p[2];
            String m2 = thrown(IOException.class, () -> { GoogleIdToken.verify(forged, AUD, J, now, "n1"); return ""; }); check(m2.contains("توقيع"), m2);
            String arr = good.replace("\"aud\":\"" + AUD + "\"", "\"aud\":[\"x\",\"" + AUD + "\"]");
            GoogleIdToken.verify(jwt(kp, "k1", "RS256", arr), AUD, J, now, null);
            return msgs.size() + " حالات رُفضت + تزوير الحمولة رُفض + aud كمصفوفة مقبول";
        });

        // ============ Cobalt ============
        HttpServer cs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); cs.setExecutor(Executors.newCachedThreadPool());
        final String info = "{\"cobalt\":{\"version\":\"11.7.1\",\"url\":\"https://rue-cobalt.xenon.zone/\",\"startTime\":\"1794533800018\",\"services\":[\"bilibili\",\"bluesky\",\"dailymotion\",\"facebook\",\"instagram\",\"loom\",\"ok\",\"pinterest\",\"newgrounds\",\"reddit\",\"rutube\",\"snapchat\",\"soundcloud\",\"streamable\",\"tiktok\",\"tumblr\",\"twitch clips\",\"twitter\",\"vimeo\",\"vk\",\"youtube\"]},\"git\":{\"branch\":\"main\",\"commit\":\"a636575b09de1fc55d9b8cd98cac88f5f2f16b42\",\"remote\":\"imputnet/cobalt\"}}";
        final String[] lastBody = {null, null, null, null}; // body, accept, content-type, auth
        cs.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] resp; int code = 200;
            if (ex.getRequestMethod().equals("GET")) {
                if (path.equals("/")) resp = info.getBytes(StandardCharsets.UTF_8);
                else if (path.startsWith("/html")) resp = "<html>hi</html>".getBytes();
                else if (path.startsWith("/forbidden")) { resp = "no".getBytes(); code = 403; }
                else if (path.startsWith("/err500")) { resp = "boom".getBytes(); code = 500; }
                else if (path.startsWith("/rate")) { resp = "x".getBytes(); code = 429; }
                else { resp = "{}".getBytes(); }
            } else {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastBody[0] = body; lastBody[1] = ex.getRequestHeaders().getFirst("Accept"); lastBody[2] = ex.getRequestHeaders().getFirst("Content-Type"); lastBody[3] = ex.getRequestHeaders().getFirst("Authorization");
                String r;
                if (body.contains("TUNNEL")) r = "{\"status\":\"tunnel\",\"url\":\"https://x/tunnel?id=1\",\"filename\":\"video.mp4\"}";
                else if (body.contains("REDIR")) r = "{\"status\":\"redirect\",\"url\":\"https://cdn/x.mp4\",\"filename\":\"a.mp4\"}";
                else if (body.contains("PICK")) r = "{\"status\":\"picker\",\"picker\":[{\"type\":\"photo\",\"url\":\"https://p/1.jpg\"},{\"type\":\"video\",\"url\":\"https://p/2.mp4\"}],\"audio\":\"https://p/a.mp3\"}";
                else if (body.contains("LOCAL")) r = "{\"status\":\"local-processing\",\"type\":\"merge\"}";
                else if (body.contains("PRIVATE")) { r = "{\"status\":\"error\",\"error\":{\"code\":\"error.api.content.video.private\"}}"; code = 400; }
                else if (body.contains("YTLOGIN")) { r = "{\"status\":\"error\",\"error\":{\"code\":\"error.api.youtube.login\"}}"; code = 400; }
                else if (body.contains("AUTH")) { r = "{\"status\":\"error\",\"error\":{\"code\":\"error.api.auth.key.missing\"}}"; code = 401; }
                else if (body.contains("WEIRD")) { r = "{\"status\":\"error\",\"error\":{\"code\":\"error.api.brand.new\"}}"; code = 400; }
                else if (body.contains("HTML")) { r = "<html>login</html>"; code = 403; }
                else r = "{\"status\":\"mystery\"}";
                resp = r.getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, resp.length); ex.getResponseBody().write(resp); ex.close();
        });
        cs.start(); String base = "http://127.0.0.1:" + cs.getAddress().getPort();
        t("Cobalt: حالة الخادم (رد حقيقي بصيغة rue-cobalt.xenon.zone)", "JSON مطابق لما أرجعه الخادم الفعلي لطلب GET", "متصل مع الإصدار والخدمات", () -> {
            CobaltClient.Status s = CobaltClient.status(base, 5000);
            check(s.online && s.version.equals("11.7.1") && s.services.size() == 21 && s.services.contains("youtube") && s.services.contains("tiktok"), s.error + "/" + s.version + "/" + s.services.size());
            return "متصل — الإصدار " + s.version + "، " + s.services.size() + " خدمة، " + s.ms + "ms";
        });
        t("Cobalt: حالات الخادم غير المتصل", "ليس Cobalt، 403، 429، 500، منفذ مغلق، مضيف غير موجود", "رسالة عربية مناسبة لكل حالة", () -> {
            String[] paths = {"/html", "/forbidden", "/rate", "/err500"}; String[] exp = {"ليس من خادم Cobalt", "يرفض", "يحدّ", "خطأ في الخادم"};
            for (int i = 0; i < 4; i++) { CobaltClient.Status s = CobaltClient.status(base + paths[i], 3000); check(!s.online && s.error.contains(exp[i]), paths[i] + ": " + s.error); }
            CobaltClient.Status c = CobaltClient.status("http://127.0.0.1:1", 2000); check(!c.online && c.error != null, "مغلق: " + c.error);
            CobaltClient.Status u = CobaltClient.status("https://nonexistent-host-mimax.invalid", 3000); check(!u.online && u.error != null, "مجهول: " + u.error);
            check(!CobaltClient.status("", 1000).online, "فارغ");
            return "مغلق: " + c.error + " | مجهول: " + u.error;
        });
        t("Cobalt: طلب تنزيل (tunnel / redirect / picker) والترويسات والمفتاح", "طلبات POST إلى خادم محاكٍ", "النتائج صحيحة والترويسات مرسلة", () -> {
            CobaltClient.Result r = CobaltClient.resolve(base, "https://youtu.be/TUNNEL", "abc123", "720", "auto");
            check(r.kind.equals("tunnel") && r.url.equals("https://x/tunnel?id=1") && r.filename.equals("video.mp4"), "tunnel");
            check(lastBody[1].equals("application/json") && lastBody[2].equals("application/json") && lastBody[3].equals("Api-Key abc123"), "الترويسات " + lastBody[1] + lastBody[2] + lastBody[3]);
            check(lastBody[0].contains("\"videoQuality\":\"720\"") && lastBody[0].contains("\"downloadMode\":\"auto\""), lastBody[0]);
            check(CobaltClient.resolve(base, "REDIR", null, null, null).kind.equals("redirect") && lastBody[3] == null, "redirect بلا مفتاح");
            CobaltClient.Result p = CobaltClient.resolve(base, "PICK", null, null, "audio");
            check(p.kind.equals("picker") && p.picker.size() == 2 && p.audio.equals("https://p/a.mp3") && lastBody[0].contains("\"audio\""), "picker");
            return "tunnel/redirect/picker صحيحة، والمفتاح يُرسل بصيغة Api-Key";
        });
        t("Cobalt: رسائل الأخطاء المفهومة", "خطأ خاص، تسجيل دخول يوتيوب، مفتاح مطلوب، معالجة محلية، رمز جديد، ردّ HTML، حالة غامضة", "رسالة عربية مناسبة لكل حالة", () -> {
            String[][] c = {{"PRIVATE", "خاص"}, {"YTLOGIN", "تسجيل دخول"}, {"AUTH", "مفتاح API"}, {"LOCAL", "معالجة محلية"}, {"WEIRD", "error.api.brand.new"}, {"HTML", "الخادم يرفض"}, {"MYSTERY", "غير معروفة"}};
            for (String[] x : c) { String m = thrown(CobaltClient.Err.class, () -> { CobaltClient.resolve(base, x[0], null, null, null); return ""; }); check(m.contains(x[1]), x[0] + ": " + m); }
            String m = thrown(CobaltClient.Err.class, () -> { CobaltClient.resolve("http://127.0.0.1:1", "x", null, null, null); return ""; });
            check(CobaltClient.normalize("example.com").equals("https://example.com/") && CobaltClient.normalize("http://a.b/x").equals("http://a.b/x/"), "normalize");
            return "7 حالات + انقطاع: " + m;
        });

        srv.stop(); cs.stop(0);
        StringBuilder md = new StringBuilder("# تقرير اختبارات: التنزيل متعدد الاتصالات / الطرفية / الأدوات / Hex / Google ID Token / Cobalt\n\nالتاريخ: " + new Date() + "\nبيئة التشغيل: JVM " + System.getProperty("java.version") + " على Linux (ليس جهاز Android). الخوادم محلية (127.0.0.1) تحاكي سلوك الخوادم الحقيقية؛ لم يُختبر أي شيء على الشبكة الحقيقية سوى رد Cobalt المقتبس من خادم rue-cobalt.xenon.zone عند الكتابة.\n\n");
        md.append("**النتيجة: ").append(pass).append(" نجح / ").append(fail).append(" فشل من ").append(pass + fail).append("**\n\n| # | الاختبار | البيانات | المتوقع | الفعلي | الحالة |\n|---|---|---|---|---|---|\n");
        int n = 1;
        for (String[] r : rows) md.append("| ").append(n++).append(" | ").append(esc(r[0])).append(" | ").append(esc(r[1])).append(" | ").append(esc(r[2])).append(" | ").append(esc(r[3])).append(" | ").append(r[4]).append(" |\n");
        Files.write(Paths.get(a[1]), md.toString().getBytes(StandardCharsets.UTF_8));
        for (String[] r : rows) System.out.println((r[4].equals("نجح") ? "PASS " : "FAIL ") + r[0] + (r[4].equals("نجح") ? "" : "\n     -> " + r[3]));
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
    static String esc(String s) { s = s.replace("|", "\\|").replace("\n", " "); return s.length() > 350 ? s.substring(0, 350) + "…" : s; }
}
