package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 全屏直播页：从桌面预览点进来。 */
public class LivePlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "CYT-LIVE";

    private SurfaceView surfaceView;
    private TextView osd;
    private IjkMediaPlayer player;
    private List<LiveSource.Ch> channels;
    private int index;
    private int jump;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(30);
        osd.setPadding(30, 16, 30, 16);
        osd.setBackgroundColor(0xAA000000);
        osd.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(36, 36, 0, 0);
        root.addView(osd, lp);
        setContentView(root);

        index = getIntent().getIntExtra("index", -1);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<LiveSource.Ch> list = LiveSource.load();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        channels = list;
                        if (channels == null || channels.isEmpty()) return;
                        if (index < 0 || index >= channels.size()) index = LiveSource.jade(channels);
                        if (index < 0) index = 0;
                        startPlay();
                    }
                });
            }
        }, "cyt-live").start();
    }

    private void startPlay() {
        if (channels == null || channels.isEmpty()) return;
        if (index < 0) index = channels.size() - 1;
        if (index >= channels.size()) index = 0;
        LiveSource.Ch ch = channels.get(index);
        osd.setText((index + 1) + "  " + ch.name);
        osd.setVisibility(View.VISIBLE);
        osd.removeCallbacks(hideOsd);
        osd.postDelayed(hideOsd, 3000);

        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        try {
            IjkMediaPlayer p = new IjkMediaPlayer();
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer");
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 131072L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L);
            p.setDisplay(surfaceView.getHolder());
            p.setDataSource(ch.url);
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) { mp.start(); }
            });
            p.prepareAsync();
            player = p;
        } catch (Throwable t) {
            Log.e(TAG, "play failed " + t);
        }
    }

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() { osd.setVisibility(View.GONE); }
    };

    private final Runnable commitJump = new Runnable() {
        @Override public void run() {
            int n = jump;
            jump = 0;
            if (n > 0) { index = n - 1; startPlay(); }
        }
    };

    @Override public void surfaceCreated(SurfaceHolder h) { if (channels != null) startPlay(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_DPAD_UP) { index--; startPlay(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { index++; startPlay(); return true; }
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            jump = jump * 10 + (code - KeyEvent.KEYCODE_0);
            osd.setText("跳转: " + jump);
            osd.setVisibility(View.VISIBLE);
            osd.removeCallbacks(commitJump);
            osd.postDelayed(commitJump, 1500);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        super.onDestroy();
    }
}