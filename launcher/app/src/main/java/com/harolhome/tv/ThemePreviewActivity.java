package com.harolhome.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen preview of one launcher theme: a mock of the B.3 home screen (compact header with
 * clock/date/weather, 2 compact 148x83dp Continue Watching cards, and the Dock row) painted with
 * the theme's real palette. "Apply theme" stores the choice in prefs; the home screen picks it up
 * on resume. Wallpaper, favourites and the stored image are never touched.
 */
public class ThemePreviewActivity extends Activity {

    private static final int SHELF_SLOTS = 2;
    private static final int DOCK_SLOTS = 6;

    private LauncherTheme theme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_preview);

        String id = getIntent().getStringExtra("theme");
        LauncherTheme found = null;
        for (LauncherTheme t : LauncherTheme.all()) {
            if (t.id.equals(id)) found = t;
        }
        if (found == null) {
            finish();
            return;
        }
        theme = found;

        findViewById(R.id.themeRoot).setBackground(ThemeKit.fallback(this, theme));
        findViewById(R.id.scrimTop).setBackground(ThemeKit.scrimTop(this, theme));
        findViewById(R.id.scrimBottom).setBackground(ThemeKit.scrimBottom(this, theme));

        float density = getResources().getDisplayMetrics().density;
        ((TextView) findViewById(R.id.mockClock)).setTextColor(theme.textPrimary);
        ((TextView) findViewById(R.id.mockDate)).setTextColor(theme.textSecondary);
        TextView weatherText = findViewById(R.id.mockWeatherText);
        if (weatherText != null) weatherText.setTextColor(theme.textPrimary);
        TextView shelfLabel = findViewById(R.id.mockShelfLabel);
        if (shelfLabel != null) shelfLabel.setTextColor(theme.textPrimary);

        TextView hero = findViewById(R.id.mockHero);
        hero.setText(theme.nameRes);
        hero.setTextColor(theme.textPrimary);
        hero.setShadowLayer(18f, 0f, 6f, theme.heroShadow);

        TextView tagline = findViewById(R.id.tagline);
        tagline.setText(theme.taglineRes);
        tagline.setTextColor(theme.textSecondary);

        // Mock top-right circular buttons: same chrome as the header spheres, not focusable in preview.
        LinearLayout pills = findViewById(R.id.mockPills);
        for (int i = 0; i < pills.getChildCount(); i++) {
            View child = pills.getChildAt(i);
            ThemeKit.applySphere(child, theme);
            child.setFocusable(false);
            child.setClickable(false);
        }

        // Mock B.3 Continue Watching shelf: two compact 148x83dp 16:9 cards with compact 10dp radius.
        LinearLayout shelfRow = findViewById(R.id.mockShelfRow);
        if (shelfRow != null) {
            int shelfW = getResources().getDimensionPixelSize(R.dimen.top_shelf_card_width);
            int shelfH = getResources().getDimensionPixelSize(R.dimen.top_shelf_card_height);
            int shelfRadius = getResources().getDimensionPixelSize(R.dimen.top_shelf_card_radius);
            for (int i = 0; i < SHELF_SLOTS; i++) {
                LinearLayout slotWrap = new LinearLayout(this);
                slotWrap.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                        shelfW, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) wrapLp.setMarginStart((int) (14 * density));

                FrameLayout frame = new FrameLayout(this);
                ThemeKit.applyCard(frame, theme, shelfRadius);
                if (i == 0) {
                    // Show the focus ring on the first mock Continue Watching card so the user sees focus contrast.
                    frame.setSelected(true);
                }
                slotWrap.addView(frame, new LinearLayout.LayoutParams(shelfW, shelfH));

                TextView cap = new TextView(this);
                cap.setText(i == 0 ? R.string.top_shelf_recent_content : R.string.top_shelf_last_used);
                cap.setTextColor(i == 0 ? theme.textPrimary : theme.textSecondary);
                cap.setTextSize(11.5f);
                cap.setSingleLine(true);
                LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                        shelfW, LinearLayout.LayoutParams.WRAP_CONTENT);
                capLp.topMargin = (int) (5 * density);
                slotWrap.addView(cap, capLp);

                shelfRow.addView(slotWrap, wrapLp);
            }
        }

        // Mock dock cards (90x51dp with 10dp compact radius matching B.3 DockBar), banded or floating.
        LinearLayout cards = findViewById(R.id.mockDockCards);
        int cardW = getResources().getDimensionPixelSize(R.dimen.dock_tile_width);
        int cardH = getResources().getDimensionPixelSize(R.dimen.dock_tile_height);
        int dockRadius = getResources().getDimensionPixelSize(R.dimen.dock_tile_radius);
        for (int i = 0; i < DOCK_SLOTS; i++) {
            TextView card = new TextView(this);
            ThemeKit.applyCard(card, theme, dockRadius);
            if (i == 0) {
                card.setText(R.string.nav_home);
                card.setTextColor(theme.textPrimary);
                card.setTextSize(11f);
                card.setGravity(Gravity.CENTER);
                card.setSelected(true);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.setMarginStart((int) (8 * density));
            cards.addView(card, lp);
        }
        FrameLayout dockWrap = findViewById(R.id.mockDockWrap);
        if (Prefs.DOCK_BAND.equals(Prefs.dockStyle(this))) {
            dockWrap.setBackground(ThemeKit.dockBand(this, theme));
        } else {
            dockWrap.setBackground(null);
        }

        TextView btnApply = findViewById(R.id.btnApply);
        TextView btnBack = findViewById(R.id.btnBack);
        ThemeKit.applyPill(btnApply, theme);
        ThemeKit.applyPill(btnBack, theme);
        btnApply.setNextFocusRightId(R.id.btnBack);
        btnBack.setNextFocusLeftId(R.id.btnApply);
        btnApply.setOnClickListener(v -> apply());
        btnBack.setOnClickListener(v -> finish());
        btnApply.requestFocus();
    }

    private void apply() {
        Prefs.setTheme(this, theme.id);
        ((TextView) findViewById(R.id.status)).setText(R.string.theme_applied);
        ((TextView) findViewById(R.id.status)).setTextColor(theme.textSecondary);
        Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
    }
}
