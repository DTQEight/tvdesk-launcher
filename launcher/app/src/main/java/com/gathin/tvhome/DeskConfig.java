package com.gathin.tvhome;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 所有外部服务的连接参数集中在这里。
 *
 * 三种来源，后者覆盖前者：
 *   1. 代码默认值 —— 全是空字符串，等于「没配置」，相关功能自动关闭
 *   2. 设置页（SharedPreferences: tvdesk_prefs）
 *   3. 批量预置文件 /sdcard/Android/data/<包名>/files/desk.json
 *
 * 只要 Jellyfin / MoviePilot / 2bt0 / 直播源 都不填，就是一台纯 CleanYourTV 风格的桌面。
 */
public final class DeskConfig {

    private static final String TAG = "CYT-CFG";

    public static final String KEY_JF_BASE = "jf_base";
    public static final String KEY_JF_TOKEN = "jf_token";
    public static final String KEY_JF_USER = "jf_user";
    public static final String KEY_MP_BASE = "mp_base";
    public static final String KEY_MP_KEY = "mp_key";
    public static final String KEY_LIB_BASE = "lib_base";
    public static final String KEY_LIB_KEY = "lib_key";
    public static final String KEY_LIVE_M3U = "live_m3u";
    public static final String KEY_EPG_URL = "epg_url";

    // 生效值（启动时 load 一次，设置改动后 refresh）
    public static String jfBase = "";
    public static String jfToken = "";
    public static String jfUser = "";
    public static String mpBase = "";
    public static String mpKey = "";
    public static String libBase = "";
    public static String libKey = "";
    public static String liveM3u = "";
    public static String epgUrl = "";

    private DeskConfig() { }

    /** 应用启动时调用：读偏好 + 可选预置文件，然后灌进各个客户端。 */
    public static void load(Context ctx) {
        android.content.SharedPreferences p = TVDeskPrefs.get(ctx);
        jfBase = p.getString(KEY_JF_BASE, "");
        jfToken = p.getString(KEY_JF_TOKEN, "");
        jfUser = p.getString(KEY_JF_USER, "");
        mpBase = p.getString(KEY_MP_BASE, "");
        mpKey = p.getString(KEY_MP_KEY, "");
        libBase = p.getString(KEY_LIB_BASE, "");
        libKey = p.getString(KEY_LIB_KEY, "");
        liveM3u = p.getString(KEY_LIVE_M3U, "");
        epgUrl = p.getString(KEY_EPG_URL, "");

        loadProvisionFile(ctx);
        apply();
    }

    /** 保存一项配置（设置页用）。 */
    public static void put(Context ctx, String key, String value) {
        TVDeskPrefs.get(ctx).edit().putString(key, value == null ? "" : value.trim()).apply();
        load(ctx);
    }

    public static String get(Context ctx, String key) {
        return TVDeskPrefs.get(ctx).getString(key, "");
    }

    /** 预置文件可能的位置：外部私有目录、/sdcard 兜底、应用内部目录。 */
    private static File provisionFile(Context ctx) {
        java.util.List<File> cands = new java.util.ArrayList<>();
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) cands.add(new File(ext, "desk.json"));
        cands.add(new File(android.os.Environment.getExternalStorageDirectory(),
                "Android/data/" + ctx.getPackageName() + "/files/desk.json"));
        cands.add(new File(ctx.getFilesDir(), "desk.json"));
        for (File c : cands) {
            if (c != null && c.isFile() && c.canRead()) return c;
        }
        return null;
    }

    /** 读预置文件，存在的字段覆盖偏好。 */
    private static void loadProvisionFile(Context ctx) {
        File f = provisionFile(ctx);
        if (f == null) { Log.i(TAG, "没有预置文件，使用设置页配置"); return; }
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
            jfBase = opt(o, "jfBase", jfBase);
            jfToken = opt(o, "jfToken", jfToken);
            jfUser = opt(o, "jfUser", jfUser);
            mpBase = opt(o, "mpBase", mpBase);
            mpKey = opt(o, "mpKey", mpKey);
            libBase = opt(o, "libBase", libBase);
            libKey = opt(o, "libKey", libKey);
            liveM3u = opt(o, "liveM3u", liveM3u);
            epgUrl = opt(o, "epgUrl", epgUrl);
            Log.i(TAG, "已读取预置配置 " + f);
        } catch (Throwable t) {
            Log.w(TAG, "预置配置读取失败 " + t);
        }
    }

    private static String opt(JSONObject o, String k, String def) {
        String v = o.optString(k, null);
        return v == null || v.isEmpty() ? def : v;
    }

    /** 把当前值灌给各客户端（它们的 BASE/KEY 是可变的静态字段）。 */
    private static void apply() {
        JellyfinClient.BASE = trimSlash(jfBase);
        JellyfinClient.TOKEN = jfToken;
        if (!TextUtils.isEmpty(jfUser)) JellyfinClient.USER_ID = jfUser;

        MpClient.BASE = trimSlash(mpBase);
        MpClient.KEY = mpKey;

        LibClient.BASE = trimSlash(libBase);
        LibClient.KEY = libKey;

        LiveSource.M3U = liveM3u;
        Epg.URL = epgUrl;

        Log.i(TAG, "jellyfin=" + hasJellyfin() + " moviepilot=" + hasMp()
                + " 2bt0=" + hasLib() + " live=" + hasLive());
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 三项都填了才算能用（地址 + 令牌/Key）。 */
    public static boolean hasJellyfin() {
        return !TextUtils.isEmpty(jfBase) && !TextUtils.isEmpty(jfToken);
    }

    public static boolean hasMp() {
        return !TextUtils.isEmpty(mpBase) && !TextUtils.isEmpty(mpKey);
    }

    public static boolean hasLib() {
        return !TextUtils.isEmpty(libBase) && !TextUtils.isEmpty(libKey);
    }

    public static boolean hasLive() {
        return !TextUtils.isEmpty(liveM3u);
    }

    /** 推荐页需要 MoviePilot 取片单 + 2bt0 找资源。 */
    public static boolean hasRecommend() {
        return hasMp() && hasLib();
    }
}
