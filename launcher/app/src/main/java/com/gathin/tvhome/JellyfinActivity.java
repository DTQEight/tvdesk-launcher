package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * 影视库独立页面：整页就是 JellyfinView，按键全部由这里转发给它。
 * 单独一个 Activity 可以彻底避开桌面 ViewPager 的焦点/按键纠缠。
 */
public class JellyfinActivity extends Activity {

    private JellyfinView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new JellyfinView(this);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(view);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.focusFirst();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (view.isDetailOpen()) { view.closeDetail(); return true; }
                if (view.isLibraryOpen()) { view.closeLibrary(); return true; }
                finish();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && view.isAtTop()) {
                finish();   // 顶到最上面再按 ↑ 就退出去（回到桌面 tab）
                return true;
            }
            if (view.handleKey(k)) return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
