package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import androidx.leanback.media.PlayerAdapter;
import androidx.leanback.media.PlaybackBaseControlGlue;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 把 ijkplayer 包成 Leanback 的 PlayerAdapter（含进度回传）。 */
public class IjkPlayerAdapter extends PlayerAdapter {

    private final IjkMediaPlayer mp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean progressEnabled;
    private int tickCount;

    public IjkPlayerAdapter(IjkMediaPlayer player) {
        this.mp = player;
    }

    @Override public void play() { mp.start(); }
    @Override public void pause() { mp.pause(); }
    @Override public void seekTo(long position) { mp.seekTo(Math.max(0, position)); }

    /** 快退 15 秒 */
    @Override public void rewind() { seekBy(-15000); }

    /** 快进 15 秒 */
    @Override public void fastForward() { seekBy(15000); }

    private void seekBy(long delta) {
        long dur = mp.getDuration();
        long pos = mp.getCurrentPosition() + delta;
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur - 500) pos = Math.max(0, dur - 500);
        mp.seekTo(pos);
    }
    @Override public boolean isPlaying() { return mp.isPlaying(); }
    @Override public boolean isPrepared() { return mp.getDuration() > 0; }
    @Override public long getCurrentPosition() { return mp.getCurrentPosition(); }
    @Override public long getDuration() { return mp.getDuration(); }
    @Override public long getBufferedPosition() { return mp.getCurrentPosition(); }

    @Override public long getSupportedActions() {
        return PlaybackBaseControlGlue.ACTION_PLAY_PAUSE
                | PlaybackBaseControlGlue.ACTION_REWIND
                | PlaybackBaseControlGlue.ACTION_FAST_FORWARD;
    }

    @Override public void setProgressUpdatingEnabled(boolean enabled) {
        if (progressEnabled == enabled) return;
        progressEnabled = enabled;
        handler.removeCallbacks(tick);
        if (enabled) handler.postDelayed(tick, 200);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!progressEnabled) return;
            Callback cb = getCallback();

            if (cb != null) {
                cb.onCurrentPositionChanged(IjkPlayerAdapter.this);
                cb.onBufferedPositionChanged(IjkPlayerAdapter.this);
            }
            handler.postDelayed(this, 200);
        }
    };
}