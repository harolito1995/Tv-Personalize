package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** The theme model is pure data: palettes and fallback behaviour are checked on the JVM. */
public class ThemeTest {

    @Test
    public void fourThemesWithUniqueIds() {
        assertEquals(4, LauncherTheme.all().size());
        Set<String> ids = new HashSet<>();
        for (LauncherTheme t : LauncherTheme.all()) {
            assertTrue("duplicate id " + t.id, ids.add(t.id));
            assertTrue(t.id.matches("[a-z][a-z0-9]*"));
            assertNotEquals(0, t.nameRes);
            assertNotEquals(0, t.taglineRes);
        }
        assertTrue(ids.contains(LauncherTheme.DEFAULT_ID));
    }

    @Test
    public void byIdFallsBackToDefault() {
        assertEquals(LauncherTheme.DEFAULT_ID, LauncherTheme.byId(null).id);
        assertEquals(LauncherTheme.DEFAULT_ID, LauncherTheme.byId("").id);
        assertEquals(LauncherTheme.DEFAULT_ID, LauncherTheme.byId("does-not-exist").id);
        assertEquals("midnight", LauncherTheme.byId("midnight").id);
        assertEquals("arctic", LauncherTheme.byId("arctic").id);
    }

    @Test
    public void palettesAreCompleteAndDistinct() {
        Set<Integer> cards = new HashSet<>();
        Set<Integer> accents = new HashSet<>();
        Set<Integer> texts = new HashSet<>();
        for (LauncherTheme t : LauncherTheme.all()) {
            assertNotEquals("card missing in " + t.id, 0, t.card);
            assertNotEquals("pressed missing in " + t.id, 0, t.cardPressed);
            assertNotEquals("dock band missing in " + t.id, 0, t.dockBand);
            assertNotEquals("dock edge missing in " + t.id, 0, t.dockEdge);
            assertNotEquals("accent missing in " + t.id, 0, t.accent);
            assertNotEquals("text missing in " + t.id, 0, t.textPrimary);
            assertNotEquals("screen missing in " + t.id, 0, t.screenBg);
            assertNotEquals("glow missing in " + t.id, 0, t.glassGlow);
            assertNotEquals("rim missing in " + t.id, 0, t.glassRim);
            assertNotEquals(t.card, t.cardPressed);
            cards.add(t.card);
            accents.add(t.accent);
            texts.add(t.textPrimary);
        }
        assertEquals(4, cards.size());
        assertEquals(4, accents.size());
        assertEquals(4, texts.size());
    }

    @Test
    public void arcticIsTheLightThemeWithDarkType() {
        for (LauncherTheme t : LauncherTheme.all()) {
            if ("arctic".equals(t.id)) {
                assertTrue(t.isLight());
                int r = (t.textPrimary >> 16) & 0xFF;
                int g = (t.textPrimary >> 8) & 0xFF;
                int b = t.textPrimary & 0xFF;
                assertTrue("dark text expected on the light theme", r + g + b < 0x180);
            } else {
                assertFalse("only arctic is light", t.isLight());
            }
        }
    }

    @Test
    public void dockStylesAreKnownValues() {
        assertNotEquals(Prefs.DOCK_BAND, Prefs.DOCK_FLOATING);
        assertEquals("band", Prefs.DOCK_BAND);
        assertEquals("floating", Prefs.DOCK_FLOATING);
    }
}
