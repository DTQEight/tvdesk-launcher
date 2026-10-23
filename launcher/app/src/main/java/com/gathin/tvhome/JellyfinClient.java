package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 本地 Jellyfin（Mcdull-Nas）海报墙数据源。 */
public final class JellyfinClient {

    private static final String TAG = "CYT-JF";
    public static final String BASE = "http://192.168.31.52:8096";
    public static final String TOKEN = "af6f7e3b29f84b50b8773c17f2bc325c";
    /** 「国产剧」媒体库 */
    public static final String LIB_CN_DRAMA = "9a63b0643a0b5e4f2cd3e747c56fffbe";

    private JellyfinClient() { }

    public static final class Item {
        public final String id;
        public final String name;
        public final String tag;
        public final int year;
        /** 剧集条目才有：所属剧的 id（封面用它，比单集截图好看） */
        public final String seriesId;
        public final boolean episode;
        Item(String id, String name, String tag, int year, String seriesId, boolean episode) {
            this.id = id; this.name = name; this.tag = tag; this.year = year;
            this.seriesId = seriesId; this.episode = episode;
        }
        public String posterUrl() {
            String imgId = seriesId != null ? seriesId : id;
            return BASE + "/Items/" + imgId + "/Images/Primary?maxHeight=720&quality=90"
                    + (tag == null ? "" : "&tag=" + tag);
        }
    }

    private static android.content.Context appCtx;

    /** 由 Application 调用，用于离线缓存。 */
    public static void init(android.content.Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    private static void saveCache(String name, String text) {
        if (appCtx == null || text == null) return;
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(appCtx.getCacheDir(), name));
            fo.write(text.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) { }
    }

    private static String readCache(String name) {
        if (appCtx == null) return null;
        try {
            java.io.File f = new java.io.File(appCtx.getCacheDir(), name);
            if (!f.exists()) return null;
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + TOKEN + "\"");
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        return c;
    }

    /** 取某媒体库里的剧集，按添加时间倒序。 */
    public static List<Item> series(String libraryId, int limit) {
        String raw = fetchJson(BASE + "/Items?ParentId=" + libraryId
                + "&IncludeItemTypes=Series&Recursive=true"
                + "&Fields=ProductionYear&ImageTypeLimit=1&EnableImageTypes=Primary"
                + "&SortBy=DateCreated&SortOrder=Descending&Limit=" + limit, "cache_series.json");
        List<Item> out = parseItems(raw);
        Log.i(TAG, "series=" + out.size());
        return out;
    }

    /** GET 一个 JSON；失败时回退到本地缓存（离线可用）。 */
    private static String fetchJson(String url, String cacheName) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = open(url);
            int code = c.getResponseCode();
            if (code != 200) { Log.w(TAG, "http " + code + " for " + cacheName); return readCache(cacheName); }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String text = sb.toString();
            saveCache(cacheName, text);
            return text;
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed, use cache " + t);
            return readCache(cacheName);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static List<Item> parseItems(String raw) {
        List<Item> out = new ArrayList<>();
        if (raw == null) return out;
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("Items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                JSONObject tags = o.optJSONObject("ImageTags");
                String tag = tags == null ? null : tags.optString("Primary", null);
                out.add(new Item(o.optString("Id"), o.optString("Name"), tag,
                        o.optInt("ProductionYear", 0), null, false));
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse failed " + t);
        }
        return out;
    }


    /** 全库随机取一批有剧照（Backdrop）的条目，用作桌面背景。 */
    public static List<Item> backdropPool(int limit) {
        List<Item> out = new ArrayList<>();
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            String url = BASE + "/Items?Recursive=true&IncludeItemTypes=Series,Movie"
                    + "&Fields=ProductionYear&ImageTypeLimit=1&EnableImageTypes=Backdrop"
                    + "&SortBy=Random&Limit=" + limit;
            c = open(url);
            if (c.getResponseCode() != 200) { Log.w(TAG, "pool http " + c.getResponseCode()); return out; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray items = root.optJSONArray("Items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                out.add(new Item(o.optString("Id"), o.optString("Name"), null,
                        o.optInt("ProductionYear", 0), null, false));
            }
            Log.i(TAG, "backdrop pool=" + out.size());
        } catch (Throwable t) {
            Log.e(TAG, "pool failed " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 下载封面；失败返回 null。 */
    public static Bitmap poster(String url, int maxH) {
        HttpURLConnection c = null;
        InputStream in = null;
        // 先看磁盘缓存（appCtx 在 Application 里初始化）
        if (appCtx != null) {
            try {
                java.io.File f = posterCacheFile(url);
                if (f != null && f.exists()) {
                    Bitmap cached = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (cached != null) return cached;
                }
            } catch (Throwable ignored) { }
        }
        try {
            c = open(url);
            c.setRequestProperty("Accept", "image/*");
            int code1 = c.getResponseCode();
            if (code1 != 200) { android.util.Log.w("CYT-JF", "poster http " + code1 + " " + url); return null; }
            in = c.getInputStream();
            // 先按目标高度采样，避免整张大图进内存
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            try { in.close(); } catch (Throwable ignored) { }
            int sample = 1;
            while (bounds.outHeight / sample > maxH * 2) sample *= 2;
            c.disconnect();
            c = open(url);
            c.setRequestProperty("Accept", "image/*");
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap out = BitmapFactory.decodeStream(in, null, opts);
            savePosterCache(url, out);
            return out;
        } catch (Throwable t) {
            android.util.Log.w("CYT-JF", "poster err " + t + " url=" + url);
            Log.e(TAG, "poster failed " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }
    /** 详情页/库页通用卡片：海报 + 标题 + 副标题。 */
    public static final class Card {
        public final String id, title, sub, posterUrl, backdropUrl;
        public Card(String id, String title, String sub, String posterUrl, String backdropUrl) {
            this.id = id; this.title = title; this.sub = sub;
            this.posterUrl = posterUrl; this.backdropUrl = backdropUrl;
        }
    }

    /** 我的媒体（Jellyfin 的各个库）。 */
    public static java.util.List<Card> views() {
        java.util.List<Card> out = new java.util.ArrayList<>();
        JSONObject o = getJson(BASE + "/Users/" + USER_ID + "/Views");
        if (o == null) return out;
        JSONArray arr = o.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("Id", "");
            String name = it.optString("Name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            JSONObject tags = it.optJSONObject("ImageTags");
            String tag = tags == null ? null : tags.optString("Primary", null);
            String poster = BASE + "/Items/" + id + "/Images/Primary?maxHeight=720&quality=90"
                    + (tag == null ? "" : "&tag=" + tag);
            String back = BASE + "/Items/" + id + "/Images/Backdrop/0?maxWidth=1280&quality=90";
            out.add(new Card(id, name, it.optString("CollectionType", ""), poster, back));
        }
        return out;
    }

    /** 某个库最近添加的影片/剧集。 */
    public static java.util.List<Card> latest(String libraryId, int limit) {
        java.util.List<Card> out = new java.util.ArrayList<>();
        String url = BASE + "/Items?ParentId=" + libraryId + "&Recursive=true"
                + "&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending"
                + "&Limit=" + limit + "&Fields=ProductionYear,ImageTags,UserData";
        JSONObject o = getJson(url);
        if (o == null) return out;
        JSONArray arr = o.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("Id", "");
            String name = it.optString("Name", "");
            if (id.isEmpty()) continue;
            JSONObject tags = it.optJSONObject("ImageTags");
            String tag = tags == null ? null : tags.optString("Primary", null);
            int year = it.optInt("ProductionYear", 0);
            String poster = BASE + "/Items/" + id + "/Images/Primary?maxHeight=720&quality=90"
                    + (tag == null ? "" : "&tag=" + tag);
            out.add(new Card(id, name, year > 0 ? String.valueOf(year) : "", poster, null));
        }
        return out;
    }

    /** 继续观看（带进度百分比）。 */
    public static java.util.List<Card> resumeCards(int limit) {
        java.util.List<Card> out = new java.util.ArrayList<>();
        String url = BASE + "/Users/" + USER_ID + "/Items/Resume?Limit=" + limit
                + "&MediaTypes=Video&Fields=ProductionYear,SeriesId,SeriesPrimaryImageTag,UserData";
        JSONObject o = getJson(url);
        if (o == null) return out;
        JSONArray arr = o.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("Id", "");
            if (id.isEmpty()) continue;
            String type = it.optString("Type", "");
            String seriesId = it.optString("SeriesId", "");
            String tag = it.optString("SeriesPrimaryImageTag", "");
            String imgId = (seriesId == null || seriesId.isEmpty()) ? id : seriesId;
            String poster = BASE + "/Items/" + imgId + "/Images/Primary?maxHeight=720&quality=90"
                    + (tag == null || tag.isEmpty() ? "" : "&tag=" + tag);
            String title;
            if ("Episode".equals(type)) {
                int s = it.optInt("ParentIndexNumber", 0);
                int e = it.optInt("IndexNumber", 0);
                String seriesName = it.optString("SeriesName", "");
                title = seriesName.isEmpty() ? it.optString("Name", "") : seriesName;
                if (s > 0 && e > 0) title = title + "  S" + s + "E" + e;
            } else {
                title = it.optString("Name", "");
            }
            JSONObject ud = it.optJSONObject("UserData");
            double pr = ud == null ? 0 : ud.optDouble("PlayedPercentage", 0);
            String sub = pr > 0 ? (Math.round(pr) + "%") : "";
            out.add(new Card(id, title, sub, poster, null));
        }
        return out;
    }


    public static final String USER_ID = "cebd8acb17734b7190f3e731c0e78301";

    /** 第几集开始播：优先「继续观看」，否则第一集。返回 {itemId, 标题}，失败返回 null。 */
    public static String[] playTarget(String seriesId) {
        String[] r = nextUp(seriesId);
        if (r == null) r = firstEpisode(seriesId);
        return r;
    }

    private static String[] nextUp(String seriesId) {
        String url = BASE + "/Shows/NextUp?UserId=" + USER_ID + "&SeriesId=" + seriesId
                + "&Limit=1&Fields=Name,IndexNumber,ParentIndexNumber";
        JSONObject o = getJson(url);
        if (o == null) return null;
        JSONArray items = o.optJSONArray("Items");
        if (items == null || items.length() == 0) return null;
        return pick(items.optJSONObject(0));
    }

    private static String[] firstEpisode(String seriesId) {
        String url = BASE + "/Shows/" + seriesId + "/Episodes?UserId=" + USER_ID
                + "&Limit=1&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending&Fields=Name";
        JSONObject o = getJson(url);
        if (o == null) return null;
        JSONArray items = o.optJSONArray("Items");
        if (items == null || items.length() == 0) return null;
        return pick(items.optJSONObject(0));
    }

    private static String[] pick(JSONObject it) {
        if (it == null) return null;
        String id = it.optString("Id", null);
        if (id == null) return null;
        String name = it.optString("Name", "");
        int season = it.optInt("ParentIndexNumber", 0);
        int ep = it.optInt("IndexNumber", 0);
        String label = season > 0 && ep > 0 ? ("S" + season + "E" + ep + "  " + name) : name;
        JSONObject ud = it.optJSONObject("UserData");
        long posMs = ud == null ? 0 : (long) (ud.optDouble("PlaybackPositionTicks", 0) / 10000.0);
        return new String[]{id, label, String.valueOf(posMs)};
    }

    /** 原片直连（static=true，不转码）。 */
    public static String streamUrl(String itemId) {
        return BASE + "/Videos/" + itemId + "/stream?static=true&MediaSourceId=" + itemId;
    }

    /** 给 ijkplayer 的鉴权头。 */
    public static String authHeader() {
        return "Authorization: MediaBrowser Token=\"" + TOKEN + "\"\r\n";
    }

    private static JSONObject getJson(String url) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) { Log.w(TAG, "http " + c.getResponseCode() + " " + url); return null; }
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "getJson failed " + t);
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }
    /** 某部剧的全部集，按季/集排序。返回 {itemId, 显示名} 列表。 */
    /** 最近观看（继续观看）：直接拿 /Users/{uid}/Items/Resume 的视频条目。 */
    /** 最近看过的「国产剧」：返回 {seriesId, itemId, "S1E3", "23"}，没有则 null。 */
    public static String[] recentDrama() {
        String url = BASE + "/Users/" + USER_ID + "/Items/Resume?Limit=1&MediaTypes=Video"
                + "&ParentId=" + LIB_CN_DRAMA + "&Recursive=true"
                + "&Fields=SeriesId,SeriesName,IndexNumber,ParentIndexNumber,UserData";
        JSONObject o = getJson(url);
        if (o == null) return null;
        JSONArray items = o.optJSONArray("Items");
        if (items == null || items.length() == 0) return null;
        JSONObject it = items.optJSONObject(0);
        if (it == null) return null;
        String seriesId = it.optString("SeriesId", null);
        String itemId = it.optString("Id", null);
        if (seriesId == null || seriesId.isEmpty() || itemId == null) return null;
        int s = it.optInt("ParentIndexNumber", 0);
        int ep = it.optInt("IndexNumber", 0);
        String seriesName = it.optString("SeriesName", "");
        JSONObject ud = it.optJSONObject("UserData");
        int pct = ud == null ? 0 : (int) Math.round(ud.optDouble("PlayedPercentage", 0));
        String label = (seriesName.isEmpty() ? "" : seriesName + " · ")
                + (s > 0 && ep > 0 ? ("S" + s + "E" + ep) : "")
                + (pct > 0 ? (" · " + pct + "%") : "");
        Log.i(TAG, "recent=" + label);
        return new String[]{seriesId, itemId, label, String.valueOf(pct)};
    }

    public static java.util.List<Item> resume(int limit) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        String url = BASE + "/Users/" + USER_ID + "/Items/Resume?Limit=" + limit
                + "&MediaTypes=Video&Fields=ProductionYear,SeriesId,SeriesPrimaryImageTag";
        JSONObject o = getJson(url);
        if (o == null) return out;
        JSONArray items = o.optJSONArray("Items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("Id", null);
            if (id == null) continue;
            String name = it.optString("Name", "");
            String type = it.optString("Type", "");
            boolean isEp = "Episode".equals(type);
            String seriesId = it.optString("SeriesId", null);
            if (seriesId != null && seriesId.isEmpty()) seriesId = null;
            String tag = it.optString("SeriesPrimaryImageTag", null);
            if (tag == null || tag.isEmpty()) tag = null;
            int year = it.optInt("ProductionYear", 0);
            // 单集名字太长（含 S01E01 等），带季集号更好认
            String label = name;
            if (isEp) {
                int s = it.optInt("ParentIndexNumber", 0);
                int e = it.optInt("IndexNumber", 0);
                String seriesName = it.optString("SeriesName", "");
                label = (seriesName.isEmpty() ? "" : seriesName + "  ")
                        + (s > 0 && e > 0 ? ("S" + s + "E" + e) : "");
            }
            out.add(new Item(id, label, tag, year, seriesId, isEp));
        }
        Log.i(TAG, "resume=" + out.size());
        return out;
    }

    public static java.util.List<String[]> episodes(String seriesId) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String url = BASE + "/Shows/" + seriesId + "/Episodes?UserId=" + USER_ID
                + "&Fields=Name,IndexNumber,ParentIndexNumber"
                + "&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending";
        JSONObject o = getJson(url);
        if (o == null) return out;
        JSONArray items = o.optJSONArray("Items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("Id", null);
            if (id == null) continue;
            int season = it.optInt("ParentIndexNumber", 0);
            int ep = it.optInt("IndexNumber", 0);
            String name = it.optString("Name", "");
            String label = season > 0 && ep > 0 ? ("S" + season + " 第" + ep + "集  " + name) : name;
            out.add(new String[]{id, label});
        }
        return out;
    }

    // ---- 播放进度上报（让「继续观看」生效） ----

    private static void post(String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + TOKEN + "\"");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            byte[] data = body.getBytes("UTF-8");
            c.getOutputStream().write(data);
            int code = c.getResponseCode();
            if (code >= 400) Log.w(TAG, "post " + path + " -> " + code);
        } catch (Throwable t) {
            Log.w(TAG, "post failed " + t);
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    public static void reportStart(String itemId, long posMs) {
        post("/Sessions/Playing", "{\"ItemId\":\"" + itemId + "\",\"MediaSourceId\":\"" + itemId
                + "\",\"PositionTicks\":" + (posMs * 10000L) + ",\"CanSeek\":true,\"PlayMethod\":\"DirectPlay\"}");
    }

    public static void reportProgress(String itemId, long posMs, boolean paused) {
        // API key 的会话归服务器，/Sessions/Playing/Progress 不会写进用户数据；
        // 直接改 UserData 才能让「继续观看」生效。
        updateUserData(itemId, posMs);
    }

    /** 直接写用户的播放位置 + 最后播放时间（后者是「继续观看」列表的必需字段）。 */
    public static void updateUserData(String itemId, long posMs) {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String now = fmt.format(new java.util.Date());
        post("/Users/" + USER_ID + "/Items/" + itemId + "/UserData",
                "{\"PlaybackPositionTicks\":" + (posMs * 10000L)
                        + ",\"Played\":false,\"LastPlayedDate\":\"" + now + "\"}");
    }

    public static void reportStop(String itemId, long posMs) {
        post("/Sessions/Playing/Stopped", "{\"ItemId\":\"" + itemId + "\",\"MediaSourceId\":\"" + itemId
                + "\",\"PositionTicks\":" + (posMs * 10000L) + "}");
        updateUserData(itemId, posMs);
    }

    private static java.io.File posterCacheFile(String url) {
        if (appCtx == null) return null;
        try {
            java.io.File dir = new java.io.File(appCtx.getCacheDir(), "posters");
            if (!dir.exists()) dir.mkdirs();
            return new java.io.File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void savePosterCache(String url, Bitmap bm) {
        try {
            java.io.File f = posterCacheFile(url);
            if (f == null || bm == null) return;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fo);
            fo.close();
        } catch (Throwable ignored) { }
    }
    /** 一条字幕流（Jellyfin 侧）。 */
    public static final class Sub {
        public final int index;
        public final String title;
        public final String language;
        Sub(int i, String t, String l) { index = i; title = t; language = l; }
        public String label() {
            String t = title == null || title.isEmpty() ? ("字幕 " + index) : title;
            return t + (language == null || language.isEmpty() || "und".equals(language) ? "" : " · " + language);
        }
    }

    /** 该视频的字幕流列表（来自 PlaybackInfo）。 */
    public static java.util.List<Sub> subtitleStreams(String itemId) {
        java.util.List<Sub> out = new java.util.ArrayList<>();
        try {
            JSONObject root = getJson(BASE + "/Items/" + itemId + "/PlaybackInfo?UserId=" + USER_ID);
            if (root == null) return out;
            JSONArray sources = root.optJSONArray("MediaSources");
            if (sources == null || sources.length() == 0) return out;
            JSONArray streams = sources.getJSONObject(0).optJSONArray("MediaStreams");
            if (streams == null) return out;
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.getJSONObject(i);
                if (!"Subtitle".equals(s.optString("Type"))) continue;
                out.add(new Sub(s.optInt("Index"), s.optString("DisplayTitle", ""),
                        s.optString("Language", "")));
            }
            Log.i(TAG, "subtitle streams=" + out.size());
        } catch (Throwable t) {
            Log.w(TAG, "subtitleStreams failed " + t);
        }
        return out;
    }

    /** 字幕文件（VTT 文本）；失败返回 null。 */
    public static String subtitleVtt(String itemId, int index) {
        String raw = fetchJson(BASE + "/Videos/" + itemId + "/" + itemId
                + "/Subtitles/" + index + "/Stream.vtt", "cache_sub_" + itemId + "_" + index + ".vtt");
        Log.i(TAG, "vtt raw=" + (raw == null ? "null" : raw.length())
                + " head=" + (raw == null ? "" : raw.substring(0, Math.min(160, raw.length())).replace("\n", "\\n")));
        return raw;
    }
}
