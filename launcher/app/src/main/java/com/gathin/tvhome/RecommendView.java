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
public class RecommendView extends FrameLayout {

    /** 左边缘再按左（或顶部 tab 点主页）时回调，由宿主决定怎么退。 */
    public interface Listener { void onBack(); }

    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    private static final String TAG = "CYT-MP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> cards = new ArrayList<>();
    private LinearLayout content;
    private int screenW;
    private View firstFocus;
    private android.content.Context ctx;
    private ScrollView wallScroll;
    private ScrollView detailScroll;
    private LinearLayout detailContent;
    private TextView detailBack;
    private boolean detailOpen;
    /** 详情请求代数：关掉/换片后作废在途响应，防止旧详情覆盖新详情。 */
    private int detailEpoch;
    private View firstDetailFocus;
    private View lastFocusedCard;
    private ImageView bg;

    public RecommendView(android.content.Context ctx) {
        super(ctx);
        this.ctx = ctx;
        build();

    }
    private void build() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout root = new FrameLayout(ctx);
        setBackgroundColor(0xFF0B1116);
        // 与影视页同理：成为当前页之前整页禁止聚焦，避免离屏页面被焦点搜索命中
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        // 背景：和主页同一套 Jellyfin 剧照（模糊 + 渐变压暗），不再是纯黑
        bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0f);
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bgDim = new View(ctx);
        bgDim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x660B1116, 0xB30B1116}));
        root.addView(bgDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(ctx);
        wallScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(20), dp(56), dp(60));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(104);   // 内容在顶部 chrome 下方滚动，不会压到时钟
        root.addView(scroll, slp);


        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 详情层（默认隐藏）：点卡片时铺上来
        detailScroll = new ScrollView(ctx);
        // 详情层完全透明，背景图直接透出（不加黑底）
        detailScroll.setBackgroundColor(0x00000000);   // 完全透明，背景图直接透出来
        detailScroll.setVisibility(View.GONE);
        detailContent = new LinearLayout(ctx);
        detailContent.setOrientation(LinearLayout.VERTICAL);
        detailContent.setPadding(dp(56), dp(66), dp(56), dp(60));   // 顶部给固定返回按钮留位
        detailScroll.addView(detailContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.topMargin = dp(104);
        root.addView(detailScroll, dlp);
        // 固定的返回按钮（不随内容滚动）
        detailBack = chipButton("← 返回片单", false);
        detailBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams bkp = new FrameLayout.LayoutParams(dp(150), dp(40));
        bkp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        bkp.rightMargin = dp(56);
        bkp.topMargin = dp(112);
        addView(detailBack, bkp);
        detailBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDetail(); }
        });
        loadBackdrop();

        loadWall();
    }

    /** 拉推荐墙；15 秒内不重复拉（开机网络未就绪时，靠进页重试恢复）。 */
    private long lastWallAt;

    public void loadWall() {
        if (System.currentTimeMillis() - lastWallAt < 15_000L) return;
        lastWallAt = System.currentTimeMillis();
        content.removeAllViews();
        cards.clear();
        firstFocus = null;
        final TextView loading = new TextView(ctx);
        loading.setText("正在载入海报…");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);
        content.addView(loading);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MpClient.Section> wall = MpClient.wall();
                post(new Runnable() {
                    @Override public void run() {
                        content.removeAllViews();
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
            TextView title = new TextView(ctx);
            title.setText(sec.title);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(20);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(18);
            tlp.bottomMargin = dp(12);
            content.addView(title, tlp);

            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(row);
            content.addView(hsv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int i = 0; i < sec.items.size(); i++) {
                MpClient.Item item = sec.items.get(i);
                View card = makeCard(item, i == 0);
                row.addView(card, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (firstFocus == null) {
                    firstFocus = card;
                    // 首行卡片和顶部 tab 几乎同高，几何搜索找不到"严格在上方"的候选，
                    // 用显式提示把 ↑ 接到「推荐」tab（TV 标准的跨容器导航写法）
                    if (topUpTarget != null) card.setNextFocusUpId(topUpTarget.getId());
                }
            }
        }
        if (firstFocus == null) {
            TextView empty = new TextView(ctx);
            empty.setText("没有拿到推荐数据（MoviePilot 接口未返回内容，请检查其登录/鉴权设置）");
            empty.setTextColor(0xFF8FA3B0);
            empty.setTextSize(16);
            content.addView(empty);
        }
    }

    private View makeCard(final MpClient.Item item, final boolean firstInRow) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setPadding(dp(8), dp(8), dp(8), dp(10));
        card.setBackground(cardBg(false));
        card.setId(View.generateViewId());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(14);
        card.setLayoutParams(clp);

        FrameLayout box = new FrameLayout(ctx);
        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1A2330);
        box.addView(poster, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(box, new LinearLayout.LayoutParams(dp(150), dp(225)));

        // 评分角标
        if (item.rating > 0) {
            TextView score = new TextView(ctx);
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

        TextView name = new TextView(ctx);
        name.setText(item.title == null || item.title.isEmpty() ? "未命名" : item.title);
        name.setTextColor(0xFFE6EEF5);
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(6);
        card.addView(name, nlp);

        TextView meta = new TextView(ctx);
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
                if (has) lastFocusedCard = v;
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
            @Override public void onClick(View v) { showMovieDetail(item); }
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
                post(new Runnable() {
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
            new AlertDialog.Builder(getContext())
                    .setTitle(item.title)
                    .setMessage(head + "\n\n" + (item.overview == null || item.overview.isEmpty()
                            ? "（暂无简介）" : item.overview)
                            + "\n\n（本地资源库暂无这部片）")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        final AlertDialog loading = new AlertDialog.Builder(getContext())
                .setTitle(item.title)
                .setMessage("正在读取磁力版本…")
                .setNegativeButton("取消", null)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<LibClient.Mag> mags = LibClient.magnets(item.libId);
                post(new Runnable() {
                    @Override public void run() {
                        loading.dismiss();
                        if (mags.isEmpty()) {
                            new AlertDialog.Builder(getContext())
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
        new AlertDialog.Builder(getContext())
                .setTitle(item.title + "  转存 115")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        final LibClient.Mag mag = mags.get(which);
                        final AlertDialog wait = new AlertDialog.Builder(getContext())
                                .setTitle(item.title)
                                .setMessage("正在转存到 115…")
                                .setCancelable(false)
                                .show();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String msg = LibClient.transfer(mag.hash);
                                post(new Runnable() {
                                    @Override public void run() {
                                        wait.dismiss();
                                        new AlertDialog.Builder(getContext())
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

    /** 让推荐页拿到焦点（进入这一页时调）。 */
    public void focusFirst() {
        if (firstFocus != null) firstFocus.requestFocus();
    }

    /** 第一张卡按 ↑ 交回的顶部控件（宿主的「推荐」tab）。 */
    private android.view.View topUpTarget;

    public void setTopUpTarget(android.view.View v) { topUpTarget = v; }

    /** 墙里有没有可聚焦的卡片（数据源失败/为空时是 false）。 */
    public boolean hasContent() {
        return firstFocus != null;
    }

    private void closeWithSlide() {
        if (listener != null) listener.onBack();
    }

    /** 拉一张 Jellyfin 剧照当这一页的背景（模糊 + 淡入），30 秒换一张。 */
    /** 用主页当前那张背景（保证两页背景一致，不割裂）。 */
    public void setBackdrop(android.graphics.Bitmap bmp) {
        if (bmp == null || bg == null) return;
        bg.setImageBitmap(bmp);
        bg.animate().alpha(1f).setDuration(400).start();
    }

    public void loadBackdrop() {
        if (bg != null && bg.getDrawable() != null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.util.List<JellyfinClient.Item> items = JellyfinClient.backdropPool(200);
                    if (items == null || items.isEmpty()) return;
                    JellyfinClient.Item pick = items.get((int) (Math.random() * items.size()));
                    String imgId = pick.seriesId != null ? pick.seriesId : pick.id;
                    String url = JellyfinClient.BASE + "/Items/" + imgId
                            + "/Images/Backdrop/0?maxWidth=1600&quality=90";
                    android.graphics.Bitmap bm = JellyfinClient.poster(url, 1080);
                    if (bm == null) return;
                    try {
                        int w = bm.getWidth(), h = bm.getHeight();
                        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(
                                bm, Math.max(40, w / 4), Math.max(40, h / 4), true);
                        android.graphics.Bitmap blur = android.graphics.Bitmap.createScaledBitmap(small, w, h, true);
                        if (blur != small) small.recycle();
                        bm = blur;
                    } catch (Throwable ignored) { }
                    final android.graphics.Bitmap out = bm;
                    post(new Runnable() {
                        @Override public void run() {
                            if (bg == null) return;
                            bg.setImageBitmap(out);
                            bg.animate().alpha(1f).setDuration(600).start();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "backdrop failed " + t);
                }
            }
        }, "cyt-rec-backdrop").start();
    }


    // ---------------- 详情页 ----------------

    /** 打开影片详情页（海报 + 信息 + 简介 + 资源列表）。 */
    private void showMovieDetail(final MpClient.Item item) {
        if (detailScroll == null) return;
        detailOpen = true;
        final int epoch = ++detailEpoch;
        firstDetailFocus = null;   // 每次开新详情，聚焦标记归零
        detailContent.removeAllViews();
        if (wallScroll != null) wallScroll.setVisibility(View.GONE);
        detailScroll.setVisibility(View.VISIBLE);
        if (detailBack != null) { detailBack.setVisibility(View.VISIBLE); detailBack.requestFocus(); }
        detailScroll.scrollTo(0, 0);

        // 顶部返回

        // 先放占位，异步取详情 + 磁力
        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        detailContent.addView(body);

        new Thread(new Runnable() {
            @Override public void run() {
                final LibClient.Movie mv = LibClient.movie(item.libId);
                final java.util.List<LibClient.Mag> mags = LibClient.magnets(item.libId);
                post(new Runnable() {
                    @Override public void run() {
                        if (epoch != detailEpoch) return;   // 期间关了/换了详情，旧响应作废
                        renderDetail(item, mv, mags);
                    }
                });
            }
        }, "cyt-detail").start();
    }

    private void renderDetail(final MpClient.Item item, final LibClient.Movie mv,
                              final java.util.List<LibClient.Mag> mags) {
        detailContent.removeAllViews();

        String title = mv != null && !mv.title.isEmpty() ? mv.title : item.title;
        String year = mv != null && !mv.years.isEmpty() ? mv.years : item.year;

        // 头：左海报 + 右信息
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);

        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1A2330);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(220), dp(330));
        plp.rightMargin = dp(28);
        head.addView(poster, plp);
        if (mv != null && !mv.poster.isEmpty()) loadInto(poster, mv.poster);
        else if (item.poster != null) loadInto(poster, item.poster);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(title + (year.isEmpty() ? "" : " (" + year + ")"));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(30);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        if (mv != null && !mv.otitle.isEmpty()) {
            TextView ot = new TextView(ctx);
            ot.setText(mv.otitle + (mv.alias.isEmpty() ? "" : "   /   又名：" + mv.alias));
            ot.setTextColor(0xFF8FA3B0);
            ot.setTextSize(14);
            ot.setMaxLines(2);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.topMargin = dp(6);
            info.addView(ot, olp);
        }

        if (mv != null) {
            addField(info, "导演", mv.director);
            addField(info, "主演", mv.performer);
            addField(info, "编剧", mv.writer);
            addField(info, "类型", mv.category);
            addField(info, "制片国家/地区", mv.area);
            addField(info, "语言", mv.language);
            addField(info, "首映", mv.release);
            if (!mv.imdbId.isEmpty()) addField(info, "IMDb", mv.imdbId);
        }

        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        head.addView(info, ilp);
        detailContent.addView(head);

        // 剧情简介
        if (mv != null && !mv.intro.isEmpty()) {
            addSectionTitle("剧情简介");
            TextView abs = new TextView(ctx);
            abs.setText(mv.intro);
            abs.setTextColor(0xFFC9D6DF);
            abs.setTextSize(15);
            abs.setLineSpacing(dp(6), 1f);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.bottomMargin = dp(8);
            detailContent.addView(abs, alp);
        }

        // 资源列表
        addSectionTitle("这部影片共 " + mags.size() + " 条资源");
        if (mags.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("（资源库里没有磁力）");
            empty.setTextColor(0xFF8FA3B0);
            empty.setTextSize(15);
            detailContent.addView(empty);
            return;
        }
        for (int i = 0; i < mags.size(); i++) {
            detailContent.addView(magRow(item, mags.get(i), i == 0));
        }
        if (firstDetailFocus != null) firstDetailFocus.requestFocus();
    }

    /** 一条资源：标题 + 大小/日期/类型 + 复制/打开/转存 三个按钮。 */
    private View magRow(final MpClient.Item item, final LibClient.Mag mag, boolean first) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF121A22);
        bg.setStroke(dp(1), 0x22FFFFFF);
        row.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(mag.title);
        t.setTextColor(0xFFE6EEF5);
        t.setTextSize(15);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);
        TextView meta = new TextView(ctx);
        StringBuilder sb = new StringBuilder();
        if (!mag.size.isEmpty()) sb.append(mag.size);
        if (!mag.published.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(mag.published);
        if (!mag.detail.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(mag.detail);
        meta.setText(sb.toString());
        meta.setTextColor(0xFF8FA3B0);
        meta.setTextSize(13);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(4);
        texts.addView(meta, mlp);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = chipButton("复制磁力", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyMagnet(mag); }
        });
        TextView open = chipButton("打开", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPage(mag.detailUrl); }
        });
        TextView save = chipButton("转存 115", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { transfer(item, mag); }
        });

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(112), dp(40));
        bp.leftMargin = dp(10);
        row.addView(copy, bp);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(dp(84), dp(40));
        bp2.leftMargin = dp(10);
        row.addView(open, bp2);
        LinearLayout.LayoutParams bp3 = new LinearLayout.LayoutParams(dp(112), dp(40));
        bp3.leftMargin = dp(10);
        row.addView(save, bp3);

        if (first && firstDetailFocus == null) firstDetailFocus = copy;
        return row;
    }

    private void addField(LinearLayout parent, String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(0xFF7E8B96);
        l.setTextSize(13);
        l.setMinWidth(dp(110));
        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(0xFFE6EEF5);
        v.setTextSize(14);
        v.setMaxLines(3);
        row.addView(l);
        row.addView(v);
        parent.addView(row, rlp);
    }

    private void addSectionTitle(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(19);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(12);
        detailContent.addView(tv, lp);
    }

    /** 小圆角按钮。 */
    private TextView chipButton(String text, boolean accent) {
        final TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setTextColor(0xFFE6EEF5);
        tv.setBackground(buttonBg(accent, false));
        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(buttonBg(accent, has));
                ((TextView) v).setTextColor(has ? 0xFF0B1116 : 0xFFE6EEF5);
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(110).start();
            }
        });
        return tv;
    }

    private GradientDrawable buttonBg(boolean accent, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        if (focused) { d.setColor(0xFFFFFFFF); d.setStroke(dp(3), 0xFF00A4DC); }
        else if (accent) { d.setColor(0xFF7C5CFF); d.setStroke(dp(1), 0x33FFFFFF); }
        else { d.setColor(0x1FFFFFFF); d.setStroke(dp(1), 0x33FFFFFF); }
        return d;
    }

    private void copyMagnet(LibClient.Mag mag) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("magnet",
                    "magnet:?xt=urn:btih:" + mag.hash));
            android.widget.Toast.makeText(ctx, "磁力已复制", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "复制失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openPage(String url) {
        if (url == null || url.isEmpty()) {
            android.widget.Toast.makeText(ctx, "没有可打开的页面", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 转存到 115（原来的逻辑）。 */
    private void transfer(final MpClient.Item item, final LibClient.Mag mag) {
        final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(ctx)
                .setTitle(item.title)
                .setMessage("正在转存到 115…")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String msg = LibClient.transfer(mag.hash);
                post(new Runnable() {
                    @Override public void run() {
                        wait.dismiss();
                        new android.app.AlertDialog.Builder(ctx)
                                .setTitle(item.title)
                                .setMessage(msg + "\n\n" + mag.label()
                                        + "\n\n转存后在 115（Movies 目录）里播放")
                                .setPositiveButton("好", null)
                                .show();
                    }
                });
            }
        }, "cyt-transfer").start();
    }

    /** 详情页是否打开。 */
    public boolean isDetailOpen() { return detailOpen; }

    /** 关掉详情页，回到片单。 */
    public void closeDetail() {
        if (!detailOpen) return;
        detailOpen = false;
        detailEpoch++;
        if (detailScroll != null) detailScroll.setVisibility(View.GONE);
        if (detailBack != null) detailBack.setVisibility(View.GONE);
        if (wallScroll != null) wallScroll.setVisibility(View.VISIBLE);
        if (lastFocusedCard != null) lastFocusedCard.requestFocus();
    }

    private void loadInto(final ImageView view, final String url) {
        LOADER.execute(new Runnable() {
            @Override public void run() {
                final android.graphics.Bitmap bmp = MpClient.poster(url);
                if (bmp == null) return;
                post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(bmp); }
                });
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
