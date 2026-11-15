package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        // 壁纸功能容易把 Jellyfin 剧照背景盖住，入口隐藏
        wallpaperChoice.setVisibility(android.view.View.GONE);
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        addTvDeskRow();
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /** 在设置页末尾追加「TVDesk 设置」一行（背景轮播 / 天气 / 预览）。 */
    /** 设置页第一行「TVDesk 设置」，带明显的焦点高亮。 */
    private void addTvDeskRow() {
        try {
            android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
            ScrollView sv = (ScrollView) content.getChildAt(0);
            LinearLayout root = (LinearLayout) sv.getChildAt(0);

            final TextView row = new TextView(this);
            row.setText("▶ TVDesk 设置（背景 / 天气 / 预览 / 图片代理）");
            row.setTextSize(18);
            row.setPadding(24, 26, 24, 26);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setBackground(pill(false));
            row.setTextColor(0xFFE6EEF5);
            row.setOnFocusChangeListener((v, has) -> {
                v.setBackground(pill(has));
                row.setTextColor(has ? 0xFF0B1116 : 0xFFE6EEF5);
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120).start();
            });
            row.setOnClickListener(v -> showTvDeskDialog());

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 10;
            root.addView(row, Math.min(1, root.getChildCount()), lp);
            row.post(new Runnable() { @Override public void run() { row.requestFocus(); } });
        } catch (Throwable t) {
            android.util.Log.w("CYT-PREFS", "addTvDeskRow failed " + t);
        }
    }

    /** 常态/焦点态背景：焦点是白底 + 青色描边，和桌面 tab 一致。 */
    private android.graphics.drawable.GradientDrawable pill(boolean focused) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        if (focused) {
            d.setColor(0xFFFFFFFF);
            d.setStroke((int) (3 * getResources().getDisplayMetrics().density), 0xFF00A4DC);
        } else {
            d.setColor(0x33121820);
            d.setStroke((int) (1 * getResources().getDisplayMetrics().density), 0x33FFFFFF);
        }
        return d;
    }

    /** 隐藏「每行应用数量 / Rows below dock」——这两项对现在的桌面没有效果。 */
    private void hideGridOptions(LinearLayout root) {
        try {
            hideBlock(findViewById(R.id.columns4));
            hideBlock(findViewById(R.id.rows0));
        } catch (Throwable t) {
            android.util.Log.w("CYT-PREFS", "hideGridOptions " + t);
        }
    }

    private void hideBlock(View any) {
        if (any == null) return;
        android.view.ViewGroup row = (android.view.ViewGroup) any.getParent();
        if (row == null) return;
        android.view.ViewParent p = row.getParent();
        if (p instanceof LinearLayout) {
            LinearLayout col = (LinearLayout) p;
            int idx = col.indexOfChild(row);
            if (idx > 0) col.getChildAt(idx - 1).setVisibility(View.GONE);   // 标题
        }
        row.setVisibility(View.GONE);
    }

    private void showTvDeskDialog() {
        final android.content.SharedPreferences p = TVDeskPrefs.get(this);
        final String[] items = {
                "背景随机轮播：" + (p.getBoolean(TVDeskPrefs.KEY_BACKDROP, true) ? "开" : "关"),
                "轮播间隔：" + (p.getInt(TVDeskPrefs.KEY_INTERVAL, 30000) / 1000) + " 秒",
                "天气定位：" + (p.getBoolean(TVDeskPrefs.KEY_WEATHER_HOME, false) ? "固定佛山顺德" : "跟随 IP"),
                "推荐页图片代理：" + (p.getBoolean(TVDeskPrefs.KEY_PROXY_ON, true) ? "开" : "关"),
                "代理地址：" + p.getString(TVDeskPrefs.KEY_PROXY_HOST, "192.168.31.1"),
                "代理端口：" + p.getInt(TVDeskPrefs.KEY_PROXY_PORT, 7890),
                "服务接入：" + (DeskConfig.hasJellyfin() || DeskConfig.hasRecommend() || DeskConfig.hasLive() ? "已配置 ›" : "未配置 ›")
        };
        new AlertDialog.Builder(this)
                .setTitle("TVDesk 设置")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            p.edit().putBoolean(TVDeskPrefs.KEY_BACKDROP,
                                    !p.getBoolean(TVDeskPrefs.KEY_BACKDROP, true)).apply();
                            break;
                        case 1: {
                            int cur = p.getInt(TVDeskPrefs.KEY_INTERVAL, 30000);
                            int next = cur == 30000 ? 60000 : cur == 60000 ? 120000 : 30000;
                            p.edit().putInt(TVDeskPrefs.KEY_INTERVAL, next).apply();
                            break;
                        }
                        case 2:
                            p.edit().putBoolean(TVDeskPrefs.KEY_WEATHER_HOME,
                                    !p.getBoolean(TVDeskPrefs.KEY_WEATHER_HOME, false)).apply();
                            break;
                        case 3:
                            p.edit().putBoolean(TVDeskPrefs.KEY_PROXY_ON,
                                    !p.getBoolean(TVDeskPrefs.KEY_PROXY_ON, true)).apply();
                            break;
                        case 4:
                            editProxyHost();
                            return;
                        case 5:
                            editProxyPort();
                            return;
                        case 6:
                            showServicesDialog();
                            return;
                    }
                    Toast.makeText(this, "已保存，回桌面生效", Toast.LENGTH_SHORT).show();
                    showTvDeskDialog();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 改代理地址（用遥控器在弹窗里输入 IP）。 */
    private void editProxyHost() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(TVDeskPrefs.proxyHost(this));
        input.setSelection(input.getText().length());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("代理地址（如 192.168.31.1）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.isEmpty()) {
                        TVDeskPrefs.get(this).edit().putString(TVDeskPrefs.KEY_PROXY_HOST, v).apply();
                    }
                    showTvDeskDialog();
                })
                .setNegativeButton("取消", (d, w) -> showTvDeskDialog())
                .show();
    }

    /** 改代理端口。 */
    private void editProxyPort() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(String.valueOf(TVDeskPrefs.proxyPort(this)));
        input.setSelection(input.getText().length());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("代理端口（如 7890）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int port = Integer.parseInt(input.getText().toString().trim());
                        TVDeskPrefs.get(this).edit().putInt(TVDeskPrefs.KEY_PROXY_PORT, port).apply();
                    } catch (Throwable ignored) { }
                    showTvDeskDialog();
                })
                .setNegativeButton("取消", (d, w) -> showTvDeskDialog())
                .show();
    }

    /** 服务接入：Jellyfin / MoviePilot / 2bt0 / 直播源，填了才启用对应页面。 */
    private void showServicesDialog() {
        DeskConfig.load(this);
        final String[] items = {
                "Jellyfin 地址：" + shown(DeskConfig.jfBase, "未配置"),
                "Jellyfin 令牌：" + (DeskConfig.jfToken.isEmpty() ? "未配置" : "已填"),
                "Jellyfin 用户 ID：" + shown(DeskConfig.jfUser, "未配置"),
                "MoviePilot 地址：" + shown(DeskConfig.mpBase, "未配置"),
                "MoviePilot Key：" + (DeskConfig.mpKey.isEmpty() ? "未配置" : "已填"),
                "2bt0 资源库地址：" + shown(DeskConfig.libBase, "未配置"),
                "2bt0 Key：" + (DeskConfig.libKey.isEmpty() ? "未配置" : "已填"),
                "直播源 M3U：" + shown(DeskConfig.liveM3u, "未配置"),
                "EPG 地址：" + shown(DeskConfig.epgUrl, "未配置"),
                "清空全部接入配置"
        };
        new AlertDialog.Builder(this)
                .setTitle("服务接入（留空 = 关闭该功能）")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: editConfig(DeskConfig.KEY_JF_BASE, "Jellyfin 地址", "http://192.168.1.10:8096", false); return;
                        case 1: editConfig(DeskConfig.KEY_JF_TOKEN, "Jellyfin 令牌（API Key / Token）", "", false); return;
                        case 2: editConfig(DeskConfig.KEY_JF_USER, "Jellyfin 用户 ID", "", false); return;
                        case 3: editConfig(DeskConfig.KEY_MP_BASE, "MoviePilot 地址", "http://192.168.1.10:3000", false); return;
                        case 4: editConfig(DeskConfig.KEY_MP_KEY, "MoviePilot API Key", "", false); return;
                        case 5: editConfig(DeskConfig.KEY_LIB_BASE, "2bt0 资源库地址", "http://192.168.1.10:8000", false); return;
                        case 6: editConfig(DeskConfig.KEY_LIB_KEY, "2bt0 API Key", "", false); return;
                        case 7: editConfig(DeskConfig.KEY_LIVE_M3U, "直播源 M3U 地址", "http://example.com/live.m3u", false); return;
                        case 8: editConfig(DeskConfig.KEY_EPG_URL, "EPG（XMLTV.gz）地址", "", false); return;
                        case 9:
                            new AlertDialog.Builder(this)
                                    .setTitle("清空接入配置？")
                                    .setMessage("清空后推荐页 / 影视页 / 直播都会关闭，只剩普通桌面。")
                                    .setPositiveButton("清空", (dd, ww) -> {
                                        String[] keys = {DeskConfig.KEY_JF_BASE, DeskConfig.KEY_JF_TOKEN, DeskConfig.KEY_JF_USER,
                                                DeskConfig.KEY_MP_BASE, DeskConfig.KEY_MP_KEY, DeskConfig.KEY_LIB_BASE,
                                                DeskConfig.KEY_LIB_KEY, DeskConfig.KEY_LIVE_M3U, DeskConfig.KEY_EPG_URL};
                                        android.content.SharedPreferences.Editor e = TVDeskPrefs.get(this).edit();
                                        for (String k : keys) e.remove(k);
                                        e.apply();
                                        DeskConfig.load(this);
                                        Toast.makeText(this, "已清空，重启桌面生效", Toast.LENGTH_SHORT).show();
                                        showServicesDialog();
                                    })
                                    .setNegativeButton("取消", (dd, ww) -> showServicesDialog())
                                    .show();
                            return;
                    }
                })
                .setNegativeButton("返回", (d, w) -> showTvDeskDialog())
                .show();
    }

    private static String shown(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }

    private void editConfig(final String key, String title, String hint, boolean numeric) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(DeskConfig.get(this, key));
        input.setSelection(input.getText().length());
        input.setHint(hint);
        input.setInputType(numeric ? android.text.InputType.TYPE_CLASS_NUMBER
                : android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    DeskConfig.put(LauncherSettingsActivity.this, key, input.getText().toString());
                    Toast.makeText(this, "已保存，重启桌面生效", Toast.LENGTH_SHORT).show();
                    showServicesDialog();
                })
                .setNegativeButton("取消", (d, w) -> showServicesDialog())
                .show();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
