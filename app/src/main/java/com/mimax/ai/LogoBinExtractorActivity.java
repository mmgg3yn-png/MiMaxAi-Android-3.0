package com.mimax.ai;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only MediaTek logo.bin extractor. It never writes to a device partition. */
public class LogoBinExtractorActivity extends Activity {
    private static final int PICK_INPUT = 9301, PICK_OUTPUT = 9302;
    private static final int PART_MAGIC = 0x58881688;
    private static final int HEADER_SIZE = 512;
    private TextView status;
    private Button export;
    private File resultZip;
    private String report = "";
    private ProgressDialog progress;

    private static final class Part {
        String name;
        int dataOffset, dataSize, align, listEnd;
    }

    private static final class Item {
        String name;
        byte[] bytes;
        boolean png;
        int width, height;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Theme th = Theme.get(Store.theme());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(16));
        page.setBackgroundColor(th.base);

        TextView title = new TextView(this);
        title.setText("Logo.bin Extractor");
        title.setTextColor(th.tx);
        title.setTextSize(22);
        page.addView(title);

        TextView description = new TextView(this);
        description.setText("استخراج صور MediaTek من logo.bin وحفظ الصور المعروفة بصيغة PNG، والبيانات ذات الأبعاد غير المعروفة بصيغة RGBA. الأداة للقراءة والاستخراج فقط ولا تفلّش أي شيء.");
        description.setTextColor(th.mut);
        description.setTextSize(14);
        description.setPadding(0, dp(8), 0, dp(12));
        page.addView(description);

        Button choose = new Button(this);
        choose.setText("اختيار ملف logo.bin");
        choose.setAllCaps(false);
        page.addView(choose, new LinearLayout.LayoutParams(-1, dp(50)));
        choose.setOnClickListener(v -> pickInput());

        status = new TextView(this);
        status.setText("اختر ملف logo.bin للبدء.");
        status.setTextColor(th.tx);
        status.setTextSize(14);
        status.setPadding(0, dp(14), 0, dp(14));
        page.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));

        export = new Button(this);
        export.setText("حفظ ملف ZIP المستخرج");
        export.setAllCaps(false);
        export.setEnabled(false);
        page.addView(export, new LinearLayout.LayoutParams(-1, dp(50)));
        export.setOnClickListener(v -> saveZip());

        Button back = new Button(this);
        back.setText("رجوع");
        back.setAllCaps(false);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));
        back.setOnClickListener(v -> finish());

        setContentView(page);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void pickInput() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_INPUT);
    }

    private void saveZip() {
        if (resultZip == null || !resultZip.isFile()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "logo_bin_extracted.zip");
        startActivityForResult(i, PICK_OUTPUT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_INPUT) extract(data.getData());
        else if (request == PICK_OUTPUT) copyZip(data.getData());
    }

    private void extract(Uri uri) {
        progress = ProgressDialog.show(this, "Logo.bin", "جارٍ فحص الملف واستخراج الصور…", true, false);
        new Thread(() -> {
            try {
                File work = new File(getExternalFilesDir(null), "logo_bin_extractor");
                if (!work.exists() && !work.mkdirs()) throw new Exception("تعذر إنشاء مجلد العمل.");
                File zip = new File(work, "logo_bin_extracted.zip");
                ArrayList<Item> items = parseAndExtract(uri);
                StringBuilder log = new StringBuilder();
                int pngs = 0, raw = 0;
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    for (Item item : items) {
                        out.putNextEntry(new ZipEntry(item.name));
                        out.write(item.bytes);
                        out.closeEntry();
                        if (item.png) pngs++; else raw++;
                        log.append(item.name).append("  •  ").append(item.bytes.length).append(" bytes\n");
                    }
                    out.putNextEntry(new ZipEntry("README_AR.txt"));
                    out.write(("Mi_Max Ai - Logo.bin Extractor\n"
                            + "PNG files are decoded from BGRA32 where dimensions are known.\n"
                            + "RGBA files contain raw BGRA pixel bytes when dimensions are unknown.\n"
                            + "This tool only extracts data; it does not flash or modify a device.\n\n"
                            + log).getBytes("UTF-8"));
                    out.closeEntry();
                }
                resultZip = zip;
                String finalText = "اكتمل الاستخراج.\n"
                        + "عدد الأجزاء: " + items.size() + "\n"
                        + "صور PNG: " + pngs + "\n"
                        + "ملفات RGBA غير المعروفة الأبعاد: " + raw + "\n\n"
                        + "محتويات الأرشيف:\n" + log;
                runOnUiThread(() -> {
                    progress.dismiss();
                    status.setText(finalText);
                    export.setEnabled(true);
                    Toast.makeText(this, "تم استخراج " + items.size() + " جزءاً", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    if (progress != null) progress.dismiss();
                    status.setText("فشل الاستخراج: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    export.setEnabled(false);
                });
            }
        }).start();
    }

    private ArrayList<Item> parseAndExtract(Uri uri) throws Exception {
        byte[] image;
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("تعذر فتح الملف.");
            byte[] b = new byte[16384]; int n; long total = 0;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > 128L * 1024 * 1024) throw new Exception("الملف أكبر من الحد الآمن 128MB.");
                out.write(b, 0, n);
            }
            image = out.toByteArray();
        }
        ArrayList<Part> parts = parseParts(image);
        Part logo = null;
        for (Part p : parts) if ("logo".equalsIgnoreCase(p.name)) { logo = p; break; }
        if (logo == null) throw new Exception("لم يتم العثور على قسم logo داخل الملف.");
        ByteBuffer payload = ByteBuffer.wrap(image, logo.dataOffset, logo.dataSize).slice().order(ByteOrder.LITTLE_ENDIAN);
        if (payload.remaining() < 8) throw new Exception("بيانات قسم logo قصيرة أو غير صالحة.");
        long countLong = Integer.toUnsignedLong(payload.getInt());
        long totalLong = Integer.toUnsignedLong(payload.getInt());
        if (countLong < 1 || countLong > 512) throw new Exception("عدد الصور غير منطقي: " + countLong);
        if (totalLong < 8 + countLong * 4 || totalLong > logo.dataSize)
            throw new Exception("حجم جدول الصور غير صالح.");
        int count = (int) countLong;
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            long off = Integer.toUnsignedLong(payload.getInt());
            if (off < 8 + count * 4L || off >= totalLong) throw new Exception("إزاحة الصورة " + i + " خارج الحدود.");
            offsets[i] = (int) off;
            if (i > 0 && offsets[i] <= offsets[i - 1]) throw new Exception("جدول إزاحات الصور غير مرتب.");
        }
        ArrayList<Item> items = new ArrayList<>();
        long extractedTotal = 0;
        for (int i = 0; i < count; i++) {
            int end = i + 1 < count ? offsets[i + 1] : (int) totalLong;
            if (end <= offsets[i]) throw new Exception("حجم مضغوط غير صالح للصورة " + i);
            int startAbs = logo.dataOffset + offsets[i];
            int compressedLen = end - offsets[i];
            byte[] decoded;
            try (InflaterInputStream inflater = new InflaterInputStream(
                    new java.io.ByteArrayInputStream(image, startAbs, compressedLen));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n; long size = 0;
                while ((n = inflater.read(buf)) != -1) {
                    size += n; extractedTotal += n;
                    if (size > 64L * 1024 * 1024 || extractedTotal > 256L * 1024 * 1024)
                        throw new Exception("البيانات المفكوكة تجاوزت الحد الآمن.");
                    out.write(buf, 0, n);
                }
                decoded = out.toByteArray();
            }
            Item item = new Item();
            int[] dims = dimensions(decoded.length);
            if (dims != null && decoded.length == (long) dims[0] * dims[1] * 4L) {
                item.name = String.format(Locale.US, "%02d_%dx%d.png", i, dims[0], dims[1]);
                item.bytes = toPng(decoded, dims[0], dims[1]);
                item.png = true; item.width = dims[0]; item.height = dims[1];
            } else {
                item.name = String.format(Locale.US, "%02d_%dbytes.rgba", i, decoded.length);
                item.bytes = decoded; item.png = false;
            }
            items.add(item);
        }
        return items;
    }

    private ArrayList<Part> parseParts(byte[] image) throws Exception {
        ArrayList<Part> parts = new ArrayList<>();
        int offset = 0;
        while (offset + HEADER_SIZE <= image.length) {
            ByteBuffer h = ByteBuffer.wrap(image, offset, HEADER_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != PART_MAGIC) {
                if (parts.isEmpty()) throw new Exception("توقيع MTK غير معروف عند بداية الملف.");
                break;
            }
            long dataSize = Integer.toUnsignedLong(h.getInt(4));
            byte[] nameBytes = new byte[32];
            System.arraycopy(image, offset + 8, nameBytes, 0, 32);
            int nameLen = 0; while (nameLen < nameBytes.length && nameBytes[nameLen] != 0) nameLen++;
            String name = new String(nameBytes, 0, nameLen, "UTF-8").trim();
            long alignLong = Integer.toUnsignedLong(h.getInt(68));
            long endLong = (long) offset + HEADER_SIZE + dataSize;
            if (dataSize > image.length || endLong > image.length) throw new Exception("حجم قسم " + name + " يتجاوز الملف.");
            int align = alignLong == 0 || alignLong > 1024 * 1024 ? 1 : (int) alignLong;
            Part p = new Part();
            p.name = name; p.dataOffset = offset + HEADER_SIZE; p.dataSize = (int) dataSize;
            p.align = align; p.listEnd = h.getInt(64);
            parts.add(p);
            long padded = ((dataSize + align - 1) / align) * align;
            long next = (long) offset + HEADER_SIZE + padded;
            if (p.listEnd != 0 || next <= offset || next > image.length) break;
            offset = (int) next;
        }
        if (parts.isEmpty()) throw new Exception("لم يتم العثور على أقسام MTK.");
        return parts;
    }

    private int[] dimensions(int bytes) {
        // Exact BGRA32 byte-count matches only; unknown sizes stay as raw .rgba.
        Map<Integer, int[]> m = new HashMap<>();
        m.put(18432000, new int[]{1440,3200}); m.put(17971200, new int[]{1440,3120});
        m.put(17510400, new int[]{1440,3040}); m.put(17049600, new int[]{1440,2960});
        m.put(16588800, new int[]{1440,2880}); m.put(14745600, new int[]{1440,2560});
        m.put(13234560, new int[]{1220,2712}); m.put(10886400, new int[]{1080,2520});
        m.put(10627200, new int[]{1080,2460}); m.put(10402560, new int[]{1080,2408});
        m.put(10368000, new int[]{1080,2400}); m.put(10108800, new int[]{1080,2340});
        m.put(9936000, new int[]{1080,2300}); m.put(9849600, new int[]{1080,2280});
        m.put(9331200, new int[]{1080,2160}); m.put(8294400, new int[]{1080,1920});
        m.put(4608000, new int[]{720,1600}); m.put(4492800, new int[]{720,1560});
        m.put(4377600, new int[]{720,1520}); m.put(4147200, new int[]{720,1440});
        m.put(3686400, new int[]{720,1280}); m.put(249200, new int[]{178,350});
        m.put(49444, new int[]{263,47}); m.put(36864, new int[]{72,128});
        m.put(15984, new int[]{54,74}); m.put(14592, new int[]{57,64});
        m.put(11520, new int[]{45,64}); m.put(7056, new int[]{42,42});
        m.put(2104, new int[]{263,2});
        return m.get(bytes);
    }

    private byte[] toPng(byte[] bgra, int width, int height) throws Exception {
        int pixels = width * height;
        int[] argb = new int[pixels];
        for (int i = 0, p = 0; i < pixels; i++, p += 4) {
            int b = bgra[p] & 255, g = bgra[p + 1] & 255, r = bgra[p + 2] & 255, a = bgra[p + 3] & 255;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new Exception("تعذر إنشاء PNG.");
        bitmap.recycle();
        return out.toByteArray();
    }

    private void copyZip(Uri destination) {
        try (InputStream in = new FileInputStream(resultZip);
             OutputStream out = getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new Exception("تعذر فتح وجهة الحفظ.");
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            Toast.makeText(this, "تم حفظ أرشيف الصور.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
