package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** 天气：IP 定位（ip-api）→ Open-Meteo 取实况，返回「深圳 26° 多云」。 */
public final class Weather {

    private static final String TAG = "CYT-WEATHER";

    /** 家里位置：佛山顺德（IP 定位常被运营商出口带到广州，所以直接写死）。 */
    private static final String CITY = "佛山顺德";
    private static final double LAT = 22.8058;
    private static final double LON = 113.2930;

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

    /** 失败返回 null。 */
    public static String fetch() {
        try {
            String city = CITY;
            double lat = LAT, lon = LON;
            if (lat == 0 && lon == 0) {
                String ip = get("http://ip-api.com/json/?lang=zh-CN&fields=status,city,lat,lon");
                if (ip == null) return null;
                JSONObject io = new JSONObject(ip);
                if (!"success".equals(io.optString("status"))) return null;
                city = io.optString("city", "");
                lat = io.optDouble("lat", 0);
                lon = io.optDouble("lon", 0);
            }

            String m = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto");
            if (m == null) return null;
            JSONObject mo = new JSONObject(m).optJSONObject("current");
            if (mo == null) return null;
            int temp = (int) Math.round(mo.optDouble("temperature_2m", 0));
            int code = mo.optInt("weather_code", -1);
            String text = describe(code);
            Log.i(TAG, city + " " + temp + " " + text);
            return (city.isEmpty() ? "" : city + "  ") + temp + "°  " + text;
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed " + t);
            return null;
        }
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
