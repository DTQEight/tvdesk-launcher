package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.util.Log;
import android.view.Gravity;
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
 * 影视页：仿 Jellyfin Android TV。
 * 左：库列表；右：横图卡片行；点卡片进详情。
 * 光标走 Android TV 标准架构：卡片/库项都是真实可聚焦 View，
 * 方向键由框架几何搜索处理，焦点框由 state_focused 状态表绘制，
 * 横向/纵向滚动由 HorizontalScrollView/ScrollView 自动跟随焦点。
 */
public class JellyfinView extends FrameLayout {

    private static final String TAG = "CYT-JF";

    private final Context ctx;
    private final java.util.concurrent.ExecutorService loader =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private ImageView bg;
    private LinearLayout rail;
    private ScrollView homeScroll;
    private LinearLayout homeContent;
    private TextView emptyHint;

    // 库页
    private ScrollView libScroll;
    private LinearLayout libContent;
    private TextView libTitle;
    private TextView libBack;

    // 详情页
    private ScrollView detailScroll;
    private LinearLayout detailContent;
    private TextView detailBack;

    private boolean libOpen;
    private boolean detailOpen;
    /** 库页请求代数：切换/关闭后作废在途响应，防止旧数据渲染进新库。 */
    private int libEpoch;
    /** 详情页请求代数：关掉/换片后作废在途响应。 */
    private int detailEpoch;
    /** 主页首次加载的代数：重试时作废旧响应。 */
    private int homeLoadEpoch;
    /** 打开详情的那张卡，关详情时把焦点还给它。 */
    private View detailSourceCard;
    /** 进库前的页面背景，退库时还原。 */
    private Bitmap pageBackdrop;

    private final List<JellyfinClient.Card> views = new ArrayList<>();
    private final List<TextView> railItems = new ArrayList<>();
    /** 左栏第一项按 ↑ 交回的顶部控件；buildRail 重建左栏后要重接。 */
    private View topUpTarget;
    private final List<View> detailButtons = new ArrayList<>();
    private final List<String[]> detailEpisodes = new ArrayList<>();
    private String detailMovieId, detailMovieTitle;

    public JellyfinView(Context c) {
        super(c);
        ctx = c;
        build();
    }

    // ---------------- 布局 ----------------

    private void build() {
        setBackgroundColor(0xFF0B1116);   // 不透明，否则主页 dock 会透出来
        // 影视页首次访问前从未参与布局，子控件坐标全是 (0,0,0,0)，
        // 会被几何焦点搜索当成"正上/正左"的候选（光标跳到看不见的页面上）。
        // 构造即封锁聚焦，成为当前页时由宿主解开。
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0f);
        addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(ctx);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x6E0B1116, 0xC00B1116}));   // 压暗层调淡，背景剧照要透出来
        addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(24), dp(112), dp(12), dp(40));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(230),
                ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.gravity = Gravity.START;
        addView(rail, rlp);

        homeScroll = new ScrollView(ctx);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeContent = new LinearLayout(ctx);
        homeContent.setOrientation(LinearLayout.VERTICAL);
        homeContent.setPadding(dp(12), dp(104), dp(56), dp(60));
        homeScroll.addView(homeContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        hlp.leftMargin = dp(230);
        addView(homeScroll, hlp);

        libScroll = new ScrollView(ctx);
        libScroll.setVerticalScrollBarEnabled(false);
        // 库页背景保持透明：和主墙一样直接透出背景剧照 + 渐变暗层（原来 80% 暗层全黑）
        libScroll.setVisibility(View.GONE);
        libContent = new LinearLayout(ctx);
        libContent.setOrientation(LinearLayout.VERTICAL);
        libContent.setPadding(dp(12), dp(40), dp(56), dp(60));
        libScroll.addView(libContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.leftMargin = dp(230);
        llp.topMargin = dp(104);
        addView(libScroll, llp);

        libTitle = new TextView(ctx);
        libTitle.setTextColor(0xFFFFFFFF);
        libTitle.setTextSize(24);
        libTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        libTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP | Gravity.START;
        tlp.leftMargin = dp(254);
        tlp.topMargin = dp(120);
        addView(libTitle, tlp);

        libBack = chipLabel("← 返回");
        libBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(110), dp(40));
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.rightMargin = dp(56);
        blp.topMargin = dp(120);
        addView(libBack, blp);

        detailScroll = new ScrollView(ctx);
        detailScroll.setVerticalScrollBarEnabled(false);
        detailScroll.setBackgroundColor(0xFF0B1116);   // 盖住左栏，详情页背景不串
        detailScroll.setVisibility(View.GONE);
        detailContent = new LinearLayout(ctx);
        detailContent.setOrientation(LinearLayout.VERTICAL);
        detailContent.setPadding(0, 0, 0, dp(60));
        detailScroll.addView(detailContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.topMargin = dp(104);
        addView(detailScroll, dlp);

        detailBack = chipLabel("← 返回");
        detailBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams dblp = new FrameLayout.LayoutParams(dp(110), dp(40));
        dblp.gravity = Gravity.TOP | Gravity.END;
        dblp.rightMargin = dp(56);
        dblp.topMargin = dp(120);
        addView(detailBack, dblp);

        loadEverything();
    }

    private void loadEverything() {
        homeLoadEpoch++;
        final int epoch = homeLoadEpoch;
        views.clear();
        buildRail();
        homeContent.removeAllViews();
        emptyHint = new TextView(ctx);
        emptyHint.setText("正在读取影视库…");
        emptyHint.setTextColor(0xFF8FA3B0);
        emptyHint.setTextSize(16);
        homeContent.addView(emptyHint);

        new Thread(new Runnable() {
            @Override public void run() {
                final List<JellyfinClient.Card> v = JellyfinClient.views();
                final List<JellyfinClient.Card> resume = JellyfinClient.resumeCards(20);
                final List<List<JellyfinClient.Card>> latests = new ArrayList<>();
                for (JellyfinClient.Card c : v) latests.add(JellyfinClient.latest(c.id, 14));
                post(new Runnable() {
                    @Override public void run() {
                        if (epoch != homeLoadEpoch) return;   // 重试后旧响应作废
                        views.clear();
                        views.addAll(v);
                        buildRail();
                        if (v.isEmpty() && resume.isEmpty()) {
                            // 服务器连不上：给出提示，主页项 OK 可重试（见 rail 点击）
                            homeContent.removeAllViews();
                            emptyHint = new TextView(ctx);
                            emptyHint.setText("连接 Jellyfin 失败\n\n按 OK 重试");
                            emptyHint.setTextColor(0xFF8FA3B0);
                            emptyHint.setTextSize(16);
                            homeContent.addView(emptyHint);
                            return;
                        }
                        renderHome(resume, latests);
                    }
                });
            }
        }, "cyt-jf-load").start();
    }

    // ---------------- 左栏 ----------------

    private void buildRail() {
        int keep = -1;
        for (int i = 0; i < railItems.size(); i++) if (railItems.get(i).isFocused()) keep = i;
        rail.removeAllViews();
        railItems.clear();
        railItems.add(railItem("主页"));
        for (JellyfinClient.Card lib : views) railItems.add(railItem(lib.title));
        for (int i = 0; i < railItems.size(); i++) {
            TextView it = railItems.get(i);
            rail.addView(it, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        }
        // 重建（重试/数据到位）后焦点留在原库项上，不跳走
        if (keep >= 0 && keep < railItems.size()) railItems.get(keep).requestFocus();
        if (topUpTarget != null) railItems.get(0).setNextFocusUpId(topUpTarget.getId());
        markCurrentRailItem();
    }

    private TextView railItem(String text) {
        final TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), 0, dp(10), 0);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setBackground(railBg());
        tv.setTextColor(railTextColors());
        tv.setOnFocusChangeListener((v, has) -> {
            if (has) {
                // 光标在左栏内：标记跟着光标走，只亮当前这一项
                for (TextView it : railItems) it.setSelected(it == v);
            } else if (!railFocused()) {
                // 焦点真的离开了左栏（进墙/进库内容）：标记才回到当前打开的库。
                // 左栏内移动时失焦回调在新项获焦之后才触发，直接回写会把标记打回主页
                markCurrentRailItem();
            }
        });
        tv.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                int idx = railItems.indexOf(tv);
                if (idx == 0) {
                    closeLibrary();
                    // 没有任何库（加载失败/空服务器）时，OK 重试
                    if (views.isEmpty()) loadEverything();
                } else if (idx - 1 < views.size()) {
                    openLibrary(views.get(idx - 1));
                }
            }
        });
        return tv;
    }

    /** 左栏状态背景：聚焦蓝框、当前库暗底、其余透明。 */
    private StateListDrawable railBg() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(8));
        focused.setColor(0xFF2A3B4D);
        focused.setStroke(dp(2), 0xFF00A4DC);
        GradientDrawable selected = new GradientDrawable();
        selected.setCornerRadius(dp(8));
        selected.setColor(0xFF1B2A38);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(8));
        normal.setColor(0x00000000);
        d.addState(new int[]{android.R.attr.state_focused}, focused);
        d.addState(new int[]{android.R.attr.state_selected}, selected);
        d.addState(new int[]{}, normal);
        return d;
    }

    private ColorStateList railTextColors() {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_focused},
                            {android.R.attr.state_selected},
                            {}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFC9D6DF});
    }

    // ---------------- 主页内容 ----------------

    private void renderHome(List<JellyfinClient.Card> resume,
                            List<List<JellyfinClient.Card>> latests) {
        homeContent.removeAllViews();
        if (!resume.isEmpty()) {
            addRowTitle(homeContent, "继续观看");
            addRow(homeContent, resume, true);
        }
        for (int i = 0; i < views.size() && i < latests.size(); i++) {
            List<JellyfinClient.Card> list = latests.get(i);
            if (list.isEmpty()) continue;
            addRowTitle(homeContent, "最近添加 · " + views.get(i).title);
            addRow(homeContent, list, false);
        }
    }

    private void addRowTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(19);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(10);
        parent.addView(tv, lp);
    }

    private void addRow(LinearLayout parent, List<JellyfinClient.Card> cards, boolean progress) {
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        for (final JellyfinClient.Card c : cards) {
            View card = landscapeCard(c, progress);
            inner.addView(card);
        }
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(inner);
        parent.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View landscapeCard(final JellyfinClient.Card card, boolean showProgress) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(cardBg());
        box.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { openDetail(card, v); }
        });

        FrameLayout thumb = new FrameLayout(ctx);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF1A2330);
        thumb.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadWithFallback(img, card.landscapeUrl, card.posterUrl, dp(170));

        if (showProgress && card.progress > 0) {
            View track = new View(ctx);
            track.setBackgroundColor(0x88000000);
            FrameLayout.LayoutParams trlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(4));
            trlp.gravity = Gravity.BOTTOM;
            thumb.addView(track, trlp);
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFF00A4DC);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(0, dp(4));
            blp.gravity = Gravity.BOTTOM;
            thumb.addView(bar, blp);
            final View barRef = bar;
            final int pct = (int) Math.max(2, Math.min(100, card.progress));
            thumb.post(new Runnable() {
                @Override public void run() {
                    ViewGroup.LayoutParams lp = barRef.getLayoutParams();
                    lp.width = Math.max(2, thumb.getWidth() * pct / 100);
                    barRef.setLayoutParams(lp);
                }
            });
        }
        box.addView(thumb, new LinearLayout.LayoutParams(dp(300), dp(169)));

        TextView name = new TextView(ctx);
        name.setText(card.title);
        name.setTextColor(0xFFE6EEF5);
        name.setTextSize(13);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(6);
        box.addView(name, nlp);

        if (card.sub != null && !card.sub.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(card.sub);
            sub.setTextColor(0xFF8FA3B0);
            sub.setTextSize(11);
            box.addView(sub);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private View posterCard(final JellyfinClient.Card card) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setBackground(cardBg());
        box.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { openDetail(card, v); }
        });

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF1A2330);
        box.addView(img, new LinearLayout.LayoutParams(dp(150), dp(225)));
        loadWithFallback(img, card.posterUrl, null, dp(225));

        TextView name = new TextView(ctx);
        name.setText(card.title);
        name.setTextColor(0xFFE6EEF5);
        name.setTextSize(12);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(5);
        box.addView(name, nlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        box.setLayoutParams(lp);
        return box;
    }

    /** 卡片状态背景：聚焦蓝框，其余透明。 */
    private StateListDrawable cardBg() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(8));
        focused.setColor(0xFF223140);
        focused.setStroke(dp(3), 0xFF00A4DC);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(8));
        normal.setColor(0x00000000);
        d.addState(new int[]{android.R.attr.state_focused}, focused);
        d.addState(new int[]{}, normal);
        return d;
    }

    // ---------------- 库页 ----------------

    public boolean isLibraryOpen() { return libOpen; }

    private void openLibrary(final JellyfinClient.Card lib) {
        libOpen = true;
        libEpoch++;
        libContent.removeAllViews();
        homeScroll.setVisibility(View.GONE);
        libScroll.setVisibility(View.VISIBLE);
        libScroll.scrollTo(0, 0);
        libTitle.setText(lib.title);
        libTitle.setVisibility(View.VISIBLE);
        libBack.setVisibility(View.VISIBLE);
        markCurrentRailItem();
        // 库页背景：库本身没设 Backdrop 时，用库里随机一部片的背景图（Jellyfin 官方同款行为）；
        // 进库前先记住页面原背景，退库时还原
        if (pageBackdrop == null) pageBackdrop = bgBitmap();
        final int libEpochNow = libEpoch;
        // 优先用库自己设置的 Backdrop，没有再从库内随机挑一部有背景图的影片
        final String bdUrl = (lib.backdropUrl != null && !lib.backdropUrl.isEmpty()
                && JellyfinClient.poster(lib.backdropUrl, 720) != null)
                ? lib.backdropUrl : JellyfinClient.libraryBackdrop(lib.id);
        if (bdUrl != null) {
            loader.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bm = JellyfinClient.poster(bdUrl, 1080);
                    if (bm == null) return;
                    post(new Runnable() {
                        @Override public void run() {
                            if (libOpen && libEpoch == libEpochNow) setBackdrop(bm);
                        }
                    });
                }
            });
        }

        final int epoch = libEpoch;
        final TextView loading = new TextView(ctx);
        loading.setText("正在读取 " + lib.title + " …");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);
        libContent.addView(loading);

        new Thread(new Runnable() {
            @Override public void run() {
                final List<JellyfinClient.Card> items = JellyfinClient.libraryItems(lib.id, 300);
                post(new Runnable() {
                    @Override public void run() {
                        if (epoch != libEpoch) return;   // 期间切了库/关了库，旧响应作废
                        libContent.removeView(loading);
                        renderLibrary(items);
                    }
                });
            }
        }, "cyt-jf-lib").start();
    }

    private void renderLibrary(List<JellyfinClient.Card> items) {
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("（这个库是空的）");
            empty.setTextColor(0xFF8FA3B0);
            libContent.addView(empty);
            return;
        }
        final int perRow = 7;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(10);
                libContent.addView(row, rlp);
            }
            View card = posterCard(items.get(i));
            row.addView(card);
        }
    }

    public void closeLibrary() {
        if (!libOpen) return;
        libOpen = false;
        libEpoch++;
        libScroll.setVisibility(View.GONE);
        libTitle.setVisibility(View.GONE);
        libBack.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        markCurrentRailItem();
        if (pageBackdrop != null) {
            final Bitmap restore = pageBackdrop;
            pageBackdrop = null;
            post(new Runnable() {
                @Override public void run() { setBackdrop(restore); }
            });
        }
        if (railItems.size() > 0) railItems.get(0).requestFocus();
    }

    /** 左栏高亮标出当前所在的库（主页 / 某个库）。 */
    private void markCurrentRailItem() {
        int cur = 0;   // 主页
        if (libOpen) {
            // 当前打开的库：标题和左栏文字一致的那一项
            for (int i = 1; i < railItems.size() && i - 1 < views.size(); i++) {
                if (views.get(i - 1).title.contentEquals(libTitle.getText())) cur = i;
            }
        }
        for (int i = 0; i < railItems.size(); i++) railItems.get(i).setSelected(i == cur);
    }

    // ---------------- 详情页 ----------------

    private void openDetail(final JellyfinClient.Card card, View sourceCard) {
        detailOpen = true;
        detailEpoch++;
        detailSourceCard = sourceCard;
        detailContent.removeAllViews();
        detailButtons.clear();
        detailEpisodes.clear();
        homeScroll.setVisibility(View.GONE);
        libScroll.setVisibility(View.GONE);
        detailScroll.setVisibility(View.VISIBLE);
        detailScroll.scrollTo(0, 0);
        detailBack.setVisibility(View.VISIBLE);
        setRailFocusable(false);   // 详情盖住左栏，别让焦点漏进被盖住的库项

        final int epoch = detailEpoch;
        final TextView loading = new TextView(ctx);
        loading.setText("正在读取详情…");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);
        detailContent.addView(loading);

        new Thread(new Runnable() {
            @Override public void run() {
                final JellyfinClient.Detail d = JellyfinClient.detail(card.id);
                final List<String[]> eps = (d != null && "Series".equals(d.type))
                        ? JellyfinClient.episodes(card.id) : new ArrayList<String[]>();
                post(new Runnable() {
                    @Override public void run() {
                        if (epoch != detailEpoch) return;   // 期间关了/换了详情，旧响应作废
                        detailContent.removeView(loading);
                        renderDetail(d, eps);
                    }
                });
            }
        }, "cyt-jf-detail").start();
    }

    /** 不可聚焦的提示 chip（返回按钮走遥控器 BACK，不参与焦点）。 */
    private TextView chipLabel(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setBackground(chipBg().getConstantState() != null
                ? chipBg().getConstantState().newDrawable() : chipBg());
        tv.setTextColor(0xFFE6EEF5);
        return tv;
    }

    private TextView chipText(String text, final boolean play, final String[] ep) {
        final TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setBackground(chipBg());
        tv.setTextColor(chipTextColors());
        tv.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (play) {
                    if (!detailEpisodes.isEmpty()) playEpisode(detailEpisodes.get(0));
                    else if (detailMovieId != null) playItem(detailMovieId, detailMovieTitle);
                } else if (ep != null) {
                    playEpisode(ep);
                }
            }
        });
        detailButtons.add(tv);
        return tv;
    }

    private void renderDetail(JellyfinClient.Detail d, List<String[]> eps) {
        detailButtons.clear();
        detailEpisodes.clear();
        detailEpisodes.addAll(eps);
        if (d == null) {
            TextView err = new TextView(ctx);
            err.setText("读取详情失败");
            err.setTextColor(0xFF8FA3B0);
            detailContent.addView(err);
            return;
        }
        detailMovieId = d.id;
        detailMovieTitle = d.name;

        FrameLayout hero = new FrameLayout(ctx);
        ImageView heroImg = new ImageView(ctx);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroImg.setBackgroundColor(0xFF16202B);
        hero.addView(heroImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadWithFallback(heroImg, d.backdrop, d.poster, dp(260));
        View fade = new View(ctx);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22000000, 0xEE0B1116}));
        hero.addView(fade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailContent.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1A2330);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(170), dp(255));
        plp.rightMargin = dp(24);
        plp.topMargin = dp(10);
        plp.leftMargin = dp(56);
        head.addView(poster, plp);
        loadWithFallback(poster, d.poster, null, dp(255));

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(d.name + (d.year.isEmpty() ? "" : "  (" + d.year + ")"));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(title);

        StringBuilder meta = new StringBuilder();
        if (!d.score.isEmpty()) meta.append("★ ").append(d.score).append("   ");
        if (!d.rating.isEmpty()) meta.append(d.rating).append("   ");
        if (!d.runtime.isEmpty()) meta.append(d.runtime);
        if (meta.length() > 0) {
            TextView m = new TextView(ctx);
            m.setText(meta.toString());
            m.setTextColor(0xFF8FA3B0);
            m.setTextSize(14);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(6);
            info.addView(m, mlp);
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView playBtn = chipText("▶ 播放", true, null);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(dp(150), dp(44));
        blp2.topMargin = dp(16);
        btns.addView(playBtn, blp2);
        info.addView(btns);

        if (!d.overview.isEmpty()) {
            TextView ov = new TextView(ctx);
            ov.setText(d.overview);
            ov.setTextColor(0xFFC9D6DF);
            ov.setTextSize(14);
            ov.setLineSpacing(dp(5), 1f);
            // 简介全文展开，不截断（页面本身可上下滚动）
            ov.setMaxLines(Integer.MAX_VALUE);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.topMargin = dp(14);
            info.addView(ov, olp);
        }
        head.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        detailContent.addView(head);

        if (!eps.isEmpty()) {
            addRowTitle(detailContent, "剧集");
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(56), 0, dp(56), 0);
            for (int i = 0; i < eps.size(); i++) {
                TextView eb = chipText(eps.get(i)[1], false, eps.get(i));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(150), dp(44));
                elp.rightMargin = dp(8);
                elp.topMargin = dp(6);
                row.addView(eb, elp);
            }
            // 剧集多时会超出屏宽，包一层横向滚动，框架自动滚到聚焦的那集
            HorizontalScrollView epScroll = new HorizontalScrollView(ctx);
            epScroll.setHorizontalScrollBarEnabled(false);
            epScroll.addView(row);
            detailContent.addView(epScroll);
        }
        detailButtons.get(0).post(new Runnable() {
            @Override public void run() { detailButtons.get(0).requestFocus(); }
        });
    }

    private StateListDrawable chipBg() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(8));
        focused.setColor(0xFFFFFFFF);
        focused.setStroke(dp(2), 0xFF00A4DC);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(8));
        normal.setColor(0x1FFFFFFF);
        normal.setStroke(dp(1), 0x33FFFFFF);
        d.addState(new int[]{android.R.attr.state_focused}, focused);
        d.addState(new int[]{}, normal);
        return d;
    }

    private ColorStateList chipTextColors() {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF0B1116, 0xFFE6EEF5});
    }

    public boolean isDetailOpen() { return detailOpen; }

    public void closeDetail() {
        if (!detailOpen) return;
        detailOpen = false;
        detailEpoch++;
        detailScroll.setVisibility(View.GONE);
        detailBack.setVisibility(View.GONE);
        setRailFocusable(true);
        if (libOpen) libScroll.setVisibility(View.VISIBLE);
        else homeScroll.setVisibility(View.VISIBLE);
        if (detailSourceCard != null) detailSourceCard.requestFocus();
    }

    /** 详情盖住左栏期间，左栏不参与焦点搜索。 */
    private void setRailFocusable(boolean on) {
        for (TextView it : railItems) {
            it.setFocusable(on);
            it.setFocusableInTouchMode(on);
        }
    }

    // ---------------- 播放 ----------------

    private void playEpisode(String[] ep) {
        try {
            Intent it = new Intent(ctx, JellyfinPlayerActivity.class);
            it.putExtra("itemId", ep[0]);
            it.putExtra("title", ep[1]);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "playEpisode failed " + t);
        }
    }

    private void playItem(String id, String name) {
        try {
            Intent it = new Intent(ctx, JellyfinPlayerActivity.class);
            it.putExtra("itemId", id);
            it.putExtra("title", name);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "playItem failed " + t);
        }
    }

    // ---------------- 对外接口 ----------------

    /** 光标直接进影视墙第一行第一张（顶部 tab 按 ↓ 时用）。 */
    public void focusWallFirst() {
        post(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < homeContent.getChildCount(); i++) {
                    View v = homeContent.getChildAt(i);
                    if (v instanceof HorizontalScrollView) {
                        LinearLayout inner = (LinearLayout) ((HorizontalScrollView) v).getChildAt(0);
                        if (inner.getChildCount() > 0) { inner.getChildAt(0).requestFocus(); return; }
                    }
                }
                focusFirst();   // 墙还没数据时落到左栏
            }
        });
    }

    /** 页面初始光标：左栏第一项（主页）。post 到 attach 之后，否则首次打开时焦点落不上。 */
    public void focusFirst() {
        post(new Runnable() {
            @Override public void run() {
                if (railFocused()) return;
                if (railItems.size() > 0) railItems.get(0).requestFocus();
                // 附着初期焦点可能被翻页布局抢走，250ms 后补一次
                postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!railFocused() && railItems.size() > 0) railItems.get(0).requestFocus();
                    }
                }, 250);
            }
        });
    }

    /** 左栏第一项按 ↑ 时交回的顶部控件（宿主的「影视」tab）。 */
    public void setTopUpTarget(View top) {
        topUpTarget = top;
        if (top != null && railItems.size() > 0) {
            railItems.get(0).setNextFocusUpId(top.getId());
        }
    }

    private boolean railFocused() {
        for (TextView it : railItems) if (it.isFocused()) return true;
        return false;
    }

    // ---------------- 图片 ----------------

    private void loadWithFallback(final ImageView view, final String primary, final String fallback,
                                  final int targetHpx) {
        loader.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = primary == null || primary.isEmpty() ? null : JellyfinClient.poster(primary, targetHpx);
                if (bm == null && fallback != null && !fallback.isEmpty()) {
                    bm = JellyfinClient.poster(fallback, targetHpx);
                }
                if (bm == null) return;
                final Bitmap out = bm;
                post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(out); }
                });
            }
        });
    }

    /** 当前背景 ImageView 里的位图（没有则 null）。 */
    private Bitmap bgBitmap() {
        android.graphics.drawable.Drawable d = bg == null ? null : bg.getDrawable();
        return d instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
    }

    public void setBackdrop(android.graphics.Bitmap bmp) {
        if (bmp == null || bg == null) return;
        bg.setImageBitmap(bmp);
        bg.animate().alpha(1f).setDuration(400).start();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
