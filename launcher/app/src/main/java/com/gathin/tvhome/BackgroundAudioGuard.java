package com.gathin.tvhome;

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
 *  1. With notification-listener access (granted over adb, see {@link MediaSessionListener}) every
 *     session that is really PLAYING gets its own pause(), except the apps listed in
 *     res/values/config.xml under keep_playing_apps (Spotify by default, so music can carry on).
 *  2. Without that access, if the TV reports music/video playing, a single "pause" media key is
 *     sent as a best effort. It cannot tell Spotify apart, so grant the access for the exact behaviour.
 *
 * It deliberately does NOT request audio focus: taking focus is what made players duck or stop
 * for no reason, and it would also silence the music you chose to keep.
 */
final class BackgroundAudioGuard {

    private final Context ctx;
    private final Set<String> keepPlaying;

    BackgroundAudioGuard(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.keepPlaying = new HashSet<>(Arrays.asList(
                ctx.getResources().getStringArray(R.array.keep_playing_apps)));
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

    /** @return false when the session list could not be read (so the caller can fall back). */
    private boolean pauseSessions() {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return false;
            List<MediaController> sessions = msm.getActiveSessions(
                    new ComponentName(ctx, MediaSessionListener.class));
            for (MediaController mc : sessions) {
                String pkg = mc.getPackageName();
                if (pkg == null || pkg.equals(ctx.getPackageName()) || keepPlaying.contains(pkg)) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                int s = st.getState();
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
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
