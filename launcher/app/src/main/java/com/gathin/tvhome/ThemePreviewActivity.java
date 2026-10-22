package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen preview of one launcher theme: a mock home (clock, pills, shelf title, dock)
 * painted with the theme's real palette. "Apply theme" stores the choice in prefs; the home
 * screen picks it up on resume. Wallpaper, favourites and the stored image are never touched.
 */
public class ThemePreviewActivity extends Activity {

    private static final int DOCK_SLOTS = 4;

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
        ((TextView) findViewById(R.id.mockGreeting)).setTextColor(theme.textDim);
        ((TextView) findViewById(R.id.mockClock)).setTextColor(theme.textPrimary);
        ((TextView) findViewById(R.id.mockAmpm)).setTextColor(theme.textSecondary);
        ((TextView) findViewById(R.id.mockDate)).setTextColor(theme.textDim);

        TextView hero = findViewById(R.id.mockHero);
        hero.setText(theme.nameRes);
        hero.setTextColor(theme.textPrimary);
        hero.setShadowLayer(18f, 0f, 6f, theme.heroShadow);

        TextView tagline = findViewById(R.id.tagline);
        tagline.setText(theme.taglineRes);
        tagline.setTextColor(theme.textSecondary);

        // Mock pills: same chrome as the top bar, not focusable in the preview.
        LinearLayout pills = findViewById(R.id.mockPills);
        for (int i = 0; i < pills.getChildCount(); i++) {
            if (pills.getChildAt(i) instanceof TextView) {
                TextView pill = (TextView) pills.getChildAt(i);
                ThemeKit.applyPill(pill, theme);
                pill.setFocusable(false);
                pill.setClickable(false);
            }
        }

        // Mock dock cards, banded or floating as configured in Interface.
        LinearLayout cards = findViewById(R.id.mockDockCards);
        int cardW = (int) (180 * density);
        int cardH = (int) (101 * density);
        for (int i = 0; i < DOCK_SLOTS; i++) {
            TextView card = new TextView(this);
            card.setBackground(ThemeKit.cardBackground(this, theme));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) lp.setMarginStart((int) (14 * density));
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
        btnApply.setOnClickListener(v -> apply());
        btnBack.setOnClickListener(v -> finish());
        btnApply.requestFocus();
    }

    private void apply() {
        Prefs.setTheme(this, theme.id);
        ((TextView) findViewById(R.id.status)).setText(R.string.theme_applied);
        ((TextView) findViewById(R.id.status)).setTextColor(theme.textSecondary);
    }
}
