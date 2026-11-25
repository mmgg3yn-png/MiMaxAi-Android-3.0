package com.mimax.ai;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Rewrites the package and application label in a compiled Android binary XML manifest. */
final class BinaryManifestPatcher {
    private BinaryManifestPatcher() {}

    static byte[] patch(byte[] xml, String packageName, String label) throws Exception {
        if (xml == null || xml.length < 8) throw new Exception("AndroidManifest.xml غير صالح");
        ByteBuffer input = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        int total = input.getInt(4);
        if (total != xml.length) throw new Exception("حجم AndroidManifest.xml غير متطابق");
        ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length + 256);
        out.write(xml, 0, 8);
        int pos = 8;
        boolean patched = false;
        while (pos + 8 <= xml.length) {
            int type = u16(input, pos);
            long chunkSizeLong = u32(input, pos + 4);
            if (chunkSizeLong < 8 || chunkSizeLong > xml.length - pos) throw new Exception("بنية Manifest غير صالحة");
            int chunkSize = (int) chunkSizeLong;
            if (type == 0x0001) {
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(xml, pos, chunk, 0, chunkSize);
                out.write(patchStringPool(chunk, packageName, label));
                patched = true;
            } else {
                out.write(xml, pos, chunkSize);
            }
            pos += chunkSize;
        }
        if (!patched || pos != xml.length) throw new Exception("تعذر تعديل بيانات Manifest");
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length);
        return result;
    }

    private static byte[] patchStringPool(byte[] chunk, String packageName, String label) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = u16(b, 2);
        int stringCount = (int) u32(b, 8);
        int styleCount = (int) u32(b, 12);
        int flags = (int) u32(b, 16);
        int stringsStart = (int) u32(b, 20);
        if (headerSize < 28 || styleCount != 0 || stringCount < 1 ||
                stringsStart < headerSize + stringCount * 4 || stringsStart > chunk.length) {
            throw new Exception("نوع StringPool غير مدعوم لتغيير اسم الحزمة");
        }
        boolean utf8 = (flags & 0x100) != 0;
        String[] strings = new String[stringCount];
        int offsetsStart = headerSize;
        for (int i = 0; i < stringCount; i++) {
            int offset = (int) u32(b, offsetsStart + i * 4);
            int at = stringsStart + offset;
            strings[i] = utf8 ? readUtf8(chunk, at) : readUtf16(chunk, at);
            if ("com.mimax.localrunner.html".equals(strings[i]) ||
                    "com.mimax.localrunner.python".equals(strings[i])) strings[i] = packageName;
            else if ("MiMax Local Project".equals(strings[i])) strings[i] = label;
        }

        ByteArrayOutputStream stringData = new ByteArrayOutputStream();
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = stringData.size();
            if (utf8) writeUtf8(stringData, strings[i]); else writeUtf16(stringData, strings[i]);
        }
        int newStringsStart = headerSize + stringCount * 4;
        int newSize = newStringsStart + stringData.size();
        ByteBuffer out = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0x0001);
        out.putShort((short) headerSize);
        out.putInt(newSize);
        out.putInt(stringCount);
        out.putInt(0);
        out.putInt(flags);
        out.putInt(newStringsStart);
        out.putInt(0);
        for (int offset : offsets) out.putInt(offset);
        out.position(newStringsStart);
        out.put(stringData.toByteArray());
        return out.array();
    }

    private static String readUtf8(byte[] data, int p) throws Exception {
        int[] a = readLen8(data, p); int utf16Len = a[0]; p = a[1];
        int[] c = readLen8(data, p); int byteLen = c[0]; p = c[1];
        if (byteLen < 0 || p + byteLen >= data.length) throw new Exception("StringPool UTF-8 غير صالح");
        return new String(data, p, byteLen, StandardCharsets.UTF_8);
    }
    private static int[] readLen8(byte[] d, int p) throws Exception {
        if (p >= d.length) throw new Exception("StringPool مبتور");
        int x = d[p++] & 255;
        if ((x & 0x80) == 0) return new int[]{x,p};
        if (p >= d.length) throw new Exception("StringPool مبتور");
        return new int[]{((x & 0x7f) << 8) | (d[p++] & 255),p};
    }
    private static String readUtf16(byte[] d, int p) throws Exception {
        if (p + 2 > d.length) throw new Exception("StringPool مبتور");
        int n = u16(ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN), p); p += 2;
        if ((n & 0x8000) != 0) {
            if (p + 2 > d.length) throw new Exception("StringPool مبتور");
            n = ((n & 0x7fff) << 16) | u16(ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN), p); p += 2;
        }
        if (n < 0 || p + n * 2 > d.length) throw new Exception("StringPool UTF-16 غير صالح");
        return new String(d, p, n * 2, StandardCharsets.UTF_16LE);
    }
    private static void writeUtf8(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeLen8(out, s.length()); writeLen8(out, bytes.length);
        out.write(bytes, 0, bytes.length); out.write(0);
    }
    private static void writeLen8(ByteArrayOutputStream out, int n) {
        if (n > 0x7f) { out.write(((n >> 8) & 0x7f) | 0x80); out.write(n & 255); }
        else out.write(n);
    }
    private static void writeUtf16(ByteArrayOutputStream out, String s) {
        int n = s.length();
        if (n > 0x7fff) { out.write(((n >> 16) & 0x7fff) | 0x80); out.write((n >> 8) & 255); out.write(n & 255); }
        else { out.write(n & 255); out.write((n >> 8) & 255); }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE); out.write(bytes, 0, bytes.length); out.write(0); out.write(0);
    }
    private static int u16(ByteBuffer b, int p) { return b.getShort(p) & 0xffff; }
    private static long u32(ByteBuffer b, int p) { return b.getInt(p) & 0xffffffffL; }
}
