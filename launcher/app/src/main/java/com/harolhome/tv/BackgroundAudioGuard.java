package com.harolhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Stops an app that keeps playing sound after you press Home.
 *
 * Streaming apps on some TVs keep their audio running once the launcher is back on screen. The
 * old approach asked for audio focus and sent a "pause" media key on every window-focus change,
 * which paused the wrong thing, fired far too often and did nothing for apps that ignore focus.
 *
 * Now, when Home comes back to the front:
 *  1. With notification-listener access, every active playback session gets an explicit pause().
 *  2. Without that access, a media-pause key is sent when Android reports active audio and no
 *     session could be paused.
 *
 * It deliberately does NOT request audio focus, which previously ducked audio and interfered
 * with playback state.
 */
final class BackgroundAudioGuard {

    private final Context ctx;
    private final Set<String> keepPlayingApps;
    private volatile Runnable onMediaRecordedListener;

    BackgroundAudioGuard(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.keepPlayingApps = loadKeepPlayingApps(this.ctx);
    }

    private static Set<String> loadKeepPlayingApps(Context c) {
        Set<String> set = new HashSet<>();
        try {
            String[] arr = c.getResources().getStringArray(R.array.keep_playing_apps);
            set.addAll(Arrays.asList(arr));
        } catch (Exception ignored) {
        }
        return set;
    }

    void setOnMediaRecordedListener(Runnable listener) {
        this.onMediaRecordedListener = listener;
    }

    static boolean shouldPausePackage(String pkg, String selfPkg, Set<String> keepPlaying) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        if (pkg.equals(selfPkg) || "com.android.bluetooth".equals(pkg)) return false;
        return keepPlaying == null || !keepPlaying.contains(pkg);
    }

    static boolean shouldRecordPlayback(String pkg, String selfPkg, Set<String> keepPlaying) {
        return shouldPausePackage(pkg, selfPkg, keepPlaying);
    }

    /** True when this app is an enabled notification listener (needed to see other apps' sessions). */
    boolean hasSessionAccess() {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(ctx.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    /** Pauses whatever is playing in the background. Safe to call repeatedly; never throws. */
    void pauseBackgroundPlayback() {
        try {
            if (hasSessionAccess() && pauseSessions()) return;
            pauseWithMediaKey();
        } catch (Exception ignored) {
        }
    }

    /** @return true when session inspection succeeded so exempted music apps are never paused by key fallback. */
    private boolean pauseSessions() {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return false;
            List<MediaController> sessions = msm.getActiveSessions(
                    new ComponentName(ctx, MediaSessionListener.class));
            String selfPkg = ctx.getPackageName();
            for (MediaController mc : sessions) {
                String pkg = mc.getPackageName();
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                int state = st.getState();
                boolean active = state == PlaybackState.STATE_PLAYING
                        || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_PAUSED;
                if (!active) continue;
                if (shouldRecordPlayback(pkg, selfPkg, keepPlayingApps)) {
                    TopShelfStore.recordActivePlaybackAsync(ctx, mc, onMediaRecordedListener);
                }
                if (shouldPausePackage(pkg, selfPkg, keepPlayingApps)
                        && (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)) {
                    mc.getTransportControls().pause();
                }
            }
            return true;
        } catch (SecurityException e) {
            return false;   // access revoked or the service is not bound yet
        }
    }

    private void pauseWithMediaKey() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || !am.isMusicActive()) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }
}
