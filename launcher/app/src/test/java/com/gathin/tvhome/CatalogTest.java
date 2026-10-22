package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for the catalogue parser and the bounded reader used by the HTTPS client. They run
 * on a plain JVM (no Android classes), so the same validation rules that guard the TV are checked
 * on every build.
 */
public class CatalogTest {

    private static final String OK = "{\"version\":1,\"wallpapers\":[{\"id\":\"a1\","
            + "\"title\":\"T\",\"image\":\"https://example.com/a.jpg\","
            + "\"width\":100,\"height\":50}]}";

    @Test
    public void parsesMinimalCatalogue() throws Exception {
        Catalog c = Catalog.parse(OK);
        assertEquals(1, c.wallpapers.size());
        Catalog.Wallpaper w = c.wallpapers.get(0);
        assertEquals("a1", w.id);
        assertEquals("T", w.title);
        assertEquals("otros", w.category);
        assertEquals(100, w.width);
        assertEquals("", w.thumbUrl);
    }

    @Test
    public void acceptsBomAndWhitespace() throws Exception {
        Catalog c = Catalog.parse("\uFEFF  " + OK + "  \n");
        assertEquals(1, c.wallpapers.size());
    }

    @Test
    public void rejectsBadVersion() {
        expectFormat("{\"version\":2,\"wallpapers\":[]}");
        expectFormat("{\"wallpapers\":[]}");
    }

    @Test
    public void rejectsGarbage() {
        expectFormat("no soy json");
        expectFormat("");
        expectFormat("{\"version\":1}");
        expectFormat("{\"version\":1,\"wallpapers\":{\"a\":1}}");
    }

    @Test
    public void skipsUnsafeAndHttpEntries() throws Exception {
        String json = "{\"version\":1,\"wallpapers\":["
                + "{\"id\":\"../evil\",\"title\":\"x\",\"image\":\"https://e.com/a.jpg\"},"
                + "{\"id\":\"UPPER\",\"title\":\"x\",\"image\":\"https://e.com/a.jpg\"},"
                + "{\"id\":\"slash/id\",\"title\":\"x\",\"image\":\"https://e.com/a.jpg\"},"
                + "{\"id\":\"ok-1\",\"title\":\"\",\"image\":\"https://e.com/a.jpg\"},"
                + "{\"id\":\"ok-2\",\"title\":\"x\",\"image\":\"http://e.com/a.jpg\"},"
                + "{\"id\":\"ok-3\",\"title\":\"x\",\"image\":\"https://e.com/a.jpg\"},"
                + "{\"id\":\"ok-3\",\"title\":\"dup\",\"image\":\"https://e.com/a.jpg\"}"
                + "]}";
        Catalog c = Catalog.parse(json);
        assertEquals(1, c.wallpapers.size());
        assertEquals("ok-3", c.wallpapers.get(0).id);
    }

    @Test
    public void normalizesOptionalFields() throws Exception {
        String json = "{\"version\":1,\"wallpapers\":[{\"id\":\"x1\",\"title\":\"T\","
                + "\"category\":\"  Naturaleza \",\"image\":\"https://e.com/a.jpg\","
                + "\"thumb\":\"http://e.com/t.jpg\",\"source\":\"ftp://e.com\","
                + "\"width\":-5,\"height\":0}]}";
        Catalog.Wallpaper w = Catalog.parse(json).wallpapers.get(0);
        assertEquals("naturaleza", w.category);
        assertEquals("", w.thumbUrl);
        assertEquals("", w.source);
        assertEquals(0, w.width);
        assertEquals(0, w.height);
    }

    @Test
    public void categoriesAreDistinctAndSorted() throws Exception {
        String json = "{\"version\":1,\"wallpapers\":["
                + "{\"id\":\"b\",\"title\":\"B\",\"image\":\"https://e.com/b.jpg\",\"category\":\"zeta\"},"
                + "{\"id\":\"a\",\"title\":\"A\",\"image\":\"https://e.com/a.jpg\",\"category\":\"alfa\"},"
                + "{\"id\":\"c\",\"title\":\"C\",\"image\":\"https://e.com/c.jpg\",\"category\":\"zeta\"}"
                + "]}";
        Catalog c = Catalog.parse(json);
        assertEquals(List.of("alfa", "zeta"), c.categories());
        assertEquals(2, c.inCategory("zeta").size());
        assertEquals(3, c.inCategory(null).size());
    }

    @Test
    public void capsLongStrings() throws Exception {
        String title = "x".repeat(400);
        String json = "{\"version\":1,\"wallpapers\":[{\"id\":\"x1\",\"title\":\"" + title
                + "\",\"image\":\"https://e.com/a.jpg\"}]}";
        assertEquals(120, Catalog.parse(json).wallpapers.get(0).title.length());
    }

    @Test
    public void isHttpsUrlChecks() {
        assertTrue(Catalog.isHttpsUrl("https://example.com/a.jpg"));
        assertTrue(Catalog.isHttpsUrl("HTTPS://EXAMPLE.COM/a.jpg"));
        assertFalse(Catalog.isHttpsUrl("http://example.com/a.jpg"));
        assertFalse(Catalog.isHttpsUrl("ftp://example.com/a.jpg"));
        assertFalse(Catalog.isHttpsUrl("https://"));
        assertFalse(Catalog.isHttpsUrl("file:///etc/passwd"));
        assertFalse(Catalog.isHttpsUrl("https:///no-host/a.jpg"));
        assertFalse(Catalog.isHttpsUrl(null));
    }

    @Test
    public void readLimitedEnforcesCap() throws Exception {
        byte[] body = new byte[100];
        byte[] ok = CatalogClient.readLimited(new ByteArrayInputStream(body), 100);
        assertEquals(100, ok.length);
        try {
            CatalogClient.readLimited(new ByteArrayInputStream(body), 50);
            fail("expected TOO_BIG");
        } catch (CatalogException e) {
            assertEquals(CatalogException.Code.TOO_BIG, e.code);
        }
    }

    @Test
    public void rejectsNonHttpsAndBadUrlsBeforeConnecting() {
        for (String url : new String[]{"http://example.com/catalog.json", "not a url",
                "ftp://example.com/x", "file:///etc/passwd"}) {
            try {
                CatalogClient.fetch(url);
                fail("expected BAD_URL for: " + url);
            } catch (CatalogException e) {
                assertEquals("for: " + url, CatalogException.Code.BAD_URL, e.code);
            }
        }
    }

    @Test
    public void parsesBundledDemoCatalogue() throws Exception {
        Path file = Path.of("src/main/assets/catalog_demo.json");
        assertTrue("demo asset missing at " + file.toAbsolutePath(), Files.exists(file));
        Catalog c = Catalog.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(5, c.wallpapers.size());
        for (Catalog.Wallpaper w : c.wallpapers) {
            assertTrue(Catalog.isSafeId(w.id));
            assertTrue(Catalog.isHttpsUrl(w.imageUrl));
            if (!w.thumbUrl.isEmpty()) assertTrue(Catalog.isHttpsUrl(w.thumbUrl));
            assertTrue(w.width > 0 && w.height > 0);
        }
    }

    @Test
    public void searchMatchesAccentInsensitiveText() throws Exception {
        String json = "{\"version\":1,\"wallpapers\":["
                + "{\"id\":\"matterhorn\",\"title\":\"Matterhorn\",\"author\":\"S\u00e9bastien\","
                + "\"image\":\"https://e.com/a.jpg\",\"category\":\"naturaleza\"},"
                + "{\"id\":\"budapest\",\"title\":\"Budapest\","
                + "\"image\":\"https://e.com/b.jpg\",\"category\":\"ciudades\"},"
                + "{\"id\":\"pink-mountain\",\"title\":\"Pink mountain\","
                + "\"image\":\"https://e.com/c.jpg\",\"category\":\"naturaleza\"}"
                + "]}";
        Catalog c = Catalog.parse(json);
        assertEquals(1, c.query(null, "budapest").size());
        assertEquals(2, c.query("naturaleza", "").size());
        assertEquals(1, c.query("naturaleza", "matter").size());
        assertEquals(2, c.query(null, "NATURALEZA").size());
        assertEquals(1, c.query(null, "sebastien").size());
        assertEquals(0, c.query(null, "xyz").size());
        assertTrue(c.query(null, "  ").size() == 3);
    }

    @Test
    public void cachedFlagOnlyWhenMarked() throws Exception {
        Catalog fresh = Catalog.parse(OK);
        assertFalse(fresh.cached);
        assertTrue(fresh.asCached().cached);
        assertEquals(fresh.wallpapers.size(), fresh.asCached().wallpapers.size());
    }

    private static void expectFormat(String json) {
        try {
            Catalog.parse(json);
            fail("expected FORMAT for: " + json);
        } catch (CatalogException e) {
            assertEquals(CatalogException.Code.FORMAT, e.code);
        }
    }
}
