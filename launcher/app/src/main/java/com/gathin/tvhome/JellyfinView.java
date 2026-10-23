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
 * 影视页：仿 Jellyfin Android TV 的排版。
 * 左侧竖排导航（主页 + 各媒体库），右侧横图卡片行（继续观看 / 最近添加）。
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
    private ScrollView libScroll;
    private LinearLayout libContent;
    private TextView libTitle;
    private View libBack;

    private boolean libOpen;
    private View firstFocus;
    private View lastFocused;
    private final List<List<View>> rows = new ArrayList<>();
    private final List<JellyfinClient.Card> views = new ArrayList<>();
    private final List<View> railItems = new ArrayList<>();

    public JellyfinView(Context c) {
        super(c);
        ctx = c;
        build();
    }

    private void build() {
        setBackgroundColor(0xCC0B1116);

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
        libBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLibrary(); }
        });

        loadEverything();
    }

    private void loadEverything() {
        final TextView loading = new TextView(ctx);
        loading.setText("正在读取影视库…");
        loading.setTextColor(0xFF8FA3B0);
        loading.setTextSize(16);
        homeContent.addView(loading);

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
                        homeContent.removeView(loading);
                        buildRail();
                        renderHome(resume, latests);
                    }
                });
            }
        }, "cyt-jf-load").start();
    }

    private void buildRail() {
        rail.removeAllViews();
        railItems.clear();

        TextView home = railItem("主页");
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        });
        rail.addView(home, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        railItems.add(home);

        for (final JellyfinClient.Card lib : views) {
            TextView item = railItem(lib.title);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openLibrary(lib); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            lp.topMargin = dp(6);
            rail.addView(item, lp);
            railItems.add(item);
        }
        firstFocus = home;
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
        tv.setTextColor(0xFFC9D6DF);
        tv.setBackground(railBg(false, false));
        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) lastFocused = v;
                boolean sel = v.getTag() != null && (Boolean) v.getTag();
                v.setBackground(railBg(sel, has));
                ((TextView) v).setTextColor(has || sel ? 0xFFFFFFFF : 0xFFC9D6DF);
            }
        });
        return tv;
    }

    private GradientDrawable railBg(boolean selected, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        if (focused) { d.setColor(0xFF2A3B4D); d.setStroke(dp(2), 0xFF00A4DC); }
        else if (selected) { d.setColor(0xFF1B2A38); }
        else { d.setColor(0x00000000); }
        return d;
    }

    private void markRailSelected(int index) {
        for (int i = 0; i < railItems.size(); i++) {
            View v = railItems.get(i);
            boolean sel = i == index;
            v.setTag(sel);
            v.setBackground(railBg(sel, v.hasFocus()));
            ((TextView) v).setTextColor(v.hasFocus() || sel ? 0xFFFFFFFF : 0xFFC9D6DF);
        }
    }

    private void renderHome(List<JellyfinClient.Card> resume,
                            List<List<JellyfinClient.Card>> latests) {
        homeContent.removeAllViews();
        rows.clear();

        if (!resume.isEmpty()) {
            addRowTitle(homeContent, "继续观看");
            List<View> cards = new ArrayList<>();
            for (JellyfinClient.Card c : resume) cards.add(landscapeCard(c, true));
            addRow(homeContent, cards);
        }

        for (int i = 0; i < views.size() && i < latests.size(); i++) {
            List<JellyfinClient.Card> list = latests.get(i);
            if (list.isEmpty()) continue;
            addRowTitle(homeContent, "最近添加 · " + views.get(i).title);
            List<View> cards = new ArrayList<>();
            for (JellyfinClient.Card c : list) cards.add(landscapeCard(c, false));
            addRow(homeContent, cards);
        }

        wireFocus(rows);
        if (!railItems.isEmpty()) railItems.get(0).requestFocus();
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

    private void addRow(LinearLayout parent, List<View> cards) {
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        for (View c : cards) inner.addView(c);
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);
        hsv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        hsv.addView(inner);
        parent.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.add(cards);
    }

    private View landscapeCard(final JellyfinClient.Card card, boolean showProgress) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        box.setBackground(cardBg(false));
        box.setId(View.generateViewId());

        final FrameLayout thumb = new FrameLayout(ctx);
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

            final View bar = new View(ctx);
            bar.setBackgroundColor(0xFF00A4DC);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(0, dp(4));
            blp.gravity = Gravity.BOTTOM;
            thumb.addView(bar, blp);
            final int pct = (int) Math.max(2, Math.min(100, card.progress));
            thumb.post(new Runnable() {
                @Override public void run() {
                    ViewGroup.LayoutParams lp = bar.getLayoutParams();
                    lp.width = Math.max(2, thumb.getWidth() * pct / 100);
                    bar.setLayoutParams(lp);
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
        box.setOnFocusChangeListener(focusEffect());
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(card); }
        });
        return box;
    }

    private void openLibrary(final JellyfinClient.Card lib) {
        libOpen = true;
        libContent.removeAllViews();
        homeScroll.setVisibility(View.GONE);
        libScroll.setVisibility(View.VISIBLE);
        libScroll.scrollTo(0, 0);
        libTitle.setText(lib.title);
        libTitle.setVisibility(View.VISIBLE);
        libBack.setVisibility(View.VISIBLE);
        libBack.requestFocus();

        int idx = 0;
        for (int i = 0; i < views.size(); i++) if (views.get(i).id.equals(lib.id)) idx = i + 1;
        markRailSelected(idx);

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

    private void renderLibrary(List<JellyfinClient.Card> items) {
        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("（这个库是空的）");
            empty.setTextColor(0xFF8FA3B0);
            empty.setTextSize(15);
            libContent.addView(empty);
            return;
        }
        final int perRow = 7;
        List<List<View>> grid = new ArrayList<>();
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
            if (row != null) row.addView(card);
            grid.get(grid.size() - 1).add(card);
        }
        wireFocus(grid);
    }

    public boolean isLibraryOpen() { return libOpen; }

    public void closeLibrary() {
        if (!libOpen) return;
        libOpen = false;
        libScroll.setVisibility(View.GONE);
        libTitle.setVisibility(View.GONE);
        libBack.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        markRailSelected(0);
        if (!railItems.isEmpty()) railItems.get(0).requestFocus();
    }

    private void showHome() {
        if (libOpen) closeLibrary();
        markRailSelected(0);
    }

    private View posterCard(final JellyfinClient.Card card) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        box.setBackground(cardBg(false));
        box.setId(View.generateViewId());

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
        box.setOnFocusChangeListener(focusEffect());
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(card); }
        });
        return box;
    }

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

    private View.OnFocusChangeListener focusEffect() {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) lastFocused = v;
                v.setBackground(cardBg(has));
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(110).start();
            }
        };
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        if (focused) { d.setColor(0xFF223140); d.setStroke(dp(3), 0xFF00A4DC); }
        else { d.setColor(0x00000000); }
        return d;
    }

    private void wireFocus(List<List<View>> rs) {
        for (int r = 0; r < rs.size(); r++) {
            List<View> row = rs.get(r);
            for (int i = 0; i < row.size(); i++) {
                View c = row.get(i);
                if (i + 1 < row.size()) c.setNextFocusRightId(row.get(i + 1).getId());
                if (i > 0) c.setNextFocusLeftId(row.get(i - 1).getId());
                if (r + 1 < rs.size() && !rs.get(r + 1).isEmpty()) {
                    List<View> next = rs.get(r + 1);
                    c.setNextFocusDownId(next.get(Math.min(i, next.size() - 1)).getId());
                }
                if (r > 0 && !rs.get(r - 1).isEmpty()) {
                    List<View> prev = rs.get(r - 1);
                    c.setNextFocusUpId(prev.get(Math.min(i, prev.size() - 1)).getId());
                }
            }
        }
    }

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

    /** 这一页拿到焦点。 */
    public void focusFirst() {
        if (libOpen && libBack != null) { libBack.requestFocus(); return; }
        if (firstFocus != null) { firstFocus.requestFocus(); return; }
        if (rail != null && rail.getChildCount() > 0) rail.getChildAt(0).requestFocus();
    }

    /** 用主页当前那张背景。 */
    public void setBackdrop(android.graphics.Bitmap bmp) {
        if (bmp == null || bg == null) return;
        bg.setImageBitmap(bmp);
        bg.animate().alpha(1f).setDuration(400).start();
    }

    private TextView chipText(String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setTextColor(0xFFE6EEF5);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setColor(0x1FFFFFFF);
        d.setStroke(dp(1), 0x33FFFFFF);
        tv.setBackground(d);
        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                GradientDrawable b = new GradientDrawable();
                b.setCornerRadius(dp(8));
                if (has) { b.setColor(0xFFFFFFFF); b.setStroke(dp(2), 0xFF00A4DC); }
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
