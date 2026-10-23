package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 推荐页：从主页往右翻过来的一页。
 * 推荐流来自 MoviePilot，海报/简介来自 2bt0 资源库（见 LibClient）；点击只弹详情，不接播放。
 */
public class RecommendActivity extends Activity {

    private static final String TAG = "CYT-MP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> cards = new ArrayList<>();
    private LinearLayout content;
    private int screenW;
    private View firstFocus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1116);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(44), dp(56), dp(60));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部页面 tab：主页 / 推荐（当前）
        LinearLayout pageBar = new LinearLayout(this);
        pageBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pblp.bottomMargin = dp(18);
        content.addView(pageBar, pblp);

        TextView tabHome = new TextView(this);
        tabHome.setText("主页");
        tabHome.setTextSize(14);
        tabHome.setGravity(Gravity.CENTER);
        tabHome.setFocusable(true);
        tabHome.setFocusableInTouchMode(true);
        tabHome.setTextColor(0xFFE6EEF5);
        GradientDrawable homeBg = new GradientDrawable();
        homeBg.setCornerRadius(dp(20));
        homeBg.setColor(0x1F101820);
        homeBg.setStroke(dp(1), 0x2AFFFFFF);
        tabHome.setBackground(homeBg);
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(dp(96), dp(40));
        thlp.rightMargin = dp(10);
        pageBar.addView(tabHome, thlp);
        tabHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeWithSlide(); }
        });

        TextView tabRec = new TextView(this);
        tabRec.setText("推荐");
        tabRec.setTextSize(14);
        tabRec.setGravity(Gravity.CENTER);
        tabRec.setFocusable(true);
        tabRec.setFocusableInTouchMode(true);
        tabRec.setTextColor(0xFF0B1116);
        GradientDrawable recBg = new GradientDrawable();
        recBg.setCornerRadius(dp(20));
        recBg.setColor(0xFFE6EEF5);
        recBg.setStroke(dp(1), 0x33000000);
        tabRec.setBackground(recBg);
        pageBar.addView(tabRec, new LinearLayout.LayoutParams(dp(96), dp(40)));

        tabHome.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(20));
                if (has) { d.setColor(0xFFFFFFFF); d.setStroke(dp(3), 0xFF00A4DC); }
                else { d.setColor(0x1F101820); d.setStroke(dp(1), 0x2AFFFFFF); }
                v.setBackground(d);
                ((TextView) v).setTextColor(has ? 0xFF0B1116 : 0xFFE6EEF5);
            }
        });
        tabHome.setNextFocusRightId(tabRec.getId());
        tabRec.setNextFocusLeftId(tabHome.getId());

        // 顶部标题
        TextView head = new TextView(this);
        head.setText("推荐");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(34);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(head);

        TextView sub = new TextView(this);
        sub.setText("推荐流 MoviePilot · 海报 2bt0 资源库 · 右滑进来，左滑回主页");
        sub.setTextColor(0xFF8FA3B0);
        sub.setTextSize(14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(24);
        content.addView(sub, subLp);

        final TextView loading = new TextView(this);
        loading.setText("正在载入海报…");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);
        content.addView(loading);

        setContentView(root);

        // 从右侧滑入
        root.setTranslationX(screenW);
        root.animate().translationX(0).setDuration(260).start();

        new Thread(new Runnable() {
            @Override public void run() {
                final List<MpClient.Section> wall = MpClient.wall();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        content.removeView(loading);
                        buildSections(wall);
                        if (firstFocus != null) firstFocus.requestFocus();
                    }
                });
            }
        }, "cyt-mp-wall").start();
    }

    private void buildSections(List<MpClient.Section> wall) {
        for (MpClient.Section sec : wall) {
            if (sec.items.isEmpty()) continue;
            TextView title = new TextView(this);
            title.setText(sec.title);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(18);
            tlp.bottomMargin = dp(12);
            content.addView(title, tlp);

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(row);
            content.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int i = 0; i < sec.items.size(); i++) {
                MpClient.Item item = sec.items.get(i);
                View card = makeCard(item, i == 0);
                row.addView(card, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (firstFocus == null) firstFocus = card;
            }
        }
        if (firstFocus == null) {
            TextView empty = new TextView(this);
            empty.setText("没有拿到推荐数据（MoviePilot 接口暂时没返回内容）");
            empty.setTextColor(0xFF8FA3B0);
            empty.setTextSize(16);
            content.addView(empty);
        }
    }

    private View makeCard(final MpClient.Item item, final boolean firstInRow) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setPadding(dp(8), dp(8), dp(8), dp(10));
        card.setBackground(cardBg(false));
        card.setId(View.generateViewId());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(14);
        card.setLayoutParams(clp);

        FrameLayout box = new FrameLayout(this);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1A2330);
        box.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(box, new LinearLayout.LayoutParams(dp(150), dp(225)));

        // 评分角标
        if (item.rating > 0) {
            TextView score = new TextView(this);
            score.setText(String.format("%.1f", item.rating));
            score.setTextColor(0xFF0B1116);
            score.setTextSize(12);
            score.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFF5C542);
            bg.setCornerRadius(dp(9));
            score.setBackground(bg);
            score.setPadding(dp(7), dp(2), dp(7), dp(2));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.gravity = Gravity.TOP | Gravity.END;
            slp.topMargin = dp(6);
            slp.rightMargin = dp(6);
            box.addView(score, slp);
        }

        TextView name = new TextView(this);
        name.setText(item.title == null || item.title.isEmpty() ? "未命名" : item.title);
        name.setTextColor(0xFFE6EEF5);
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(6);
        card.addView(name, nlp);

        TextView meta = new TextView(this);
        String y = item.year == null || item.year.isEmpty() ? "" : item.year;
        meta.setText((item.type == null ? "" : item.type) + (y.isEmpty() ? "" : " · " + y));
        meta.setTextColor(0xFF8FA3B0);
        meta.setTextSize(11);
        meta.setMaxLines(1);
        card.addView(meta);

        // 焦点
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(cardBg(has));
                v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                        .setDuration(130).start();
            }
        });
        // 左边缘再按左 → 回主页
        card.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && firstInRow) {
                    closeWithSlide();
                    return true;
                }
                return false;
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDetail(item); }
        });

        loadPoster(poster, item.poster);
        cards.add(card);
        return card;
    }

    /** 海报单线程顺序下载，避免几十张同时抢带宽。 */
    private static final java.util.concurrent.ExecutorService LOADER =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private void loadPoster(final ImageView view, final String url) {
        if (url == null || url.isEmpty()) return;
        LOADER.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = MpClient.poster(url);
                if (bmp == null) { Log.w(TAG, "poster failed " + url); return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() { view.setImageBitmap(bmp); }
                });
            }
        });
    }

    /** 点卡片：有资源库 ID 就列磁力版本，选中转存 115；否则只显示元数据。 */
    private void showDetail(final MpClient.Item item) {
        StringBuilder head = new StringBuilder();
        if (item.year != null && !item.year.isEmpty()) head.append(item.year).append("  ");
        if (item.type != null && !item.type.isEmpty()) head.append(item.type).append("  ");
        if (item.rating > 0) head.append("★ ").append(String.format("%.1f", item.rating));
        if (item.versions > 0) head.append("  ").append(item.versions).append(" 个版本");

        if (item.libId == null || item.libId.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setMessage(head + "\n\n" + (item.overview == null || item.overview.isEmpty()
                            ? "（暂无简介）" : item.overview)
                            + "\n\n（本地资源库暂无这部片）")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        final AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage("正在读取磁力版本…")
                .setNegativeButton("取消", null)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<LibClient.Mag> mags = LibClient.magnets(item.libId);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading.dismiss();
                        if (mags.isEmpty()) {
                            new AlertDialog.Builder(RecommendActivity.this)
                                    .setTitle(item.title)
                                    .setMessage(head + "\n\n（资源库里没有磁力）")
                                    .setPositiveButton("关闭", null).show();
                            return;
                        }
                        showMagnetPicker(item, mags, head.toString());
                    }
                });
            }
        }, "cyt-lib-magnets").start();
    }

    /** 磁力版本列表：选中 → 转存 115。 */
    private void showMagnetPicker(final MpClient.Item item,
                                  final java.util.List<LibClient.Mag> mags,
                                  final String head) {
        final String[] labels = new String[mags.size()];
        for (int i = 0; i < mags.size(); i++) labels[i] = mags.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle(item.title + "  转存 115")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        final LibClient.Mag mag = mags.get(which);
                        final AlertDialog wait = new AlertDialog.Builder(RecommendActivity.this)
                                .setTitle(item.title)
                                .setMessage("正在转存到 115…")
                                .setCancelable(false)
                                .show();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String msg = LibClient.transfer(mag.hash);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        wait.dismiss();
                                        new AlertDialog.Builder(RecommendActivity.this)
                                                .setTitle(item.title)
                                                .setMessage(msg + "\n\n" + mag.label()
                                                        + "\n\n转存后在 115（Movies 目录）里播放")
                                                .setPositiveButton("好", null)
                                                .show();
                                    }
                                });
                            }
                        }, "cyt-lib-transfer").start();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        if (focused) {
            d.setColor(0xFF16202B);
            d.setStroke(dp(3), 0xFF00A4DC);
        } else {
            d.setColor(0x00000000);
            d.setStroke(dp(1), 0x22FFFFFF);
        }
        return d;
    }

    private void closeWithSlide() {
        View root = findViewById(android.R.id.content);
        root.animate().translationX(screenW).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override public void run() { finish(); overridePendingTransition(0, 0); }
                }).start();
    }

    @Override public void onBackPressed() {
        closeWithSlide();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
