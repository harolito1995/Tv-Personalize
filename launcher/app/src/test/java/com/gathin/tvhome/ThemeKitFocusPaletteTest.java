package com.gathin.tvhome;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Verifies that all four launcher themes (Aurora Cinema, Midnight Glass, Arctic Light, Ember)
 * provide distinct, high-contrast tokens for card focus rings, selectable chips (5-state),
 * text fields (input vs focused input), and dialog surfaces.
 */
public class ThemeKitFocusPaletteTest {

    @Test
    public void allFourThemesHaveValidFocusAndSurfaceTokens() {
        List<LauncherTheme> themes = LauncherTheme.all();
        assertEquals(4, themes.size());

        for (LauncherTheme t : themes) {
            assertNotNull(t.id);
            assertFalse(t.id.isEmpty());
            assertEquals("accent alpha must be opaque for " + t.id, 0xFF, (t.accent >>> 24) & 0xFF);
            assertEquals("focusRing alpha must be opaque for " + t.id, 0xFF, (t.focusRing >>> 24) & 0xFF);
            assertTrue("glassRim alpha must be visible for " + t.id, ((t.glassRim >>> 24) & 0xFF) >= 0xAA);
            assertTrue("card surface alpha must be visible for " + t.id, ((t.card >>> 24) & 0xFF) >= 0xAA);
            assertTrue("cardBorderIdle must be translucent for " + t.id, ((t.cardBorderIdle >>> 24) & 0xFF) > 0x00);
            assertEquals("card focus uses its theme accent in " + t.id,
                    t.focusRing, t.cardFocusStroke);
            assertEquals("control focus uses its theme accent in " + t.id,
                    t.focusRing, t.controlFocusStroke);
            assertEquals("focused control stroke is fully visible in " + t.id,
                    0xFF, (t.controlFocusStroke >>> 24) & 0xFF);
            assertTrue("control fill must keep strong text contrast in " + t.id,
                    luminanceDiff(t.controlFocusFill, t.textPrimary) >= 140);
            assertTrue("card focus border must be clearly visible in " + t.id,
                    ((t.cardFocusStroke >>> 24) & 0xFF) >= 0xA8);
            assertTrue("selected card border must be quieter than focused card in " + t.id,
                    ((t.cardSelectedStroke >>> 24) & 0xFF) < ((t.cardFocusStroke >>> 24) & 0xFF));
            assertTrue("surfaceDialog must be high-opacity for " + t.id, ((t.surfaceDialog >>> 24) & 0xFF) >= 0xEE);
        }
    }

    @Test
    public void lightAndDarkThemesAreCorrectlyClassifiedForFocusRimContrast() {
        LauncherTheme midnight = LauncherTheme.byId("midnight");
        LauncherTheme aurora = LauncherTheme.byId("aurora");
        LauncherTheme ember = LauncherTheme.byId("ember");
        LauncherTheme arctic = LauncherTheme.byId("arctic");

        assertFalse(midnight.isLight());
        assertFalse(aurora.isLight());
        assertFalse(ember.isLight());
        assertTrue(arctic.isLight());
    }

    @Test
    public void selectableChipsDifferentiateIdleFromFocusedStatesInAllThemes() {
        for (LauncherTheme t : LauncherTheme.all()) {
            // Focus changes the surface and rim; primary text stays stable for a calmer state.
            assertNotEquals("selected chip fill must change on focus in " + t.id,
                    t.chipSelectedFill, t.chipSelectedFocusedFill);
            assertNotEquals("selected chip stroke must change on focus in " + t.id,
                    t.chipSelectedStroke, t.chipSelectedFocusedStroke);
            assertTrue("selected focused fill must keep text contrast in " + t.id,
                    luminanceDiff(t.chipSelectedFocusedFill, t.chipSelectedFocusedText) >= 120);
        }
    }

    @Test
    public void textFieldTokensMaintainHighLegibilityWhenFocusedInAllThemes() {
        for (LauncherTheme t : LauncherTheme.all()) {
            assertNotEquals("input surface must never equal textPrimary in " + t.id,
                    t.surfaceInput & 0x00FFFFFF, t.textPrimary & 0x00FFFFFF);
            assertNotEquals("focused input surface must never equal textPrimary in " + t.id,
                    t.surfaceInputFocused & 0x00FFFFFF, t.textPrimary & 0x00FFFFFF);
            assertTrue("textPrimary must have strong luminance contrast over surfaceInput in " + t.id,
                    luminanceDiff(t.surfaceInput, t.textPrimary) >= 140);
            assertTrue("textPrimary must have strong luminance contrast over surfaceInputFocused in " + t.id,
                    luminanceDiff(t.surfaceInputFocused, t.textPrimary) >= 140);
        }
    }

    private static int luminanceDiff(int c1, int c2) {
        return Math.abs(luma(c1) - luma(c2));
    }

    private static int luma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    @Test
    public void searchQueryDeduplicationSkipsAlreadyLoadedQueryAndAcceptsNewOnes() {
        // When the debounce TextWatcher already updated query="cyberpunk" and loadedQuery="cyberpunk",
        // clicking the Done button with "cyberpunk" or "  cyberpunk  " must NOT trigger a second fetch.
        assertFalse(PersonalizeActivity.shouldApplySearchQuery("cyberpunk", "cyberpunk", "cyberpunk"));
        assertFalse(PersonalizeActivity.shouldApplySearchQuery("  cyberpunk  ", "cyberpunk", "cyberpunk"));
        assertFalse(PersonalizeActivity.shouldApplySearchQuery("", "", ""));

        // If debounce has not yet fired (query="cyber", loadedQuery=""), confirming "cyber" MUST fetch.
        assertTrue(PersonalizeActivity.shouldApplySearchQuery("cyber", "cyber", ""));

        // If the user confirms a different query than what is currently loaded, it MUST fetch.
        assertTrue(PersonalizeActivity.shouldApplySearchQuery("naruto", "cyberpunk", "cyberpunk"));
        assertTrue(PersonalizeActivity.shouldApplySearchQuery("", "cyberpunk", "cyberpunk"));
    }

    @Test
    public void initialAndReturnThemeFocusPrioritizesPendingThemeAndFallsBackToActiveTheme() {
        // Returning from ThemePreviewActivity with pendingThemeId="arctic" while active is "midnight"
        assertEquals("arctic", PersonalizeActivity.resolveInitialThemeFocusId("arctic", "midnight"));
        assertEquals("ember", PersonalizeActivity.resolveInitialThemeFocusId("ember", "midnight"));

        // Opening Themes directly (no pendingThemeId) focuses the currently active theme
        assertEquals("midnight", PersonalizeActivity.resolveInitialThemeFocusId(null, "midnight"));
        assertEquals("midnight", PersonalizeActivity.resolveInitialThemeFocusId("", "midnight"));

        // Unknown theme IDs fall back safely to default ("aurora") without crashing
        assertEquals(LauncherTheme.DEFAULT_ID, PersonalizeActivity.resolveInitialThemeFocusId(null, "unknown"));
    }

    @Test
    public void homeControlsTopShelfAndPopupsHaveHighContrastAcrossAllThemes() {
        for (LauncherTheme t : LauncherTheme.all()) {
            // 1. Focused pill/sphere/popup uses the matte control surface and theme primary text
            assertTrue("controlFocusFill vs textPrimary contrast in " + t.id,
                    luminanceDiff(t.controlFocusFill, t.textPrimary) >= 140);
            // 2. Dialog / Popup panel surface vs textPrimary must have high contrast
            assertTrue("surfaceDialog vs textPrimary contrast in " + t.id,
                    luminanceDiff(t.surfaceDialog, t.textPrimary) >= 140);
            // 3. Card surface vs textPrimary and textSecondary (used on Home Dock & Top Shelf)
            assertTrue("bgMid vs textPrimary contrast in " + t.id,
                    luminanceDiff(t.bgMid, t.textPrimary) >= 140);
            assertNotEquals("textPrimary and textSecondary must differ in " + t.id,
                    t.textPrimary, t.textSecondary);
        }
    }

    @Test
    public void secondaryScreensPreserveLegibilityOverFallbackAndScrimInAllFourThemes() {
        for (LauncherTheme t : LauncherTheme.all()) {
            // 1. AllAppsActivity / DreamPhotoActivity / UsbFolderActivity headers over fallback bgTop/bgMid
            assertTrue("title textPrimary over bgTop in " + t.id,
                    luminanceDiff(t.bgTop, t.textPrimary) >= 140);
            assertTrue("subtitle/count textSecondary over bgTop in " + t.id,
                    luminanceDiff(t.bgTop, t.textSecondary) >= 85);
            // 2. WallpaperPreviewActivity bottom metadata over scrimBottom
            int scrimSolid = 0xFF000000 | (t.scrimBottom & 0x00FFFFFF);
            assertTrue("preview title textPrimary over scrimBottom in " + t.id,
                    luminanceDiff(scrimSolid, t.textPrimary) >= 140);
            assertTrue("preview meta textSecondary over scrimBottom in " + t.id,
                    luminanceDiff(scrimSolid, t.textSecondary) >= 85);
        }
    }
}
