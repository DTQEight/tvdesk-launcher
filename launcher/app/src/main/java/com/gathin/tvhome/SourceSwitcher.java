package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** 创维/酷开电视的信号源切换：厂商 ContentProvider（实测有效）。 */
public final class SourceSwitcher {

    private static final String TAG = "CYT-LIVE";
    private static final Uri URI = Uri.parse("content://com.skyworth.tv.sdk.provider/method");

    private SourceSwitcher() { }

    public static boolean gotoSource(Context ctx, String name, int index) {
        try {
            Bundle b = new Bundle();
            b.putString("name", name);
            b.putInt("index", index);
            b.putInt("flag", 0);
            Bundle ret = ctx.getContentResolver().call(URI, "METHOD_GOTO_SOURCE", null, b);
            boolean ok = ret != null && ret.getBoolean("ret", false);
            Log.i(TAG, "gotoSource " + name + " -> " + ok);
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