package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * UI theme for the launcher: a stable id plus the full palette the chrome is painted with
 * (fallback background, surfaces, pills, type, scrims and focus glow). Pure data on purpose:
 * no android.* calls, so the palettes stay unit-testable on the JVM. The code that turns a
 * palette into drawables lives in {@link ThemeKit}; themes are local and work offline.
 */
final class LauncherTheme {

    static final String DEFAULT_ID = "aurora";

    final String id;
    final int nameRes;
    final int taglineRes;

    // Fallback background shown when no wallpaper is set: a three-stop gradient plus two
    // radial accents; every theme shares the same geometry.
    int bgTop, bgMid, bgBottom, accent, accent2;

    // Surfaces: app tiles, the dock band and its rim, the options screen background.
    int card, cardPressed, dockBand, dockEdge, screenBg;

    // Frosted pills: top bar buttons and chips.
    int pillFillTop, pillFillBottom, pillStroke, pillFocused, pillText, pillTextFocused;

    // Type and the contrast scrims that sit over the wallpaper.
    int textPrimary, textSecondary, textDim;
    int scrimTop, scrimMid, scrimBottom, heroShadow;

    // Focus chrome drawn by GlassView and unified card/chip/dialog/input tokens.
    int glassGlow, glassRim;
    int focusRing, cardBorderIdle;
    // Focus strokes use the theme accent, with a dark keyline in ThemeKit for contrast over artwork.
    int cardFocusStroke, cardSelectedStroke;
    int controlFocusFill, controlFocusStroke;
    int chipSelectedFill, chipSelectedStroke, chipSelectedText;
    int chipSelectedFocusedFill, chipSelectedFocusedStroke, chipSelectedFocusedText;
    int surfaceDialog, surfaceInput, surfaceInputFocused, inputStrokeIdle;

    private LauncherTheme(String id, int nameRes, int taglineRes) {
        this.id = id;
        this.nameRes = nameRes;
        this.taglineRes = taglineRes;
    }

    private static final List<LauncherTheme> ALL = Collections.unmodifiableList(Arrays.asList(
            aurora(), midnight(), arctic(), ember()));

    static List<LauncherTheme> all() {
        return ALL;
    }

    /** Theme for an id, or the default one when the id is unknown (falls back, never crashes). */
    static LauncherTheme byId(String id) {
        for (LauncherTheme t : ALL) {
            if (t.id.equals(id)) return t;
        }
        return ALL.get(0);
    }

    /** True for palettes built on light surfaces; text and pills invert accordingly. */
    boolean isLight() {
        int r = (screenBg >> 16) & 0xFF;
        int g = (screenBg >> 8) & 0xFF;
        int b = screenBg & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 128;
    }

    /** Deep cinematic blue with turquoise light. The original 3.2 identity. */
    private static LauncherTheme aurora() {
        LauncherTheme t = new LauncherTheme("aurora",
                R.string.theme_name_aurora, R.string.theme_tagline_aurora);
        t.bgTop = 0xFF0A1F38; t.bgMid = 0xFF0C2E3B; t.bgBottom = 0xFF050F1A;
        t.accent = 0xFF2AD8C8; t.accent2 = 0xFF2B5CFF;
        t.card = 0xD91A2531; t.cardPressed = 0xE62E3B4C;
        t.dockBand = 0xB3101B26; t.dockEdge = 0x3D2AD8C8; t.screenBg = 0xFF0A121C;
        t.pillFillTop = 0x2EFFFFFF; t.pillFillBottom = 0x14FFFFFF; t.pillStroke = 0x38FFFFFF;
        t.pillFocused = 0xF5FFFFFF; t.pillText = 0xFFFFFFFF; t.pillTextFocused = 0xFF111111;
        t.textPrimary = 0xFFFFFFFF; t.textSecondary = 0xF2FFFFFF; t.textDim = 0x8CFFFFFF;
        t.scrimTop = 0x8A000000; t.scrimMid = 0x66000000; t.scrimBottom = 0xB3000000;
        t.heroShadow = 0xA6000000;
        t.glassGlow = 0xFF2AD8C8; t.glassRim = 0xCCFFFFFF;
        t.focusRing = 0xFF5CF0E2; t.cardBorderIdle = 0x26FFFFFF;
        t.cardFocusStroke = t.focusRing; t.cardSelectedStroke = 0x665CF0E2;
        t.controlFocusFill = 0xE62C3942; t.controlFocusStroke = t.focusRing;
        t.chipSelectedFill = 0x292AD8C8; t.chipSelectedStroke = 0x702AD8C8; t.chipSelectedText = 0xFFFFFFFF;
        t.chipSelectedFocusedFill = t.controlFocusFill; t.chipSelectedFocusedStroke = t.controlFocusStroke; t.chipSelectedFocusedText = t.textPrimary;
        t.surfaceDialog = 0xF20D1824; t.surfaceInput = 0xCC071019; t.surfaceInputFocused = 0xE60B1724; t.inputStrokeIdle = 0x3DFFFFFF;
        return t;
    }

    /** Near-black glass over deep navy, with a cool blue accent. */
    private static LauncherTheme midnight() {
        LauncherTheme t = new LauncherTheme("midnight",
                R.string.theme_name_midnight, R.string.theme_tagline_midnight);
        t.bgTop = 0xFF0B0E16; t.bgMid = 0xFF10141F; t.bgBottom = 0xFF05070C;
        t.accent = 0xFF6E9BFF; t.accent2 = 0xFF3D5A99;
        t.card = 0xCC141826; t.cardPressed = 0xE61C2233;
        t.dockBand = 0xB30D111A; t.dockEdge = 0x3D6E9BFF; t.screenBg = 0xFF0A0D14;
        t.pillFillTop = 0x2EAFB9FF; t.pillFillBottom = 0x14AFB9FF; t.pillStroke = 0x38AFB9FF;
        t.pillFocused = 0xF5E8EEFF; t.pillText = 0xFFF6F8FF; t.pillTextFocused = 0xFF0B0E16;
        t.textPrimary = 0xFFF2F6FF; t.textSecondary = 0xE6F2F6FF; t.textDim = 0x99C2D1F0;
        t.scrimTop = 0x8A05070C; t.scrimMid = 0x6605070C; t.scrimBottom = 0xB305070C;
        t.heroShadow = 0xA6000000;
        t.glassGlow = 0xFF6E9BFF; t.glassRim = 0xCCD9E4FF;
        t.focusRing = 0xFF8FB3FF; t.cardBorderIdle = 0x26C2D1F0;
        t.cardFocusStroke = t.focusRing; t.cardSelectedStroke = 0x668FB3FF;
        t.controlFocusFill = 0xE62A3040; t.controlFocusStroke = t.focusRing;
        t.chipSelectedFill = 0x2E6E9BFF; t.chipSelectedStroke = 0x706E9BFF; t.chipSelectedText = 0xFFF2F6FF;
        t.chipSelectedFocusedFill = t.controlFocusFill; t.chipSelectedFocusedStroke = t.controlFocusStroke; t.chipSelectedFocusedText = t.textPrimary;
        t.surfaceDialog = 0xF40B0F1A; t.surfaceInput = 0xCC060911; t.surfaceInputFocused = 0xE60A0F1D; t.inputStrokeIdle = 0x3DAFB9FF;
        return t;
    }

    /** Light surfaces with a clear blue accent and dark, legible type. */
    private static LauncherTheme arctic() {
        LauncherTheme t = new LauncherTheme("arctic",
                R.string.theme_name_arctic, R.string.theme_tagline_arctic);
        t.bgTop = 0xFFFAFCFF; t.bgMid = 0xFFE9EFF7; t.bgBottom = 0xFFDCE6F2;
        t.accent = 0xFF2B6CFF; t.accent2 = 0xFF7FB3FF;
        t.card = 0xF2FFFFFF; t.cardPressed = 0xFFE3ECFA;
        t.dockBand = 0xD9FFFFFF; t.dockEdge = 0x402B6CFF; t.screenBg = 0xFFF2F6FB;
        t.pillFillTop = 0x33000000; t.pillFillBottom = 0x1A000000; t.pillStroke = 0x33000000;
        t.pillFocused = 0xFF10141F; t.pillText = 0xFF0B1220; t.pillTextFocused = 0xFFFFFFFF;
        t.textPrimary = 0xFF0B1220; t.textSecondary = 0xE60B1220; t.textDim = 0x990B1220;
        t.scrimTop = 0x99FFFFFF; t.scrimMid = 0x59FFFFFF; t.scrimBottom = 0xB8FFFFFF;
        t.heroShadow = 0xB3FFFFFF;
        t.glassGlow = 0xCC2B6CFF; t.glassRim = 0xCC0B1220;
        t.focusRing = 0xFF1E6BFF; t.cardBorderIdle = 0x260B1220;
        t.cardFocusStroke = t.focusRing; t.cardSelectedStroke = 0x661E6BFF;
        t.controlFocusFill = 0xE4E3EAF2; t.controlFocusStroke = t.focusRing;
        t.chipSelectedFill = 0x242B6CFF; t.chipSelectedStroke = 0x702B6CFF; t.chipSelectedText = 0xFF0B1220;
        t.chipSelectedFocusedFill = t.controlFocusFill; t.chipSelectedFocusedStroke = t.controlFocusStroke; t.chipSelectedFocusedText = t.textPrimary;
        t.surfaceDialog = 0xF7FFFFFF; t.surfaceInput = 0xFFFFFFFF; t.surfaceInputFocused = 0xFFFFFFFF; t.inputStrokeIdle = 0x380B1220;
        return t;
    }

    /** Warm charcoal with amber light. */
    private static LauncherTheme ember() {
        LauncherTheme t = new LauncherTheme("ember",
                R.string.theme_name_ember, R.string.theme_tagline_ember);
        t.bgTop = 0xFF1A120C; t.bgMid = 0xFF241610; t.bgBottom = 0xFF0D0805;
        t.accent = 0xFFFF8A3D; t.accent2 = 0xFFC2591F;
        t.card = 0xD9211712; t.cardPressed = 0xE62E2018;
        t.dockBand = 0xB3160F0B; t.dockEdge = 0x3DFF8A3D; t.screenBg = 0xFF140D08;
        t.pillFillTop = 0x33FFD9B3; t.pillFillBottom = 0x14FFD9B3; t.pillStroke = 0x38FFD9B3;
        t.pillFocused = 0xF5FFE9D6; t.pillText = 0xFFFFF4EA; t.pillTextFocused = 0xFF1A120C;
        t.textPrimary = 0xFFFFF4EA; t.textSecondary = 0xE6FFF4EA; t.textDim = 0x99FFD9B3;
        t.scrimTop = 0x8A0D0603; t.scrimMid = 0x660D0603; t.scrimBottom = 0xB30D0603;
        t.heroShadow = 0xA6000000;
        t.glassGlow = 0xFFFF8A3D; t.glassRim = 0xCCFFE9D6;
        t.focusRing = 0xFFFFA366; t.cardBorderIdle = 0x26FFD9B3;
        t.cardFocusStroke = t.focusRing; t.cardSelectedStroke = 0x66FFA366;
        t.controlFocusFill = 0xE62C231D; t.controlFocusStroke = t.focusRing;
        t.chipSelectedFill = 0x2EFF8A3D; t.chipSelectedStroke = 0x70FF8A3D; t.chipSelectedText = 0xFFFFF4EA;
        t.chipSelectedFocusedFill = t.controlFocusFill; t.chipSelectedFocusedStroke = t.controlFocusStroke; t.chipSelectedFocusedText = t.textPrimary;
        t.surfaceDialog = 0xF41B110C; t.surfaceInput = 0xCC110A07; t.surfaceInputFocused = 0xE6180E0A; t.inputStrokeIdle = 0x3DFFD9B3;
        return t;
    }
}
