package com.gathin.tvhome;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.leanback.media.PlaybackTransportControlGlue;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.PlaybackControlsRow;

/** Leanback 的传输控制（播放/暂停、进度条、时间）+ 自定义次级按钮。 */
public class PlayerGlue extends PlaybackTransportControlGlue<IjkPlayerAdapter> {

    public interface Listener {
        void onSubtitles();
        void onAudioTrack();
        void onSpeed();
        void onNext();
        void onEpisodes();
    }

    private final Listener listener;
    private PlaybackControlsRow.MultiAction subAction, audioAction, speedAction, nextAction, listAction;

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

    /** 主按钮行：快退 + 播放/暂停 + 快进（Jellyfin 也是这三颗）。 */
    @Override
    protected void onCreatePrimaryActions(ArrayObjectAdapter primary) {
        super.onCreatePrimaryActions(primary);
        primary.add(0, new PlaybackControlsRow.RewindAction(getContext()));
        primary.add(2, new PlaybackControlsRow.FastForwardAction(getContext()));
    }

    @Override
    protected void onCreateSecondaryActions(ArrayObjectAdapter secondaryActionsAdapter) {
        subAction = action(0, R.drawable.ic_p_sub, "字幕");
        audioAction = action(1, R.drawable.ic_p_audio, "音轨");
        speedAction = action(2, R.drawable.ic_p_speed, "倍速");
        nextAction = action(3, R.drawable.ic_p_next, "下一集");
        listAction = action(4, R.drawable.ic_p_list, "选集");
        secondaryActionsAdapter.add(subAction);
        secondaryActionsAdapter.add(audioAction);
        secondaryActionsAdapter.add(speedAction);
        secondaryActionsAdapter.add(nextAction);
        secondaryActionsAdapter.add(listAction);
    }

    @Override
    public void onActionClicked(Action action) {
        if (action == subAction) { listener.onSubtitles(); return; }
        if (action == audioAction) { listener.onAudioTrack(); return; }
        if (action == speedAction) { listener.onSpeed(); return; }
        if (action == nextAction) { listener.onNext(); return; }
        if (action == listAction) { listener.onEpisodes(); return; }
        super.onActionClicked(action);
    }
}