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
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "gotoSource failed " + t);
            return false;
        }
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