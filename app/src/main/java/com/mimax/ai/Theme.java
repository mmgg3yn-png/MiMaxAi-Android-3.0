package com.mimax.ai;

/** ثيمات هادئة. كل ثيم: ألوان الأسطح (للنمط العادي وMaterial 3) + بقع الضوء الناعمة (للزجاج السائل). */
public final class Theme {
    public final String id, nameAr, descAr;
    public final boolean dark;
    public final int base, surface, surface2, line, tx, mut, ac, er;
    public final float glow;      // شدة بقع الضوء في الزجاج السائل
    public final int[] blobs;

    private Theme(String id, String nameAr, String descAr, boolean dark, int base, int surface, int surface2, int line,
                  int tx, int mut, int ac, int er, float glow, int... blobs) {
        this.id = id; this.nameAr = nameAr; this.descAr = descAr; this.dark = dark;
        this.base = base; this.surface = surface; this.surface2 = surface2; this.line = line;
        this.tx = tx; this.mut = mut; this.ac = ac; this.er = er; this.glow = glow; this.blobs = blobs;
    }

    public static final Theme[] ALL = {
            new Theme("basic", "الأساسي", "رمادي داكن مع برتقالي — مثل النسخة الأولى", true,
                    0xFF15171A, 0xFF1F2226, 0xFF2A2E34, 0xFF33383F, 0xFFECEFF3, 0xFF98A1AD, 0xFFFF6A00, 0xFFFF6B6B, 0.70f,
                    0xFF6B3410, 0xFF2B3342, 0xFF4A2A18, 0xFF1F3A4A),
            new Theme("amoled", "أسود نقي", "AMOLED: خلفية سوداء كاملة", true,
                    0xFF000000, 0xFF0D0D10, 0xFF18181C, 0xFF26262B, 0xFFEDEDF0, 0xFF8E8E98, 0xFF8AB4F8, 0xFFF28B82, 0.40f,
                    0xFF1B2A4A, 0xFF2A1B4A, 0xFF123A3A, 0xFF1A1A1A),
            new Theme("slate", "الضباب", "رمادي مزرق هادئ", true,
                    0xFF11161C, 0xFF1A2129, 0xFF232C36, 0xFF2F3A46, 0xFFE6EBF1, 0xFF8F9DAD, 0xFF7FA6C9, 0xFFE57373, 0.75f,
                    0xFF2F4A63, 0xFF3A4E5E, 0xFF2A3E52, 0xFF47606F),
            new Theme("forest", "الغابة", "أخضر مريمي ناعم", true,
                    0xFF0F1512, 0xFF18211C, 0xFF212C26, 0xFF2E3B34, 0xFFE4EEE8, 0xFF92A89B, 0xFF7FB69A, 0xFFE57373, 0.75f,
                    0xFF2D4A3C, 0xFF3B5A47, 0xFF2A4A4A, 0xFF4A5A3A),
            new Theme("dusk", "الغسق", "بنفسجي رمادي دافئ", true,
                    0xFF15121A, 0xFF1F1B27, 0xFF292433, 0xFF372F44, 0xFFEAE6F0, 0xFFA39BB3, 0xFFB39DDB, 0xFFE57373, 0.75f,
                    0xFF3E3358, 0xFF4A3A5A, 0xFF2F3A5A, 0xFF5A3A4A),
            new Theme("sand", "الرمل", "فاتح دافئ هادئ", false,
                    0xFFF3EFE8, 0xFFFFFFFF, 0xFFEAE4DA, 0xFFD9D1C4, 0xFF2A2620, 0xFF7A7064, 0xFFB5651D, 0xFFC0392B, 0.90f,
                    0xFFE6D5BC, 0xFFD8E2D0, 0xFFEBD0C0, 0xFFD0DCE6)
    };

    /** ثيم مبني من الألوان المخصصة المحفوظة (الافتراضي: ألوان الثيم الأساس b). */
    public static Theme custom(Theme b) {
        int base = Store.cc("base", b.base), surface = Store.cc("surface", b.surface), surface2 = Store.cc("surface2", b.surface2),
                line = Store.cc("line", b.line), tx = Store.cc("tx", b.tx), mut = Store.cc("mut", b.mut), ac = Store.cc("ac", b.ac), er = Store.cc("er", b.er);
        return new Theme("custom", "مخصص", "ألوان مخصصة", lum(base) < 0.5f, base, surface, surface2, line, tx, mut, ac, er, b.glow,
                Store.cc("b0", b.blobs[0]), Store.cc("b1", b.blobs[1]), Store.cc("b2", b.blobs[2]), Store.cc("b3", b.blobs[3]));
    }

    public static Theme get(int i) { Theme base = ALL[(i < 0 || i >= ALL.length) ? 0 : i]; return Store.flag("ccOn", false) ? custom(base) : base; }

    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public static float lum(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }
}
