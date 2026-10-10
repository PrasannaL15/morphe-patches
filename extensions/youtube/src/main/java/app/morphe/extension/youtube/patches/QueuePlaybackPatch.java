/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Player Next/Prev and end-of-video hooks for the Morphe queue.
 */
@SuppressWarnings("unused")
public final class QueuePlaybackPatch {

    private static final long ADVANCE_COOLDOWN_MS = 2500;
    private static final int NEXT_FORCE_TAG_KEY = 0x6d72716e; // mrqn

    private static final int[] NEXT_IDS = new int[2];
    private static final int[] PREV_IDS = new int[2];
    private static volatile boolean idsResolved;
    private static volatile long lastAdvanceTimeMs;

    private static final List<WeakReference<View>> nextButtonRefs = new ArrayList<>();

    private QueuePlaybackPatch() {
    }

    /**
     * Injection point.
     */
    public static void hookPlayerControls(View parentView) {
        try {
            if (parentView == null || !Settings.QUEUE_ADVANCE_PLAYBACK.get()) {
                return;
            }
            resolveIds();
            wrapControlTree(parentView);
            parentView.post(() -> wrapControlTree(parentView));
            parentView.postDelayed(() -> {
                wrapControlTree(parentView);
                refreshQueueNavButtons();
            }, 400);
        } catch (Exception ex) {
            Logger.printException(() -> "hookPlayerControls failure", ex);
        }
    }

    /**
     * Shows the stock Next control while the queue has a successor.
     */
    public static void refreshQueueNavButtons() {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                boolean showNext = Settings.QUEUE_ADVANCE_PLAYBACK.get()
                        && PlaylistPatch.canPlayNextInQueue();
                Iterator<WeakReference<View>> it = nextButtonRefs.iterator();
                while (it.hasNext()) {
                    View view = it.next().get();
                    if (view == null) {
                        it.remove();
                        continue;
                    }
                    if (showNext) {
                        showNextControl(view);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "refreshQueueNavButtons failure", ex);
            }
        });
    }

    /**
     * Injection point.
     *
     * @return true if end-of-video was handled and native handling should stop.
     */
    public static boolean onPlayerStatus(Enum<?> status) {
        try {
            if (status == null || !"ENDED".equals(status.name())) {
                return false;
            }
            if (!Settings.QUEUE_ADVANCE_PLAYBACK.get() || Settings.LOOP_VIDEO.get()) {
                return false;
            }

            // Do not remove queue items here. ENDED also fires during Previous, and
            // PlayerType can be hidden mid-transition so "has next" looks false and the
            // wrong video gets dropped. Forward playback trims in syncQueuePlaybackPosition.
            boolean hadNext = PlaylistPatch.canPlayNextInQueue();
            boolean inPlaylist = PlaylistPatch.isPlayerInMorpheQueue();
            boolean advanced = false;
            if (hadNext && !inPlaylist) {
                advanced = tryAdvanceQueue(+1);
            }
            refreshQueueNavButtons();
            return advanced;
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerStatus failure", ex);
            return false;
        }
    }

    private static void resolveIds() {
        if (idsResolved) {
            return;
        }
        NEXT_IDS[0] = ResourceUtils.getIdentifier(ResourceType.ID, "player_control_next_button_touch_area");
        NEXT_IDS[1] = ResourceUtils.getIdentifier(ResourceType.ID, "player_control_next_button");
        PREV_IDS[0] = ResourceUtils.getIdentifier(ResourceType.ID, "player_control_previous_button_touch_area");
        PREV_IDS[1] = ResourceUtils.getIdentifier(ResourceType.ID, "player_control_previous_button");
        idsResolved = true;
    }

    private static void wrapControlTree(View root) {
        for (int id : NEXT_IDS) {
            if (id != 0) {
                wrapView(root.findViewById(id), +1);
            }
        }
        for (int id : PREV_IDS) {
            if (id != 0) {
                wrapView(root.findViewById(id), -1);
            }
        }
    }

    private static void wrapView(@Nullable View view, int delta) {
        if (view == null) {
            return;
        }
        View.OnClickListener existing = getOnClickListener(view);
        if (!(existing instanceof QueueNavClickListener)) {
            view.setOnClickListener(new QueueNavClickListener(existing, delta));
            view.setClickable(true);
        }
        if (delta > 0) {
            trackNextButton(view);
        }
    }

    private static void trackNextButton(View view) {
        for (WeakReference<View> ref : nextButtonRefs) {
            if (ref.get() == view) {
                attachNextForceVisible(view);
                return;
            }
        }
        nextButtonRefs.add(new WeakReference<>(view));
        attachNextForceVisible(view);
        if (PlaylistPatch.canPlayNextInQueue()) {
            showNextControl(view);
        }
    }

    private static void attachNextForceVisible(View view) {
        if (Boolean.TRUE.equals(view.getTag(NEXT_FORCE_TAG_KEY))) {
            return;
        }
        view.setTag(NEXT_FORCE_TAG_KEY, Boolean.TRUE);
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (view.isAttachedToWindow()
                        && Settings.QUEUE_ADVANCE_PLAYBACK.get()
                        && PlaylistPatch.canPlayNextInQueue()) {
                    showNextControl(view);
                }
                return true;
            }
        });
    }

    private static void showNextControl(View view) {
        view.setVisibility(View.VISIBLE);
        view.setEnabled(true);
        view.setClickable(true);
        view.setAlpha(1f);
        Object parent = view.getParent();
        if (parent instanceof View parentView) {
            parentView.setVisibility(View.VISIBLE);
            parentView.setEnabled(true);
            if (parentView instanceof ViewGroup) {
                parentView.setAlpha(1f);
            }
        }
    }

    private static boolean tryAdvanceQueue(int delta) {
        final long now = System.currentTimeMillis();
        if (now - lastAdvanceTimeMs < ADVANCE_COOLDOWN_MS) {
            return false;
        }

        boolean can = delta < 0
                ? PlaylistPatch.canPlayPreviousInQueue()
                : PlaylistPatch.canPlayNextInQueue();
        if (!can) {
            return false;
        }

        // Already bound with list= — prefer native playlist navigation.
        if (PlaylistPatch.isPlayerInMorpheQueue()) {
            return false;
        }

        boolean advanced = delta < 0
                ? PlaylistPatch.playPreviousInQueue()
                : PlaylistPatch.playNextInQueue();
        if (advanced) {
            lastAdvanceTimeMs = now;
        }
        return advanced;
    }

    @Nullable
    private static View.OnClickListener getOnClickListener(View view) {
        try {
            Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo == null) {
                return null;
            }
            Field clickField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
            clickField.setAccessible(true);
            Object listener = clickField.get(listenerInfo);
            return listener instanceof View.OnClickListener
                    ? (View.OnClickListener) listener
                    : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static final class QueueNavClickListener implements View.OnClickListener {
        @Nullable
        private final View.OnClickListener original;
        private final int delta;

        QueueNavClickListener(@Nullable View.OnClickListener original, int delta) {
            this.original = original;
            this.delta = delta;
        }

        @Override
        public void onClick(View v) {
            try {
                if (tryAdvanceQueue(delta)) {
                    return;
                }
                if (delta > 0
                        && PlaylistPatch.canPlayNextInQueue()
                        && PlaylistPatch.isPlayerInMorpheQueue()
                        && original != null) {
                    original.onClick(v);
                    return;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Queue nav click failure", ex);
            }
            if (original != null) {
                original.onClick(v);
            } else if (delta > 0
                    && PlaylistPatch.canPlayNextInQueue()
                    && !PlaylistPatch.isPlayerInMorpheQueue()) {
                // Only force an intent load when not already in the Morphe playlist.
                // Repeating openVideoIntent while in-playlist spams PoToken generation.
                PlaylistPatch.playNextInQueue();
            }
        }
    }
}
