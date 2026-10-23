package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * MoviePilot 客户端：只读，取「推荐墙」用的海报数据。
 * 鉴权：X-API-Key（用 Bearer 会 401）。接口：/api/v1/media/search?title=
 */
public final class MpClient {

    private static final String TAG = "CYT-MP";
    public static final String BASE = "http://192.168.31.52:3000";
    private static final String KEY = "oasHPCoFmMUARUrkSYlFrw";
    /** 家里路由上的 HTTP 代理（Clash），图片直接走它出网，不依赖 MoviePilot 的 cookie。 */
    private static final String PROXY_HOST = "192.168.31.1";
    private static final int PROXY_PORT = 7890;

    private static android.content.Context ctx;

    /** 墙上一张卡。 */
    public static final class Item {
        public final String title, year, type, poster, overview;
        public final double rating;
        Item(String t, String y, String ty, String p, String o, double r) {
            title = t; year = y; type = ty; poster = p; overview = o; rating = r;
        }
    }

    /** 一个分区（流行趋势 / 正在热映 …）。 */
    public static final class Section {
        public final String title;
        public final List<Item> items = new ArrayList<>();
        Section(String t) { title = t; }
    }

    private static List<Section> cache;
    private static long cacheAt;

    private MpClient() { }

    /**
     * 推荐页数据。MoviePilot 的 /recommend/source 目前返回空，
     * 所以先用「精选片单 → TMDB 搜索」拼墙；接口通了只改这个方法。
     */
    public static synchronized List<Section> wall() {
        if (cache != null && System.currentTimeMillis() - cacheAt < 6 * 3600_000L) return cache;
        List<Section> out = new ArrayList<>();

        // 用的是 MoviePilot v3 的推荐接口（逆向自 app/api/endpoints/recommend.py）
        out.add(section("流行趋势", "/api/v1/recommend/tmdb_trending?page=1"));
        out.add(section("正在热映", "/api/v1/recommend/douban_showing"));
        out.add(section("豆瓣热门剧", "/api/v1/recommend/douban_tv_hot"));

        cache = out;
        cacheAt = System.currentTimeMillis();
        int total = 0;
        for (Section sec : out) total += sec.items.size();
        Log.i(TAG, "wall built sections=" + out.size() + " items=" + total);
        return out;
    }

    /** 一个分区：调一个推荐接口，取回条目。 */
    private static Section section(String title, String path) {
        Section sec = new Section(title);
        for (Item it : getItems(path)) sec.items.add(it);
        return sec;
    }

    /** GET 一个返回 {success,data:[...]} 的接口。 */
    private static List<Item> getItems(String path) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            java.net.URL url = new java.net.URL(BASE + path);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-API-Key", KEY);
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "get " + code + " " + path); return out; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String poster = o.optString("poster_path", "");
                if (poster.isEmpty()) continue;
                out.add(new Item(o.optString("title", ""), o.optString("year", ""),
                        o.optString("type", ""), poster, o.optString("overview", ""),
                        o.optDouble("vote_average", 0)));
            }
            Log.i(TAG, "get " + path + " -> " + out.size());
        } catch (Throwable t) {
            Log.w(TAG, "get failed " + path + " " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }


    public static void init(android.content.Context c) {
        ctx = c.getApplicationContext();
    }


    /**
     * 海报：走 MoviePilot 的图片代理（/api/v1/system/img/1），NAS 24 小时开着，
     * 电视不需要能连外网。带磁盘缓存。
     */
    public static Bitmap poster(String url) {
        if (url == null || url.isEmpty()) return null;
        java.io.File cached = cacheFile(url);
        if (cached != null && cached.exists()) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (b != null) return b;
        }
        // 1) 走代理直连图床（TMDB / 豆瓣）
        Bitmap b = fetchDirect(url);
        // 代理没开就没有海报（片名/评分照常显示）
        if (b != null && cached != null) save(cached, b);
        return b;
    }

    /** 通过 192.168.31.1:7890 直连图床，带 UA/Referer（豆瓣反爬必须带）。 */
    private static Bitmap fetchDirect(String url) {
        HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            if (ctx != null && !TVDeskPrefs.proxyOn(ctx)) {
                // 设置里关了代理：直连（一般会失败，只当调试用）
                c = (HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                c.setRequestProperty("Referer", url.contains("doubanio")
                        ? "https://movie.douban.com/" : "https://www.themoviedb.org/");
                int code0 = c.getResponseCode();
                if (code0 != 200) { Log.w(TAG, "direct http " + code0); return null; }
                in = c.getInputStream();
                return android.graphics.BitmapFactory.decodeStream(in);
            }
            String ph = ctx == null ? PROXY_HOST : TVDeskPrefs.proxyHost(ctx);
            int pp = ctx == null ? PROXY_PORT : TVDeskPrefs.proxyPort(ctx);
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(ph, pp));
            c = (HttpURLConnection) new java.net.URL(url).openConnection(proxy);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            c.setRequestProperty("Referer", url.contains("doubanio")
                    ? "https://movie.douban.com/" : "https://www.themoviedb.org/");
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "direct http " + code + " " + url); return null; }
            in = c.getInputStream();
            return android.graphics.BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            Log.w(TAG, "direct err " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }


    private static java.io.File cacheFile(String url) {
        if (ctx == null) return null;
        try {
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "mp_posters");
            if (!dir.exists()) dir.mkdirs();
            String name = Integer.toHexString(url.hashCode()) + "_" + url.length() + ".img";
            return new java.io.File(dir, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(java.io.File f, Bitmap b) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.flush(); out.close();
        } catch (Throwable ignored) { }
    }
}
