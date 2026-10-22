package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 创维/酷开电视的信号源切换。
 * 原生设置用的参数是：name=HDMI / index=0|1 / flag=0 / sourceId=HDMI1|HDMI2
 * （只传 name=HDMI1 时电视只会打开 TV 应用，不会真正切到该输入）
 */
public final class SourceSwitcher {

    private static final String TAG = "CYT-LIVE";
    private static final Uri URI = Uri.parse("content://com.skyworth.tv.sdk.provider/method");

    private SourceSwitcher() { }

    /** sourceId 形如 HDMI1 / HDMI2 / AV1。 */
    public static boolean gotoSource(Context ctx, String sourceId) {
        try {
            String name = sourceId;
            int index = 0;
            if (sourceId.startsWith("HDMI")) {
                name = "HDMI";
                String num = sourceId.substring(4);
                try { index = Math.max(0, Integer.parseInt(num) - 1); } catch (Throwable ignored) { }
            } else if (sourceId.startsWith("AV")) {
                name = "AV";
                String num = sourceId.substring(2);
                try { index = Math.max(0, Integer.parseInt(num) - 1); } catch (Throwable ignored) { }
            }
            Bundle b = new Bundle();
            b.putString("name", name);
            b.putInt("index", index);
            b.putInt("flag", 0);
            b.putString("sourceId", sourceId);
            Bundle ret = ctx.getContentResolver().call(URI, "METHOD_GOTO_SOURCE", null, b);
            boolean ok = ret != null && ret.getBoolean("ret", false);
            Log.i(TAG, "gotoSource " + sourceId + " (name=" + name + " index=" + index + ") -> " + ok);
            if (ok) checkSignalLater(ctx, sourceId);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "gotoSource failed " + t);
            return false;
        }
    }

    /** 释放当前占用的信号源（否则回到桌面后 HDMI 声音还在响）。 */
    public static boolean releaseSource(Context ctx) {
        try {
            Bundle ret = ctx.getContentResolver().call(URI, "METHOD_FORCE_RELEASE_SOURCE", null, (Bundle) null);
            boolean ok = ret != null && ret.getBoolean("ret", false);
            Log.i(TAG, "releaseSource -> " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "releaseSource failed " + t);
            return false;
        }
    }

    /** 读电视 HDMI 接收端信息，判断有没有信号（Amlogic 平台）。 */
    public static boolean hasHdmiSignal() {
        try {
            java.io.File f = new java.io.File("/sys/class/hdmirx/hdmirx0/info");
            if (!f.exists()) return true;   // 读不到就别误报
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            boolean ok = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("Hactive:") || line.startsWith("Vactive:")) {
                    String[] p = line.split(":");
                    if (p.length == 2) {
                        try { if (Integer.parseInt(p[1].trim()) > 0) ok = true; } catch (Throwable ignored) { }
                    }
                }
            }
            r.close();
            return ok;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 切换后 2.5 秒检查一次：没信号就提示（Toast 在电视应用之上也能看到）。 */
    public static void checkSignalLater(final Context ctx, final String sourceId) {
        final Context app = ctx.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (!hasHdmiSignal()) {
                    try {
                        android.widget.Toast.makeText(app,
                                sourceId + " 没有检测到信号，请检查盒子电源 / HDMI 线",
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) { }
                }
            }
        }, 2500L);
    }

    public static boolean backToTv(Context ctx) {
        try {
            Bundle ret = ctx.getContentResolver().call(URI, "METHOD_BACK_TO_TV", null, (Bundle) null);
            return ret != null && ret.getBoolean("ret", false);
        } catch (Throwable t) {
            Log.e(TAG, "backToTv failed " + t);
            return false;
        }
    }
}