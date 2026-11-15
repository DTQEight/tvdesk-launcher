package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * MoviePilot 客户端：给「推荐页」提供推荐流（流行趋势 / 正在热映 / 豆瓣热门剧）。
 * 海报和详情交给 LibClient（2bt0 资源库 192.168.31.52:8000），走局域网。
 */
public final class MpClient {

    private static final String TAG = "CYT-MP";
    public static String BASE = "";   // 由 DeskConfig 注入
    static String KEY = "";            // 由 DeskConfig 注入

    /** 图床兜底代理（Clash），只给资源库没命中的片用。 */
    private static final String PROXY_HOST = "192.168.31.1";
    private static final int PROXY_PORT = 7890;

    private static android.content.Context ctx;
    private static List<Section> cache;
    private static long cacheAt;

    public static final class Item {
        public final String title, year, type, overview;
        public final double rating;
        public String poster;
        public String libId;
        public int versions;

        Item(String t, String y, String ty, String p, String o, double r) {
            title = t; year = y; type = ty; poster = p; overview = o; rating = r;
        }
    }

    public static final class Section {
        public final String title;
        public final List<Item> items = new ArrayList<>();
        Section(String t) { title = t; }
    }

    private MpClient() { }

    public static void init(android.content.Context c) {
        ctx = c.getApplicationContext();
    }

    public static boolean configured() {
        return BASE != null && !BASE.trim().isEmpty() && KEY != null && !KEY.trim().isEmpty();
    }

    public static synchronized List<Section> wall() {
        if (!configured()) return new ArrayList<>();
        if (cache != null && System.currentTimeMillis() - cacheAt < 30 * 60_000L) return cache;
        List<Section> out = new ArrayList<>();
        out.add(section("流行趋势", "/api/v1/recommend/tmdb_trending?page=1"));
        out.add(section("正在热映", "/api/v1/recommend/douban_showing"));
        out.add(section("豆瓣热门剧", "/api/v1/recommend/douban_tv_hot"));

        // 用资源库并行补海报 + movie_id（局域网直出，比图床快且不依赖代理）
        final List<Item> all = new ArrayList<>();
        for (Section sec : out) all.addAll(sec.items);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>();
        for (final Item it : all) {
            tasks.add(new java.util.concurrent.Callable<Void>() {
                @Override public Void call() {
                    try {
                        LibClient.Hit h = LibClient.lookup(it.title);
                        if (h != null) {
                            it.libId = h.id;
                            it.poster = h.poster;
                            it.versions = h.versions;
                        }
                    } catch (Throwable ignored) { }
                    return null;
                }
            });
        }
        try {
            pool.invokeAll(tasks, 12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "enrich failed " + t);
        } finally {
            pool.shutdownNow();
        }
        // 只留有资源库资源的片（保证每张卡点开都有磁力），不够的用资源库补位
        String[] topUp = new String[]{
                "/api/groups?page=1&sort=latest",
                "/api/groups?category=%E7%94%B5%E5%BD%B1&page=1&sort=latest",
                "/api/groups?page=1&sort=score"
        };
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int si = 0; si < out.size(); si++) {
            Section sec = out.get(si);
            java.util.Iterator<Item> itr = sec.items.iterator();
            while (itr.hasNext()) {
                Item it = itr.next();
                if (it.libId == null) { itr.remove(); continue; }
                used.add(it.libId);
            }
            if (sec.items.size() >= 12) continue;
            for (LibClient.Row row : LibClient.rows(topUp[Math.min(si, topUp.length - 1)])) {
                if (sec.items.size() >= 12) break;
                if (used.contains(row.id)) continue;
                used.add(row.id);
                Item fill = new Item(row.title, row.year, "", row.poster, "", row.score);
                fill.libId = row.id;
                fill.poster = row.poster;
                fill.versions = row.versions;
                sec.items.add(fill);
            }
        }
        for (int si = out.size() - 1; si >= 0; si--) if (out.get(si).items.isEmpty()) out.remove(si);

        int hit2 = 0;
        for (Section s3 : out) hit2 += s3.items.size();
        int hit = hit2;
        // 空结果不进缓存（开机时网络未就绪拉取失败，不能让空页挂 30 分钟）
        if (!out.isEmpty()) {
            cache = out;
            cacheAt = System.currentTimeMillis();
        }
        int total = 0;
        for (Section s2 : out) total += s2.items.size();
        Log.i(TAG, "wall built sections=" + out.size() + " items=" + total + " libHit=" + hit);
        return out;
    }

    private static Section section(String title, String path) {
        Section sec = new Section(title);
        JSONArray arr = data(path);
        if (arr == null) return sec;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String poster = o.optString("poster_path", "");
                if (poster.isEmpty()) continue;
                sec.items.add(new Item(o.optString("title", ""), o.optString("year", ""),
                        o.optString("type", ""), poster, o.optString("overview", ""),
                        o.optDouble("vote_average", 0)));
            } catch (Throwable ignored) { }
        }
        return sec;
    }

    private static JSONArray data(String path) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            // MoviePilot 的 API Key 只认 query 参数 token=（X-API-Key 头会被 401 拒掉）
            String sep = path.contains("?") ? "&" : "?";
            c = (HttpURLConnection) new URL(BASE + path + sep + "token=" + KEY).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-API-Key", KEY);
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "get " + code + " " + path); return null; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONObject(sb.toString()).optJSONArray("data");
            Log.i(TAG, "get " + path + " -> " + (arr == null ? 0 : arr.length()));
            return arr;
        } catch (Throwable t) {
            Log.w(TAG, "get failed " + path + " " + t);
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    public static Bitmap poster(String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith(LibClient.BASE)) return LibClient.poster(url);
        return fetchViaProxy(url);
    }

    private static Bitmap fetchViaProxy(String url) {
        java.io.File cached = cacheFile(url);
        if (cached != null && cached.exists()) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (b != null) return b;
        }
        HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            String host = ctx == null ? PROXY_HOST : TVDeskPrefs.proxyHost(ctx);
            int port = ctx == null ? PROXY_PORT : TVDeskPrefs.proxyPort(ctx);
            c = (HttpURLConnection) new URL(url).openConnection(
                    new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(host, port)));
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Referer", url.contains("doubanio")
                    ? "https://movie.douban.com/" : "https://www.themoviedb.org/");
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "poster http " + code); return null; }
            in = c.getInputStream();
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b != null && cached != null) save(cached, b);
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "poster err " + t);
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
            return new java.io.File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".img");
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
