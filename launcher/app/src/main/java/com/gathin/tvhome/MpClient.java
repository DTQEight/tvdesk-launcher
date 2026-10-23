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
    /** PC 上的图片代理（IMGPROXY，家里电脑跑 imgproxy.py）；留空就直连。 */
    public static final String PROXY = "http://192.168.31.28:8899/i?u=";

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


    private static void add(Section sec, String keyword) {
        Item it = searchOne(keyword);
        if (it == null) return;
        // 电视连不上 image.tmdb.org（TCP 被重置），改从 Jellyfin 取同名片的海报（走局域网）
        String jf = jellyfinPoster(it.title, it.year);
        if (jf == null) return;                       // 本地没有就不用，免得卡片空着
        sec.items.add(new Item(it.title, it.year, it.type, jf, it.overview, it.rating));
    }

    /** 在 Jellyfin 里按标题找同一部片，返回它的海报地址；找不到返回 null。 */
    public static String jellyfinPoster(String title, String year) {
        if (title == null || title.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            String q = URLEncoder.encode(title, "UTF-8");
            java.net.URL url = new java.net.URL(JellyfinClient.BASE + "/Items?searchTerm=" + q
                    + "&IncludeItemTypes=Series,Movie&Recursive=true&Limit=1&Fields=ImageTags,ProductionYear");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + JellyfinClient.TOKEN + "\"");
            if (c.getResponseCode() != 200) return null;
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("Items");
            if (arr == null || arr.length() == 0) return null;
            JSONObject it = arr.getJSONObject(0);
            JSONObject tags = it.optJSONObject("ImageTags");
            if (tags == null || !tags.has("Primary")) return null;
            String id = it.optString("Id", "");
            if (id.isEmpty()) return null;
            return JellyfinClient.BASE + "/Items/" + id + "/Images/Primary?maxHeight=520&quality=90";
        } catch (Throwable t) {
            Log.w(TAG, "jellyfinPoster failed " + title + " " + t);
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 搜一个关键词，取第一条有海报的。 */
    public static Item searchOne(String keyword) {
        List<Item> list = search(keyword, 6);
        for (Item i : list) {
            if (i.poster != null && !i.poster.isEmpty()) return i;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public static List<Item> search(String keyword, int limit) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            String q = URLEncoder.encode(keyword, "UTF-8");
            URL url = new URL(BASE + "/api/v1/media/search?title=" + q);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("X-API-Key", KEY);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "search http " + code + " for " + keyword); return out; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && i < limit; i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Item(
                        o.optString("title", ""),
                        o.optString("year", ""),
                        o.optString("type", ""),
                        o.optString("poster_path", ""),
                        o.optString("overview", ""),
                        o.optDouble("vote_average", 0)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "search failed " + keyword + " " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 海报位图（复用 Jellyfin 的磁盘缓存）。 */
    public static Bitmap poster(String url) {
        if (url == null || url.isEmpty()) return null;
        // 电视直连 image.tmdb.org 会被 TLS 重置，走 PC 上的图片代理（imgproxy.py）
        if (PROXY != null && !PROXY.isEmpty()) {
            try {
                String proxied = PROXY + java.net.URLEncoder.encode(url, "UTF-8");
                Bitmap b = JellyfinClient.poster(proxied, 520);
                if (b != null) return b;
            } catch (Throwable ignored) { }
        }
        return JellyfinClient.poster(url, 520);
    }
}
