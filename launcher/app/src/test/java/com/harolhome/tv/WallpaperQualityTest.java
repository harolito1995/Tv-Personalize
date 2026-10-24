package com.harolhome.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 4K metadata preference: order changes, entries are never dropped, provider order is stable. */
public class WallpaperQualityTest {

    @Test
    public void fourKThresholdRequiresBothDimensions() {
        assertTrue(Catalog.is4k(3840, 2160));
        assertTrue(Catalog.is4k(5120, 2880));
        assertFalse(Catalog.is4k(3839, 2160));
        assertFalse(Catalog.is4k(3840, 2159));
    }

    @Test
    public void fourKEntriesMoveFirstWithoutDroppingOthers() {
        List<Catalog.Wallpaper> in = new ArrayList<>();
        in.add(w("sd", 1920, 1080));
        in.add(w("uhd1", 3840, 2160));
        in.add(w("qhd", 2560, 1440));
        in.add(w("uhd2", 4096, 2160));
        in.add(w("portrait", 2160, 3840));

        List<Catalog.Wallpaper> out = RemoteRepository.prefer4k(in);

        assertEquals(5, out.size());
        assertEquals(List.of("uhd1", "uhd2", "sd", "qhd", "portrait"), ids(out));
    }

    @Test
    public void prefer4kKeepsASingleEntryUnchanged() {
        List<Catalog.Wallpaper> in = List.of(w("one", 1920, 1080));
        assertEquals(in, RemoteRepository.prefer4k(in));
    }

    private static Catalog.Wallpaper w(String id, int width, int height) {
        return new Catalog.Wallpaper(id, id, "test", "https://example.com/" + id + ".jpg",
                "https://example.com/" + id + "-thumb.jpg", width, height, "", "", "", "");
    }

    private static List<String> ids(List<Catalog.Wallpaper> in) {
        List<String> out = new ArrayList<>();
        for (Catalog.Wallpaper w : in) out.add(w.id);
        return out;
    }
}
