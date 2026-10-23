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
    /** Jellyfin 侧字幕流 + 解析出来的 VTT 字幕。 */
    private final List<JellyfinClient.Sub> jfSubs = new ArrayList<>();
    private List<Vtt.Cue> cues;
    private int curJfSub = -1;
    private int subLog;


    private final Handler handler = new Handler();
    private TextView seekTip;
    private TextView subView;   // 自绘字幕（字号/颜色可调）

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

        // 字幕层：底部居中，字号/颜色走设置
        subView = new TextView(this);
        subView.setTextColor(0xFFFFFFFF);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(dp(18), dp(6), dp(18), dp(6));
        subView.setVisibility(View.GONE);
        subView.setShadowLayer(dp(4), 0, dp(2), 0xE6000000);
        applySubStyle();
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(-2, -2);
        subLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        subLp.bottomMargin = dp(120);
        root.addView(subView, subLp);

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
                final String directId = getIntent().getStringExtra("itemId");
                final String[] t = directId != null && !directId.isEmpty()
                        ? JellyfinClient.itemTarget(directId)
                        : JellyfinClient.playTarget(seriesId);
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
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L);   // H.265 也走硬解
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);         // 卡了先丢帧保同步
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024L);
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L);
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
                    attachSubtitleListener();
                    applyDebugTrack();
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

    /** 按设置调整字幕字号/颜色。 */
    /** 调试用：--ei sub N 直接选中第 N 条字幕，--ei seek ms 跳到指定位置。 */
    private void applyDebugTrack() {
        try {
            final int sub = getIntent().getIntExtra("sub", -1);
            final int seek = getIntent().getIntExtra("seek", -1);
            if (seek >= 0) player.seekTo(seek);
            if (sub >= 0) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            if (sub < jfSubs.size()) {
                                loadJfSubtitle(jfSubs.get(sub).index);
                                Log.i(TAG, "debug: picked jf sub " + sub);
                            } else if (sub < subIdx.size()) {
                                player.selectTrack(subIdx.get(sub));
                                curSub = subIdx.get(sub);
                            }
                        } catch (Throwable ignored) { }
                    }
                }, 1500L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyDebugTrack failed " + t);
        }
    }


    private void applySubStyle() {
        int size = TVDeskPrefs.subSize(this);
        int colorIdx = TVDeskPrefs.subColor(this);
        int dbgS = getIntent().getIntExtra("subsize", -1);
        int dbgC = getIntent().getIntExtra("subcolor", -1);
        if (dbgS >= 0) { size = dbgS; TVDeskPrefs.get(this).edit().putInt(TVDeskPrefs.KEY_SUB_SIZE, dbgS).apply(); }
        if (dbgC >= 0) { colorIdx = dbgC; TVDeskPrefs.get(this).edit().putInt(TVDeskPrefs.KEY_SUB_COLOR, dbgC).apply(); }
        int sp = size == 0 ? 22 : size == 2 ? 40 : 30;
        int color = colorIdx == 0 ? 0xFFFFFFFF : 0xFFFFEB3B;
        Log.i(TAG, "sub style sp=" + sp + " color=" + Integer.toHexString(color) + " (size=" + size + ", dbg=" + dbgS + "/" + dbgC + ")");
        if (subView != null) {
            subView.setTextSize(sp);
            subView.setTextColor(color);
        }
    }

    /** 拿 ijk 的字幕文本自己画，这样字号/颜色可控。 */
    private void attachSubtitleListener() {
        try {
            if (player == null) return;
            player.setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override public void onTimedText(IMediaPlayer mp, tv.danmaku.ijk.media.player.IjkTimedText text) {
                    final String t = text == null ? null : text.getText();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (subView == null) return;
                            if (t == null || t.trim().isEmpty()) {
                                subView.setVisibility(View.GONE);
                            } else {
                                subView.setText(t);
                                subView.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "subtitle listener failed " + t);
        }
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
        loadJfSubtitles();
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
        for (JellyfinClient.Sub s : jfSubs) names.add(s.label());
        if (jfSubs.isEmpty()) {
            names.addAll(subLabels);
            if (subLabels.isEmpty()) names.add("没有可用字幕");
        }
        final int trackCount = names.size();
        // 末尾两项：播放样式里的字幕大小 / 颜色
        names.add("字幕大小：" + new String[]{"小", "中", "大"}[TVDeskPrefs.subSize(this)]);
        names.add("字幕颜色：" + (TVDeskPrefs.subColor(this) == 0 ? "白色" : "黄色"));

        showMenu("字幕", names, new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos == trackCount) {
                    int next = (TVDeskPrefs.subSize(JellyfinPlayerActivity.this) + 1) % 3;
                    TVDeskPrefs.get(JellyfinPlayerActivity.this).edit()
                            .putInt(TVDeskPrefs.KEY_SUB_SIZE, next).apply();
                    applySubStyle();
                    hideMenu();
                    handler.postDelayed(new Runnable() { @Override public void run() { showSubtitleMenu(); } }, 120);
                    return;
                }
                if (pos == trackCount + 1) {
                    int next = TVDeskPrefs.subColor(JellyfinPlayerActivity.this) == 0 ? 1 : 0;
                    TVDeskPrefs.get(JellyfinPlayerActivity.this).edit()
                            .putInt(TVDeskPrefs.KEY_SUB_COLOR, next).apply();
                    applySubStyle();
                    hideMenu();
                    handler.postDelayed(new Runnable() { @Override public void run() { showSubtitleMenu(); } }, 120);
                    return;
                }
                if (pos == 0) {
                    if (curSub >= 0) { try { player.deselectTrack(curSub); } catch (Throwable ignored) { } }
                    curSub = -1; curJfSub = -1; cues = null;
                    if (subView != null) subView.setVisibility(View.GONE);
                } else if (!jfSubs.isEmpty() && pos - 1 < jfSubs.size()) {
                    loadJfSubtitle(jfSubs.get(pos - 1).index);
                } else if (pos - 1 < subIdx.size()) {
                    int i = subIdx.get(pos - 1); player.selectTrack(i); curSub = i;
                }
                hideMenu();
            }
        });
    }


    /** 后台拉 Jellyfin 的字幕列表。 */
    private void loadJfSubtitles() {
        final String id = itemId;
        if (id == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<JellyfinClient.Sub> list = JellyfinClient.subtitleStreams(id);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        jfSubs.clear();
                        jfSubs.addAll(list);
                        Log.i(TAG, "jellyfin subs=" + jfSubs.size());
                    }
                });
            }
        }, "cyt-sub-list").start();
    }

    /** 拉某条字幕的 VTT 并解析。 */
    private void loadJfSubtitle(final int index) {
        final String id = itemId;
        if (id == null) return;
        curJfSub = index;
        if (subView != null) subView.setText("字幕加载中…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String vtt = JellyfinClient.subtitleVtt(id, index);
                final List<Vtt.Cue> parsed = Vtt.parse(vtt);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        cues = parsed;
                        Log.i(TAG, "subtitle cues=" + parsed.size());
                        if (subView != null) {
                            subView.setText("");
                            subView.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "cyt-sub-load").start();
        handler.removeCallbacks(subTick);
        handler.post(subTick);
    }

    /** 每 200ms 按播放位置刷新字幕。 */
    private final Runnable subTick = new Runnable() {
        @Override public void run() {
            try {
                if (player != null && cues != null && subView != null) {
                    String t = Vtt.textAt(cues, player.getCurrentPosition());
                    if ((subLog++ % 25) == 0) Log.i(TAG, "sub pos=" + player.getCurrentPosition() + " text=" + t + " cues=" + (cues == null ? -1 : cues.size()));
                    if (t == null) {
                        subView.setVisibility(View.GONE);
                    } else {
                        subView.setText(t);
                        subView.setVisibility(View.VISIBLE);
                    }
                }
            } catch (Throwable ignored) { }
            handler.postDelayed(this, 200);
        }
    };


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
