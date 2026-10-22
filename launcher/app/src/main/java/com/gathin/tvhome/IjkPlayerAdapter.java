package com.gathin.tvhome;

import androidx.leanback.media.PlayerAdapter;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 把 ijkplayer 包成 Leanback 的 PlayerAdapter。 */
public class IjkPlayerAdapter extends PlayerAdapter {

    private final IjkMediaPlayer mp;
    private Callback callback;

    public IjkPlayerAdapter(IjkMediaPlayer player) {
        this.mp = player;
    }

    @Override public void play() { mp.start(); }
    @Override public void pause() { mp.pause(); }
    @Override public void seekTo(long position) { mp.seekTo(position); }
    @Override public boolean isPlaying() { return mp.isPlaying(); }
    @Override public long getCurrentPosition() { return mp.getCurrentPosition(); }
    @Override public long getDuration() { return mp.getDuration(); }
    @Override public long getBufferedPosition() { return mp.getCurrentPosition(); }
    @Override public void setProgressUpdatingEnabled(boolean enabled) { }
    @Override public void setCallback(Callback cb) { callback = cb; }
}