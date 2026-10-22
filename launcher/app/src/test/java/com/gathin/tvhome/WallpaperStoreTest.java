package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** JVM tests for the local recovery lookup and the cache retention rules. */
public class WallpaperStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void findIgnoresPartsAndPicksNewestVersion() throws Exception {
        File dir = tmp.newFolder("wallpapers");
        File old = file(dir, "id.jpg", 1_000);
        File newest = file(dir, "id.png", 3_000);
        File part = file(dir, "id.webp.part", 4_000);

        assertEquals(newest, WallpaperStore.find(dir, "id"));
        assertTrue(old.exists());
        assertTrue(part.exists());
    }

    @Test
    public void findReturnsNullWithoutACompleteFile() throws Exception {
        File dir = tmp.newFolder("wallpapers");
        file(dir, "id.jpg.part", 1_000);
        assertEquals(null, WallpaperStore.find(dir, "id"));
    }

    @Test
    public void cleanupProtectsCurrentAndRecoveryThenKeepsNewest() throws Exception {
        File dir = tmp.newFolder("wallpapers");
        File thumbs = tmp.newFolder("thumbs");
        long now = 10_000_000L;
        File current = file(dir, "current.jpg", now - 500);
        File recovery = file(dir, "recovery.jpg", now - 600);
        File[] others = new File[30];
        for (int i = 1; i <= others.length; i++) {
            others[i - 1] = file(dir, "c" + i + ".jpg", now - i * 1_000L);
        }

        WallpaperStore.cleanupFiles(dir, thumbs, "current", "recovery", now);

        assertTrue(current.exists());
        assertTrue(recovery.exists());
        assertTrue(others[0].exists());
        assertTrue(others[21].exists());
        assertFalse(others[22].exists());
        assertFalse(others[29].exists());
    }

    @Test
    public void cleanupDeletesOnlyStaleParts() throws Exception {
        File dir = tmp.newFolder("wallpapers");
        File thumbs = tmp.newFolder("thumbs");
        long now = 10_000_000L;
        File active = file(dir, "active.jpg.part", now);
        File stale = file(dir, "stale.jpg.part", now - 2L * 60 * 60 * 1000);

        WallpaperStore.cleanupFiles(dir, thumbs, "", "", now);

        assertTrue(active.exists());
        assertFalse(stale.exists());
    }

    @Test
    public void cleanupTrimsThumbnailsButNotFreshParts() throws Exception {
        File dir = tmp.newFolder("wallpapers");
        File thumbs = tmp.newFolder("thumbs");
        long now = 10_000_000L;
        for (int i = 0; i < 402; i++) {
            file(thumbs, "t" + i + ".thumb.jpg", now - i * 1_000L);
        }
        File active = file(thumbs, "active.thumb.jpg.part", now);

        WallpaperStore.cleanupFiles(dir, thumbs, "", "", now);

        File[] left = thumbs.listFiles();
        assertNotNull(left);
        assertTrue(left.length <= 401);
        assertTrue(active.exists());
    }

    private static File file(File dir, String name, long modified) throws IOException {
        File f = new File(dir, name);
        assertTrue("could not create " + name, f.createNewFile());
        assertTrue("could not set mtime " + name, f.setLastModified(modified));
        return f;
    }

    @Test
    public void favoritesSavedListedAndRemovedCorrectly() throws Exception {
        File dir = tmp.newFolder("wallpapers_fav");
        File source = file(dir, "source.jpg", 1_000);
        Catalog.Wallpaper w = new Catalog.Wallpaper("wh-test1", "My Test Wallpaper", "anime",
                "https://example.com/test.jpg", "https://example.com/thumb.jpg",
                1920, 1080, "Artist", "CC", "https://example.com", "");

        assertFalse(WallpaperStore.isFavorite(dir, "wh-test1"));
        WallpaperStore.saveFavorite(dir, w, source);

        assertTrue(WallpaperStore.isFavorite(dir, "wh-test1"));
        assertNotNull(WallpaperStore.getFavoriteFile(dir, "wh-test1"));

        List<Catalog.Wallpaper> list = WallpaperStore.listFavorites(dir);
        assertEquals(1, list.size());
        assertEquals("wh-test1", list.get(0).id);
        assertEquals("My Test Wallpaper", list.get(0).title);

        assertTrue(WallpaperStore.removeFavorite(dir, "wh-test1"));
        assertFalse(WallpaperStore.isFavorite(dir, "wh-test1"));
        assertEquals(0, WallpaperStore.listFavorites(dir).size());
    }

    @Test
    public void cleanupFilesNeverDeletesFavorites() throws Exception {
        File dir = tmp.newFolder("wallpapers_clean_fav");
        File thumbs = tmp.newFolder("thumbs_clean_fav");
        File favDir = new File(dir, "favorites");
        assertTrue(favDir.mkdirs());
        File favImage = file(favDir, "fav1.jpg", 1_000);
        File favMeta = file(favDir, "fav1.json", 1_000);

        long now = 10_000_000L;
        // Fill wallpapers with 30 items
        for (int i = 1; i <= 30; i++) {
            file(dir, "c" + i + ".jpg", now - i * 1_000L);
        }

        WallpaperStore.cleanupFiles(dir, thumbs, "none", "none", now);

        assertTrue(favImage.exists());
        assertTrue(favMeta.exists());
    }
}