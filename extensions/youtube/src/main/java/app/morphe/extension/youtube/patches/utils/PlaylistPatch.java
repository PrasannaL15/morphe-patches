/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.GuardedBy;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.QueuePlaybackPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.CreatePlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.EditPlaylistRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistsRequest;
import app.morphe.extension.youtube.patches.utils.requests.SavePlaylistRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Pair;

@SuppressWarnings({"unused", "StaticFieldLeak"})
public class PlaylistPatch {
    private static final int PLAYLIST_LIST_MAX_HEIGHT_PERCENT = 50;

    private static final String checkFailedAuth = str("morphe_queue_manager_check_failed_auth");
    private static final String checkFailedPlaylistId = str("morphe_queue_manager_check_failed_playlist_id");
    private static final String checkFailedQueue = str("morphe_queue_manager_check_failed_queue");
    private static final String checkFailedVideoId = str("morphe_queue_manager_check_failed_video_id");
    private static final String checkFailedGeneric = str("morphe_queue_manager_check_failed_generic");

    private static final String fetchFailedAdd = str("morphe_queue_manager_fetch_failed_add");
    private static final String fetchFailedCreate = str("morphe_queue_manager_fetch_failed_create");
    private static final String fetchFailedRemove = str("morphe_queue_manager_fetch_failed_remove");
    private static final String fetchFailedSave = str("morphe_queue_manager_fetch_failed_save");

    private static final String fetchSucceededAdd = str("morphe_queue_manager_fetch_succeeded_add");
    private static final String fetchSucceededCreate = str("morphe_queue_manager_fetch_succeeded_create");
    private static final String fetchSucceededRemove = str("morphe_queue_manager_fetch_succeeded_remove");
    private static final String fetchSucceededSave = str("morphe_queue_manager_fetch_succeeded_save");
    private static final String fetchSucceededPlayNext = str("morphe_queue_manager_fetch_succeeded_play_next");
    private static final String fetchSucceededPlayLast = str("morphe_queue_manager_fetch_succeeded_play_last");

    private static volatile String playlistId = Settings.QUEUE_RESTORE.get()
            ? Settings.QUEUE_PLAYLIST_ID.get()
            : Settings.QUEUE_PLAYLIST_ID.resetToDefault();
    private static volatile String videoId = "";
    private static volatile boolean syncStarted;

    @GuardedBy("itself")
    private static final BidiMap<String, String> lastVideoIds = new DualHashBidiMap<>();

    @GuardedBy("lastVideoIds")
    private static final List<String> orderedVideoIds = new ArrayList<>();

    /** Locally removed ids awaiting confirmation from edit_playlist. */
    private static final Set<String> pendingRemoteRemoveVideoIds = ConcurrentHashMap.newKeySet();

    @GuardedBy("remoteRemoveLock")
    private static final List<Pair<String, String>> remoteRemoveQueue = new ArrayList<>();
    private static final Object remoteRemoveLock = new Object();
    private static final AtomicBoolean remoteRemoveFlushScheduled = new AtomicBoolean(false);
    private static final long REMOTE_REMOVE_COALESCE_MS = 150;

    /**
     * Invoked by extension.
     */
    public static void prepareDialogBuilder(Context context, String currentVideoId) {
        Utils.verifyOnMainThread();

        if (AuthUtils.isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (currentVideoId.isEmpty()) {
            buildBottomSheetDialog(context, QueueManager.noVideoIdQueueEntries);
        } else {
            synchronized (lastVideoIds) {
                videoId = currentVideoId;
                QueueManager[] customActionsEntries;
                boolean canReload = !PlayerType.getCurrent().isNoneOrHidden() &&
                        lastVideoIds.get(VideoInformation.getVideoId()) != null;
                if (playlistId.isEmpty() || lastVideoIds.get(currentVideoId) == null) {
                    customActionsEntries = canReload
                            ? QueueManager.addToQueueWithReloadEntries
                            : QueueManager.addToQueueEntries;
                } else {
                    customActionsEntries = canReload
                            ? QueueManager.removeFromQueueWithReloadEntries
                            : QueueManager.removeFromQueueEntries;
                }
                buildBottomSheetDialog(context, customActionsEntries);
            }
        }
    }

    /**
     * Invoked by extension.
     */
    public static void syncIfNeeded() {
        if (!playlistId.isEmpty() && !syncStarted && !AuthUtils.isNotLoggedIn()) {
            syncStarted = true;
            syncPlaylistItems();
        }
    }

    private static void syncPlaylistItems() {
        Utils.submitOnBackgroundThread(() -> {
            Map<String, String> items = GetPlaylistItemsRequest.fetch(playlistId, AuthUtils.getRequestHeader());
            if (items != null && !items.isEmpty()) {
                synchronized (lastVideoIds) {
                    final LinkedHashMap<String, String> merged = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : items.entrySet()) {
                        if (!pendingRemoteRemoveVideoIds.contains(entry.getKey())) {
                            merged.put(entry.getKey(), entry.getValue());
                        }
                    }
                    for (String localId : orderedVideoIds) {
                        if (pendingRemoteRemoveVideoIds.contains(localId)) {
                            continue;
                        }
                        if (!merged.containsKey(localId) && lastVideoIds.containsKey(localId)) {
                            merged.put(localId, lastVideoIds.get(localId));
                        }
                    }
                    lastVideoIds.clear();
                    orderedVideoIds.clear();
                    for (Map.Entry<String, String> entry : merged.entrySet()) {
                        lastVideoIds.put(entry.getKey(), entry.getValue());
                        orderedVideoIds.add(entry.getKey());
                    }
                }
                Logger.printDebug(() -> "Synced queue playlist items");
            }
            return null;
        });
    }

    private static void rememberOrdered(String videoIdToRemember, String setVideoId, @Nullable String afterVideoId) {
        lastVideoIds.putIfAbsent(videoIdToRemember, setVideoId);
        orderedVideoIds.remove(videoIdToRemember);
        if (afterVideoId != null && !afterVideoId.isEmpty()) {
            int anchor = orderedVideoIds.indexOf(afterVideoId);
            if (anchor >= 0) {
                orderedVideoIds.add(anchor + 1, videoIdToRemember);
                return;
            }
        }
        orderedVideoIds.add(videoIdToRemember);
    }

    private static volatile String lastQueuePlaybackVideoId = "";

    public static boolean canPlayNextInQueue() {
        return canPlayRelativeInQueue(+1);
    }

    public static boolean canPlayPreviousInQueue() {
        return canPlayRelativeInQueue(-1);
    }

    /**
     * @return true if the player response playlist id is the Morphe queue playlist.
     */
    public static boolean isPlayerInMorpheQueue() {
        String ours = playlistId;
        return !ours.isEmpty() && ours.equals(VideoInformation.getPlaylistId());
    }

    /**
     * Updates the queue cursor for {@code newlyLoadedVideoId}. When moving forward,
     * removes earlier items (played / skipped). Going backward (Previous) keeps the queue.
     */
    public static void syncQueuePlaybackPosition(String newlyLoadedVideoId) {
        String id = sanitizeVideoId(newlyLoadedVideoId);
        if (id.isEmpty()) {
            return;
        }
        final List<String> playedBefore = new ArrayList<>();
        synchronized (lastVideoIds) {
            int index = orderedVideoIds.indexOf(id);
            if (index < 0) {
                return;
            }
            int previousIndex = lastQueuePlaybackVideoId.isEmpty()
                    ? -1
                    : orderedVideoIds.indexOf(lastQueuePlaybackVideoId);
            // Previous / same item: never trim. Only remove items when index moves forward.
            if (previousIndex >= 0 && index <= previousIndex) {
                lastQueuePlaybackVideoId = id;
                QueuePlaybackPatch.refreshQueueNavButtons();
                return;
            }
            if (index > 0) {
                playedBefore.addAll(orderedVideoIds.subList(0, index));
            }
            lastQueuePlaybackVideoId = id;
        }
        if (!playedBefore.isEmpty()) {
            removeVideosFromQueueLocalAndRemote(playedBefore);
        }
        QueuePlaybackPatch.refreshQueueNavButtons();
    }

    private static void removeVideosFromQueueLocalAndRemote(List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return;
        }
        final List<Pair<String, String>> removals = new ArrayList<>();
        synchronized (lastVideoIds) {
            for (String videoIdToRemove : videoIds) {
                String setId = lastVideoIds.get(videoIdToRemove);
                if (setId != null) {
                    removals.add(new Pair<>(videoIdToRemove, setId));
                }
                lastVideoIds.remove(videoIdToRemove);
                orderedVideoIds.remove(videoIdToRemove);
                pendingRemoteRemoveVideoIds.add(videoIdToRemove);
            }
        }
        if (removals.isEmpty()) {
            return;
        }
        if (playlistId.isEmpty()) {
            pendingRemoteRemoveVideoIds.removeAll(videoIds);
            return;
        }
        enqueueRemoteRemoves(removals);
    }

    private static void enqueueRemoteRemoves(List<Pair<String, String>> removals) {
        synchronized (remoteRemoveLock) {
            remoteRemoveQueue.addAll(removals);
        }
        if (!remoteRemoveFlushScheduled.compareAndSet(false, true)) {
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                do {
                    try {
                        Thread.sleep(REMOTE_REMOVE_COALESCE_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    flushRemoteRemovesOnce();
                } while (hasPendingRemoteRemoves());
            } finally {
                remoteRemoveFlushScheduled.set(false);
                if (hasPendingRemoteRemoves()) {
                    enqueueRemoteRemoves(Collections.emptyList());
                }
            }
        });
    }

    private static boolean hasPendingRemoteRemoves() {
        synchronized (remoteRemoveLock) {
            return !remoteRemoveQueue.isEmpty();
        }
    }

    private static void flushRemoteRemovesOnce() {
        final String currentPlaylistId = playlistId;
        final List<Pair<String, String>> batch;
        synchronized (remoteRemoveLock) {
            if (remoteRemoveQueue.isEmpty()) {
                return;
            }
            if (currentPlaylistId.isEmpty()) {
                remoteRemoveQueue.clear();
                return;
            }
            final LinkedHashMap<String, String> deduped = new LinkedHashMap<>();
            for (Pair<String, String> removal : remoteRemoveQueue) {
                deduped.put(removal.getFirst(), removal.getSecond());
            }
            remoteRemoveQueue.clear();
            batch = new ArrayList<>(deduped.size());
            for (Map.Entry<String, String> entry : deduped.entrySet()) {
                batch.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
        }

        final Set<String> setVideoIds = new LinkedHashSet<>();
        for (Pair<String, String> removal : batch) {
            setVideoIds.add(removal.getSecond());
        }
        boolean ok = EditPlaylistRequest.removeVideosSync(
                currentPlaylistId, setVideoIds, AuthUtils.getRequestHeader());
        for (Pair<String, String> removal : batch) {
            EditPlaylistRequest.clearVideoId(removal.getFirst());
            if (ok) {
                pendingRemoteRemoveVideoIds.remove(removal.getFirst());
            }
        }
        Logger.printDebug(() -> "Batch removed " + batch.size() + " queue playlist items, ok=" + ok);
    }

    private static boolean canPlayRelativeInQueue(int delta) {
        if (!Settings.QUEUE_ADVANCE_PLAYBACK.get() || playlistId.isEmpty()) {
            return false;
        }
        if (PlayerType.getCurrent().isNoneOrHidden()) {
            return false;
        }
        synchronized (lastVideoIds) {
            return resolveNextVideoIdLocked(delta) != null;
        }
    }

    public static boolean playNextInQueue() {
        return playRelativeInQueue(+1);
    }

    public static boolean playPreviousInQueue() {
        return playRelativeInQueue(-1);
    }

    private static String sanitizeVideoId(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return "";
        }
        final int amp = videoId.indexOf('&');
        return amp > 0 ? videoId.substring(0, amp) : videoId;
    }

    @Nullable
    private static String resolveNextVideoIdLocked(int delta) {
        if (orderedVideoIds.isEmpty()) {
            return null;
        }

        String currentVideoId = sanitizeVideoId(VideoInformation.getVideoId());
        String responseVideoId = sanitizeVideoId(VideoInformation.getPlayerResponseVideoId());
        if (currentVideoId.isEmpty()) {
            currentVideoId = responseVideoId;
        }

        int index = orderedVideoIds.indexOf(currentVideoId);
        if (index < 0 && !responseVideoId.isEmpty()) {
            index = orderedVideoIds.indexOf(responseVideoId);
        }
        if (index < 0 && !lastQueuePlaybackVideoId.isEmpty()) {
            index = orderedVideoIds.indexOf(lastQueuePlaybackVideoId);
        }
        if (index < 0) {
            return null;
        }

        lastQueuePlaybackVideoId = orderedVideoIds.get(index);
        int targetIndex = index + delta;
        if (targetIndex < 0 || targetIndex >= orderedVideoIds.size()) {
            return null;
        }
        return orderedVideoIds.get(targetIndex);
    }

    private static boolean playRelativeInQueue(int delta) {
        try {
            if (!Settings.QUEUE_ADVANCE_PLAYBACK.get() || playlistId.isEmpty()) {
                return false;
            }
            if (PlayerType.getCurrent().isNoneOrHidden()) {
                return false;
            }

            final String currentPlaylistId = playlistId;
            String targetVideoId;
            synchronized (lastVideoIds) {
                targetVideoId = resolveNextVideoIdLocked(delta);
                if (targetVideoId == null) {
                    return false;
                }
                // Leave lastQueuePlaybackVideoId on the current item until the new video
                // loads — syncQueuePlaybackPosition needs it to detect forward vs previous.
            }

            // Use watch URL with list= so native Next stays in the Morphe playlist.
            // Do not pass list= through openVideoIntentWithInternalContext (treated as video id).
            final String url = "https://www.youtube.com/watch?v=" + targetVideoId
                    + "&list=" + currentPlaylistId;
            Logger.printDebug(() -> "Queue advance delta=" + delta + " videoId=" + targetVideoId);
            LoadVideoPatch.openVideoIntent(url, false);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "playRelativeInQueue failure", ex);
            return false;
        }
    }

    /**
     * Inserts {@code targetVideoId} immediately after the currently playing video.
     */
    public static void queuePlayNextOneTap(Context context, String targetVideoId) {
        if (AuthUtils.isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (targetVideoId == null || targetVideoId.isEmpty()) {
            handleCheckError(checkFailedVideoId);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            boolean ok = ensureQueueAndInsertAfterCurrent(targetVideoId);
            if (ok) {
                String playingId = sanitizeVideoId(VideoInformation.getVideoId());
                if (playingId.isEmpty()) {
                    playingId = sanitizeVideoId(VideoInformation.getPlayerResponseVideoId());
                }
                if (!playingId.isEmpty()) {
                    lastQueuePlaybackVideoId = playingId;
                }
                showToast(fetchSucceededPlayNext);
                QueuePlaybackPatch.refreshQueueNavButtons();
            } else {
                showToast(fetchFailedAdd);
            }
        });
    }

    /**
     * Appends {@code targetVideoId} to the end of the Morphe queue.
     */
    public static void queuePlayLastOneTap(Context context, String targetVideoId) {
        if (AuthUtils.isNotLoggedIn()) {
            handleCheckError(checkFailedAuth);
            return;
        }
        if (targetVideoId == null || targetVideoId.isEmpty()) {
            handleCheckError(checkFailedVideoId);
            return;
        }
        videoId = targetVideoId;
        Utils.runOnBackgroundThread(() -> {
            boolean ok = appendVideo(targetVideoId, AuthUtils.getRequestHeader());
            if (ok) {
                String playingId = sanitizeVideoId(VideoInformation.getVideoId());
                if (playingId.isEmpty()) {
                    playingId = sanitizeVideoId(VideoInformation.getPlayerResponseVideoId());
                }
                if (!playingId.isEmpty()) {
                    lastQueuePlaybackVideoId = playingId;
                }
                showToast(fetchSucceededPlayLast);
                QueuePlaybackPatch.refreshQueueNavButtons();
            } else {
                fetchQueue(context, false, false, false, false, true, fetchSucceededPlayLast);
            }
        });
    }

    /**
     * Ensures the currently playing video is in the queue, then inserts {@code targetVideoId}
     * immediately after it.
     */
    private static boolean ensureQueueAndInsertAfterCurrent(String targetVideoId) {
        Utils.verifyOffMainThread();
        Map<String, String> headers = AuthUtils.getRequestHeader();
        String playingCandidate = VideoInformation.getVideoId();
        if (playingCandidate.isEmpty()) {
            playingCandidate = VideoInformation.getPlayerResponseVideoId();
        }
        final String playingId = playingCandidate;

        // Nothing playing: fall back to append.
        if (playingId.isEmpty()) {
            return appendVideo(targetVideoId, headers);
        }

        // Same video as currently playing: ensure it exists in the queue.
        if (playingId.equals(targetVideoId)) {
            synchronized (lastVideoIds) {
                if (lastVideoIds.containsKey(targetVideoId)) {
                    return true;
                }
            }
            return appendVideo(targetVideoId, headers);
        }

        String localPlaylistId;
        String anchorSetVideoId;
        String existingSetId;
        synchronized (lastVideoIds) {
            localPlaylistId = playlistId;
            anchorSetVideoId = lastVideoIds.get(playingId);
            existingSetId = lastVideoIds.get(targetVideoId);
        }

        if (localPlaylistId.isEmpty()) {
            if (!createQueueWithVideo(playingId, headers)) {
                return false;
            }
            synchronized (lastVideoIds) {
                localPlaylistId = playlistId;
                anchorSetVideoId = lastVideoIds.get(playingId);
            }
        } else if (anchorSetVideoId == null) {
            String seeded = EditPlaylistRequest.addVideoSync(playingId, localPlaylistId, headers);
            if (seeded == null || seeded.isEmpty()) {
                playlistId = Settings.QUEUE_PLAYLIST_ID.resetToDefault();
                if (!createQueueWithVideo(playingId, headers)) {
                    return false;
                }
                synchronized (lastVideoIds) {
                    localPlaylistId = playlistId;
                    anchorSetVideoId = lastVideoIds.get(playingId);
                }
            } else {
                synchronized (lastVideoIds) {
                    rememberOrdered(playingId, seeded, null);
                    anchorSetVideoId = seeded;
                }
            }
        }

        if (localPlaylistId.isEmpty() || anchorSetVideoId == null || anchorSetVideoId.isEmpty()) {
            return false;
        }

        if (existingSetId != null) {
            boolean moved = EditPlaylistRequest.moveVideoAfterSync(
                    localPlaylistId, existingSetId, anchorSetVideoId, headers);
            if (moved) {
                synchronized (lastVideoIds) {
                    orderedVideoIds.remove(targetVideoId);
                    int anchor = orderedVideoIds.indexOf(playingId);
                    if (anchor >= 0) {
                        orderedVideoIds.add(anchor + 1, targetVideoId);
                    } else {
                        orderedVideoIds.add(targetVideoId);
                    }
                }
            }
            return moved;
        }

        String addedSetId = EditPlaylistRequest.addVideoSync(targetVideoId, localPlaylistId, headers);
        if (addedSetId == null || addedSetId.isEmpty()) {
            return false;
        }
        boolean moved = EditPlaylistRequest.moveVideoAfterSync(
                localPlaylistId, addedSetId, anchorSetVideoId, headers);
        synchronized (lastVideoIds) {
            rememberOrdered(targetVideoId, addedSetId, moved ? playingId : null);
        }
        Logger.printDebug(() -> "Inserted " + targetVideoId + " after " + playingId + ", moved=" + moved);
        return true;
    }

    private static boolean createQueueWithVideo(String seedVideoId, Map<String, String> headers) {
        CreatePlaylistRequest.fetchRequestIfNeeded(seedVideoId, headers);
        CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoId(seedVideoId);
        if (request == null) {
            return false;
        }
        Pair<String, String> playlistIds = request.getPlaylistId();
        if (playlistIds == null) {
            return false;
        }
        String createdPlaylistId = playlistIds.getFirst();
        String setVideoId = playlistIds.getSecond();
        if (createdPlaylistId == null || setVideoId == null) {
            return false;
        }
        synchronized (lastVideoIds) {
            playlistId = createdPlaylistId;
            if (Settings.QUEUE_RESTORE.get()) {
                Settings.QUEUE_PLAYLIST_ID.save(createdPlaylistId);
            }
            lastVideoIds.clear();
            orderedVideoIds.clear();
            rememberOrdered(seedVideoId, setVideoId, null);
        }
        Logger.printDebug(() -> "Queue created for play-next, playlistId: " + createdPlaylistId);
        return true;
    }

    private static boolean appendVideo(String targetVideoId, Map<String, String> headers) {
        String localPlaylistId;
        synchronized (lastVideoIds) {
            localPlaylistId = playlistId;
            if (!localPlaylistId.isEmpty() && lastVideoIds.containsKey(targetVideoId)) {
                return true;
            }
        }
        if (localPlaylistId.isEmpty()) {
            return createQueueWithVideo(targetVideoId, headers);
        }
        String addedSetId = EditPlaylistRequest.addVideoSync(targetVideoId, localPlaylistId, headers);
        if (addedSetId == null || addedSetId.isEmpty()) {
            return false;
        }
        synchronized (lastVideoIds) {
            rememberOrdered(targetVideoId, addedSetId, null);
        }
        return true;
    }

    private static void buildBottomSheetDialog(Context context, QueueManager[] queueManagerEntries) {
        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                .createMainLayout(context, null);

        Map<View, Function<Context, Void>> actionsMap = new LinkedHashMap<>(2 * queueManagerEntries.length);
        for (QueueManager queueManager : queueManagerEntries) {
            View itemLayout = createItemLayout(context, queueManager.label, queueManager.drawableId);
            actionsMap.put(itemLayout, queueManager.onClickAction);
            mainLayout.addView(itemLayout);
        }

        SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                .createSlideDialog(context, mainLayout, 300);

        for (Map.Entry<View, Function<Context, Void>> entry : actionsMap.entrySet()) {
            Function<Context, Void> action = entry.getValue();
            entry.getKey().setOnClickListener(v -> {
                dialog.dismiss();
                action.apply(context);
            });
        }

        dialog.show();
    }

    @SuppressLint("ResourceType")
    private static View createItemLayout(Context context, String title, int iconId) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp16);
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconId);
        icon.setColorFilter(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        iconParams.setMarginEnd(Dim.dp16);
        icon.setLayoutParams(iconParams);
        row.addView(icon);

        TextView text = new TextView(context);
        text.setText(title);
        text.setTextColor(ThemeUtils.getAppForegroundColor());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        text.setLayoutParams(textParams);
        row.addView(text);

        return row;
    }

    private static void fetchQueue(Context context, boolean remove, boolean openPlaylist,
                                   boolean openVideo, boolean reload, boolean retry) {
        fetchQueue(context, remove, openPlaylist, openVideo, reload, retry, null);
    }

    private static void fetchQueue(Context context, boolean remove, boolean openPlaylist,
                                   boolean openVideo, boolean reload, boolean retry,
                                   @Nullable String successToastOverride) {
        String currentPlaylistId = playlistId;
        String currentVideoId = videoId;
        Utils.runOnBackgroundThread(() -> {
            synchronized (lastVideoIds) {
                if (currentPlaylistId.isEmpty()) {
                    CreatePlaylistRequest.fetchRequestIfNeeded(currentVideoId, AuthUtils.getRequestHeader());
                    CreatePlaylistRequest request = CreatePlaylistRequest.getRequestForVideoId(currentVideoId);
                    if (request != null) {
                        Pair<String, String> playlistIds = request.getPlaylistId();
                        if (playlistIds != null) {
                            String createdPlaylistId = playlistIds.getFirst();
                            String setVideoId = playlistIds.getSecond();
                            if (createdPlaylistId != null && setVideoId != null) {
                                playlistId = createdPlaylistId;
                                if (Settings.QUEUE_RESTORE.get()) {
                                    Settings.QUEUE_PLAYLIST_ID.save(createdPlaylistId);
                                }
                                rememberOrdered(currentVideoId, setVideoId, null);
                                showToast(successToastOverride != null
                                        ? successToastOverride
                                        : fetchSucceededCreate);
                                Logger.printDebug(() -> "Queue created, playlistId: "
                                        + createdPlaylistId + ", setVideoId: " + setVideoId);
                                if (openPlaylist) {
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                        }
                    }
                    showToast(fetchFailedCreate);
                } else {
                    String setVideoId = lastVideoIds.get(currentVideoId);
                    EditPlaylistRequest.fetchRequestIfNeeded(currentVideoId, currentPlaylistId,
                            setVideoId, AuthUtils.getRequestHeader());
                    EditPlaylistRequest request = EditPlaylistRequest.getRequestForVideoId(currentVideoId);
                    if (request != null) {
                        String fetchedSetVideoId = request.getResult();
                        Logger.printDebug(() -> "fetchedSetVideoId: " + fetchedSetVideoId);
                        if (remove) {
                            if ("".equals(fetchedSetVideoId)) {
                                lastVideoIds.remove(currentVideoId);
                                orderedVideoIds.remove(currentVideoId);
                                EditPlaylistRequest.clearVideoId(currentVideoId);
                                showToast(fetchSucceededRemove);
                                if (openPlaylist) {
                                    openQueue(context, currentVideoId, openVideo, reload);
                                }
                                return;
                            }
                            showToast(fetchFailedRemove);
                        } else {
                            if (fetchedSetVideoId == null || fetchedSetVideoId.isEmpty()) {
                                Logger.printDebug(() -> "Playlist not available fetchedSetVideoId: "
                                        + fetchedSetVideoId);
                                if (!retry) {
                                    // Already retried, give up.
                                    showToast(fetchFailedAdd);
                                    return;
                                }
                                // Clear saved playlist and try again.
                                playlistId = Settings.QUEUE_PLAYLIST_ID.resetToDefault();
                                fetchQueue(context, false, openPlaylist, openVideo, reload, false,
                                        successToastOverride);
                                return;
                            }

                            rememberOrdered(currentVideoId, fetchedSetVideoId, null);
                            EditPlaylistRequest.clearVideoId(currentVideoId);
                            Logger.printDebug(() -> "Video added, setVideoId: " + fetchedSetVideoId);
                            showToast(successToastOverride != null
                                    ? successToastOverride
                                    : fetchSucceededAdd);
                            if (openPlaylist) {
                                openQueue(context, currentVideoId, openVideo, reload);
                            }
                        }
                    }
                }
            }
        });
    }

    private static void saveToPlaylist(Context context) {
        String currentPlaylistId = playlistId;
        if (currentPlaylistId.isEmpty()) {
            handleCheckError(checkFailedQueue);
            return;
        }
        try {
            GetPlaylistsRequest request = GetPlaylistsRequest.fetchRequestIfNeeded(
                    currentPlaylistId, AuthUtils.getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() ->  {
                Pair<String, String>[] playlists = request.getPlaylists();
                if (playlists == null || playlists.length == 0) return;
                Utils.runOnMainThread(() -> {
                    SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                            .createMainLayout(context, null);
                    Map<View, Runnable> actionsMap = new LinkedHashMap<>(2 * playlists.length);
                    int libraryIconId = QueueManager.SAVE_QUEUE.drawableId;

                    LinearLayout listContainer = new LinearLayout(context);
                    listContainer.setOrientation(LinearLayout.VERTICAL);

                    for (Pair<String, String> playlist : playlists) {
                        String listId = playlist.getFirst();
                        String title = playlist.getSecond();
                        Runnable action = () -> saveToPlaylist(listId, title);
                        View itemLayout = createItemLayout(context, title, libraryIconId);
                        actionsMap.put(itemLayout, action);
                        listContainer.addView(itemLayout);
                    }

                    ScrollView scrollView = SheetBottomDialog.createCappedScrollView(
                            context, PLAYLIST_LIST_MAX_HEIGHT_PERCENT);
                    scrollView.addView(listContainer);
                    mainLayout.addView(scrollView);

                    SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                            .createSlideDialog(context, mainLayout, 300);
                    for (Map.Entry<View, Runnable> entry : actionsMap.entrySet()) {
                        Runnable action = entry.getValue();
                        entry.getKey().setOnClickListener(v -> {
                            dialog.dismiss();
                            action.run();
                        });
                    }
                    dialog.show();
                    GetPlaylistsRequest.clear();
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void saveToPlaylist(@Nullable String libraryId, @Nullable String libraryTitle) {
        try {
            if (TextUtils.isEmpty(libraryId)) {
                handleCheckError(checkFailedPlaylistId);
                return;
            }
            SavePlaylistRequest request = SavePlaylistRequest.fetchRequestIfNeeded(
                    playlistId, libraryId, AuthUtils.getRequestHeader());
            if (request == null) {
                return;
            }
            Utils.runOnBackgroundThread(() -> {
                Boolean result = request.getResult();
                if (Boolean.TRUE.equals(result)) {
                    showToast(String.format(fetchSucceededSave, libraryTitle));
                    SavePlaylistRequest.clear();
                    return;
                }
                showToast(fetchFailedSave);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "saveToPlaylist failure", ex);
        }
    }

    private static void openQueue(Context context) {
        openQueue(context, "", false, false);
    }

    private static void openQueue(Context context, String currentVideoId, boolean openVideo, boolean reload) {
        Utils.runOnMainThreadNowOrLater(() -> {
            String currentPlaylistId = playlistId;
            if (currentPlaylistId.isEmpty()) {
                handleCheckError(checkFailedQueue);
                return;
            }
            try {
                String url;
                if (openVideo) {
                    if (TextUtils.isEmpty(currentVideoId)) {
                        handleCheckError(checkFailedVideoId);
                        return;
                    }
                    if (reload) {
                        final long videoTime = VideoInformation.getVideoTime();

                        url = "https://www.youtube.com/watch?v=" +
                                VideoInformation.getVideoId() +
                                "&list=" +
                                currentPlaylistId +
                                (videoTime > 0 ? "&t=" + (videoTime / 1000) : "");
                    } else {
                        url = "https://www.youtube.com/watch?v=" +
                                currentVideoId +
                                "&list=" +
                                currentPlaylistId;
                    }
                } else {
                    url = "https://www.youtube.com/playlist?list=" +
                            currentPlaylistId;
                }

                LoadVideoPatch.openVideoIntent(url, reload);
            } catch (Exception ex) {
                Logger.printException(() -> "openQueue failure", ex);
            }
        });
    }

    private static void handleCheckError(String reason) {
        showToast(String.format(checkFailedGeneric, reason));
    }

    private static void showToast(String reason) {
        Utils.showToastShort(reason);
    }

    public enum QueueManager {
        ADD_TO_QUEUE(
                "morphe_queue_manager_add_to_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                context -> {
                    fetchQueue(context, false, false, false, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_add_to_queue_and_open_queue",
                "yt_outline_list_add_black_24",
                "yt_outline_experimental_playlist_add_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, false, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_PLAY_VIDEO(
                "morphe_queue_manager_add_to_queue_and_play_video",
                "yt_outline_list_play_arrow_black_24",
                "yt_outline_experimental_playlist_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, true, false, true);
                    return null;
                }
        ),
        ADD_TO_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_add_to_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    fetchQueue(context, false, true, true, true, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE(
                "morphe_queue_manager_remove_from_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, false, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_OPEN_QUEUE(
                "morphe_queue_manager_remove_from_queue_and_open_queue",
                "yt_outline_trash_can_black_24",
                "yt_outline_experimental_circle_slash_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, false, false, true);
                    return null;
                }
        ),
        REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO(
                "morphe_queue_manager_remove_from_queue_and_reload_video",
                "yt_outline_arrow_circle_black_24",
                "yt_outline_experimental_replay_vd_theme_24",
                context -> {
                    fetchQueue(context, true, true, true, true, true);
                    return null;
                }
        ),
        OPEN_QUEUE(
                "morphe_queue_manager_open_queue",
                "yt_outline_list_view_black_24",
                "yt_outline_experimental_queue_vd_theme_24",
                context -> {
                    PlaylistPatch.openQueue(context);
                    return null;
                }
        ),
        SAVE_QUEUE(
                "morphe_queue_manager_save_queue",
                "yt_outline_bookmark_black_24",
                "yt_outline_experimental_bookmark_vd_theme_24",
                context -> {
                    PlaylistPatch.saveToPlaylist(context);
                    return null;
                }
        );

        public final int drawableId;
        public final String label;

        public final Function<Context, Void> onClickAction;

        QueueManager(String label, String icon, String boldIcon, Function<Context, Void> onClickAction) {
            this.drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE,
                    YouTubeActivityHook.USE_BOLD_ICONS ? boldIcon : icon);
            this.label = str(label);
            this.onClickAction = onClickAction;
        }

        public static final QueueManager[] addToQueueEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] addToQueueWithReloadEntries = {
                ADD_TO_QUEUE,
                ADD_TO_QUEUE_AND_OPEN_QUEUE,
                ADD_TO_QUEUE_AND_PLAY_VIDEO,
                ADD_TO_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] removeFromQueueWithReloadEntries = {
                REMOVE_FROM_QUEUE,
                REMOVE_FROM_QUEUE_AND_OPEN_QUEUE,
                REMOVE_FROM_QUEUE_AND_RELOAD_VIDEO,
                OPEN_QUEUE,
                SAVE_QUEUE,
        };

        public static final QueueManager[] noVideoIdQueueEntries = {
                OPEN_QUEUE,
                SAVE_QUEUE,
        };
    }
}
