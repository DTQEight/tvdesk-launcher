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
 * 影视库页（ViewPager2 第 3 页）：Jellyfin 全库，排版参考 Jellyfin 网页版。
 * 我的媒体 / 继续观看 / 最近添加的 各分类。
 */
public class JellyfinView extends FrameLayout {

    private static final String TAG = "CYT-JF";

    private final Context ctx;
    private LinearLayout content;
    private View firstFocus;
    private ImageView bg;
    private ScrollView wallScroll;
    private ScrollView libScroll;
    private LinearLayout libContent;
    private TextView libTitle, libBack;
    private boolean libOpen;
    private final List<List<View>> rows = new ArrayList<>();
    private View lastFocused;
    private final java.util.concurrent.ExecutorService loader =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    public JellyfinView(Context c) {
        super(c);
        ctx = c;
        build();
    }

    private void build() {
        final TextView loading = new TextView(ctx);
        loading.setText("正在读取影视库…");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);

        // 背景：用主页同一张剧照 + 压暗，既遮住主页内容又能看到背景
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

        ScrollView scroll = new ScrollView(ctx);
        wallScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(20), dp(56), dp(60));
        content.addView(loading);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(104);
        addView(scroll, slp);

        // 媒体库页（默认隐藏）
        libScroll = new ScrollView(ctx);
        libScroll.setVerticalScrollBarEnabled(false);
        libScroll.setVisibility(View.GONE);
        libScroll.setBackgroundColor(0xD90B1116);
        libContent = new LinearLayout(ctx);
        libContent.setOrientation(LinearLayout.VERTICAL);
        libContent.setPadding(dp(56), dp(66), dp(56), dp(60));
        libScroll.addView(libContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams llp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp2.topMargin = dp(104);
        addView(libScroll, llp2);

        libTitle = new TextView(ctx);
        libTitle.setTextColor(0xFFFFFFFF);
        libTitle.setTextSize(26);
        libTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        libTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp2.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        tlp2.leftMargin = dp(56);
        tlp2.topMargin = dp(120);   // 让开顶部 chrome
        addView(libTitle, tlp2);

        libBack = chipText("← 我的媒体");
        libBack.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(dp(150), dp(40));
        blp2.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        blp2.rightMargin = dp(56);
        blp2.topMargin = dp(120);
        addView(libBack, blp2);
        libBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLibrary(); }
        });

        new Thread(new Runnable() {
            @Override public void run() {
                final List<JellyfinClient.Card> views = JellyfinClient.views();
                final List<JellyfinClient.Card> resume = JellyfinClient.resumeCards(20);
                final List<List<JellyfinClient.Card>> latests = new ArrayList<>();
                for (JellyfinClient.Card v : views) latests.add(JellyfinClient.latest(v.id, 16));
                post(new Runnable() {
                    @Override public void run() {
                        content.removeView(loading);
                        render(views, resume, latests);
                    }
                });
            }
        }, "cyt-jf-views").start();
    }

    private void render(List<JellyfinClient.Card> views, List<JellyfinClient.Card> resume,
                        List<List<JellyfinClient.Card>> latests) {
        if (!views.isEmpty()) {
            addSectionTitle("我的媒体", null);
            List<View> cards = new ArrayList<>();
            for (JellyfinClient.Card v : views) cards.add(wideCard(v));
            addRow(cards);
        }
        if (!resume.isEmpty()) {
            addSectionTitle("继续观看", null);
            List<View> cards = new ArrayList<>();
            for (JellyfinClient.Card c : resume) cards.add(posterCard(c));
            addRow(cards);
        }
        for (int i = 0; i < views.size() && i < latests.size(); i++) {
            List<JellyfinClient.Card> list = latests.get(i);
            if (list.isEmpty()) continue;
            JellyfinClient.Card v = views.get(i);
            addSectionTitle("最近添加的 " + v.title, null);
            List<View> cards = new ArrayList<>();
            for (JellyfinClient.Card c : list) cards.add(posterCard(c));
            addRow(cards);
        }
        wireFocus(rows);
        if (firstFocus != null) firstFocus.requestFocus();
    }

    /** 这一页拿到焦点。 */
    /** 用主页当前那张背景（两页一致）。 */
    public void setBackdrop(android.graphics.Bitmap bmp) {
        if (bmp == null || bg == null) return;
        bg.setImageBitmap(bmp);
        bg.animate().alpha(1f).setDuration(400).start();
    }

    public void focusFirst() {
        if (firstFocus != null) { firstFocus.requestFocus(); return; }
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof HorizontalScrollView) {
                ViewGroup r = (ViewGroup) ((HorizontalScrollView) v).getChildAt(0);
                if (r != null && r.getChildCount() > 0) { r.getChildAt(0).requestFocus(); return; }
            }
        }
    }

    /** 加一横行（可左右滚动）。 */
    /** 把每行卡片左右串起来，上下按列对齐，避免焦点乱跳。 */
    private void wireFocus(List<List<View>> rs) {
        for (int r = 0; r < rs.size(); r++) {
            List<View> row = rs.get(r);
            for (int i = 0; i < row.size(); i++) {
                View c = row.get(i);
                if (i + 1 < row.size()) c.setNextFocusRightId(row.get(i + 1).getId());
                if (i > 0) c.setNextFocusLeftId(row.get(i - 1).getId());
                if (r + 1 < rs.size()) {
                    List<View> next = rs.get(r + 1);
                    if (!next.isEmpty()) {
                        c.setNextFocusDownId(next.get(Math.min(i, next.size() - 1)).getId());
                    }
                }
                if (r > 0) {
                    List<View> prev = rs.get(r - 1);
                    if (!prev.isEmpty()) {
                        c.setNextFocusUpId(prev.get(Math.min(i, prev.size() - 1)).getId());
                    }
                }
            }
        }
    }

    private void addRow(List<View> cards) {
        rows.add(cards);
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        for (View c : cards) inner.addView(c);
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);   // 焦点交给里面的卡片
        hsv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        hsv.setClipChildren(false);
        hsv.addView(inner);
        content.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 我的媒体：宽卡片 + 背景图。 */
    private View wideCard(final JellyfinClient.Card card) {
        FrameLayout box = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF16202B);
        box.setBackground(bg);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setId(View.generateViewId());

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadWithFallback(img, card.backdropUrl, card.posterUrl);

        View dim = new View(ctx);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22000000, 0xCC000000}));
        box.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView name = new TextView(ctx);
        name.setText(card.title);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(20);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.START | Gravity.BOTTOM;
        nlp.leftMargin = dp(18);
        nlp.bottomMargin = dp(14);
        box.addView(name, nlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(170));
        lp.rightMargin = dp(16);
        box.setLayoutParams(lp);
        box.setOnFocusChangeListener(focusEffect());
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLibrary(card); }
        });
        box.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                boolean ok = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
                        if (ok && e.getAction() == android.view.KeyEvent.ACTION_UP) {   // DOWN 被上层吞了，只能收 UP
                    openLibrary(card);
                    return true;
                }
                return false;
            }
        });
        if (firstFocus == null) firstFocus = box;
        return box;
    }

    /** 影片/剧集海报卡。 */
    private View posterCard(final JellyfinClient.Card card) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setPadding(dp(8), dp(8), dp(8), dp(10));
        box.setBackground(cardBg(false));
        box.setId(View.generateViewId());

        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF1A2330);
        box.addView(img, new LinearLayout.LayoutParams(dp(150), dp(225)));
        if (card.posterUrl != null) load(img, card.posterUrl);

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
        lp.rightMargin = dp(14);
        box.setLayoutParams(lp);
        box.setOnFocusChangeListener(focusEffect());
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(card); }
        });
        if (firstFocus == null) firstFocus = box;
        return box;
    }

    private void play(JellyfinClient.Card card) {
        try {
            Intent it = new Intent(ctx, JellyfinPlayerActivity.class);
            if ("Series".equals(card.type)) {
                it.putExtra("seriesId", card.id);
            } else {
                it.putExtra("itemId", card.id);   // 电影/单集：直接播这一条
            }
            it.putExtra("title", card.title);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "play failed " + t);
        }
    }

    private View.OnFocusChangeListener focusEffect() {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) lastFocused = v;
                v.setBackground(cardBg(has));
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
            }
        };
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        if (focused) {
            d.setColor(0xFF16202B);
            d.setStroke(dp(3), 0xFF00A4DC);
        } else {
            d.setColor(0x00000000);
            d.setStroke(dp(1), 0x22FFFFFF);
        }
        return d;
    }

    /** 先试背景图，404 就退到海报。 */
    private void loadWithFallback(final ImageView view, final String primary, final String fallback) {
        loader.execute(new Runnable() {
            @Override public void run() {
                Bitmap bm = primary == null ? null : JellyfinClient.poster(primary, 520);
                if (bm == null && fallback != null) bm = JellyfinClient.poster(fallback, 520);
                if (bm == null) return;
                final Bitmap out = bm;
                post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(out); }
                });
            }
        });
    }

    private void load(final ImageView view, final String url) {
        loader.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bm = JellyfinClient.poster(url, 520);
                if (bm == null) return;
                post(new Runnable() {
                    @Override public void run() { view.setImageBitmap(bm); }
                });
            }
        });
    }

    private void addSectionTitle(String text, final View.OnClickListener more) {
        TextView tv = new TextView(ctx);
        tv.setText(text + (more != null ? "  ›" : ""));
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(20);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        if (more != null) {
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.setOnClickListener(more);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(12);
        content.addView(tv, lp);
    }

    // ---------------- 媒体库页（点「我的媒体」卡片进入） ----------------

    /** 打开某个库的条目列表。 */
    private void openLibrary(final JellyfinClient.Card lib) {
        Log.i(TAG, "openLibrary " + lib.id + " " + lib.title + " libScroll=" + (libScroll != null));
        if (libScroll == null) return;
        libOpen = true;
        libContent.removeAllViews();
        if (wallScroll != null) wallScroll.setVisibility(View.GONE);
        libScroll.setVisibility(View.VISIBLE);
        libScroll.scrollTo(0, 0);
        if (libTitle != null) { libTitle.setText(lib.title); libTitle.setVisibility(View.VISIBLE); }
        if (libBack != null) { libBack.setVisibility(View.VISIBLE); libBack.requestFocus(); }

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
                    }
                });
            }
        }, "cyt-jf-lib").start();
    }

    /** 网格铺海报：每行 6 张。 */
    private void renderLibrary(List<JellyfinClient.Card> items) {
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("（这个库是空的）");
            empty.setTextColor(0xFF8FA3B0);
            empty.setTextSize(15);
            libContent.addView(empty);
            return;
        }
        final int perRow = 6;
        final java.util.List<java.util.List<View>> grid = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(10);
                libContent.addView(row, rlp);
                grid.add(new ArrayList<View>());
            }
            View card = posterCard(items.get(i));
            if (!grid.isEmpty()) grid.get(grid.size() - 1).add(card);
            if (row != null) row.addView(card);
        }
        wireFocus(grid);
    }

    public boolean isLibraryOpen() { return libOpen; }

    public void closeLibrary() {
        if (!libOpen) return;
        libOpen = false;
        if (libScroll != null) libScroll.setVisibility(View.GONE);
        if (libTitle != null) libTitle.setVisibility(View.GONE);
        if (libBack != null) libBack.setVisibility(View.GONE);
        if (wallScroll != null) wallScroll.setVisibility(View.VISIBLE);
        if (lastFocused != null) lastFocused.requestFocus();
    }

    /** 简单的圆角按钮（媒体库页返回用）。 */
    private TextView chipText(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setTextColor(0xFFE6EEF5);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(0x1FFFFFFF);
        d.setStroke(dp(1), 0x33FFFFFF);
        tv.setBackground(d);
        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(dp(10));
                if (has) { b.setColor(0xFFFFFFFF); b.setStroke(dp(3), 0xFF00A4DC); }
                else { b.setColor(0x1FFFFFFF); b.setStroke(dp(1), 0x33FFFFFF); }
                v.setBackground(b);
                ((TextView) v).setTextColor(has ? 0xFF0B1116 : 0xFFE6EEF5);
            }
        });
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
