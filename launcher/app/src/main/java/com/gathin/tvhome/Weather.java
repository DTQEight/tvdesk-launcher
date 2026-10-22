package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 天气定位：优先「家里 Wi-Fi 指纹」，其次 IP 定位。
 *  - 第一次在家运行时，自动记下当前 Wi-Fi SSID 作为「家」
 *  - 之后只要连的还是这个 Wi-Fi，就用家里坐标（佛山顺德）
 *  - 出门/热点 → 退回 IP 定位（跟随出口城市）
 */
public final class Weather {

    private static final String TAG = "CYT-WEATHER";
    private static final String PREFS = "cyt_weather";
    private static final String KEY_HOME = "home_ssid";

    /** 家：佛山顺德 */
    private static final String HOME_CITY = "佛山顺德";
    private static final double HOME_LAT = 22.8058;
    private static final double HOME_LON = 113.2930;

    private Weather() { }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (c.getResponseCode() != 200) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    /** 当前 Wi-Fi 名字；拿不到返回 null。 */
    @SuppressWarnings("MissingPermission")
    private static String currentSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.trim();
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid.isEmpty() || "<unknown ssid>".equals(ssid)) return null;
            return ssid;
        } catch (Throwable t) {
            Log.w(TAG, "ssid failed " + t);
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 失败返回 null。ctx 可为 null（则只用 IP）。 */
    public static String fetch(Context ctx) {
        try {
            if (ctx != null) {
                String cur = currentSsid(ctx);
                String home = prefs(ctx).getString(KEY_HOME, null);
                if (home == null && cur != null) {
                    prefs(ctx).edit().putString(KEY_HOME, cur).apply();
                    home = cur;
                    Log.i(TAG, "recorded home ssid=" + cur);
                }
                if (home != null && home.equals(cur)) {
                    Log.i(TAG, "at home (" + cur + ")");
                    return build(HOME_CITY, HOME_LAT, HOME_LON);
                }
            }
            return byIp();
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed " + t);
            return null;
        }
    }

    private static String byIp() {
        try {
            String ip = get("http://ip-api.com/json/?lang=zh-CN&fields=status,city,lat,lon");
            if (ip == null) return null;
            JSONObject io = new JSONObject(ip);
            if (!"success".equals(io.optString("status"))) return null;
            String city = io.optString("city", "");
            double lat = io.optDouble("lat", 0);
            double lon = io.optDouble("lon", 0);
            if (lat == 0 && lon == 0) return null;
            // 珠三角的 IP 常被登记到广州/深圳/香港，家里在顺德就统一显示佛山顺德
            if (city.matches(".*(广州|佛山|深圳|香港|澳门|东莞|中山|珠海|江门|肇庆).*")) {
                Log.i(TAG, "ip city " + city + " -> home");
                return build(HOME_CITY, HOME_LAT, HOME_LON);
            }
            return build(city, lat, lon);
        } catch (Throwable t) {
            Log.w(TAG, "ip failed " + t);
            return null;
        }
    }

    private static String build(String city, double lat, double lon) throws Exception {
        String m = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto");
        if (m == null) return null;
        JSONObject mo = new JSONObject(m).optJSONObject("current");
        if (mo == null) return null;
        int temp = (int) Math.round(mo.optDouble("temperature_2m", 0));
        int code = mo.optInt("weather_code", -1);
        String text = describe(code);
        Log.i(TAG, city + " " + temp + " " + text);
        return (city == null || city.isEmpty() ? "" : city + "  ") + temp + "°  " + text;
    }

    private static String describe(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "少云";
        if (code == 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code >= 95) return "雷雨";
        return "";
    }
}