package com.gathin.tvhome;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 直播全屏：UP/DOWN 换台，OK 打开分类选台（左分类 / 右频道），数字键跳台。 */
public class LivePlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "CYT-LIVE";

    private SurfaceView surfaceView;
    private TextView osd;
    private IjkMediaPlayer player;
    private List<LiveSource.Ch> channels = new ArrayList<>();
    private LinkedHashMap<String, List<LiveSource.Ch>> cats = new LinkedHashMap<>();
    private String curCat = "全部";
    private int index = -1;
    private int jump;
    private int retry;
    /** 换台预加载：提前 prepare 相邻频道，切过去时直接 start。 */
    private IjkMediaPlayer spare;
    private int spareIndex = -1;
    private boolean spareReady;

    /** 失效频道（url 集合），播放失败后记下来，列表里标 ✕。 */
    private final java.util.Set<String> deadChannels = new java.util.HashSet<>();

    private String wantName;
    private String autoPrefix;   // 自动换源用：频道名前缀
    private int autoTries, autoSkip;
    private long autoStartMs;

    private LinearLayout panel;
    private ListView catList;
    private ListView chList;
    private boolean panelShown;

    private final Handler handler = new Handler();

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
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(28);
        osd.setPadding(dp(30), dp(14), dp(30), dp(14));
        osd.setBackgroundColor(0xAA000000);
        osd.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(-2, -2);
        olp.gravity = Gravity.TOP | Gravity.START;
        olp.setMargins(dp(36), dp(36), 0, 0);
        root.addView(osd, olp);

        buildPanel(root);
        setContentView(root);

        index = getIntent().getIntExtra("index", -1);
        wantName = getIntent().getStringExtra("name");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<LiveSource.Ch> list = LiveSource.load(getApplicationContext());
                deadChannels.addAll(getSharedPreferences("tvdesk_dead", MODE_PRIVATE).getStringSet("urls", new java.util.HashSet<String>()));
                final LinkedHashMap<String, List<LiveSource.Ch>> map = LiveSource.categories(list);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        channels = list;
                        cats = map;
                        if (channels.isEmpty()) { showOsd("频道加载失败"); return; }
                        if (wantName != null) { for (int k = 0; k < channels.size(); k++) { if (channels.get(k).name.contains(wantName)) { index = k; break; } } }
                        if (index < 0 || index >= channels.size()) index = LiveSource.jade(channels);
                        if (index < 0) index = 0;
                        fillCats();
                        startPlay();
                    }
                });
            }
        }, "cyt-live").start();
    }

    // ---------------- 分类选台面板 ----------------

    private void buildPanel(FrameLayout root) {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackgroundColor(0xF20B0F14);
        panel.setVisibility(View.GONE);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));

        catList = new ListView(this);
        catList.setBackgroundColor(0xFF131C26);
        catList.setSelector(new android.graphics.drawable.ColorDrawable(0x5500A4DC));
        catList.setDrawSelectorOnTop(false);
        catList.setBackgroundColor(Color.TRANSPARENT);
        panel.addView(catList, new LinearLayout.LayoutParams(dp(220), -1));

        chList = new ListView(this);
        chList.setSelector(new android.graphics.drawable.ColorDrawable(0x5500A4DC));
        chList.setDrawSelectorOnTop(false);
        chList.setBackgroundColor(0xFF101820);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
        clp.leftMargin = dp(14);
        panel.addView(chList, clp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(panel, plp);
    }

    private void fillCats() {
        List<String> names = new ArrayList<>(cats.keySet());
        catList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int p, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(p, cv, parent);
                tv.setTextColor(0xFFDCE7F2);
                tv.setTextSize(17);
                tv.setPadding(dp(12), dp(16), dp(12), dp(16));
                return tv;
            }
        });
        catList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                curCat = (String) p.getItemAtPosition(pos);
                fillChannels();
                chList.requestFocus();
            }
        });
        catList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                curCat = (String) p.getItemAtPosition(pos);
                fillChannels();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        curCat = names.isEmpty() ? "全部" : names.get(0);
        fillChannels();
    }

    private void fillChannels() {
        List<LiveSource.Ch> list = cats.get(curCat);
        if (list == null) list = channels;
        List<String> names = new ArrayList<>();
        for (LiveSource.Ch c : list) names.add((deadChannels.contains(c.url) ? "✕ " : "") + c.name);
        final List<LiveSource.Ch> shown = list;
        chList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int p, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(p, cv, parent);
                tv.setTextColor(0xFFDCE7F2);
                tv.setTextSize(16);
                tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                return tv;
            }
        });
        chList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                LiveSource.Ch c = shown.get(pos);
                int idx = channels.indexOf(c);
                if (idx >= 0) index = idx;
                hidePanel();
                startPlay();
            }
        });
    }

    private void showPanel() {
        if (channels.isEmpty()) return;
        panelShown = true;
        panel.setVisibility(View.VISIBLE);
        catList.requestFocus();
        handler.removeCallbacks(hideOsd);
        osd.setVisibility(View.GONE);
    }

    private void hidePanel() {
        panelShown = false;
        panel.setVisibility(View.GONE);
        surfaceView.requestFocus();
    }

    // ---------------- 播放 ----------------

    private void startPlay() {
        // 命中预加载的相邻频道：省掉握手/探测时间
        if (spare != null && spareReady && spareIndex == index) {
            IjkMediaPlayer old = player;
            player = spare;
            spare = null; spareIndex = -1; spareReady = false;
            if (old != null) { try { old.stop(); } catch (Throwable ignored) { } try { old.release(); } catch (Throwable ignored) { } }
            try {
                player.setDisplay(surfaceView.getHolder());
                player.start();
                retry = 0;
                Log.i(TAG, "preload hit #" + index);
                handler.removeCallbacks(statTick);
                handler.postDelayed(statTick, 2000);
                preloadNeighbor();
                return;
            } catch (Throwable ignored) { }
        }
        if (channels.isEmpty() || index < 0 || index >= channels.size()) return;
        LiveSource.Ch ch = channels.get(index);
        autoPrefix = null;   // 先关掉自动换源（会选到 4K 源）
        autoStartMs = System.currentTimeMillis();
        String epg = null;
        try { epg = Epg.now(getApplicationContext(), ch.name); } catch (Throwable ignored) { }
        showOsd((index + 1) + "  " + ch.name + (ch.group.isEmpty() ? "" : "  ·  " + ch.group)
                + (epg == null ? "" : "`n" + epg));
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        try {
            IjkMediaPlayer p = new IjkMediaPlayer();
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            // HEVC/其他编码也走硬解，否则 1080p H.265 只能软解（10~15fps）
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L);
            // 缓冲优先：直播源抖动大，nobuffer 会一直卡
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 12L * 1024 * 1024);
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
            p.setDisplay(surfaceView.getHolder());
            p.setDataSource(ch.url);
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) { retry = 0; mp.start(); handler.removeCallbacks(statTick); handler.postDelayed(statTick, 2000); }
            });
            p.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override public boolean onError(IMediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "error " + what + "/" + extra);
                    // 源站断流：自动重试，最多 4 次
                    if (retry < 4) {
                        retry++;
                        showOsd("重新连接中… (" + retry + "/4)");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() { startPlay(); }
                        }, 1200L);
                    } else {
                        showOsd("播放失败（" + what + "）");
                        markDead(channels.get(index).url);
                    }
                    return true;
                }
            });
            p.prepareAsync();
            player = p;
            handler.postDelayed(new Runnable() { @Override public void run() { preloadNeighbor(); } }, 3000L);
        } catch (Throwable t) { Log.e(TAG, "start failed " + t); }
    }

    /** 找下一个同名（同前缀）的频道，exclude 是已经试过的次数偏移。 */
    private int findNextSource(String prefix, int from, int skip) {
        if (channels == null || prefix == null) return -1;
        int seen = 0;
        for (int k = from + 1; k < channels.size(); k++) {
            String n = channels.get(k).name;
            if (n != null && n.startsWith(prefix)) {
                seen++;
                if (seen == skip) return k;
            }
        }
        return -1;
    }


    /** 预加载下一个频道（不下发画面，只把连接/解封装/首帧准备好）。 */
    private void preloadNeighbor() {
        try {
            if (channels == null || channels.size() < 2) return;
            int next = index + 1;
            if (next >= channels.size()) next = 0;
            if (next == index) return;
            if (spare != null && spareIndex == next) return;
            releaseSpare();
            final int target = next;
            final IjkMediaPlayer p = new IjkMediaPlayer();
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 8L * 1024 * 1024);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1500000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512L * 1024);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 10000000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L);
            p.setVolume(0f, 0f);
            p.setDataSource(channels.get(target).url);
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) {
                    if (spare == p) {
                        spareReady = true;
                        Log.i(TAG, "preload ready #" + target);
                    }
                }
            });
            p.prepareAsync();
            spare = p;
            spareIndex = target;
            spareReady = false;
        } catch (Throwable t) {
            Log.w(TAG, "preload failed " + t);
        }
    }

    private void releaseSpare() {
        if (spare != null) {
            try { spare.stop(); } catch (Throwable ignored) { }
            try { spare.release(); } catch (Throwable ignored) { }
            spare = null;
        }
        spareIndex = -1;
        spareReady = false;
    }


    private void markDead(String url) {
        try {
            deadChannels.add(url);
            java.util.Set<String> copy = new java.util.HashSet<>(deadChannels);
            getSharedPreferences("tvdesk_dead", MODE_PRIVATE).edit()
                    .putStringSet("urls", copy).apply();
        } catch (Throwable ignored) { }
    }


    private void showOsd(String text) {
        osd.setText(text);
        osd.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 3000);
    }

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() { osd.setVisibility(View.GONE); }
    };

    /** 诊断：每 2 秒打印解码/缓冲状态（排查卡顿用）。 */
    private final Runnable statTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            try {
                Log.i("CYT-STAT", "res=" + player.getVideoWidth() + "x" + player.getVideoHeight()
                        + " dec=" + player.getVideoDecoder()
                        + " decFps=" + player.getVideoDecodeFramesPerSecond()
                        + " outFps=" + player.getVideoOutputFramesPerSecond()
                        + " vCache=" + player.getVideoCachedDuration() + "ms"
                        + " aCache=" + player.getAudioCachedDuration() + "ms");
                // 解码明显跟不上（<18fps）时，自动换同名频道里更流畅的源
                if (autoPrefix != null && autoTries < 2
                        && System.currentTimeMillis() - autoStartMs > 10000
                        && player.getVideoDecodeFramesPerSecond() < 18) {
                    autoTries++;
                    int next = findNextSource(autoPrefix, index, ++autoSkip);
                    if (next >= 0) {
                        Log.w("CYT-STAT", "too slow, switching to index " + next);
                        index = next;
                        autoStartMs = System.currentTimeMillis();
                        startPlay();
                    }
                }
            } catch (Throwable ignored) { }
            handler.postDelayed(this, 2000);
        }
    };

    private final Runnable commitJump = new Runnable() {
        @Override public void run() {
            int n = jump;
            jump = 0;
            if (n > 0) { index = n - 1; startPlay(); }
        }
    };

    @Override public void surfaceCreated(SurfaceHolder h) { if (!channels.isEmpty()) startPlay(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (panelShown) {
            if (code == KeyEvent.KEYCODE_BACK) { hidePanel(); return true; }
            return super.onKeyDown(code, e);
        }
        if (e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_DPAD_UP) { index--; startPlay(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { index++; startPlay(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) { showPanel(); return true; }
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            jump = jump * 10 + (code - KeyEvent.KEYCODE_0);
            showOsd("跳转: " + jump);
            handler.removeCallbacks(commitJump);
            handler.postDelayed(commitJump, 1500);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onStop() {
        handler.removeCallbacks(statTick);
        releaseSpare();
        super.onStop();
        // 离开直播页就释放解码器，避免和桌面预览抢硬件
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }


    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        super.onDestroy();
    }
}
