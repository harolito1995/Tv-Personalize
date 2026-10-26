package com.harolhome.tv;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only lets an app list the other apps' media sessions
 * (MediaSessionManager.getActiveSessions) if it owns an enabled notification listener, so this
 * service exists only to give {@link BackgroundAudioGuard} that access. It never reads, stores
 * or posts any notification.
 *
 * Enable it once over adb (build-and-install does this for you):
 *   adb shell cmd notification allow_listener com.harolhome.tv/com.harolhome.tv.MediaSessionListener
 */
public class MediaSessionListener extends NotificationListenerService {
}
