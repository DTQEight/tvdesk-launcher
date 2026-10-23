package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
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
 * 焦点不交给系统，自己维护 (行, 列) 选择状态，遥控器走位才可预期。
 */
public class JellyfinView extends FrameLayout {

    private static final String TAG = "CYT-JF";
    private static final int MODE_RAIL = 0;
    private static final int MODE_CONTENT = 1;

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
    /** 顶部 tab 接管焦点时，影视页不再吃按键、也不画选中框。 */
    private boolean tabsFocused;
    private boolean detailOpen;

    private final List<JellyfinClient.Card> views = new ArrayList<>();
    private final List<View> railItems = new ArrayList<>();
    private final List<List<View>> rowViews = new ArrayList<>();
    private final List<List<JellyfinClient.Card>> rowCards = new ArrayList<>();
    private final List<List<View>> libViews = new ArrayList<>();
    private final List<List<JellyfinClient.Card>> libCards = new ArrayList<>();

    // 详情页里的可选项：播放按钮 + 剧集
    private final List<View> detailButtons = new ArrayList<>();
    private final List<String[]> detailEpisodes = new ArrayList<>();
    private String detailMovieId, detailMovieTitle;

    private int mode = MODE_RAIL;
    private int railSel = 0;
    private int rowSel = 0;
    private int colSel = 0;
    private int libRowSel = 0, libColSel = 0;
    private int detailSel = 0;

    public JellyfinView(Context c) {
        super(c);
        ctx = c;
        build();
    }

    // ---------------- 布局 ----------------

    private void build() {
        setBackgroundColor(0xFF0B1116);   // 不透明，否则主页 dock 会透出来
        // 整页自己拿焦点（方向键由 Activity 转进来），不依赖子控件能不能聚焦
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0f);
        addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(ctx);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC0B1116, 0xE60B1116}));
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
        libScroll.setBackgroundColor(0xFF0B1116);
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

        libBack = chipText("← 返回");
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

        detailBack = chipText("← 返回");
        detailBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams dblp = new FrameLayout.LayoutParams(dp(110), dp(40));
        dblp.gravity = Gravity.TOP | Gravity.END;
        dblp.rightMargin = dp(56);
        dblp.topMargin = dp(120);
        addView(detailBack, dblp);

        loadEverything();
    }

    private void loadEverything() {
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
                        views.clear();
                        views.addAll(v);
                        homeContent.removeView(emptyHint);
                        buildRail();
                        renderHome(resume, latests);
                        highlight();
                    }
                });
            }
        }, "cyt-jf-load").start();
    }

    // ---------------- 左栏 ----------------

    private void buildRail() {
        rail.removeAllViews();
        railItems.clear();
        railItems.add(railItem("主页"));
        for (JellyfinClient.Card lib : views) railItems.add(railItem(lib.title));
        for (View v : railItems) rail.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private TextView railItem(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), 0, dp(10), 0);
        tv.setTextColor(0xFFC9D6DF);
        tv.setBackground(railBg(false, false));
        return tv;
    }

    private GradientDrawable railBg(boolean selected, boolean current) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        if (selected) { d.setColor(0xFF2A3B4D); d.setStroke(dp(2), 0xFF00A4DC); }
        else if (current) { d.setColor(0xFF1B2A38); }
        else { d.setColor(0x00000000); }
        return d;
    }

    // ---------------- 主页内容 ----------------

    private void renderHome(List<JellyfinClient.Card> resume,
                            List<List<JellyfinClient.Card>> latests) {
        homeContent.removeAllViews();
        rowViews.clear();
        rowCards.clear();

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
        List<View> viewsInRow = new ArrayList<>();
        for (JellyfinClient.Card c : cards) {
            View card = landscapeCard(c, progress);
            inner.addView(card);
            viewsInRow.add(card);
        }
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(inner);
        parent.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowViews.add(viewsInRow);
        rowCards.add(cards);
    }

    private View landscapeCard(final JellyfinClient.Card card, boolean showProgress) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        box.setBackground(cardBg(false));

        FrameLayout thumb = new FrameLayout(ctx);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF1A2330);
        thumb.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadWithFallback(img, card.landscapeUrl, card.posterUrl);

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
        box.setBackground(cardBg(false));

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF1A2330);
        box.addView(img, new LinearLayout.LayoutParams(dp(150), dp(225)));
        loadWithFallback(img, card.posterUrl, null);

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

    private GradientDrawable cardBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        if (selected) { d.setColor(0xFF223140); d.setStroke(dp(3), 0xFF00A4DC); }
        else { d.setColor(0x00000000); }
        return d;
    }

    // ---------------- 高亮 ----------------

    private void highlight() {
        for (int i = 0; i < railItems.size(); i++) {
            View v = railItems.get(i);
            boolean sel = mode == MODE_RAIL && i == railSel;
            v.setBackground(railBg(sel, i == railSel));
            ((TextView) v).setTextColor(sel || i == railSel ? 0xFFFFFFFF : 0xFFC9D6DF);
        }
        for (int r = 0; r < rowViews.size(); r++) {
            List<View> row = rowViews.get(r);
            for (int c = 0; c < row.size(); c++) {
                boolean sel = !tabsFocused && !libOpen && mode == MODE_CONTENT && r == rowSel && c == colSel;
                row.get(c).setBackground(cardBg(sel));
            }
        }
        for (int r = 0; r < libViews.size(); r++) {
            List<View> row = libViews.get(r);
            for (int c = 0; c < row.size(); c++) {
                boolean sel = !tabsFocused && libOpen && mode == MODE_CONTENT && r == libRowSel && c == libColSel;
                row.get(c).setBackground(cardBg(sel));
            }
        }
        for (int i = 0; i < detailButtons.size(); i++) {
            View v = detailButtons.get(i);
            v.setBackground(chipBg(detailOpen && i == detailSel));
            ((TextView) v).setTextColor(detailOpen && i == detailSel ? 0xFF0B1116 : 0xFFE6EEF5);
        }
        scrollSelectionIntoView();
    }

    private void scrollSelectionIntoView() {
        View target = null;
        if (detailOpen) {
            if (detailSel < detailButtons.size()) target = detailButtons.get(detailSel);
        } else if (libOpen) {
            if (libRowSel < libViews.size() && libColSel < libViews.get(libRowSel).size()) {
                target = libViews.get(libRowSel).get(libColSel);
            }
        } else if (mode == MODE_CONTENT && rowSel < rowViews.size()
                && colSel < rowViews.get(rowSel).size()) {
            target = rowViews.get(rowSel).get(colSel);
        }
        if (target == null) return;
        final View t = target;
        t.post(new Runnable() {
            @Override public void run() {
                t.requestRectangleOnScreen(new android.graphics.Rect(0, dp(110), 0, dp(70)), true);
            }
        });
    }

    // ---------------- 按键 ----------------

    public void setTabsFocused(boolean on) {
        tabsFocused = on;
        highlight();
    }

    public boolean handleKey(int keyCode) {
        if (tabsFocused) return false;
        if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                && keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP
                && keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                && keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER
                && keyCode != android.view.KeyEvent.KEYCODE_ENTER) {
            return false;
        }
        if (detailOpen) { detailKey(keyCode); return true; }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            activate();
            return true;
        }
        if (mode == MODE_RAIL) railKey(keyCode); else contentKey(keyCode);
        highlight();
        return true;
    }

    private void railKey(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            if (railSel > 0) railSel--;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (railSel + 1 < railItems.size()) railSel++;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (libOpen) {
                if (!libViews.isEmpty()) { mode = MODE_CONTENT; libRowSel = 0; libColSel = 0; }
            } else if (!rowViews.isEmpty()) {
                mode = MODE_CONTENT; rowSel = 0; colSel = 0;
            }
        }
    }

    private void contentKey(int keyCode) {
        List<List<View>> viewsRows = libOpen ? libViews : rowViews;
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            if (libOpen) {
                if (libColSel > 0) libColSel--;
                else mode = MODE_RAIL;
            } else {
                if (colSel > 0) colSel--;
                else mode = MODE_RAIL;
            }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (libOpen) {
                if (libColSel + 1 < viewsRows.get(libRowSel).size()) libColSel++;
            } else {
                if (colSel + 1 < viewsRows.get(rowSel).size()) colSel++;
            }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            if (libOpen) {
                if (libRowSel > 0) { libRowSel--; libColSel = Math.min(libColSel, viewsRows.get(libRowSel).size() - 1); }
                else mode = MODE_RAIL;
            } else {
                if (rowSel > 0) { rowSel--; colSel = Math.min(colSel, viewsRows.get(rowSel).size() - 1); }
                else mode = MODE_RAIL;
            }
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (libOpen) {
                if (libRowSel + 1 < viewsRows.size()) { libRowSel++; libColSel = Math.min(libColSel, viewsRows.get(libRowSel).size() - 1); }
            } else {
                if (rowSel + 1 < viewsRows.size()) { rowSel++; colSel = Math.min(colSel, viewsRows.get(rowSel).size() - 1); }
            }
        }
    }

    private void activate() {
        if (mode == MODE_RAIL) {
            if (railSel == 0) { closeLibrary(); }
            else if (railSel - 1 < views.size()) openLibrary(views.get(railSel - 1));
            return;
        }
        if (libOpen) {
            if (libRowSel < libCards.size() && libColSel < libCards.get(libRowSel).size()) {
                openDetail(libCards.get(libRowSel).get(libColSel));
            }
        } else if (rowSel < rowCards.size() && colSel < rowCards.get(rowSel).size()) {
            openDetail(rowCards.get(rowSel).get(colSel));
        }
    }

    private void detailKey(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (detailSel + 1 < detailButtons.size()) detailSel++;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            if (detailSel > 0) detailSel--;
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            detailSel = Math.max(0, detailSel - 1);
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            detailSel = Math.min(detailButtons.size() - 1, detailSel + 1);
        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            activateDetail();
        }
        highlight();
    }

    private void activateDetail() {
        if (detailSel == 0) {
            if (!detailEpisodes.isEmpty()) playEpisode(detailEpisodes.get(0));
            else if (detailMovieId != null) playItem(detailMovieId, detailMovieTitle);
        } else {
            int idx = detailSel - 1;
            if (idx < detailEpisodes.size()) playEpisode(detailEpisodes.get(idx));
        }
    }

    // ---------------- 库页 ----------------

    private void openLibrary(final JellyfinClient.Card lib) {
        libOpen = true;
        mode = MODE_RAIL;
        libContent.removeAllViews();
        libViews.clear();
        libCards.clear();
        homeScroll.setVisibility(View.GONE);
        libScroll.setVisibility(View.VISIBLE);
        libScroll.scrollTo(0, 0);
        libTitle.setText(lib.title);
        libTitle.setVisibility(View.VISIBLE);
        libBack.setVisibility(View.VISIBLE);

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
                        libContent.removeView(loading);
                        renderLibrary(items);
                        highlight();
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
                libViews.add(new ArrayList<View>());
                libCards.add(new ArrayList<JellyfinClient.Card>());
            }
            View card = posterCard(items.get(i));
            row.addView(card);
            libViews.get(libViews.size() - 1).add(card);
            libCards.get(libCards.size() - 1).add(items.get(i));
        }
    }

    public boolean isLibraryOpen() { return libOpen; }

    public void closeLibrary() {
        if (!libOpen) return;
        libOpen = false;
        mode = MODE_RAIL;
        libScroll.setBackgroundColor(0xFF0B1116);
        libScroll.setVisibility(View.GONE);
        libTitle.setVisibility(View.GONE);
        libBack.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        highlight();
    }

    // ---------------- 详情页 ----------------

    private void openDetail(final JellyfinClient.Card card) {
        detailOpen = true;
        detailSel = 0;
        detailButtons.clear();
        detailEpisodes.clear();
        detailContent.removeAllViews();
        homeScroll.setVisibility(View.GONE);
        libScroll.setBackgroundColor(0xFF0B1116);
        libScroll.setVisibility(View.GONE);
        detailScroll.setVisibility(View.VISIBLE);
        detailScroll.scrollTo(0, 0);
        detailBack.setVisibility(View.VISIBLE);

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
                        detailContent.removeView(loading);
                        renderDetail(d, eps);
                        highlight();
                    }
                });
            }
        }, "cyt-jf-detail").start();
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
        loadWithFallback(heroImg, d.backdrop, d.poster);
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
        loadWithFallback(poster, d.poster, null);

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
        TextView playBtn = chipText("▶ 播放");
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(dp(150), dp(44));
        blp2.topMargin = dp(16);
        btns.addView(playBtn, blp2);
        detailButtons.add(playBtn);
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
                TextView eb = chipText(eps.get(i)[1]);
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(150), dp(44));
                elp.rightMargin = dp(8);
                elp.topMargin = dp(6);
                row.addView(eb, elp);
                detailButtons.add(eb);
            }
            detailContent.addView(row);
        }
    }

    /** 选择是否已顶到最上面（左栏第一项、不是详情/库页）。 */
    public boolean isAtTop() {
        return !detailOpen && !libOpen && mode == MODE_RAIL && railSel == 0;
    }
    public boolean isDetailOpen() { return detailOpen; }

    public void closeDetail() {
        if (!detailOpen) return;
        detailOpen = false;
        detailScroll.setVisibility(View.GONE);
        detailBack.setVisibility(View.GONE);
        if (libOpen) libScroll.setVisibility(View.VISIBLE);
        else homeScroll.setVisibility(View.VISIBLE);
        highlight();
    }

    // ---------------- 播放 ----------------

    private void play(JellyfinClient.Card card) {
        try {
            Intent it = new Intent(ctx, JellyfinPlayerActivity.class);
            if ("Series".equals(card.type)) it.putExtra("seriesId", card.id);
            else it.putExtra("itemId", card.id);
            it.putExtra("title", card.title);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "play failed " + t);
        }
    }

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

    // ---------------- 工具 ----------------

    private void loadWithFallback(final ImageView view, final String primary, final String fallback) {
        loader.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = primary == null || primary.isEmpty() ? null : JellyfinClient.poster(primary, 520);
                if (bm == null && fallback != null && !fallback.isEmpty()) {
                    bm = JellyfinClient.poster(fallback, 520);
                }
                if (bm == null) return;
                final Bitmap out = bm;
                post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(out); }
                });
            }
        });
    }

    public void focusFirst() {
        mode = MODE_RAIL;
        highlight();
    }

    public void setBackdrop(android.graphics.Bitmap bmp) {
        if (bmp == null || bg == null) return;
        bg.setImageBitmap(bmp);
        bg.animate().alpha(1f).setDuration(400).start();
    }

    private GradientDrawable chipBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        if (selected) { d.setColor(0xFFFFFFFF); d.setStroke(dp(2), 0xFF00A4DC); }
        else { d.setColor(0x1FFFFFFF); d.setStroke(dp(1), 0x33FFFFFF); }
        return d;
    }

    private TextView chipText(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTextColor(0xFFE6EEF5);
        tv.setBackground(chipBg(false));
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
