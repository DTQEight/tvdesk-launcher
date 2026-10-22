package com.gathin.tvhome;

import android.content.Context;
import android.view.KeyEvent;

import androidx.core.content.ContextCompat;
import androidx.leanback.media.PlaybackTransportControlGlue;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.PlaybackControlsRow;

/**
 * Leanback 播放控制条：
 *  - 主行：播放/暂停（Leanback 自带）
 *  - 次级行：退15秒 / 进15秒 / 字幕 / 音轨 / 倍速 / 下一集 / 选集
 *
 * 注意：Leanback 的 transport glue 只处理 PlayPause / SkipNext / SkipPrevious，
 *      自带的 RewindAction / FastForwardAction 点了没反应，所以跳转按钮全部自定义。
 */
public class PlayerGlue extends PlaybackTransportControlGlue<IjkPlayerAdapter> {

    public interface Listener {
        void onSeekBack();
        void onSeekForward();
        void onSubtitles();
        void onAudioTrack();
        void onSpeed();
        void onNext();
        void onEpisodes();
        void onSeekFeedback();
    }

    private final Listener listener;
    private PlaybackControlsRow.MultiAction rewAction, ffAction, subAction,
            audioAction, speedAction, nextAction, listAction;

    public PlayerGlue(Context context, IjkPlayerAdapter adapter, Listener listener) {
        super(context, adapter);
        this.listener = listener;
    }

    private PlaybackControlsRow.MultiAction action(int id, int icon, String label) {
        PlaybackControlsRow.MultiAction a = new PlaybackControlsRow.MultiAction(id) { };
        a.setIcon(ContextCompat.getDrawable(getContext(), icon));
        a.setLabel1(label);
        return a;
    }

    /** 手动触发一次 onHostStart，让进度刷新跑起来。 */
    public void forceStart() {
        onHostStart();
    }

    @Override
    protected void onCreateSecondaryActions(ArrayObjectAdapter secondaryActionsAdapter) {
        rewAction = action(10, R.drawable.ic_p_rew10, "退15秒");
        ffAction = action(11, R.drawable.ic_p_fwd10, "进15秒");
        subAction = action(0, R.drawable.ic_p_sub, "字幕");
        audioAction = action(1, R.drawable.ic_p_audio, "音轨");
        speedAction = action(2, R.drawable.ic_p_speed, "倍速");
        nextAction = action(3, R.drawable.ic_p_next, "下一集");
        listAction = action(4, R.drawable.ic_p_list, "选集");
        secondaryActionsAdapter.add(rewAction);
        secondaryActionsAdapter.add(ffAction);
        secondaryActionsAdapter.add(subAction);
        secondaryActionsAdapter.add(audioAction);
        secondaryActionsAdapter.add(speedAction);
        secondaryActionsAdapter.add(nextAction);
        secondaryActionsAdapter.add(listAction);
    }

    @Override
    public void onActionClicked(Action action) {
        if (action == rewAction) { listener.onSeekBack(); return; }
        if (action == ffAction) { listener.onSeekForward(); return; }
        if (action == subAction) { listener.onSubtitles(); return; }
        if (action == audioAction) { listener.onAudioTrack(); return; }
        if (action == speedAction) { listener.onSpeed(); return; }
        if (action == nextAction) { listener.onNext(); return; }
        if (action == listAction) { listener.onEpisodes(); return; }
        super.onActionClicked(action);
    }

    @Override
    public boolean onKey(android.view.View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) listener.onSeekBack();
                else listener.onSeekForward();
            }
            return true;
        }
        boolean handled = super.onKey(v, keyCode, event);
        if (handled && event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (listener != null) listener.onSeekFeedback();
        }
        return handled;
    }
}
