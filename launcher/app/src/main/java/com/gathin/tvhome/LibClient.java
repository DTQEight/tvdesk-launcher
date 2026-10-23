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
 * 2bt0 资源库（192.168.31.52:8000）客户端：给「推荐页」提供海报墙数据。
 * 鉴权：X-API-Key。海报 /posters/{id}.jpg 是局域网直出，不需要 key，也不需要代理。
 */
public final class LibClient {

    private static final String TAG = "CYT-LIB";
    public static final String BASE = "http://192.168.31.52:8000";
    private static final String KEY = "8t6j6_xW3RRHbWRtgbSGcrpdBABZ5Psd";

    private static android.content.Context ctx;
    private static List<Section> cache;
    private static long cacheAt;

    /** 墙上一张卡。 */
    public static final class Item {
        public final String id, title, year, poster, overview;
        public final double rating;
        public final int versions;
        Item(String i, String t, String y, String p, String o, double r, int v) {
            id = i; title = t; year = y; poster = p; overview = o; rating = r; versions = v;
        }
    }

    /** 资源库里命中的结果：给 MP 的推荐卡补海报 / movie_id / 版本数。 */
    public static final class Hit {
        public final String id, poster;
        public final int versions;
        Hit(String i, String p, int v) { id = i; poster = p; versions = v; }
    }

    /**
     * 按片名在资源库里找同名片。
     * GET /api/groups?q=<片名> → [{movie_id,title,image,years,versions,...}]
     */
    public static Hit lookup(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        String raw = get("/api/groups?q=" + enc(title.trim()) + "&page=1");
        if (raw == null) return null;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("groups");
            if (arr == null || arr.length() == 0) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("movie_id", "");
                String img = o.optString("image", "");
                if (id.isEmpty() || img.isEmpty()) continue;
                String t = o.optString("title", "");
                if (!similar(t, title)) continue;
                return new Hit(id, BASE + img, o.optInt("versions", 0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "lookup parse failed " + t);
        }
        return null;
    }

    /** 片名大致对得上（资源库片名常带季/副标题）。 */
    private static boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.replaceAll("[\\\\s　·・:：!-]","");
        String y = b.replaceAll("[\\\\s　·・:：!-]","");
        return !x.isEmpty() && (x.contains(y) || y.contains(x));
    }

    /** 一个分区。 */
    public static final class Section {
        public final String title;
        public final List<Item> items = new ArrayList<>();
        Section(String t) { title = t; }
    }

    private LibClient() { }

    public static void init(android.content.Context c) {
        ctx = c.getApplicationContext();
    }

    /** 推荐页数据：最新入库 / 电影 / 高分榜。 */
    /** 影片详情（点击卡片时取简介/导演/主演）。 */
    public static String detail(String id) {
        String raw = get("/api/movie/" + enc(id));
        if (raw == null) return null;
        try {
            JSONObject m = new JSONObject(raw).optJSONObject("movie");
            if (m == null) return null;
            StringBuilder sb = new StringBuilder();
            String cat = m.optString("category", "");
            String area = m.optString("area", "");
            if (!cat.isEmpty()) sb.append(cat);
            if (!area.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(area);
            String y = m.optString("years", "");
            if (!y.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(y);
            double score = m.optDouble("doub_score", 0);
            if (score > 0) sb.append("  ★ ").append(String.format("%.1f", score));
            sb.append("\n\n");
            String dir = m.optString("director", "");
            String act = m.optString("performer", "");
            if (!dir.isEmpty()) sb.append("导演：").append(dir).append('\n');
            if (!act.isEmpty()) sb.append("主演：").append(act).append("\n\n");
            String abs = m.optString("abstract", "");
            sb.append(abs.isEmpty() ? "（暂无简介）" : abs);
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "detail parse failed " + t);
            return null;
        }
    }

    /** 海报（局域网直连 + 磁盘缓存）。 */
    public static Bitmap poster(String url) {
        if (url == null || url.isEmpty()) return null;
        java.io.File f = cacheFile(url);
        if (f != null && f.exists()) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setRequestProperty("X-API-Key", KEY);
            if (c.getResponseCode() != 200) { Log.w(TAG, "poster http " + c.getResponseCode()); return null; }
            in = c.getInputStream();
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b != null && f != null) save(f, b);
            return b;
        } catch (Throwable t) {
            Log.w(TAG, "poster err " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static String get(String path) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-API-Key", KEY);
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "get " + code + " " + path); return null; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "get failed " + path + " " + t);
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static java.io.File cacheFile(String url) {
        if (ctx == null) return null;
        try {
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "lib_posters");
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

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }
}

