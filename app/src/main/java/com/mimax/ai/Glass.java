package com.mimax.ai;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** سطح زجاجي شفاف: تعبئة نصف شفافة + لمعان علوي + حافة متدرجة تعطي إحساس السماكة. */
public class Glass extends Drawable {
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius, stroke;
    private final boolean accent, dark;
    private final float boost;

    public Glass(float radiusPx, int fill, boolean accent, boolean dark, float density) {
        this(radiusPx, fill, accent, dark, density, 1f);
    }

    public Glass(float radiusPx, int fill, boolean accent, boolean dark, float density, float boost) {
        this.boost = boost;
        this.radius = radiusPx;
        this.accent = accent;
        this.dark = dark;
        this.stroke = Math.max(1f, density);
        fillP.setStyle(Paint.Style.FILL);
        fillP.setColor(accent ? ((fill & 0x00FFFFFF) | 0xDD000000) : fill);
        glossP.setStyle(Paint.Style.FILL);
        borderP.setStyle(Paint.Style.STROKE);
        borderP.setStrokeWidth(stroke);
        edgeP.setStyle(Paint.Style.STROKE);
        edgeP.setStrokeWidth(stroke);
        edgeP.setColor(dark ? 0x22000000 : 0x1F1B2A55);
    }

    @Override protected void onBoundsChange(Rect b) {
        float h = b.height();
        rect.set(b.left + stroke / 2f, b.top + stroke / 2f, b.right - stroke / 2f, b.bottom - stroke / 2f);
        int g0 = a(accent ? 0x55 : (dark ? 0x26 : 0x88));
        glossP.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.62f, g0, 0x00FFFFFF, Shader.TileMode.CLAMP));
        borderP.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{a(0xA0), a(0x1A), a(0x0C), a(0x50)}, new float[]{0f, 0.42f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    private int a(int alpha) { return (Math.min(255, (int) (alpha * boost)) << 24) | 0x00FFFFFF; }

    @Override public void draw(Canvas c) {
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        c.drawRoundRect(rect, r, r, fillP);
        c.drawRoundRect(rect, r, r, glossP);
        c.drawRoundRect(rect, r, r, edgeP);
        c.drawRoundRect(rect, r, r, borderP);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
