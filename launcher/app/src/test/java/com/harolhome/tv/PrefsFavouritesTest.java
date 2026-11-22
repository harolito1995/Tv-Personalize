package com.harolhome.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JVM tests for Prefs favourite normalization, ghost-package pruning, dock capacity clamping,
 * and slot reordering.
 */
public class PrefsFavouritesTest {

    @Test
    public void testNormalizePackageStripsActivityAndWhitespace() {
        assertEquals("", Prefs.normalizePackage(null));
        assertEquals("", Prefs.normalizePackage("   "));
        assertEquals("com.netflix.ninja", Prefs.normalizePackage(" com.netflix.ninja "));
        assertEquals("com.netflix.ninja", Prefs.normalizePackage("com.netflix.ninja/.MainActivity"));
        assertEquals("com.nuvio.tv", Prefs.normalizePackage("  com.nuvio.tv/com.nuvio.tv.MainActivity  "));
    }

    @Test
    public void testCaixunBugRepro_RemoveAfterGhostPruneAllowsAddingNewApp() {
        // Real scenario from Caixun tvhome.xml: 10 stored packages (8 installed + 2 not installed)
        List<String> stored = Arrays.asList(
                "com.netflix.ninja/.MainActivity",
                "com.google.android.youtube.tv",
                "com.disney.disneyplus",
                "com.wbd.stream",
                "com.amazon.amazonvideo.livingroom",
                "com.crunchyroll.crunchyroid",       // ghost (not installed)
                "com.spotify.tv.android",            // ghost (not installed)
                "com.nuvio.tv",
                "com.cbs.ca",
                "com.xuper.netxxus",
                "com.netflix.ninja"                  // duplicate
        );
        Set<String> installed = new HashSet<>(Arrays.asList(
                "com.netflix.ninja",
                "com.google.android.youtube.tv",
                "com.disney.disneyplus",
                "com.wbd.stream",
                "com.amazon.amazonvideo.livingroom",
                "com.nuvio.tv",
                "com.cbs.ca",
                "com.xuper.netxxus",
                "com.stremio.one"
        ));

        // Reconcile drops ghosts, duplicates, and clamps to APP_SLOTS = 8
        List<String> list = Prefs.reconcileFavourites(stored, installed, 8);
        assertEquals(8, list.size());
        assertFalse(list.contains("com.crunchyroll.crunchyroid"));
        assertFalse(list.contains("com.spotify.tv.android"));

        // Dock is full at 8: adding Stremio returns false
        assertFalse(Prefs.toggleFavouriteInList(list, "com.stremio.one", 8));
        assertEquals(8, list.size());

        // Remove one favourite -> leaves 7 slots used
        assertTrue(Prefs.toggleFavouriteInList(list, "com.cbs.ca", 8));
        assertEquals(7, list.size());
        assertFalse(list.contains("com.cbs.ca"));

        // Adding a replacement app now succeeds and brings dock back to 8!
        assertTrue(Prefs.toggleFavouriteInList(list, "com.stremio.one", 8));
        assertEquals(8, list.size());
        assertTrue(list.contains("com.stremio.one"));

        // Attempting to add a 9th app when full returns false cleanly
        assertFalse(Prefs.toggleFavouriteInList(list, "com.plexapp.android", 8));
        assertEquals(8, list.size());
    }

    @Test
    public void testMoveFavouriteRespectsEdgesAndOrder() {
        List<String> list = new ArrayList<>(Arrays.asList("com.a", "com.b", "com.c"));

        assertFalse(Prefs.moveFavouriteInList(list, "com.a", -1));
        assertFalse(Prefs.moveFavouriteInList(list, "com.c", 1));
        assertFalse(Prefs.moveFavouriteInList(list, "com.missing", 1));
        assertFalse(Prefs.moveFavouriteInList(list, "com.b", 0));

        assertTrue(Prefs.moveFavouriteInList(list, "com.b", -1));
        assertEquals(Arrays.asList("com.b", "com.a", "com.c"), list);

        assertTrue(Prefs.moveFavouriteInList(list, "com.a", 1));
        assertEquals(Arrays.asList("com.b", "com.c", "com.a"), list);
    }
}
