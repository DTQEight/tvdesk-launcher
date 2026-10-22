package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * EPG（节目单）：从 M3U 里的 x-tvg-url 下载 XMLTV（gzip），流式解析后只保留
 * 当前 ±时间窗内的节目，写成 TSV 缓存；查询时按频道名找「现在在播什么」。
 *
 * XMLTV 解压后有 80+ MB，不能整份读进内存，所以用 XmlPullParser 边读边筛。
 */
public final class Epg {

    private static final String TAG = "CYT-EPG";
    private static final String URL = "http://epg.808993.xyz/t.xml.gz";
    private static final String CACHE = "epg.tsv";
    private static final long FRESH_MS = 6 * 60 * 60 * 1000L;
    private static final long WINDOW_BACK = 2 * 60 * 60 * 1000L;     // 保留已播 2 小时
    private static final long WINDOW_FORWARD = 8 * 60 * 60 * 1000L;  // 保留未来 8 小时

    private static Map<String, List<long[]>> times;   // channel -> [start,end]
    private static Map<String, List<String>> names;   // channel -> title

    private Epg() { }

    private static File file(Context ctx) {
        return new File(ctx.getCacheDir(), CACHE);
    }

    /** 需要时（缓存过期）后台刷新。 */
    public static void refreshIfStale(Context ctx) {
        final Context app = ctx.getApplicationContext();
        File f = file(app);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    download(app);
                } catch (Throwable t) {
                    Log.w(TAG, "refresh failed " + t);
                }
            }
        }, "cyt-epg").start();
    }

    private static void download(Context ctx) throws Exception {
        long now = System.currentTimeMillis();
        long minEnd = now - WINDOW_BACK;
        long maxStart = now + WINDOW_FORWARD;

        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "okhttp/3.12.0");
        if (c.getResponseCode() != 200) { Log.w(TAG, "http " + c.getResponseCode()); return; }
        InputStream in = new BufferedInputStream(new GZIPInputStream(c.getInputStream()), 64 * 1024);

        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(in, "UTF-8");

        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss Z");
        fmt.setTimeZone(TimeZone.getTimeZone("GMT+8"));

        StringBuilder out = new StringBuilder(1 << 20);
        int kept = 0, total = 0;
        String channel = null, title = null, start = null, stop = null;
        int event = p.getEventType();
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("programme".equals(tag)) {
                    channel = p.getAttributeValue(null, "channel");
                    start = p.getAttributeValue(null, "start");
                    stop = p.getAttributeValue(null, "stop");
                    title = null;
                } else if ("title".equals(tag)) {
                    title = p.nextText();
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG
                    && "programme".equals(p.getName())) {
                total++;
                if (channel != null && title != null && start != null && stop != null) {
                    try {
                        long s = fmt.parse(start).getTime();
                        long e = fmt.parse(stop).getTime();
                        if (e >= minEnd && s <= maxStart) {
                            out.append(channel).append('\t').append(s).append('\t')
                                    .append(e).append('\t')
                                    .append(title.replace('\t', ' ').replace('\n', ' '))
                                    .append('\n');
                            kept++;
                        }
                    } catch (Throwable ignored) { }
                }
                channel = null;
            }
            event = p.next();
        }
        in.close();
        c.disconnect();

        FileOutputStream fo = new FileOutputStream(file(ctx));
        fo.write(out.toString().getBytes("UTF-8"));
        fo.close();
        Log.i(TAG, "epg saved kept=" + kept + "/" + total);
    }

    private static void ensureLoaded(Context ctx) {
        if (times != null) return;
        Map<String, List<long[]>> t = new HashMap<>();
        Map<String, List<String>> n = new HashMap<>();
        File f = file(ctx);
        if (f.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", 4);
                    if (p.length < 4) continue;
                    try {
                        long s = Long.parseLong(p[1]);
                        long e = Long.parseLong(p[2]);
                        List<long[]> tl = t.get(p[0]);
                        List<String> nl = n.get(p[0]);
                        if (tl == null) { tl = new ArrayList<>(); t.put(p[0], tl); }
                        if (nl == null) { nl = new ArrayList<>(); n.put(p[0], nl); }
                        tl.add(new long[]{s, e});
                        nl.add(p[3]);
                    } catch (Throwable ignored) { }
                }
                r.close();
            } catch (Throwable e) {
                Log.w(TAG, "load failed " + e);
            }
        }
        times = t;
        names = n;
    }

    /** 当前在播的节目名；找不到返回 null。 */
    public static String now(Context ctx, String channel) {
        try {
            ensureLoaded(ctx);
            List<long[]> tl = times.get(channel);
            if (tl == null) return null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < tl.size(); i++) {
                long[] r = tl.get(i);
                if (r[0] <= now && now < r[1]) return names.get(channel).get(i);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 下一档节目名；找不到返回 null。 */
    public static String next(Context ctx, String channel) {
        try {
            ensureLoaded(ctx);
            List<long[]> tl = times.get(channel);
            if (tl == null) return null;
            long now = System.currentTimeMillis();
            long best = Long.MAX_VALUE;
            String found = null;
            for (int i = 0; i < tl.size(); i++) {
                long[] r = tl.get(i);
                if (r[0] > now && r[0] < best) { best = r[0]; found = names.get(channel).get(i); }
            }
            return found;
        } catch (Throwable ignored) { return null; }
    }
}
