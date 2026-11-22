package com.harolhome.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * JVM tests for BackgroundAudioGuard exemption rules (keep_playing_apps) and MainActivity's
 * bitmap subsampling computation.
 */
public class BackgroundAudioGuardTest {

    @Test
    public void testKeepPlayingAndSystemPackagesExempted() {
        String self = "com.harolhome.tv";
        Set<String> keepPlaying = new HashSet<>(Arrays.asList(
                "com.spotify.tv.android",
                "com.spotify.music"
        ));

        // Music apps in keep_playing_apps must NOT be paused or recorded as Top Shelf video
        assertFalse(BackgroundAudioGuard.shouldPausePackage("com.spotify.tv.android", self, keepPlaying));
        assertFalse(BackgroundAudioGuard.shouldRecordPlayback("com.spotify.tv.android", self, keepPlaying));
        assertFalse(BackgroundAudioGuard.shouldPausePackage("com.spotify.music", self, keepPlaying));

        // Self & bluetooth AVRCP idle stacks are ignored
        assertFalse(BackgroundAudioGuard.shouldPausePackage(self, self, keepPlaying));
        assertFalse(BackgroundAudioGuard.shouldPausePackage("com.android.bluetooth", self, keepPlaying));
        assertFalse(BackgroundAudioGuard.shouldPausePackage(null, self, keepPlaying));
        assertFalse(BackgroundAudioGuard.shouldPausePackage("   ", self, keepPlaying));

        // Streaming apps must be paused and captured
        assertTrue(BackgroundAudioGuard.shouldPausePackage("com.netflix.ninja", self, keepPlaying));
        assertTrue(BackgroundAudioGuard.shouldRecordPlayback("com.netflix.ninja", self, keepPlaying));
        assertTrue(BackgroundAudioGuard.shouldPausePackage("com.google.android.youtube.tv", self, keepPlaying));
        assertTrue(BackgroundAudioGuard.shouldRecordPlayback("com.google.android.youtube.tv", self, keepPlaying));
    }

    @Test
    public void testComputeInSampleSizePreservesThumbnailsAndDownscales4K() {
        // Target card minimum decode size is 480x270
        assertEquals(1, MainActivity.computeInSampleSize(544, 306, 480, 270));
        assertEquals(1, MainActivity.computeInSampleSize(362, 203, 480, 270));
        assertEquals(2, MainActivity.computeInSampleSize(1920, 1080, 480, 270));
        assertEquals(4, MainActivity.computeInSampleSize(3840, 2160, 480, 270));
        // Defensive invalid sizes
        assertEquals(1, MainActivity.computeInSampleSize(0, 0, 480, 270));
    }
}
