package com.gathin.tvhome;

import android.util.Log;
import android.view.Surface;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 桌面直播预览（静音）。 */
public final class LivePreview {

    private static final String TAG = "CYT-LIVE";

    private IjkMediaPlayer player;
    private String url;

    public void play(String u, Surface surface) {
        if (u == null || u.equals(url)) { if (player != null && surface != null) player.setSurface(surface); return; }
        release();
        url = u;
        try {
            IjkMediaPlayer p = new IjkMediaPlayer();
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            // HEVC/其他编码也走硬解，否则 1080p H.265 只能软解（10~15fps）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L);
            // 允许缓冲，减少网络抖动导致的卡顿（原来 nobuffer 太激进）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 8L * 1024 * 1024);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1500000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512L * 1024);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);
            // 断流自动重连（免费源经常抽风）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 2L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 10000000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rw_timeout", 10000000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "loop", 1L);
            p.setVolume(0f, 0f);
            if (surface != null) p.setSurface(surface);
            p.setDataSource(url);
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) { mp.start(); }
            });
            p.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override public boolean onError(IMediaPlayer mp, int w, int e) {
                    Log.w(TAG, "preview error " + w + "/" + e);
                    return true;
                }
            });
            p.prepareAsync();
            player = p;
        } catch (Throwable t) {
            Log.e(TAG, "preview failed " + t);
        }
    }

    /** 桌面不可见时释放解码器（否则进直播全屏会两个解码器抢资源）。 */
    public void stop() {
        release();
    }

    public void release() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        url = null;
    }
}