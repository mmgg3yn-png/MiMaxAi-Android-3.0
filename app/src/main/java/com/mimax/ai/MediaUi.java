package com.mimax.ai;

import android.app.Dialog;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

/** مشغل مصغّر: شريط صوت دائم أسفل الشاشة + نافذة فيديو. يعتمد على مشغّل النظام (MediaPlayer/VideoView). */
class MediaUi {
    final MainActivity a;
    MediaPlayer mp;
    LinearLayout bar;
    TextView name;
    Button play;
    SeekBar seek;
    boolean tracking;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp == null) return;
            try { if (mp.isPlaying() && !tracking) seek.setProgress(mp.getCurrentPosition()); } catch (Exception e) { /* تجاهل */ }
            a.ui.postDelayed(this, 500);
        }
    };

    MediaUi(MainActivity a) { this.a = a; }

    static boolean isAudio(String n) { return n.toLowerCase().matches(".*\\.(mp3|m4a|aac|wav|ogg|oga|opus|flac|amr|mid|midi|wma)$"); }
    static boolean isVideo(String n) { return n.toLowerCase().matches(".*\\.(mp4|mkv|3gp|webm|avi|mov|m4v|ts|flv)$"); }

    View bar() {
        bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        bar.setBackground(a.rr(a.SF2, 22, a.LN));
        play = a.small("▶", v -> toggle());
        bar.addView(play, new LinearLayout.LayoutParams(a.dp(46), a.dp(40)));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        name = a.tv("", a.TX, 13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(name);
        seek = new SeekBar(a);
        seek.getProgressDrawable().setTint(a.AC);
        seek.getThumb().setTint(a.AC);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) { if (fromUser && mp != null) try { mp.seekTo(v); } catch (Exception e) { /* تجاهل */ } }
            public void onStartTrackingTouch(SeekBar s) { tracking = true; }
            public void onStopTrackingTouch(SeekBar s) { tracking = false; }
        });
        mid.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams mp0 = new LinearLayout.LayoutParams(0, -2, 1f);
        mp0.setMargins(a.dp(8), 0, a.dp(8), 0);
        bar.addView(mid, mp0);
        bar.addView(a.small("✕", v -> stopAudio()), new LinearLayout.LayoutParams(a.dp(46), a.dp(40)));
        bar.setVisibility(View.GONE);
        return bar;
    }

    void playAudio(File f) {
        stopAudio();
        try {
            mp = new MediaPlayer();
            mp.setDataSource(f.getPath());
            mp.setOnPreparedListener(m -> {
                seek.setMax(m.getDuration());
                m.start();
                play.setText("⏸");
                a.ui.removeCallbacks(tick);
                a.ui.post(tick);
            });
            mp.setOnCompletionListener(m -> { play.setText("▶"); seek.setProgress(0); });
            mp.setOnErrorListener((m, w, x) -> { a.toast("تعذر تشغيل الملف (صيغة غير مدعومة؟)"); stopAudio(); return true; });
            name.setText(f.getName());
            seek.setProgress(0);
            play.setText("▶");
            bar.setVisibility(View.VISIBLE);
            mp.prepareAsync();
        } catch (Exception e) {
            a.toast("تعذر تشغيل الملف");
            stopAudio();
        }
    }

    void toggle() {
        if (mp == null) return;
        try {
            if (mp.isPlaying()) { mp.pause(); play.setText("▶"); } else { mp.start(); play.setText("⏸"); }
        } catch (Exception e) { /* تجاهل */ }
    }

    void stopAudio() {
        a.ui.removeCallbacks(tick);
        if (mp != null) {
            try { mp.stop(); } catch (Exception e) { /* تجاهل */ }
            mp.release();
            mp = null;
        }
        if (bar != null) bar.setVisibility(View.GONE);
    }

    void release() { stopAudio(); }

    void playVideo(File f) {
        stopAudio();
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        final VideoView vv = new VideoView(a);
        MediaController mc = new MediaController(a);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setVideoPath(f.getPath());
        vv.setOnPreparedListener(m -> vv.start());
        vv.setOnErrorListener((m, w, x) -> { a.toast("تعذر تشغيل الفيديو (صيغة أو ترميز غير مدعوم)"); d.dismiss(); return true; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.gravity = Gravity.CENTER;
        root.addView(vv, lp);
        Button close = a.small("✕", v -> d.dismiss());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(a.dp(48), a.dp(44));
        cp.gravity = Gravity.TOP | Gravity.LEFT;
        cp.setMargins(a.dp(8), a.dp(28), 0, 0);
        root.addView(close, cp);
        d.setContentView(root);
        d.setOnDismissListener(x -> vv.stopPlayback());
        d.show();
    }
}
