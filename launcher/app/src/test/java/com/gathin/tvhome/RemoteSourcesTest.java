package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * Plain-JVM tests for the remote providers. They cover the request used against each public API and
 * the defensive filters applied to its response (SFW, landscape, size, HTTPS, metadata).
 */
public class RemoteSourcesTest {

    private static final String WH_ID = "abc123";
    private static final String WH_IMAGE =
            "https://w.wallhaven.cc/full/ab/wallhaven-" + WH_ID + ".jpg";
    private static final String WH_THUMB =
            "https://th.wallhaven.cc/lg/ab/" + WH_ID + ".jpg";
    private static final String WH_PAGE = "https://wallhaven.cc/w/" + WH_ID;

    @Test
    public void wallhavenUrlTargetsAnimeAndEncodesQuery() {
        String url = WallhavenSource.buildUrl(RemoteRepository.CAT_DRAGONBALL, "goku ssj");
        assertTrue(url.startsWith("https://wallhaven.cc/api/v1/search?"));
        assertTrue(url.contains("categories=010"));
        assertTrue(url.contains("purity=100"));
        assertTrue(url.contains("atleast=1920x1080"));
        assertTrue(url.contains("ratios=16x9"));
        assertTrue(url.contains("sorting=relevance"));
        assertTrue(url.contains("q=dragon%20ball%20goku%20ssj"));
        assertFalse(url.contains("+"));
        assertFalse(url.contains("topRange"));
    }

    @Test
    public void wallhavenUrlUsesToplistForAllWithoutQuery() {
        String url = WallhavenSource.buildUrl(RemoteRepository.CAT_ALL, "");
        assertTrue(url.contains("categories=111"));
        assertTrue(url.contains("sorting=toplist"));
        assertTrue(url.contains("topRange=1M"));
        assertFalse(url.contains("&q="));
    }

    @Test
    public void wallhavenUrlUsesBaseQueryForNature() {
        String url = WallhavenSource.buildUrl(RemoteRepository.CAT_NATURE, "");
        assertTrue(url.contains("categories=111"));
        assertTrue(url.contains("q=nature"));
        assertTrue(url.contains("sorting=relevance"));
        assertFalse(url.contains("topRange"));
    }

    @Test
    public void wallhavenUrlAddsProviderPage() {
        String url = WallhavenSource.buildUrl(RemoteRepository.CAT_ALL, "", 3);
        assertTrue(url.contains("&page=3"));
        assertFalse(WallhavenSource.buildUrl(RemoteRepository.CAT_ALL, "", 1).contains("&page="));
    }

    @Test
    public void commonsUrlUsesOffsetAndLargerThumbs() {
        String url = CommonsSource.buildUrl(RemoteRepository.CAT_NATURE, "lake", 2);
        assertTrue(url.contains("gsroffset=24"));
        assertTrue(url.contains("iiurlwidth=960"));
        assertFalse(CommonsSource.buildUrl(RemoteRepository.CAT_NATURE, "lake", 1)
                .contains("gsroffset"));
    }

    @Test
    public void wallhavenParsesOnlySafeLandscapeEntries() throws Exception {
        JSONObject root = data(
                wh(WH_ID, "sfw", "image/jpeg", 2_000_000, 1920, 1080, WH_IMAGE, WH_THUMB, WH_PAGE),
                wh("sketchy1", "sketchy", "image/jpeg", 2_000_000, 1920, 1080,
                        "https://w.wallhaven.cc/full/sk/sketchy1.jpg",
                        "https://th.wallhaven.cc/lg/sk/sketchy1.jpg",
                        "https://wallhaven.cc/w/sketchy1"),
                wh("portrait1", "sfw", "image/jpeg", 2_000_000, 1080, 1920,
                        "https://w.wallhaven.cc/full/po/portrait1.jpg",
                        "https://th.wallhaven.cc/lg/po/portrait1.jpg",
                        "https://wallhaven.cc/w/portrait1"));
        List<Catalog.Wallpaper> out = WallhavenSource.parse(
                root, RemoteRepository.CAT_DRAGONBALL, "Dragon Ball", "goku");
        assertEquals(1, out.size());
        Catalog.Wallpaper w = out.get(0);
        assertEquals("wh-" + WH_ID, w.id);
        assertEquals("Goku \u00b7 Dragon Ball", w.title);
        assertEquals(WH_IMAGE, w.imageUrl);
        assertEquals(WH_THUMB, w.thumbUrl);
        assertEquals("Wallhaven", w.author);
        assertEquals("", w.license);
        assertEquals(WH_PAGE, w.source);
    }

    @Test
    public void wallhavenSkipsHttpThumbsOversizeAndGif() throws Exception {
        JSONObject root = data(
                wh("a1b2c3", "sfw", "image/jpeg", 2_000_000, 1920, 1080,
                        "https://w.wallhaven.cc/full/a1/a1b2c3.jpg",
                        "http://th.wallhaven.cc/lg/a1/a1b2c3.jpg",
                        "https://wallhaven.cc/w/a1b2c3"),
                wh("d4e5f6", "sfw", "image/jpeg", RemoteRepository.MAX_IMAGE_BYTES + 1, 1920, 1080,
                        "https://w.wallhaven.cc/full/d4/d4e5f6.jpg",
                        "https://th.wallhaven.cc/lg/d4/d4e5f6.jpg",
                        "https://wallhaven.cc/w/d4e5f6"),
                wh("g7h8i9", "sfw", "image/gif", 2_000_000, 1920, 1080,
                        "https://w.wallhaven.cc/full/g7/g7h8i9.gif",
                        "https://th.wallhaven.cc/lg/g7/g7h8i9.jpg",
                        "https://wallhaven.cc/w/g7h8i9"));
        assertEquals(0, WallhavenSource.parse(
                root, RemoteRepository.CAT_ALL, "Todo", "").size());
    }

    @Test
    public void wallhavenRejectsMissingData() {
        try {
            WallhavenSource.parse(new JSONObject(), RemoteRepository.CAT_ALL, "Todo", "");
        } catch (CatalogException e) {
            assertEquals(CatalogException.Code.FORMAT, e.code);
            return;
        }
        throw new AssertionError("expected FORMAT");
    }

    @Test
    public void commonsKeepsLandscapeAndCleansMetadata() throws Exception {
        JSONObject root = new JSONObject("{\"query\":{\"pages\":{"
                + "\"123\":{\"pageid\":123,\"ns\":6,\"title\":\"File:Nature_photo.jpg\","
                + "\"imageinfo\":[{\"width\":1920,\"height\":1080,\"mime\":\"image/jpeg\","
                + "\"size\":3000000,"
                + "\"url\":\"https://upload.wikimedia.org/a.jpg\","
                + "\"thumburl\":\"https://upload.wikimedia.org/thumb/a.jpg\","
                + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:Nature_photo.jpg\","
                + "\"extmetadata\":{"
                + "\"Artist\":{\"value\":\"<a href=\\\"x\\\">Jane Doe</a>\"},"
                + "\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"}}}]},"
                + "\"124\":{\"pageid\":124,\"ns\":6,\"title\":\"File:Tall.jpg\","
                + "\"imageinfo\":[{\"width\":1080,\"height\":1920,\"mime\":\"image/jpeg\","
                + "\"size\":3000000,\"url\":\"https://upload.wikimedia.org/b.jpg\","
                + "\"thumburl\":\"https://upload.wikimedia.org/thumb/b.jpg\","
                + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:Tall.jpg\"}]},"
                + "\"125\":{\"pageid\":125,\"ns\":0,\"title\":\"Article\",\"imageinfo\":[]}"
                + "}}}");
        List<Catalog.Wallpaper> out = CommonsSource.parse(root, RemoteRepository.CAT_NATURE);
        assertEquals(1, out.size());
        Catalog.Wallpaper w = out.get(0);
        assertEquals("wc-123", w.id);
        assertEquals("Nature photo", w.title);
        assertEquals("Jane Doe", w.author);
        assertEquals("CC BY-SA 4.0", w.license);
        assertEquals("https://upload.wikimedia.org/a.jpg", w.imageUrl);
        assertEquals("https://commons.wikimedia.org/wiki/File:Nature_photo.jpg", w.source);
    }

    @Test
    public void commonsSkipsMissingThumbAndUnsafeMime() throws Exception {
        JSONObject root = new JSONObject("{\"query\":{\"pages\":{"
                + "\"1\":{\"pageid\":1,\"ns\":6,\"title\":\"File:A.jpg\","
                + "\"imageinfo\":[{\"width\":1920,\"height\":1080,\"mime\":\"image/jpeg\","
                + "\"size\":3000000,\"url\":\"https://upload.wikimedia.org/a.jpg\","
                + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:A.jpg\"}]},"
                + "\"2\":{\"pageid\":2,\"ns\":6,\"title\":\"File:B.svg\","
                + "\"imageinfo\":[{\"width\":1920,\"height\":1080,\"mime\":\"image/svg+xml\","
                + "\"size\":3000000,\"url\":\"https://upload.wikimedia.org/b.svg\","
                + "\"thumburl\":\"https://upload.wikimedia.org/thumb/b.png\","
                + "\"descriptionurl\":\"https://commons.wikimedia.org/wiki/File:B.svg\"}]}"
                + "}}}");
        assertEquals(0, CommonsSource.parse(root, RemoteRepository.CAT_NATURE).size());
    }

    @Test
    public void commonsMissingPagesIsEmpty() {
        assertEquals(0, CommonsSource.parse(new JSONObject(), RemoteRepository.CAT_NATURE).size());
    }

    private static JSONObject data(String... entries) throws Exception {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(entries[i]);
        }
        return new JSONObject(sb.append("]}").toString());
    }

    private static String wh(String id, String purity, String mime, long size, int w, int h,
                             String image, String thumb, String page) {
        return "{\"id\":\"" + id + "\",\"purity\":\"" + purity + "\",\"file_type\":\"" + mime
                + "\",\"file_size\":" + size + ",\"dimension_x\":" + w + ",\"dimension_y\":" + h
                + ",\"path\":\"" + image + "\",\"url\":\"" + page + "\","
                + "\"thumbs\":{\"large\":\"" + thumb + "\"}}";
    }

    @Test
    public void wallhavenUrlSupportsQuality4kAndSorting() {
        String url4k = WallhavenSource.buildUrl(RemoteRepository.CAT_ANIME, "bleach", 1, "4k", "top");
        assertTrue(url4k.contains("atleast=3840x2160"));
        assertTrue(url4k.contains("sorting=toplist"));
        assertTrue(url4k.contains("topRange=1M"));

        String url2k = WallhavenSource.buildUrl(RemoteRepository.CAT_ANIME, "naruto", 2, "2k", "date");
        assertTrue(url2k.contains("atleast=2560x1440"));
        assertTrue(url2k.contains("sorting=date_added"));
        assertTrue(url2k.contains("&page=2"));
    }

    @Test
    public void wallhavenUrlSupportsNewCategories() {
        String urlSciFi = WallhavenSource.buildUrl(RemoteRepository.CAT_SCIFI, "");
        assertTrue(urlSciFi.contains("categories=111"));
        assertTrue(urlSciFi.contains("q=science%20fiction"));

        String urlArch = WallhavenSource.buildUrl(RemoteRepository.CAT_ARCHITECTURE, "");
        assertTrue(urlArch.contains("categories=111"));
        assertTrue(urlArch.contains("q=architecture"));

        String urlSpace = WallhavenSource.buildUrl(RemoteRepository.CAT_SPACE, "");
        assertTrue(urlSpace.contains("categories=111"));
        assertTrue(urlSpace.contains("q=space"));
    }

    @Test
    public void commonsSupportsAllCategoriesUniversally() {
        CommonsSource commons = new CommonsSource();
        assertTrue(commons.supports(RemoteRepository.CAT_ALL));
        assertTrue(commons.supports(RemoteRepository.CAT_ANIME));
        assertTrue(commons.supports(RemoteRepository.CAT_DRAGONBALL));
        assertTrue(commons.supports(RemoteRepository.CAT_NARUTO));
        assertTrue(commons.supports(RemoteRepository.CAT_ONEPIECE));
        assertTrue(commons.supports(RemoteRepository.CAT_GAMES));
        assertTrue(commons.supports(RemoteRepository.CAT_CYBERPUNK));
        assertTrue(commons.supports(RemoteRepository.CAT_FANTASY));
        assertTrue(commons.supports(RemoteRepository.CAT_NATURE));
        assertTrue(commons.supports(RemoteRepository.CAT_CINEMA));
        assertTrue(commons.supports(RemoteRepository.CAT_SCIFI));
        assertTrue(commons.supports(RemoteRepository.CAT_ARCHITECTURE));
        assertTrue(commons.supports(RemoteRepository.CAT_SPACE));
        assertTrue(commons.supports("any_custom_franchise"));
    }

    @Test
    public void commonsUrlSupportsFreeTextAndDynamicTags() {
        String url = CommonsSource.buildUrl(RemoteRepository.CAT_SCIFI, "cyber city neon", 1);
        assertTrue(url.contains("science%20fiction"));
        assertTrue(url.contains("cyber%20city%20neon"));
        assertTrue(url.contains("filetype%3Abitmap"));

        String urlAnime = CommonsSource.buildUrl(RemoteRepository.CAT_ANIME, "chainsaw man", 1);
        assertTrue(urlAnime.contains("anime"));
        assertTrue(urlAnime.contains("chainsaw%20man"));
    }

    @Test
    public void catalogIs4kDetectionAccurate() {
        assertTrue(Catalog.is4k(3840, 2160));
        assertTrue(Catalog.is4k(4096, 2160));
        assertTrue(Catalog.is4k(5120, 2880));
        assertFalse(Catalog.is4k(2560, 1440));
        assertFalse(Catalog.is4k(1920, 1080));
        assertFalse(Catalog.is4k(1280, 720));
    }

    @Test
    public void resilienceExceptionCodesHandling() {
        CatalogException e521 = new CatalogException(CatalogException.Code.HTTP, "HTTP 521: Origin server down", 521);
        assertEquals(CatalogException.Code.HTTP, e521.code);
        assertEquals(521, e521.httpCode);

        CatalogException eTimeout = new CatalogException(CatalogException.Code.OFFLINE, "timeout");
        assertEquals(CatalogException.Code.OFFLINE, eTimeout.code);

        CatalogException eBadJson = new CatalogException(CatalogException.Code.FORMAT, "malformed response");
        assertEquals(CatalogException.Code.FORMAT, eBadJson.code);
    }

    @Test
    public void prefer4kPrioritizesHighResolutionWithoutLosingItems() {
        Catalog.Wallpaper w1080 = new Catalog.Wallpaper("id1", "Full HD", "cat", "https://img/1.jpg", "https://thumb/1.jpg",
                1920, 1080, "Author A", "CC", "https://src/1", "");
        Catalog.Wallpaper w4k = new Catalog.Wallpaper("id2", "4K Ultra", "cat", "https://img/2.jpg", "https://thumb/2.jpg",
                3840, 2160, "Author B", "CC", "https://src/2", "");
        Catalog.Wallpaper w2k = new Catalog.Wallpaper("id3", "2K QHD", "cat", "https://img/3.jpg", "https://thumb/3.jpg",
                2560, 1440, "Author C", "CC", "https://src/3", "");

        java.util.List<Catalog.Wallpaper> input = java.util.Arrays.asList(w1080, w4k, w2k);
        java.util.List<Catalog.Wallpaper> ordered = RemoteRepository.prefer4k(input);

        assertEquals(3, ordered.size());
        assertEquals("id2", ordered.get(0).id); // 4K moved to top
        assertEquals("id1", ordered.get(1).id); // Non-4K retains relative order
        assertEquals("id3", ordered.get(2).id);
    }
    @Test
    public void commonsAnimeSearchTermDoesNotOverconstrain() {
        String urlBleach = CommonsSource.buildUrl(RemoteRepository.CAT_ANIME, "bleach", 1);
        assertTrue(urlBleach.contains("bleach"));
        assertTrue(urlBleach.contains("anime"));
        assertFalse(urlBleach.contains("art%20illustration"));

        String urlAllGoku = CommonsSource.buildUrl(RemoteRepository.CAT_ALL, "goku", 1);
        assertTrue(urlAllGoku.contains("goku"));
        assertFalse(urlAllGoku.contains("anime"));

        String urlPage2 = CommonsSource.buildUrl(RemoteRepository.CAT_ANIME, "naruto", 2);
        assertTrue(urlPage2.contains("gsroffset=24"));

        String urlPage3 = CommonsSource.buildUrl(RemoteRepository.CAT_ANIME, "naruto", 3);
        assertTrue(urlPage3.contains("gsroffset=48"));
    }
}
