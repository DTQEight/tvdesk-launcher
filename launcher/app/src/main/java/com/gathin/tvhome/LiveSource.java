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
    public static final String M3U = "http://z.zzr.cc/live.php?token=18JanWhxouzPykea&s=1&c=m";
    private static final Pattern NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern GROUP = Pattern.compile("group-title=\"([^\"]*)\"");

    public static final class Ch {
        public final String name;
        public final String url;
        Ch(String n, String u) { name = n; url = u; }
    }

    private LiveSource() { }

    public static List<Ch> load() {
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
                    if (!"订阅信息".equals(group)) out.add(new Ch(pending, line));
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
}