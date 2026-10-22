package com.gathin.tvhome;

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
}
