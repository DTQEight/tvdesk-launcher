package com.gathin.tvhome;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 应用入口：装一个崩溃处理器，把堆栈写到 cache/crash.txt，方便 adb 拉出来。 */
public class TVDeskApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        JellyfinClient.init(this);
        LibClient.init(this);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String head = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date()) + "  " + t.getName() + "\n";
                    File f = new File(getCacheDir(), "crash.txt");
                    java.io.FileWriter w = new java.io.FileWriter(f, true);
                    w.write("\n===== " + head + sw + "\n");
                    w.close();
                    Log.e("CYT-CRASH", "logged to " + f);
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}