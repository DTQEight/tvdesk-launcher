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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/** 播放页：按 Jellyfin（Leanback）的样子做 —— 双行标题、细进度条、圆形图标按钮。 */
public class JellyfinPlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "CYT-JF";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int ACCENT = 0xFF00A4DC;

    private SurfaceView surfaceView;
    private IjkMediaPlayer player;
    private String seriesId, title, itemId, streamUrl;
    private long startMs, durationMs;
    private boolean seeking;
    private float speed = 1.0f;

    private FrameLayout overlay;
    private TextView titleView, subView, timeLeft, timeRight;
    private SeekBar seekBar;
    private ImageView playIcon;
    private final List<View> buttons = new ArrayList<>();
    private int sel = 0;

    private LinearLayout sidePanel;
    private TextView panelTitle;
    private ListView panelList;
    private boolean panelShown;

    private final List<String[]> episodes = new ArrayList<>();
    private final List<Integer> audioIdx = new ArrayList<>();
    private final List<String> audioLabels = new ArrayList<>();
    private final List<Integer> subIdx = new ArrayList<>();
    private final List<String> subLabels = new ArrayList<>();
    private int curSub = -1, curEp = -1, pendingNext = -1, countdown;
    private TextView nextTip;

    private final Handler handler = new Handler();

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

        buildOverlay(root);
        buildSidePanel(root);
        setContentView(root);

        seriesId = getIntent().getStringExtra("seriesId");
        title = getIntent().getStringExtra("title");
        showOverlay();

        new Thread(new Runnable() {
            @Override public void run() {
                final String[] t = JellyfinClient.playTarget(seriesId);
                final List<String[]> eps = seriesId == null
                        ? new ArrayList<String[]>() : JellyfinClient.episodes(seriesId);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        episodes.clear();
                        episodes.addAll(eps);
                        if (t == null) { setTitle("没找到可播放的剧集", ""); return; }
                        playEpisode(t[0], t[1], Long.parseLong(t[2]));
                    }
                });
            }
        }, "cyt-vod").start();
    }

    // ---------------- 界面 ----------------

    private void buildOverlay(FrameLayout root) {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x73000000);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(56), dp(60), dp(56), dp(30));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF0B0F14, 0x00000000}));

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(25);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(10, 0, 2, Color.BLACK);
        bottom.addView(titleView);

        subView = new TextView(this);
        subView.setTextColor(0xFF9FB0C0);
        subView.setTextSize(14);
        subView.setPadding(0, dp(4), 0, dp(14));
        bottom.addView(subView);

        // 时间 + 进度条
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);

        timeLeft = new TextView(this);
        timeLeft.setTextColor(0xFFE2E8F0);
        timeLeft.setTextSize(13);
        timeLeft.setMinWidth(dp(60));
        barRow.addView(timeLeft);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setFocusable(false);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x59FFFFFF));
        seekBar.setThumb(getDrawable(R.drawable.seek_thumb));
        seekBar.setSplitTrack(false);
        seekBar.setPadding(dp(0), 0, dp(0), 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(16), 1f);
        slp.leftMargin = dp(12); slp.rightMargin = dp(12);
        barRow.addView(seekBar, slp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (u && durationMs > 0) {
                    startMs = durationMs * p / 1000;
                    timeLeft.setText(fmt(startMs));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (player != null) player.seekTo(startMs);
            }
        });

        timeRight = new TextView(this);
        timeRight.setTextColor(0xFF9FB0C0);
        timeRight.setTextSize(13);
        timeRight.setMinWidth(dp(60));
        timeRight.setGravity(Gravity.END);
        barRow.addView(timeRight);
        bottom.addView(barRow);

        // 按钮行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        row.addView(iconBtn(R.drawable.ic_p_rew10, new Runnable() {
            @Override public void run() { seekBy(-10000); } }));
        playIcon = (ImageView) ((FrameLayout) iconBtn(R.drawable.ic_p_pause, new Runnable() {
            @Override public void run() { togglePlay(); } })).getChildAt(0);
        row.addView(buttons.get(buttons.size() - 1));
        row.addView(iconBtn(R.drawable.ic_p_fwd10, new Runnable() {
            @Override public void run() { seekBy(10000); } }));
        row.addView(iconBtn(R.drawable.ic_p_sub, new Runnable() {
            @Override public void run() { showSubtitleMenu(); } }));
        row.addView(iconBtn(R.drawable.ic_p_audio, new Runnable() {
            @Override public void run() { showAudioMenu(); } }));
        row.addView(iconBtn(R.drawable.ic_p_speed, new Runnable() {
            @Override public void run() { showSpeedMenu(); } }));
        row.addView(iconBtn(R.drawable.ic_p_next, new Runnable() {
            @Override public void run() { playNext(); } }));
        row.addView(iconBtn(R.drawable.ic_p_list, new Runnable() {
            @Override public void run() { showEpisodeMenu(); } }));
        bottom.addView(row);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2);
        blp.gravity = Gravity.BOTTOM;
        overlay.addView(bottom, blp);
    }

    private View iconBtn(int iconRes, final Runnable action) {
        final FrameLayout box = new FrameLayout(this);
        box.setBackground(circle(false));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(28), dp(28));
        ilp.gravity = Gravity.CENTER;
        box.addView(iv, ilp);
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(circle(has));
                if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() > 0) {
                    ((ImageView) ((FrameLayout) v).getChildAt(0)).setColorFilter(has ? 0xFF101418 : 0xFFFFFFFF);
                }
                if (has) { sel = buttons.indexOf(v); v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start(); }
                else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
        });
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); showOverlay(); }
        });
        buttons.add(box);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58));
        lp.leftMargin = dp(10); lp.rightMargin = dp(10);
        box.setLayoutParams(lp);
        return box;
    }

    private GradientDrawable circle(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(focused ? 0xFFFFFFFF : 0x26FFFFFF);
        return d;
    }


    private void buildSidePanel(FrameLayout root) {
        sidePanel = new LinearLayout(this);
        sidePanel.setOrientation(LinearLayout.VERTICAL);
        sidePanel.setBackgroundColor(0xF20E141B);
        sidePanel.setPadding(dp(26), dp(24), dp(26), dp(24));
        sidePanel.setVisibility(View.GONE);

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
        handler.removeCallbacks(hideOverlay);
    }

    private void hideMenu() {
        panelShown = false;
        sidePanel.setVisibility(View.GONE);
        showOverlay();
    }

    // ---------------- 菜单 ----------------

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
                if (pos < audioIdx.size() && player != null) player.selectTrack(audioIdx.get(pos));
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
                if (player != null) {
                    if (pos == 0) { if (curSub >= 0) player.deselectTrack(curSub); curSub = -1; }
                    else if (pos - 1 < subIdx.size()) { int i = subIdx.get(pos - 1); player.selectTrack(i); curSub = i; }
                }
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
                if (player != null) player.setSpeed(speed);
                hideMenu();
            }
        });
    }

    // ---------------- 播放 ----------------

    private void playEpisode(String id, String label, long posMs) {
        cancelAutoNext();
        curEp = -1;
        for (int k = 0; k < episodes.size(); k++) if (episodes.get(k)[0].equals(id)) { curEp = k; break; }
        itemId = id; startMs = posMs;
        streamUrl = JellyfinClient.streamUrl(id);
        setTitle(buildTitle(label), buildSub(label));
        startPlay();
        showOverlay();
    }

    private void setTitle(String t, String sub) {
        if (titleView != null) titleView.setText(t);
        if (subView != null) subView.setText(sub);
    }

    private String buildTitle(String label) {
        if (title == null || title.isEmpty()) return label == null ? "" : label;
        return title;
    }

    private String buildSub(String label) {
        String tag = label == null ? "" : label;
        int sp = tag.indexOf("  ");
        if (sp > 0) tag = tag.substring(0, sp);
        return tag;
    }

    private void startPlay() {
        if (streamUrl == null) return;
        stopPlayer();
        try {
            IjkMediaPlayer p = new IjkMediaPlayer();
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 524288L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "subtitle", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L);
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", JellyfinClient.authHeader());
            p.setDisplay(surfaceView.getHolder());
            p.setDataSource(streamUrl);
            p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(IMediaPlayer mp) {
                    durationMs = mp.getDuration() > 0 ? mp.getDuration() : 0;
                    if (speed != 1.0f) ((IjkMediaPlayer) mp).setSpeed(speed);
                    if (startMs > 0) mp.seekTo(startMs);
                    mp.start();
                    scanTracks();
                    final String id = itemId;
                    final long pos = startMs;
                    new Thread(new Runnable() { @Override public void run() { JellyfinClient.reportStart(id, pos); } }).start();
                    updateProgress();
                }
            });
            p.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override public boolean onError(IMediaPlayer mp, int what, int extra) {
                    setTitle("播放失败", what + "/" + extra);
                    return true;
                }
            });
            p.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(IMediaPlayer mp) { onPlaybackFinished(); }
            });
            p.prepareAsync();
            player = p;
        } catch (Throwable t) { Log.e(TAG, "start failed " + t); }
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
            updateProgress();
            handler.postDelayed(this, 5000);
        }
    };

    private void updateProgress() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) durationMs = dur;
        startMs = pos;
        if (!seeking && durationMs > 0) seekBar.setProgress((int) (1000L * pos / durationMs));
        timeLeft.setText(fmt(pos));
        timeRight.setText(fmt(durationMs));
        if (playIcon != null) playIcon.setImageResource(
                player.isPlaying() ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    private void seekBy(long delta) {
        if (player == null) return;
        long t = player.getCurrentPosition() + delta;
        if (t < 0) t = 0;
        if (durationMs > 0 && t > durationMs) t = durationMs;
        player.seekTo(t);
        updateProgress();
        showOverlay();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.start();
        updateProgress();
        showOverlay();
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }

    // ---------------- 下一集 ----------------

    private void playNext() {
        cancelAutoNext();
        if (episodes.isEmpty()) return;
        int next = curEp + 1;
        if (curEp < 0 || next >= episodes.size()) { setTitle("已经是最后一集了", ""); showOverlay(); return; }
        String[] e = episodes.get(next);
        playEpisode(e[0], e[1], 0);
    }

    private void onPlaybackFinished() {
        if (episodes.isEmpty() || curEp < 0 || curEp + 1 >= episodes.size()) { finish(); return; }
        pendingNext = curEp + 1;
        countdown = 8;
        overlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOverlay);
        if (nextTip == null) {
            nextTip = new TextView(this);
            nextTip.setTextColor(Color.WHITE);
            nextTip.setTextSize(19);
            nextTip.setGravity(Gravity.CENTER);
            nextTip.setLineSpacing(dp(6), 1f);
            nextTip.setPadding(dp(40), dp(24), dp(40), dp(24));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6101820);
            bg.setCornerRadius(dp(16));
            nextTip.setBackground(bg);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
            nlp.gravity = Gravity.CENTER;
            overlay.addView(nextTip, nlp);
        }
        nextTip.setVisibility(View.VISIBLE);
        tickCountdown();
    }

    private void tickCountdown() {
        if (pendingNext < 0 || pendingNext >= episodes.size()) { cancelAutoNext(); return; }
        nextTip.setText(countdown + " 秒后播放下一集\n" + episodes.get(pendingNext)[1] + "\n\n按 OK 立即播放");
        if (countdown <= 0) {
            int n = pendingNext; pendingNext = -1;
            if (nextTip != null) nextTip.setVisibility(View.GONE);
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

    // ---------------- 焦点 / 显隐 ----------------

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        if (!buttons.isEmpty()) buttons.get(sel).requestFocus();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 4500);
    }

    private void moveSel(int d) {
        if (buttons.isEmpty()) return;
        sel = (sel + d + buttons.size()) % buttons.size();
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setBackground(circle(i == sel));
        buttons.get(sel).requestFocus();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, 4500);
    }

    private final Runnable hideOverlay = new Runnable() {
        @Override public void run() { if (!panelShown) overlay.setVisibility(View.GONE); }
    };

    @Override public void surfaceCreated(SurfaceHolder h) { if (streamUrl != null) startPlay(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (pendingNext >= 0) {
            if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                int n = pendingNext;
                pendingNext = -1;
                handler.removeCallbacks(countdownTick);
                if (nextTip != null) nextTip.setVisibility(View.GONE);
                if (n < episodes.size()) { String[] nx = episodes.get(n); playEpisode(nx[0], nx[1], 0); }
                return true;
            }
            return true;
        }
        if (e.getRepeatCount() > 0) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (panelShown) { hideMenu(); return true; }
            finish();
            return true;
        }
        if (panelShown) return super.onKeyDown(code, e);

        if (overlay.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { moveSel(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { moveSel(1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (sel >= 0 && sel < buttons.size()) buttons.get(sel).performClick();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) { overlay.setVisibility(View.GONE); return true; }
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-10000); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { seekBy(10000); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            showOverlay();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null && itemId != null) {
            final String id = itemId;
            final long pos = player.getCurrentPosition();
            stopPlayer();
            new Thread(new Runnable() { @Override public void run() { JellyfinClient.reportStop(id, pos); } }).start();
        } else {
            stopPlayer();
        }
        super.onDestroy();
    }
}
