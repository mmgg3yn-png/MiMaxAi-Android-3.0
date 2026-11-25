package com.mimax.ai.desktop;

import java.awt.Color;

/** Exact desktop port of Android Theme.java: same six palettes and glow metadata. */
public final class DesktopTheme {
    public final String id,nameAr,descAr;
    public final boolean dark;
    public final Color base,surface,surface2,line,text,muted,accent,error;
    public final float glow;
    public final Color[] blobs;

    private DesktopTheme(String id,String name,String desc,boolean dark,long base,long surface,long surface2,long line,
                         long text,long muted,long accent,long error,float glow,long... blobs){
        this.id=id;this.nameAr=name;this.descAr=desc;this.dark=dark;
        this.base=c(base);this.surface=c(surface);this.surface2=c(surface2);this.line=c(line);
        this.text=c(text);this.muted=c(muted);this.accent=c(accent);this.error=c(error);this.glow=glow;
        this.blobs=new Color[blobs.length];for(int i=0;i<blobs.length;i++)this.blobs[i]=c(blobs[i]);
    }
    private static Color c(long argb){return new Color((int)argb,true);}
    public static final DesktopTheme[] ALL={
        new DesktopTheme("basic","الأساسي","رمادي داكن مع برتقالي — مثل النسخة الأولى",true,0xFF15171AL,0xFF1F2226L,0xFF2A2E34L,0xFF33383FL,0xFFECEFF3L,0xFF98A1ADL,0xFFFF6A00L,0xFFFF6B6BL,.70f,0xFF6B3410L,0xFF2B3342L,0xFF4A2A18L,0xFF1F3A4AL),
        new DesktopTheme("amoled","أسود نقي","AMOLED: خلفية سوداء كاملة",true,0xFF000000L,0xFF0D0D10L,0xFF18181CL,0xFF26262BL,0xFFEDEDF0L,0xFF8E8E98L,0xFF8AB4F8L,0xFFF28B82L,.40f,0xFF1B2A4AL,0xFF2A1B4AL,0xFF123A3AL,0xFF1A1A1AL),
        new DesktopTheme("slate","الضباب","رمادي مزرق هادئ",true,0xFF11161CL,0xFF1A2129L,0xFF232C36L,0xFF2F3A46L,0xFFE6EBF1L,0xFF8F9DADL,0xFF7FA6C9L,0xFFE57373L,.75f,0xFF2F4A63L,0xFF3A4E5EL,0xFF2A3E52L,0xFF47606FL),
        new DesktopTheme("forest","الغابة","أخضر مريمي ناعم",true,0xFF0F1512L,0xFF18211CL,0xFF212C26L,0xFF2E3B34L,0xFFE4EEE8L,0xFF92A89BL,0xFF7FB69AL,0xFFE57373L,.75f,0xFF2D4A3CL,0xFF3B5A47L,0xFF2A4A4AL,0xFF4A5A3AL),
        new DesktopTheme("dusk","الغسق","بنفسجي رمادي دافئ",true,0xFF15121AL,0xFF1F1B27L,0xFF292433L,0xFF372F44L,0xFFEAE6F0L,0xFFA39BB3L,0xFFB39DDBL,0xFFE57373L,.75f,0xFF3E3358L,0xFF4A3A5AL,0xFF2F3A5AL,0xFF5A3A4AL),
        new DesktopTheme("sand","الرمل","فاتح دافئ هادئ",false,0xFFF3EFE8L,0xFFFFFFFFL,0xFFEAE4DAL,0xFFD9D1C4L,0xFF2A2620L,0xFF7A7064L,0xFFB5651DL,0xFFC0392BL,.90f,0xFFE6D5BCL,0xFFD8E2D0L,0xFFEBD0C0L,0xFFD0DCE6L)
    };
    public static DesktopTheme byId(String id){for(DesktopTheme t:ALL)if(t.id.equals(id))return t;return ALL[0];}
    public static float luminance(Color c){return (.299f*c.getRed()+.587f*c.getGreen()+.114f*c.getBlue())/255f;}
}
