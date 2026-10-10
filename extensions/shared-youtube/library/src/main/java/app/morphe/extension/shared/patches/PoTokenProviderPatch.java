/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.ui.ReminderDialog;

@SuppressWarnings("unused")
public class PoTokenProviderPatch {
    public static final class PoTokenProviderAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return isPoTokenProviderAvailable();
        }
    }

    private static final String PO_TOKEN_SERVICE_SUFFIX = ".potokens.service.START";
    private static final String PO_TOKEN_HELPER_PACKAGE_NAME = "app.morphe.pot.helper";
    private static final String PO_TOKEN_HELPER_SERVICE_ACTION = PO_TOKEN_HELPER_PACKAGE_NAME + PO_TOKEN_SERVICE_SUFFIX;
    private static final Uri PO_TOKEN_HELPER_CONTENT_AUTHORITIES;
    /**
     * The newest helper release whose built in token no longer works (1.1.1).
     */
    private static final long LAST_EXPIRED_HELPER_VERSION_CODE = 100100199;

    private static boolean expiredHelperCheckRegistered;

    static {
        Uri.Builder builder = new Uri.Builder();
        builder.scheme("content");
        builder.authority(PO_TOKEN_HELPER_PACKAGE_NAME + ".chimera");
        PO_TOKEN_HELPER_CONTENT_AUTHORITIES = builder.build();
    }

    /**
     * Injection point.
     */
    public static Uri overrideAuthorities(String serviceAction, Uri uri) {
        return useExternalPoTokenProvider(serviceAction)
                ? PO_TOKEN_HELPER_CONTENT_AUTHORITIES
                : uri;
    }

    /**
     * Injection point.
     */
    public static String overrideServiceAction(String serviceAction) {
        return useExternalPoTokenProvider(serviceAction)
                ? PO_TOKEN_HELPER_SERVICE_ACTION
                : serviceAction;
    }

    private static boolean useExternalPoTokenProvider(String serviceAction) {
        return serviceAction != null
                && serviceAction.endsWith(PO_TOKEN_SERVICE_SUFFIX)
                && SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER.get()
                && isPoTokenProviderAvailable();
    }

    private static boolean isPoTokenProviderAvailable() {
        // To minimize confusion, it works only when 'Spoof video streams' is turned off.
        if (!SpoofVideoStreamsPatch.isPatchIncluded() || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get()) {
            try {
                if (Utils.getContext().getPackageManager().getApplicationInfo(PO_TOKEN_HELPER_PACKAGE_NAME, 0).enabled) {
                    Logger.printDebug(() -> "App installed: " + PO_TOKEN_HELPER_PACKAGE_NAME);
                    return true;
                }
            } catch (PackageManager.NameNotFoundException error) {
                Logger.printDebug(() -> "App not installed: " + PO_TOKEN_HELPER_PACKAGE_NAME);
            }
        }
        return false;
    }

    /**
     * Injection point.
     * Offers to turn on 'Spoof video streams' while the token built into the installed helper no longer works,
     * since playback then stops with an error.
     */
    public static void checkExpiredHelper(Activity activity) {
        // The main activity is created again on some configuration changes.
        if (expiredHelperCheckRegistered) {
            return;
        }
        expiredHelperCheckRegistered = true;

        // Checked whenever the app returns to the foreground, because it can stay
        // in the background for days without being launched again.
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity resumed) {
                showExpiredHelperDialogIfNeeded(resumed);
            }

            public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            public void onActivityStarted(@NonNull Activity a) {}
            public void onActivityPaused(@NonNull Activity a) {}
            public void onActivityStopped(@NonNull Activity a) {}
            public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            public void onActivityDestroyed(@NonNull Activity a) {}
        });
    }

    @SuppressWarnings("deprecation")
    private static void showExpiredHelperDialogIfNeeded(Activity activity) {
        try {
            LongSetting lastShown = SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER_EXPIRED_DIALOG_LAST_SHOWN;
            if (!ReminderDialog.isDue(lastShown)
                    || !SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER.get()
                    || !SpoofVideoStreamsPatch.isPatchIncluded()
                    || !isPoTokenProviderAvailable()) {
                return;
            }

            PackageInfo helper = activity.getPackageManager().getPackageInfo(PO_TOKEN_HELPER_PACKAGE_NAME, 0);
            final long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? helper.getLongVersionCode()
                    : helper.versionCode;
            if (versionCode > LAST_EXPIRED_HELPER_VERSION_CODE) {
                return;
            }
            Logger.printDebug(() -> "Helper token no longer works, version code: " + versionCode);

            ReminderDialog.show(
                    activity,
                    lastShown,
                    str("morphe_external_potoken_provider_expired_dialog_title"),
                    str("morphe_external_potoken_provider_expired_dialog_message"),
                    str("morphe_external_potoken_provider_expired_dialog_turn_on"),
                    () -> {
                        SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.save(true);
                        Utils.restartApp(activity);
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "showExpiredHelperDialogIfNeeded failure", ex);
        }
    }
}
