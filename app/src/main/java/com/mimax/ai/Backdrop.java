package com.mimax.ai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** خلفية سائلة: بقع ضوء كبيرة ناعمة تتحرك ببطء خلف الأسطح الزجاجية. */
public class Backdrop extends View {
    private final Theme t;
    private final boolean motion;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient[] sh = new RadialGradient[4];
    private float phase;
    private int tick;
    private ValueAnimator anim;
    // مركز كل بقعة (نسبة من العرض/الارتفاع)، السعة، وإزاحة الطور
    private static final float[][] B = {
            {0.15f, 0.18f, 0.22f, 0.0f}, {0.85f, 0.30f, 0.20f, 1.7f},
            {0.25f, 0.80f, 0.24f, 3.1f}, {0.80f, 0.88f, 0.18f, 4.6f}};
    private static final int[] SPEED = {1, 2, 3, 2};

    public Backdrop(Context c, Theme t, boolean motion) {
        super(c);
        this.t = t;
        this.motion = motion;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Math.max(w, h) * 0.62f;
        for (int i = 0; i < 4; i++) {
            int col = t.blobs[i] & 0x00FFFFFF;
            int a0 = (int) ((t.dark ? 0xA0 : 0x90) * t.glow), a1 = (int) ((t.dark ? 0x4A : 0x48) * t.glow);
            sh[i] = new RadialGradient(0, 0, r, new int[]{(a0 << 24) | col, (a1 << 24) | col, col},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        radius = r;
    }

    private float radius;

    @Override protected void onDraw(Canvas c) {
        c.drawColor(t.base);
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < 4; i++) {
            if (sh[i] == null) return;
            float x = w * (B[i][0] + B[i][2] * (float) Math.sin(phase * SPEED[i] + B[i][3]));
            float y = h * (B[i][1] + B[i][2] * (float) Math.cos(phase * SPEED[i] * 0.8f + B[i][3]));
            c.save();
            c.translate(x, y);
            p.setShader(sh[i]);
            c.drawCircle(0, 0, radius, p);
            c.restore();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (motion && ValueAnimator.areAnimatorsEnabled()) {
            anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            anim.setDuration(90000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (Float) a.getAnimatedValue();
                if ((++tick & 1) == 0) postInvalidateOnAnimation();
            });
            anim.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
