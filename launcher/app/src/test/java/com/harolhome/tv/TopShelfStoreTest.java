package com.harolhome.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TopShelfStoreTest {

    @Test
    public void testTopShelfItemProperties() {
        TopShelfStore.Item item = new TopShelfStore.Item(
                "slot_0", "Netflix", "Episodio 3", "file:///path/to/img.jpg",
                "com.netflix.ninja", 0, true
        );
        assertEquals("slot_0", item.id);
        assertEquals("Netflix", item.title);
        assertEquals("Episodio 3", item.subtitle);
        assertEquals("file:///path/to/img.jpg", item.backdropUri);
        assertEquals("com.netflix.ninja", item.targetPackage);
        assertEquals(0, item.order);
        assertTrue(item.isCustom);
        assertTrue(item.hasTarget());
        assertTrue(item.hasBackdrop());
        assertEquals(-1, item.progressPercent);
    }

    @Test
    public void testCinematicMediaPropertiesWithProgressAndIntent() {
        TopShelfStore.Item media = new TopShelfStore.Item(
                "wn_101", "The Last of Us", "T1 E3 · 48 min",
                "https://example.com/poster.jpg", "com.hbo.broadband",
                0, false, 45, "https://play.hbomax.com/episode/123"
        );
        assertEquals("wn_101", media.id);
        assertEquals("The Last of Us", media.title);
        assertEquals("T1 E3 · 48 min", media.subtitle);
        assertTrue(media.hasBackdrop());
        assertTrue(media.hasTarget());
        assertEquals(45, media.progressPercent);
        assertEquals("https://play.hbomax.com/episode/123", media.intentUri);
    }

    @Test
    public void testEmptyItemWithoutTarget() {
        TopShelfStore.Item empty = new TopShelfStore.Item(
                "slot_1", "Continuar viendo", "Sin reproducciones recientes", "", "", 1, false
        );
        assertFalse(empty.hasTarget());
        assertFalse(empty.hasBackdrop());
        assertFalse(empty.isCustom);
        assertEquals(1, empty.order);
        assertEquals(-1, empty.progressPercent);
    }

    @Test
    public void testArtworkPrefersLandscapeThumbnail() {
        assertEquals("content://thumb", TopShelfStore.pickArtwork("content://thumb", "content://poster"));
        assertEquals("content://poster", TopShelfStore.pickArtwork("  ", "content://poster"));
        assertEquals("", TopShelfStore.pickArtwork(null, null));
    }

    @Test
    public void testProgressIsRealAndBounded() {
        assertEquals(50, TopShelfStore.progressPercent(30_000, 60_000));
        assertEquals(100, TopShelfStore.progressPercent(90_000, 60_000));
        assertEquals(-1, TopShelfStore.progressPercent(0, 60_000));
        assertEquals(-1, TopShelfStore.progressPercent(30_000, 0));
        assertEquals(-1, TopShelfStore.progressPercent(-5, 60_000));
    }

    @Test
    public void testTwoSlotsIndependence() {
        TopShelfStore.Item slot0 = new TopShelfStore.Item(
                "slot_0", "YouTube", "Ver más tarde", "", "com.google.android.youtube.tv", 0, true
        );
        TopShelfStore.Item slot1 = new TopShelfStore.Item(
                "slot_1", "Prime Video", "Continuar viendo", "", "com.amazon.amazonvideo.livingroom", 1, true
        );

        assertEquals("slot_0", slot0.id);
        assertEquals("slot_1", slot1.id);
        assertEquals("com.google.android.youtube.tv", slot0.targetPackage);
        assertEquals("com.amazon.amazonvideo.livingroom", slot1.targetPackage);
        assertFalse(slot0.targetPackage.equals(slot1.targetPackage));
    }

    @Test
    public void testLruDeduplicationLogic() {
        List<String> list = new ArrayList<>();
        String[] pkgs = {"com.netflix.ninja", "com.google.android.youtube.tv", "com.netflix.ninja"};
        for (String p : pkgs) {
            list.remove(p);
            list.add(0, p);
        }
        assertEquals(2, list.size());
        assertEquals("com.netflix.ninja", list.get(0));
        assertEquals("com.google.android.youtube.tv", list.get(1));
    }

    @Test
    public void testLruCappedAtMaxRecents() {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String p = "com.app." + i;
            list.remove(p);
            list.add(0, p);
            while (list.size() > 6) {
                list.remove(list.size() - 1);
            }
        }
        assertEquals(6, list.size());
        assertEquals("com.app.10", list.get(0));
        assertEquals("com.app.5", list.get(5));
    }

    @Test
    public void testWatchNextCacheReuseHonorsForceAndTtl() {
        long now = 1_000_000L;
        assertTrue(TopShelfStore.shouldReuseCache(false, now - 5_000L, now, 10_000L));
        assertFalse(TopShelfStore.shouldReuseCache(true, now - 5_000L, now, 10_000L));
        assertFalse(TopShelfStore.shouldReuseCache(false, now - 10_000L, now, 10_000L));
        assertFalse(TopShelfStore.shouldReuseCache(false, 0L, now, 10_000L));
    }

    @Test
    public void testWatchNextNormalizationDropsDuplicateAndInvalidRows() {
        TopShelfStore.Item first = new TopShelfStore.Item(
                "wn_1", "First", "T1 E1", "content://thumb/1", "com.example.one", 0, false, 20, ""
        );
        TopShelfStore.Item duplicate = new TopShelfStore.Item(
                "wn_1", "Duplicate", "", "content://thumb/dup", "com.example.one", 1, false, 30, ""
        );
        TopShelfStore.Item invalid = new TopShelfStore.Item(
                "wn_2", "   ", "", "", "", 0, false
        );
        TopShelfStore.Item second = new TopShelfStore.Item(
                "wn_3", "Second", "T2 E4", "content://thumb/3", "com.example.three", 1, false, 60, ""
        );

        TopShelfStore.Item[] out = TopShelfStore.normalizeWatchNext(
                new TopShelfStore.Item[]{first, duplicate, null, invalid, second}
        );

        assertEquals(2, out.length);
        assertEquals("wn_1", out[0].id);
        assertEquals("wn_3", out[1].id);
        assertEquals(2, TopShelfStore.populatedCount(out));
    }

    @Test
    public void testWatchNextNormalizationPadsMissingSlots() {
        TopShelfStore.Item[] out = TopShelfStore.normalizeWatchNext(null);
        assertEquals(2, out.length);
        assertEquals(0, TopShelfStore.populatedCount(out));
        assertFalse(out[0].hasTarget());
        assertFalse(out[1].hasTarget());
    }

    @Test
    public void testSanitizeSubtitleRemovesRemainingMinutesAndKeepsEpisodeCode() {
        assertEquals("T1 E3", TopShelfStore.sanitizeSubtitle("T1 E3 \u00B7 48 min"));
        assertEquals("T2 E5", TopShelfStore.sanitizeSubtitle("T2 E5 \u00B7 Quedan 22 min"));
        assertEquals("E4", TopShelfStore.sanitizeSubtitle("E4 - 15m left"));
        assertEquals("", TopShelfStore.sanitizeSubtitle("35 min restantes"));
        assertEquals("", TopShelfStore.sanitizeSubtitle("Quedan 12 min"));
        assertEquals("T1 E2", TopShelfStore.buildCleanSubtitle("1", "2", "Episodio 2 \u00B7 44 min", "Dark"));
    }

    @Test
    public void testReconcilePartialDropPreservesMissingSlotWhenTwoDropToOne() {
        TopShelfStore.Item cached0 = new TopShelfStore.Item(
                "wn_10", "Stranger Things", "T4 E1", "content://art/10",
                "com.netflix.ninja", 0, false, 65, "intent://netflix/10"
        );
        TopShelfStore.Item cached1 = new TopShelfStore.Item(
                "wn_20", "The Boys", "T3 E6", "content://art/20",
                "com.amazon.avod", 1, false, 40, "intent://prime/20"
        );
        // Suppose Netflix temporarily removes wn_10 while updating its checkpoint, leaving only wn_20
        TopShelfStore.Item[] freshWithOne = TopShelfStore.normalizeWatchNext(
                new TopShelfStore.Item[]{cached1}
        );
        assertEquals(1, TopShelfStore.populatedCount(freshWithOne));

        TopShelfStore.Item[] reconciled = TopShelfStore.reconcilePartialDrop(
                freshWithOne, new TopShelfStore.Item[]{cached0, cached1}
        );
        assertEquals(2, TopShelfStore.populatedCount(reconciled));
        assertEquals("wn_20", reconciled[0].id);
        assertEquals("wn_10", reconciled[1].id);
    }

    @Test
    public void testReconcilePartialDropUpdatesReturnedRowWithoutDuplicatingSamePackage() {
        TopShelfStore.Item cached0 = new TopShelfStore.Item(
                "wn_10", "Stranger Things", "T4 E1", "content://art/10",
                "com.netflix.ninja", 0, false, 65, "intent://netflix/10"
        );
        TopShelfStore.Item cached1 = new TopShelfStore.Item(
                "wn_20", "The Boys", "T3 E6", "content://art/20",
                "com.amazon.avod", 1, false, 40, "intent://prime/20"
        );
        // Netflix publishes updated episode wn_11; both rows are present
        TopShelfStore.Item updatedNetflix = new TopShelfStore.Item(
                "wn_11", "Stranger Things", "T4 E2", "content://art/11",
                "com.netflix.ninja", 0, false, 12, "intent://netflix/11"
        );
        TopShelfStore.Item[] freshBoth = TopShelfStore.normalizeWatchNext(
                new TopShelfStore.Item[]{updatedNetflix, cached1}
        );
        assertEquals(2, TopShelfStore.populatedCount(freshBoth));
        assertEquals("wn_11", freshBoth[0].id);
        assertEquals("T4 E2", freshBoth[0].subtitle);
        assertEquals("wn_20", freshBoth[1].id);
    }

    @Test
    public void testFillWithRecentFallbackPreservesPopulatedItems() {
        TopShelfStore.Item movie = new TopShelfStore.Item(
                "wn_50", "Avatar", "T1 E1", "content://thumb/avatar",
                "com.disney.disneyplus", 0, false, 80, ""
        );
        TopShelfStore.Item[] slots = new TopShelfStore.Item[]{movie, null};
        TopShelfStore.Item[] result = TopShelfStore.fillWithRecentFallback(null, slots);
        assertEquals(2, result.length);
        assertEquals("wn_50", result[0].id);
        assertEquals("com.disney.disneyplus", result[0].targetPackage);
    }

    @Test
    public void testVerifiedDefaultCinematicSlots() {
        TopShelfStore.Item[] defaults = TopShelfStore.getVerifiedDefaultSlots();
        assertEquals(2, defaults.length);
        assertEquals(TopShelfStore.DEFAULT_SLOT_0_ID, defaults[0].id);
        assertEquals("Historia de un crimen: Colmenares", defaults[0].title);
        assertEquals("com.netflix.ninja", defaults[0].targetPackage);
        assertEquals(TopShelfStore.DEFAULT_SLOT_1_ID, defaults[1].id);
        assertEquals("Moana 2", defaults[1].title);
        assertEquals("com.disney.disneyplus", defaults[1].targetPackage);
    }

    @Test
    public void testResolveAppLabelFallback() {
        assertEquals("com.netflix.ninja", TopShelfStore.resolveAppLabel(null, "com.netflix.ninja"));
        assertEquals("", TopShelfStore.resolveAppLabel(null, ""));
    }
}