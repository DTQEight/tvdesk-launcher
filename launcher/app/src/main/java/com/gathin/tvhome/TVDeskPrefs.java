package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** TVDesk 自己的偏好（背景轮播 / 天气定位）。 */
public final class TVDeskPrefs {

    private static final String NAME = "tvdesk_prefs";

    public static final String KEY_BACKDROP = "backdrop_enabled";
    public static final String KEY_INTERVAL = "backdrop_interval_ms";
    public static final String KEY_WEATHER_HOME = "weather_home";     // true = 固定佛山顺德
    public static final String KEY_SUB_SIZE = "sub_size";      // 0 小 / 1 中 / 2 大
    public static final String KEY_SUB_COLOR = "sub_color";    // 0 白 / 1 黄
    public static final String KEY_PROXY_ON = "proxy_enabled";     // 推荐页海报是否走代理
    public static final String KEY_PROXY_HOST = "proxy_host";
    public static final String KEY_PROXY_PORT = "proxy_port";

    private TVDeskPrefs() { }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean backdrop(Context ctx) { return get(ctx).getBoolean(KEY_BACKDROP, true); }
    public static int interval(Context ctx) { return get(ctx).getInt(KEY_INTERVAL, 30000); }
    public static boolean weatherHome(Context ctx) { return get(ctx).getBoolean(KEY_WEATHER_HOME, false); }
    public static int subSize(Context ctx) { return get(ctx).getInt(KEY_SUB_SIZE, 1); }
    public static int subColor(Context ctx) { return get(ctx).getInt(KEY_SUB_COLOR, 0); }
    public static boolean proxyOn(Context ctx) { return get(ctx).getBoolean(KEY_PROXY_ON, true); }
    public static String proxyHost(Context ctx) { return get(ctx).getString(KEY_PROXY_HOST, "192.168.31.1"); }
    public static int proxyPort(Context ctx) { return get(ctx).getInt(KEY_PROXY_PORT, 7890); }
}