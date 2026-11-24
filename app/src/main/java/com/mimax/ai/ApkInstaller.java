package com.mimax.ai;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.mimax.ai.engine.Archives;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** تثبيت التطبيقات (APK / APKS / XAPK) عبر PackageInstaller الرسمي. لا يتجاوز أي قيد أمني: المستخدم يؤكد في نافذة النظام. */
class ApkInstaller {
    static final String ACTION = "com.mimax.ai.INSTALL_DONE";
    final MainActivity a;
    boolean registered;

    final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(confirm); }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                a.toast("تم تثبيت التطبيق");
            } else {
                a.showError("لم يكتمل التثبيت (" + st + ")" + (msg == null ? "" : ":\n" + msg));
            }
        }
    };

    ApkInstaller(MainActivity a) { this.a = a; }

    void register() {
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(rcv, f);
        registered = true;
    }

    void unregister() {
        if (!registered) return;
        try { a.unregisterReceiver(rcv); } catch (Exception e) { /* تجاهل */ }
        registered = false;
    }

    static boolean isInstallable(String name) { return name.toLowerCase().matches(".*\\.(apk|apks|xapk)$"); }

    void install(final File f) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            a.confirm("لتثبيت التطبيقات يجب السماح لهذا التطبيق بـ «تثبيت تطبيقات غير معروفة». فتح الإعدادات الآن؟ (ارجع بعد السماح وأعد المحاولة)", () ->
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))));
            return;
        }
        if (f.getName().toLowerCase().endsWith(".apk")) confirmAndGo(Collections.singletonList(f));
        else prepareBundle(f);
    }

    static boolean abiOk(String ln) {
        Matcher m = Pattern.compile("config[._-](armeabi[_-]v7a|arm64[_-]v8a|x86_64|x86)(?![a-z0-9_])").matcher(ln);
        if (!m.find()) return true;
        String abi = m.group(1).replace('-', '_');
        for (String s : Build.SUPPORTED_ABIS) if (s.replace('-', '_').equals(abi)) return true;
        return false;
    }

    void prepareBundle(final File f) {
        final List<File> out = new ArrayList<>();
        a.runBg("جارٍ تجهيز الحزمة…", null, () -> {
            File dir = new File(a.getCacheDir(), "apkinstall");
            BootScreen.delRec(dir);
            dir.mkdirs();
            List<String> pick = new ArrayList<>();
            for (Archives.Item it : Archives.list(f)) {
                String ln = it.name.toLowerCase();
                if (it.dir || !ln.endsWith(".apk") || ln.contains("standalones/")) continue;
                if (ln.endsWith("universal.apk")) { pick.clear(); pick.add(it.name); break; }
                if (abiOk(ln)) pick.add(it.name);
            }
            if (pick.isEmpty()) throw new IOException("لم أجد ملفات APK صالحة داخل الحزمة");
            int i = 0;
            for (String nm : pick) {
                File o = new File(dir, (i++) + "_" + new File(nm).getName());
                Archives.extractOne(f, nm, o);
                out.add(o);
            }
        }, () -> confirmAndGo(out));
    }

    void confirmAndGo(final List<File> apks) {
        PackageManager pm = a.getPackageManager();
        File base = apks.get(0);
        for (File x : apks) if (x.getName().toLowerCase().contains("base")) { base = x; break; }
        String info = "";
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(base.getPath(), 0);
            if (pi != null) {
                pi.applicationInfo.sourceDir = base.getPath();
                pi.applicationInfo.publicSourceDir = base.getPath();
                info = pi.applicationInfo.loadLabel(pm) + "\n" + pi.packageName + "\nالإصدار " + pi.versionName + "\n";
            }
        } catch (Exception e) { /* معلومات اختيارية */ }
        a.confirm("تثبيت هذا التطبيق؟\n\n" + info + "عدد الملفات: " + apks.size() + (apks.size() > 1 ? " (حزمة مقسّمة)" : "")
                + "\n\nيمرّ التثبيت عبر نافذة نظام Android الرسمية، وتؤكد أنت فيها.", () -> commit(apks));
    }

    void commit(final List<File> apks) {
        a.runBg("جارٍ تجهيز التثبيت…", null, () -> {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                int k = 0;
                for (File f : apks) {
                    try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite((k++) + "_" + f.getName(), 0, f.length())) {
                        byte[] b = new byte[1 << 16];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        s.fsync(o);
                    }
                }
                Intent i = new Intent(ACTION).setPackage(a.getPackageName());
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pe = PendingIntent.getBroadcast(a, id, i, fl);
                s.commit(pe.getIntentSender());
            } catch (Exception e) {
                s.abandon();
                throw e;
            } finally {
                s.close();
            }
        }, null);
    }
}
