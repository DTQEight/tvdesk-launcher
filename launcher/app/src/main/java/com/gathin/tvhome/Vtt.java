package com.gathin.tvhome;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 WEBVTT 字幕。
 * 注意：Jellyfin 返回的 VTT 常常**没有换行**（所有 cue 连成一行），
 * 所以不能按行解析，这里用正则找时间轴再切文本。
 */
public final class Vtt {

    private static final String TAG = "CYT-SUB";

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3}|\\d{1,2}:\\d{2}[.,]\\d{1,3})"
                    + "\\s*-->\\s*"
                    + "(\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3}|\\d{1,2}:\\d{2}[.,]\\d{1,3})");

    /** 一条字幕：起止时间（毫秒）+ 文本。 */
    public static final class Cue {
        public final long start, end;
        public final String text;
        Cue(long s, long e, String t) { start = s; end = e; text = t; }
    }

    private Vtt() { }

    public static List<Cue> parse(String vtt) {
        List<Cue> out = new ArrayList<>();
        if (vtt == null || vtt.isEmpty()) return out;
        Matcher m = TIME.matcher(vtt);
        java.util.List<int[]> spans = new ArrayList<>();
        java.util.List<long[]> times = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.end(), 0});
            times.add(new long[]{ms(m.group(1)), ms(m.group(2))});
            spans.get(spans.size() - 1)[1] = m.start();
        }
        // 每条字幕的文本 = 本次时间轴结束 → 下一次时间轴开始（Jellyfin 的 VTT 没有换行）
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[0];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[1] : vtt.length();
            if (to <= from) continue;
            String text = clean(vtt.substring(from, to));
            if (!text.isEmpty()) {
                out.add(new Cue(times.get(i)[0], times.get(i)[1], text));
            }
        }
        Log.i(TAG, "vtt cues=" + out.size());
        return out;
    }

    /** 去掉 <font>/<c> 等标签和常见转义。 */
    private static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]*>", "");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"");
        return s.trim();
    }

    private static long ms(String ts) {
        try {
            String v = ts.replace(',', '.');
            String[] a = v.split(":");
            long h = 0, m = 0;
            double s;
            if (a.length == 3) {
                h = Long.parseLong(a[0]);
                m = Long.parseLong(a[1]);
                s = Double.parseDouble(a[2]);
            } else if (a.length == 2) {
                m = Long.parseLong(a[0]);
                s = Double.parseDouble(a[1]);
            } else {
                s = Double.parseDouble(a[0]);
            }
            return (long) ((h * 3600 + m * 60 + s) * 1000);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前位置应显示的字幕；没有则返回 null。 */
    public static String textAt(List<Cue> cues, long posMs) {
        if (cues == null || cues.isEmpty()) return null;
        int lo = 0, hi = cues.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= posMs) { found = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        if (found < 0) return null;
        Cue c = cues.get(found);
        return posMs <= c.end ? c.text : null;
    }
}
