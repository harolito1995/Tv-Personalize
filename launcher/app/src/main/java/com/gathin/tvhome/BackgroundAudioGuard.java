package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.List;


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

    BackgroundAudioGuard(Context ctx) {
        this.ctx = ctx.getApplicationContext();
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

    /** @return true when one or more active sessions were paused. */
    private boolean pauseSessions() {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return false;
            List<MediaController> sessions = msm.getActiveSessions(
                    new ComponentName(ctx, MediaSessionListener.class));
            boolean paused = false;
            for (MediaController mc : sessions) {
                String pkg = mc.getPackageName();
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                int state = st.getState();
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    mc.getTransportControls().pause();
                    paused = true;
                }
            }
            return paused;
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
