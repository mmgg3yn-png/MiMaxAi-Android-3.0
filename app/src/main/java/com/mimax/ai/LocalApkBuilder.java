package com.mimax.ai;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.security.auth.x500.X500Principal;

/**
 * Builds a local project APK by injecting the project into a prebuilt WebView shell
 * and signing the result on the device. HTML is fully local. Python uses Pyodide in
 * the generated WebView app; pip packages must be compatible with Pyodide/WebAssembly.
 */
public final class LocalApkBuilder {
    private static final String KEY_ALIAS = "mimax-local-project-signing-v1";
    private LocalApkBuilder() { }

    public static File build(Context context, File project, boolean html, String title, String packageName, File iconFile) throws Exception {
        if (project == null || !project.isDirectory()) throw new Exception("مجلد المشروع غير موجود");
        if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,7}"))
            throw new Exception("اسم الحزمة غير صالح");
        File main = new File(project, html ? "main.html" : "main.py");
        if (!main.isFile()) throw new Exception(html ? "ملف main.html غير موجود" : "ملف main.py غير موجود");

        String runnerAsset = html ? "local_runner_html.apk" : "local_runner_python.apk";
        File template = new File(context.getCacheDir(), runnerAsset);
        try (InputStream in = context.getAssets().open(runnerAsset);
             FileOutputStream out = new FileOutputStream(template)) {
            copy(in, out);
        }

        File unsigned = new File(context.getCacheDir(), "mimax-local-unsigned.apk");
        File signed = new File(context.getCacheDir(), safeName(project.getName()) + (html ? "-html.apk" : "-python.apk"));
        if (unsigned.exists()) unsigned.delete();
        if (signed.exists()) signed.delete();

        try (ZipFile source = new ZipFile(template);
             CountingOutputStream counted = new CountingOutputStream(new FileOutputStream(unsigned));
             ZipOutputStream output = new ZipOutputStream(counted)) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            byte[] buffer = new byte[16384];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/") || name.equals("assets/app-config.json")
                        || name.startsWith("assets/site/") || name.equals("assets/python-source.js") || name.equals("assets/app-icon.png")) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(name);
                if ("AndroidManifest.xml".equals(name)) {
                    byte[] raw;
                    try (InputStream in = source.getInputStream(entry)) { raw = readAll(in); }
                    byte[] patched = BinaryManifestPatcher.patch(raw, packageName,
                            title == null || title.trim().isEmpty() ? project.getName() : title.trim());
                    addBytes(output, name, patched);
                } else if ("resources.arsc".equals(name)) {
                    byte[] raw;
                    try (InputStream in = source.getInputStream(entry)) { raw = readAll(in); }
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(raw);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(raw.length);
                    copy.setCompressedSize(raw.length);
                    copy.setCrc(crc.getValue());
                    // apksig performs standards-compliant 4-byte alignment before signing.
                    output.putNextEntry(copy);
                    output.write(raw);
                } else {
                    output.putNextEntry(copy);
                    try (InputStream in = source.getInputStream(entry)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) output.write(buffer, 0, n);
                    }
                }
                output.closeEntry();
            }

            JSONObject config = new JSONObject();
            config.put("type", html ? "html" : "python");
            config.put("title", title == null ? project.getName() : title);\n            config.put("package", packageName);
            addBytes(output, "assets/app-config.json", config.toString().getBytes(StandardCharsets.UTF_8));

            if (html) {
                ArrayList<File> files = new ArrayList<>();
                collect(project, files);
                for (File file : files) {
                    String relative = project.toURI().relativize(file.toURI()).getPath();
                    if (relative == null || relative.isEmpty() || relative.startsWith("../")) continue;
                    addFile(output, "assets/site/" + relative, file);
                }
            } else {
                String sourceCode = readText(main, 2 * 1024 * 1024);
                File requirements = new File(project, "requirements.txt");
                JSONArray reqs = new JSONArray();
                if (requirements.isFile()) {
                    String[] lines = readText(requirements, 64 * 1024).split("\\r?\\n");
                    for (String raw : lines) {
                        String line = raw.trim();
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
                        if (line.matches("[A-Za-z0-9_.-]+([<>=!~].*)?")) reqs.put(line);
                    }
                }
                String js = "window.MIMAX_PYTHON_SOURCE=" + JSONObject.quote(sourceCode) + ";"
                        + "window.MIMAX_PYTHON_REQUIREMENTS=" + reqs.toString() + ";";
                addBytes(output, "assets/python-source.js", js.getBytes(StandardCharsets.UTF_8));
            }
        }

        try {
            sign(context, unsigned, signed);
        } finally {
            unsigned.delete();
            template.delete();
        }
        if (!signed.isFile() || signed.length() < 4096) throw new Exception("ملف APK الناتج غير صالح");
        return signed;
    }

    private static void sign(Context context, File input, File output) throws Exception {
        ensureSigningKey();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        PrivateKey key = (PrivateKey) store.getKey(KEY_ALIAS, null);
        X509Certificate cert = (X509Certificate) store.getCertificate(KEY_ALIAS);
        if (key == null || cert == null) throw new Exception("تعذر تجهيز مفتاح توقيع APK المحلي");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "mimax-local", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    private static void ensureSigningKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return;
        long now = System.currentTimeMillis();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=MiMax Local Project"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(now - 86400000L))
                .setCertificateNotAfter(new Date(now + 10L * 365L * 86400000L))
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;
        CountingOutputStream(java.io.OutputStream out) { super(out); }
        long getCount() { return count; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) { copy(in, zip); }
        zip.closeEntry();
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void collect(File dir, ArrayList<File> result) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, result);
            else if (child.isFile()) result.add(child);
        }
    }

    private static String readText(File file, int maxBytes) throws Exception {
        if (file.length() > maxBytes) throw new Exception(file.getName() + " أكبر من الحد المسموح");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) { copy(in, out); }
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private static String safeName(String value) {
        String safe = value == null ? "project" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return safe.length() == 0 ? "project" : safe;
    }
}
