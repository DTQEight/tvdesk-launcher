package com.gathin.tvhome;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.leanback.app.PlaybackSupportFragment;
import androidx.leanback.app.PlaybackSupportFragmentGlueHost;

import java.util.ArrayList;
import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/**
 * 播放页：UI 交给 androidx.leanback 的 PlaybackSupportFragment（Jellyfin 用的同一套组件），
 * 播放器仍是内置的 ijkplayer。
 */
public class JellyfinPlayerActivity extends FragmentActivity
        implements SurfaceHolder.Callback, PlayerGlue.Listener {

    private static final String TAG = "CYT-JF";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private SurfaceView surfaceView;
    private IjkMediaPlayer player;
    private IjkPlayerAdapter adapter;
    private PlayerGlue glue;
    private PlaybackSupportFragment fragment;

    private String seriesId, title, itemId, streamUrl;
    private long startMs, durationMs;
    private float speed = 1.0f;

    private LinearLayout sidePanel;
    private TextView panelTitle;
    private ListView panelList;
    private boolean panelShown;

    private TextView nextTip;
    private int pendingNext = -1, countdown;

    private final List<String[]> episodes = new ArrayList<>();
    private final List<Integer> audioIdx = new ArrayList<>();
    private final List<String> audioLabels = new ArrayList<>();
    private final List<Integer> subIdx = new ArrayList<>();
    private final List<String> subLabels = new ArrayList<>();
    private int curSub = -1, curEp = -1;

    private final Handler handler = new Handler();
    private TextView seekTip;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));

        seekTip = new TextView(this);
        seekTip.setTextColor(Color.WHITE);
        seekTip.setTextSize(30);
        seekTip.setPadding(dp(28), dp(14), dp(28), dp(14));
        seekTip.setBackgroundColor(0xCC000000);
        seekTip.setVisibility(View.GONE);
        FrameLayout.LayoutParams stlp = new FrameLayout.LayoutParams(-2, -2);
        stlp.gravity = Gravity.CENTER;
        root.addView(seekTip, stlp);

        buildSidePanel(root);
        setContentView(root);

        fragment = new PlaybackSupportFragment();
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(container.getId(), fragment);
        tx.commitNow();

        player = new IjkMediaPlayer();
        adapter = new IjkPlayerAdapter(player);
        glue = new PlayerGlue(this, adapter, this);
        glue.setHost(new PlaybackSupportFragmentGlueHost(fragment));
        glue.setTitle("");
        glue.forceStart();
        Log.i(TAG, "host set: gluePrepared=" + glue.isPrepared()
                + " adapterCb=" + (adapter.getCallback() != null)
                + " adapterPrepared=" + adapter.isPrepared());
        glue.setSubtitle("");

        seriesId = getIntent().getStringExtra("seriesId");
        title = getIntent().getStringExtra("title");

        new Thread(new Runnable() {
            @Override public void run() {
                final String[] t = JellyfinClient.playTarget(seriesId);
                final List<String[]> eps = seriesId == null
                        ? new ArrayList<String[]>() : JellyfinClient.episodes(seriesId);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        episodes.clear();
                        episodes.addAll(eps);
                        if (t == null) { glue.setTitle("没找到可播放的剧集"); return; }
                        playEpisode(t[0], t[1], Long.parseLong(t[2]));
                    }
                });
            }
        }, "cyt-vod").start();
    }

    // ---------------- 播放 ----------------

    private void playEpisode(String id, String label, long posMs) {
        cancelAutoNext();
        curEp = -1;
        for (int k = 0; k < episodes.size(); k++) if (episodes.get(k)[0].equals(id)) { curEp = k; break; }
        itemId = id;
        startMs = posMs;
        streamUrl = JellyfinClient.streamUrl(id);
        glue.setTitle(title == null || title.isEmpty() ? label : title);
        glue.setSubtitle(epTag(label));
        prepare();
    }

    private String epTag(String label) {
        String s = label == null ? "" : label;
        int sp = s.indexOf("  ");
        return sp > 0 ? s.substring(0, sp) : s;
    }

    private void prepare() {
        try {
            player.reset();
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 524288L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "subtitle", 1L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", JellyfinClient.authHeader());
            player.setDisplay(surfaceView.getHolder());
            player.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) {
                    durationMs = mp.getDuration() > 0 ? mp.getDuration() : 0;
                    if (speed != 1.0f) player.setSpeed(speed);
                    if (startMs > 0) mp.seekTo(startMs);
                    Log.i(TAG, "onPrepared: dur=" + mp.getDuration()
                            + " adapterPrepared=" + adapter.isPrepared()
                            + " cb=" + (adapter.getCallback() != null)
                            + " gluePrepared=" + glue.isPrepared());
                    androidx.leanback.media.PlayerAdapter.Callback cb = adapter.getCallback();
                    if (cb != null) {
                        cb.onDurationChanged(adapter);
                        cb.onPreparedStateChanged(adapter);
                    }
                    glue.play();
                    if (cb != null) cb.onPlayStateChanged(adapter);
                    scanTracks();
                    final String id = itemId;
                    final long pos = startMs;
                    new Thread(new Runnable() { @Override public void run() { JellyfinClient.reportStart(id, pos); } }).start();
                }
            });
            player.setDataSource(streamUrl);
            player.prepareAsync();
        } catch (Throwable t) { Log.e(TAG, "prepare failed " + t); }
        handler.removeCallbacks(progressTick);
        handler.postDelayed(progressTick, 5000);
    }

    private void scanTracks() {
        audioIdx.clear(); audioLabels.clear(); subIdx.clear(); subLabels.clear();
        curSub = -1;
        try {
            ITrackInfo[] infos = player.getTrackInfo();
            if (infos == null) return;
            int ai = 0, si = 0;
            for (int i = 0; i < infos.length; i++) {
                ITrackInfo t = infos[i];
                if (t == null) continue;
                String lang = t.getLanguage() == null || t.getLanguage().isEmpty() ? "" : t.getLanguage();
                if (t.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    ai++; audioIdx.add(i);
                    audioLabels.add("音轨 " + ai + (lang.isEmpty() ? "" : " · " + lang));
                } else if (t.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                        || t.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    si++; subIdx.add(i);
                    subLabels.add("字幕 " + si + (lang.isEmpty() ? "" : " · " + lang));
                }
            }
        } catch (Throwable t) { Log.e(TAG, "scanTracks " + t); }
    }

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player != null && itemId != null) {
                final String id = itemId;
                final long pos = player.getCurrentPosition();
                final boolean paused = !player.isPlaying();
                new Thread(new Runnable() { @Override public void run() { JellyfinClient.reportProgress(id, pos, paused); } }).start();
            }
            handler.postDelayed(this, 5000);
        }
    };

    // ---------------- PlayerGlue.Listener ----------------

    @Override public void onSubtitles() { showSubtitleMenu(); }
    @Override public void onAudioTrack() { showAudioMenu(); }
    @Override public void onSpeed() { showSpeedMenu(); }
    @Override public void onNext() { playNext(); }
    @Override public void onSeekBack() { seekBy(-15000); }

    @Override public void onSeekForward() { seekBy(15000); }


    @Override public void onSeekFeedback() {
        try {
            long pos = player == null ? 0 : player.getCurrentPosition();
            long dur = player == null ? 0 : player.getDuration();
            showSeekTip(clock(pos) + "  /  " + clock(dur));
        } catch (Throwable ignored) { }
    }

    @Override public void onEpisodes() { showEpisodeMenu(); }

    // ---------------- 菜单 ----------------

    private void buildSidePanel(FrameLayout root) {
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setBackgroundColor(0xF20E141B);
        sidePanel.setPadding(dp(26), dp(24), dp(26), dp(24));
        sidePanel.setVisibility(View.GONE);
        sidePanel.setFocusable(true);

        panelTitle = new TextView(this);
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setTextSize(21);
        panelTitle.setPadding(dp(6), 0, dp(6), dp(14));
        sidePanel.addView(panelTitle);

        panelList = new ListView(this);
        panelList.setBackgroundColor(Color.TRANSPARENT);
        sidePanel.addView(panelList, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(440), -1);
        plp.gravity = Gravity.END;
        root.addView(sidePanel, plp);
    }

    private void showMenu(String heading, List<String> items, AdapterView.OnItemClickListener pick) {
        panelShown = true;
        panelTitle.setText(heading);
        panelList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFDCE7F2);
                tv.setTextSize(17);
                tv.setPadding(dp(12), dp(13), dp(12), dp(13));
                return tv;
            }
        });
        panelList.setOnItemClickListener(pick);
        sidePanel.setVisibility(View.VISIBLE);
        panelList.requestFocus();
    }

    private void hideMenu() {
        panelShown = false;
        sidePanel.setVisibility(View.GONE);
    }

    private void showEpisodeMenu() {
        if (episodes.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (String[] e : episodes) names.add(e[1]);
        showMenu("选集", names, new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                hideMenu();
                String[] e = episodes.get(pos);
                playEpisode(e[0], e[1], e.length > 2 ? Long.parseLong(e[2]) : 0);
            }
        });
    }

    private void showAudioMenu() {
        List<String> names = new ArrayList<>(audioLabels);
        if (names.isEmpty()) names.add("没有可切换的音轨");
        showMenu("音轨", names, new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < audioIdx.size()) player.selectTrack(audioIdx.get(pos));
                hideMenu();
            }
        });
    }

    private void showSubtitleMenu() {
        List<String> names = new ArrayList<>();
        names.add("关闭字幕");
        names.addAll(subLabels);
        if (subLabels.isEmpty()) names.add("没有内嵌字幕");
        showMenu("字幕", names, new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) { if (curSub >= 0) player.deselectTrack(curSub); curSub = -1; }
                else if (pos - 1 < subIdx.size()) { int i = subIdx.get(pos - 1); player.selectTrack(i); curSub = i; }
                hideMenu();
            }
        });
    }

    private void showSpeedMenu() {
        List<String> names = new ArrayList<>();
        for (float s : SPEEDS) names.add(s + "x");
        showMenu("播放速度", names, new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                speed = SPEEDS[pos];
                player.setSpeed(speed);
                hideMenu();
            }
        });
    }

    // ---------------- 下一集 ----------------

    private void playNext() {
        cancelAutoNext();
        if (episodes.isEmpty()) return;
        int next = curEp + 1;
        if (curEp < 0 || next >= episodes.size()) { glue.setSubtitle("已经是最后一集了"); return; }
        String[] e = episodes.get(next);
        playEpisode(e[0], e[1], 0);
    }

    private void onPlaybackFinished() {
        if (episodes.isEmpty() || curEp < 0 || curEp + 1 >= episodes.size()) { finish(); return; }
        pendingNext = curEp + 1;
        countdown = 8;
        if (nextTip == null) {
            nextTip = new TextView(this);
            nextTip.setTextColor(Color.WHITE);
            nextTip.setTextSize(19);
            nextTip.setGravity(Gravity.CENTER);
            nextTip.setPadding(dp(40), dp(24), dp(40), dp(24));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6101820);
            bg.setCornerRadius(dp(16));
            nextTip.setBackground(bg);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
            nlp.gravity = Gravity.CENTER;
            ((ViewGroup) findViewById(android.R.id.content)).addView(nextTip, nlp);
        }
        nextTip.setVisibility(View.VISIBLE);
        tickCountdown();
    }

    private void tickCountdown() {
        if (pendingNext < 0 || pendingNext >= episodes.size()) { cancelAutoNext(); return; }
        nextTip.setText(countdown + " 秒后播放下一集\n" + episodes.get(pendingNext)[1] + "\n\n按 OK 立即播放");
        if (countdown <= 0) {
            int n = pendingNext; pendingNext = -1;
            nextTip.setVisibility(View.GONE);
            String[] e = episodes.get(n);
            playEpisode(e[0], e[1], 0);
            return;
        }
        countdown--;
        handler.postDelayed(countdownTick, 1000);
    }

    private final Runnable countdownTick = new Runnable() {
        @Override public void run() { tickCountdown(); }
    };

    private void cancelAutoNext() {
        pendingNext = -1;
        handler.removeCallbacks(countdownTick);
        if (nextTip != null) nextTip.setVisibility(View.GONE);
    }

    // ---------------- 生命周期 ----------------

    @Override public void surfaceCreated(SurfaceHolder h) { if (streamUrl != null) prepare(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { }

    @Override public void onBackPressed() {
        if (pendingNext >= 0) { finish(); return; }
        if (panelShown) { hideMenu(); return; }
        super.onBackPressed();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------------- 遥控器播放控制 ----------------

    /**
     * 控制条隐藏时：左右 = ±15 秒，OK = 播放/暂停，上下 = 唤出控制条。
     * 控制条已显示时交给 Leanback 自己处理（这样按钮和进度条都还能操作）。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && !panelShown
                && fragment != null && !fragment.isControlsOverlayVisible()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seekBy(-15000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seekBy(15000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    fragment.showControlsOverlay(true);
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void togglePlay() {
        try {
            if (player == null) return;
            if (player.isPlaying()) player.pause();
            else player.start();
            showSeekTip(player.isPlaying() ? "▶" : "❚❚");
        } catch (Throwable ignored) { }
    }

    /** 相对当前位置跳转（毫秒）。 */
    private void seekBy(long delta) {
        try {
            if (player == null) return;
            long dur = player.getDuration();
            long pos = player.getCurrentPosition() + delta;
            if (pos < 0) pos = 0;
            if (dur > 0 && pos > dur - 500) pos = Math.max(0, dur - 500);
            player.seekTo(pos);
            showSeekTip((delta < 0 ? "« " : "» ") + clock(pos) + "  /  " + clock(dur));
        } catch (Throwable ignored) { }
    }

    private void showSeekTip(String text) {
        if (seekTip == null) return;
        seekTip.setText(text);
        seekTip.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekTip);
        handler.postDelayed(hideSeekTip, 1200);
    }

    private final Runnable hideSeekTip = new Runnable() {
        @Override public void run() { if (seekTip != null) seekTip.setVisibility(View.GONE); }
    };

    private static String clock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                : String.format("%d:%02d", m, sec);
    }



    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null && itemId != null) {
            final String id = itemId;
            final long pos = player.getCurrentPosition();
            try { player.stop(); } catch (Throwable ignored) { }
            new Thread(new Runnable() { @Override public void run() { JellyfinClient.reportStop(id, pos); } }).start();
        }
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        super.onDestroy();
    }
}
