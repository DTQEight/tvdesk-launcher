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
    private void addRow(List<View> cards) {
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        for (View c : cards) inner.addView(c);
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
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
            @Override public void onClick(View v) {
                android.widget.Toast.makeText(ctx, card.title, android.widget.Toast.LENGTH_SHORT).show();
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
            it.putExtra("seriesId", card.id);
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
