package com.gathin.tvhome;

import android.content.Context;

import androidx.leanback.media.PlaybackTransportControlGlue;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ControlButtonPresenterSelector;
import androidx.leanback.widget.PlaybackControlsRow;

/** Leanback 的传输控制（播放/暂停、进度条、时间）。 */
public class PlayerGlue extends PlaybackTransportControlGlue<IjkPlayerAdapter> {

    public interface Listener {
        void onSubtitles();
        void onAudioTrack();
        void onSpeed();
        void onNext();
        void onEpisodes();
    }

    private final Listener listener;
    private PlaybackControlsRow.MultiAction subAction;
    private PlaybackControlsRow.MultiAction audioAction;
    private PlaybackControlsRow.MultiAction speedAction;
    private PlaybackControlsRow.MultiAction nextAction;
    private PlaybackControlsRow.MultiAction listAction;

    public PlayerGlue(Context context, IjkPlayerAdapter adapter, Listener listener) {
        super(context, adapter);
        this.listener = listener;
    }

    @Override
    protected void onCreateSecondaryActions(ArrayObjectAdapter secondaryActionsAdapter) {
        subAction = new PlaybackControlsRow.MultiAction(0);
        subAction.setIcon(getContext(), R.drawable.ic_p_sub);
        subAction.setLabel1("字幕");
        audioAction = new PlaybackControlsRow.MultiAction(1);
        audioAction.setIcon(getContext(), R.drawable.ic_p_audio);
        audioAction.setLabel1("音轨");
        speedAction = new PlaybackControlsRow.MultiAction(2);
        speedAction.setIcon(getContext(), R.drawable.ic_p_speed);
        speedAction.setLabel1("倍速");
        nextAction = new PlaybackControlsRow.MultiAction(3);
        nextAction.setIcon(getContext(), R.drawable.ic_p_next);
        nextAction.setLabel1("下一集");
        listAction = new PlaybackControlsRow.MultiAction(4);
        listAction.setIcon(getContext(), R.drawable.ic_p_list);
        listAction.setLabel1("选集");

        ArrayObjectAdapter adapter = new ArrayObjectAdapter(new ControlButtonPresenterSelector());
        adapter.add(subAction);
        adapter.add(audioAction);
        adapter.add(speedAction);
        adapter.add(nextAction);
        adapter.add(listAction);
        secondaryActionsAdapter.addAll(0, adapter.size());
        for (int i = 0; i < adapter.size(); i++) {
            secondaryActionsAdapter.add(adapter.get(i));
        }
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