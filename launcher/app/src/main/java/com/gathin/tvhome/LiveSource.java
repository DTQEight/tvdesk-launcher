package com.gathin.tvhome;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 直播源：拉 M3U，挑「翡翠台」。 */
public final class LiveSource {

    private static final String TAG = "CYT-LIVE";
    public static String M3U = "";   // 由 DeskConfig 注入
    private static final Pattern NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");

    public static final class Ch {
        public final String name;
        public final String url;
        public final String group;
        Ch(String n, String u, String g) { name = n; url = u; group = g == null ? "" : g; }
    }

    private LiveSource() { }

    private static final String CACHE = "live_channels.tsv";
    private static final long CACHE_FRESH_MS = 6 * 60 * 60 * 1000L;

    /** 先用本地缓存（秒开），缓存过期或不存在再拉网络；过期时后台静默刷新。 */
    public static List<Ch> load(android.content.Context ctx) {
        if (M3U == null || M3U.trim().isEmpty()) {
            Log.i(TAG, "未配置直播源，跳过");
            return new ArrayList<>();
        }
        if (ctx != null) {
            try {
                java.io.File f = new java.io.File(ctx.getCacheDir(), CACHE);
                if (f.exists()) {
                    List<Ch> cached = parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    if (!cached.isEmpty()) {
                        Log.i(TAG, "channels(cache)=" + cached.size());
                        if (System.currentTimeMillis() - f.lastModified() > CACHE_FRESH_MS) {
                            final android.content.Context c2 = ctx.getApplicationContext();
                            new Thread(new Runnable() {
                                @Override public void run() {
                                    List<Ch> fresh = fetch();
                                    if (!fresh.isEmpty()) saveCache(c2, fresh);
                                }
                            }, "cyt-m3u-refresh").start();
                        }
                        return cached;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "cache read failed " + t);
            }
        }
        List<Ch> list = fetch();
        if (!list.isEmpty() && ctx != null) saveCache(ctx.getApplicationContext(), list);
        return list;
    }

    private static void saveCache(android.content.Context ctx, List<Ch> list) {
        try {
            StringBuilder sb = new StringBuilder(list.size() * 96);
            for (Ch c : list) {
                sb.append(c.name).append('\t').append(c.group).append('\t').append(c.url).append('\n');
            }
            java.io.File f = new java.io.File(ctx.getCacheDir(), CACHE);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
            Log.i(TAG, "cache saved " + list.size());
        } catch (Throwable t) {
            Log.w(TAG, "cache save failed " + t);
        }
    }

    private static List<Ch> parse(String text) {
        List<Ch> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", 3);
            if (p.length == 3) out.add(new Ch(p[0], p[2], p[1]));
        }
        return out;
    }

    /** 拉网络并解析 M3U。 */
    public static List<Ch> fetch() {
        List<Ch> out = new ArrayList<>();
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = (HttpURLConnection) new URL(M3U).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "okhttp/3.12.0");
            if (c.getResponseCode() != 200) return out;
            r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"), 64 * 1024);
            String line;
            String pending = null;
            String group = "";
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    Matcher m = NAME.matcher(line);
                    pending = m.find() ? m.group(1) : null;
                    Matcher g = GROUP.matcher(line);
                    group = g.find() ? g.group(1) : "";
                    if (pending == null) {
                        int i = line.lastIndexOf(',');
                        pending = i >= 0 && i + 1 < line.length() ? line.substring(i + 1).trim() : "未知";
                    }
                } else if (!line.startsWith("#") && pending != null) {
                    if (!"订阅信息".equals(group)) out.add(new Ch(pending, line, group));
                    pending = null;
                }
            }
            Log.i(TAG, "channels=" + out.size());
        } catch (Throwable t) {
            Log.e(TAG, "load failed " + t);
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static int jade(List<Ch> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.contains("翡翠台")) return i;
        }
        return list.isEmpty() ? -1 : 0;
    }
    /** 按 OK影视 的习惯把 3000+ 频道分成十来类。 */
    public static java.util.LinkedHashMap<String, java.util.List<Ch>> categories(java.util.List<Ch> all) {
        java.util.LinkedHashMap<String, java.util.List<Ch>> map = new java.util.LinkedHashMap<>();
        String[] names = {"全部", "央视", "卫视", "港澳台", "影视", "体育", "少儿", "地方", "音乐", "栏目", "其他"};
        for (String n : names) map.put(n, new java.util.ArrayList<Ch>());
        map.get("全部").addAll(all);
        for (Ch c : all) {
            String g = c.group == null ? "" : c.group;
            String n = c.name == null ? "" : c.name;
            String cat;
            if (n.matches(".*(翡翠|明珠|凤凰|香港|澳门|台湾|TVB|无线).*")) cat = "港澳台";
            else if (g.startsWith("央视") || g.contains("央卫")) cat = "央视";
            else if (g.contains("卫视")) cat = "卫视";
            else if (g.contains("体育")) cat = "体育";
            else if (g.contains("少儿")) cat = "少儿";
            else if (g.contains("地方")) cat = "地方";
            else if (g.contains("电影") || g.contains("剧场") || g.startsWith("VIP")) cat = "影视";
            else if (g.contains("歌") || g.contains("舞曲") || g.contains("音乐")) cat = "音乐";
            else if (g.contains("春晚") || g.contains("小品") || g.contains("解说")
                    || g.contains("户外") || g.contains("菜谱") || g.contains("戏曲")
                    || g.contains("电台") || g.contains("轮播") || g.contains("4K") || g.contains("高清")) cat = "栏目";
            else cat = "其他";
            map.get(cat).add(c);
        }
        // 丢掉空分类
        java.util.LinkedHashMap<String, java.util.List<Ch>> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.util.List<Ch>> e : map.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}