package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpaper catalog and personalization activity. Features a clean 3x2 grid of 16:9 cards,
 * pagination across remote APIs and local favorites, search dialog, and hidden settings panel.
 * Header and pagination controls use icon-only circular buttons for a modern TV look.
 */
public class PersonalizeActivity extends Activity {

    /** Intent extra: open straight on the theme cards (the home screen's Themes button). */
    static final String EXTRA_FOCUS_THEMES = "focus_themes";


    private static final int REQ_PICK = 10;
    private static final int REQ_PERM = 11;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ITEMS = 400;
    private static final int PAGE_SIZE = 6;
    private static final int GRID_COLUMNS = 3;
    private static final int MAX_RESTORE_PAGES = 12;
    private static final String MODE_LOCAL = "local";
    private static final String MODE_CATALOG = "catalog";
    private static final String MODE_REMOTE = "remote";
    private static final String MODE_FAVORITES = "favorites";
    private static final long SEARCH_DEBOUNCE_MS = 500;
    private static final String DEMO_ASSET = "catalog_demo.json";
    private static final String CACHE_FILE = "catalog_cache.json";

    private WallpaperAdapter localAdapter;
    private CatalogAdapter catalogAdapter;
    private RecyclerView grid;
    private RecyclerView settingsGrid;
    private TextView status;
    private TextView settingsStatus;
    private TextView pageLabel;
    private ImageView btnPrev;
    private ImageView btnNext;
    private TextView chipActiveSearch;
    private ImageView btnSearch;
    private ImageView btnSettings;
    private ImageView btnFilter;
    private String selectedProvider = RemoteRepository.PROVIDER_ALL;
    private String selectedQuality = RemoteRepository.QUALITY_ALL;
    private String selectedSort = RemoteRepository.SORT_RELEVANCE;
    private LinearLayout catalogPanel;
    private LinearLayout settingsPanel;
    private HorizontalScrollView catScroll;
    private LinearLayout catRow;
    private LinearLayout themeRail;
    private TextView chipDockBand;
    private TextView chipDockFloat;
    private TextView chipWeatherOn;
    private TextView chipWeatherOff;
    private TextView weatherAttribution;
    private LauncherTheme theme;

    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();
    private final ExecutorService catalogExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String mode = MODE_REMOTE;
    private final RemoteRepository remoteRepo = new RemoteRepository();
    private final Runnable remoteSearch = () -> {
        if (MODE_REMOTE.equals(mode)) loadRemote(false);
        else if (MODE_FAVORITES.equals(mode)) loadFavorites(false);
    };

    private String activeCat;
    private String query = "";
    private Catalog catalog;
    private Catalog demoCatalog;
    private CancelToken remoteToken;
    private boolean catalogDemo;
    private String catalogUrl;
    private boolean scanned;
    private boolean localEmpty;
    private boolean focusLocalAfterScan;
    private boolean settingsOpen;
    private String pendingThemeId;
    private int pendingWallpaperPos = -1;

    private final List<Catalog.Wallpaper> remoteItems = new ArrayList<>();
    private int remotePage = 1;
    private int remoteApiPage = 1;
    private boolean remoteHasMore = true;
    private boolean remoteLoading;
    private boolean pendingNextPage;
    private String remoteSources = "";
    private String loadedCat = "";
    private String loadedQuery = "";
    private int emptyFetchStreak;
    private String restoreWallpaperId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_personalize);

        theme = LauncherTheme.byId(Prefs.theme(this));
        status = findViewById(R.id.status);
        settingsStatus = findViewById(R.id.settingsStatus);
        pageLabel = findViewById(R.id.pageLabel);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        chipActiveSearch = findViewById(R.id.chipActiveSearch);
        if (chipActiveSearch != null) chipActiveSearch.setOnClickListener(v -> clearActiveSearch());
        btnSearch = findViewById(R.id.btnSearch);
        btnFilter = findViewById(R.id.btnFilter);
        btnSettings = findViewById(R.id.btnSettings);
        selectedProvider = Prefs.panelProvider(this);
        selectedQuality = Prefs.panelQuality(this);
        selectedSort = Prefs.panelSort(this);
        catalogPanel = findViewById(R.id.catalogPanel);
        settingsPanel = findViewById(R.id.settingsPanel);
        catScroll = findViewById(R.id.catScroll);
        catRow = findViewById(R.id.cats);
        grid = findViewById(R.id.grid);
        settingsGrid = findViewById(R.id.settingsGrid);
        themeRail = findViewById(R.id.themeRail);
        chipDockBand = findViewById(R.id.chipDockBand);
        chipDockFloat = findViewById(R.id.chipDockFloat);
        chipWeatherOn = findViewById(R.id.chipWeatherOn);
        chipWeatherOff = findViewById(R.id.chipWeatherOff);
        weatherAttribution = findViewById(R.id.weatherAttribution);
        chipWeatherOn.setOnClickListener(v -> setWeatherEnabled(true));
        chipWeatherOff.setOnClickListener(v -> setWeatherEnabled(false));


        btnSearch.setNextFocusRightId(R.id.btnFilter);
        btnFilter.setNextFocusLeftId(R.id.btnSearch);
        btnFilter.setNextFocusRightId(R.id.btnSettings);
        btnSettings.setNextFocusLeftId(R.id.btnFilter);
        btnFilter.setOnClickListener(v -> openFilterDialog());

        btnPrev.setNextFocusRightId(R.id.btnNext);
        btnNext.setNextFocusLeftId(R.id.btnPrev);

        grid.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        grid.setHasFixedSize(true);
        settingsGrid.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        settingsGrid.setHasFixedSize(true);

        localAdapter = new WallpaperAdapter(this, this::apply);
        catalogAdapter = new CatalogAdapter(this, this::openPreview);
        grid.setAdapter(catalogAdapter);
        settingsGrid.setAdapter(localAdapter);

        catalogUrl = Prefs.catalogUrl(this);
        try {
            demoCatalog = Catalog.parse(readDemo());
        } catch (CatalogException ignored) {
        }

        btnSearch.setOnClickListener(v -> openSearchDialog());
        btnSettings.setOnClickListener(v -> setSettingsOpen(!settingsOpen));
        btnPrev.setOnClickListener(v -> previousPage());
        btnNext.setOnClickListener(v -> nextPage());
        chipDockBand.setOnClickListener(v -> setDockStyle(Prefs.DOCK_BAND));
        chipDockFloat.setOnClickListener(v -> setDockStyle(Prefs.DOCK_FLOATING));

        TextView dreamPick = findViewById(R.id.btnDreamPick);
        TextView dreamReset = findViewById(R.id.btnDreamReset);
        TextView btnNone = findViewById(R.id.btnNone);
        TextView btnScan = findViewById(R.id.btnScan);
        TextView btnPick = findViewById(R.id.btnPick);
        chipDockBand.setNextFocusLeftId(R.id.chipDockBand);
        chipDockBand.setNextFocusRightId(R.id.chipDockFloat);
        chipDockFloat.setNextFocusLeftId(R.id.chipDockBand);
        chipDockFloat.setNextFocusRightId(R.id.chipDockFloat);
        chipDockBand.setNextFocusDownId(R.id.chipWeatherOn);
        chipDockFloat.setNextFocusDownId(R.id.chipWeatherOff);

        chipWeatherOn.setNextFocusUpId(R.id.chipDockBand);
        chipWeatherOff.setNextFocusUpId(R.id.chipDockFloat);
        chipWeatherOn.setNextFocusLeftId(R.id.chipWeatherOn);
        chipWeatherOn.setNextFocusRightId(R.id.chipWeatherOff);
        chipWeatherOff.setNextFocusLeftId(R.id.chipWeatherOn);
        chipWeatherOff.setNextFocusRightId(R.id.chipWeatherOff);
        chipWeatherOn.setNextFocusDownId(R.id.btnDreamPick);
        chipWeatherOff.setNextFocusDownId(R.id.btnDreamReset);

        dreamPick.setNextFocusUpId(R.id.chipWeatherOn);
        dreamReset.setNextFocusUpId(R.id.chipWeatherOff);
        dreamPick.setNextFocusLeftId(R.id.btnDreamPick);
        dreamPick.setNextFocusRightId(R.id.btnDreamReset);
        dreamReset.setNextFocusLeftId(R.id.btnDreamPick);
        dreamReset.setNextFocusRightId(R.id.btnDreamReset);
        dreamPick.setNextFocusDownId(R.id.btnNone);
        dreamReset.setNextFocusDownId(R.id.btnScan);

        btnNone.setNextFocusUpId(R.id.btnDreamPick);
        btnScan.setNextFocusUpId(R.id.btnDreamPick);
        btnPick.setNextFocusUpId(R.id.btnDreamReset);
        btnNone.setNextFocusLeftId(R.id.btnNone);
        btnNone.setNextFocusRightId(R.id.btnScan);
        btnScan.setNextFocusLeftId(R.id.btnNone);
        btnScan.setNextFocusRightId(R.id.btnPick);
        btnPick.setNextFocusLeftId(R.id.btnScan);
        btnPick.setNextFocusRightId(R.id.btnPick);
        btnNone.setNextFocusDownId(R.id.settingsGrid);
        btnScan.setNextFocusDownId(R.id.settingsGrid);
        btnPick.setNextFocusDownId(R.id.settingsGrid);
        settingsGrid.setNextFocusUpId(R.id.btnNone);

        btnNone.setOnClickListener(v -> clear());
        btnScan.setOnClickListener(v -> {
            focusLocalAfterScan = true;
            scan();
        });
        btnPick.setOnClickListener(v -> pickFile());
        dreamPick.setOnClickListener(v -> pickDreamPhoto());
        dreamReset.setOnClickListener(v -> useWallpaperForDream());

        String savedCat = Prefs.panelCategory(this);
        if (RemoteRepository.CAT_FAVORITES.equals(savedCat)) {
            mode = MODE_FAVORITES;
            activeCat = RemoteRepository.CAT_FAVORITES;
        } else {
            mode = MODE_REMOTE;
            activeCat = RemoteRepository.CAT_ALL.equals(savedCat) ? null : savedCat;
        }
        query = Prefs.panelQuery(this);
        remotePage = Prefs.panelPage(this);
        restoreWallpaperId = Prefs.panelWallpaperId(this);

        buildChips();
        applyThemeUi();

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
        scan();

        boolean openThemesDirectly = getIntent().getBooleanExtra(EXTRA_FOCUS_THEMES, false);
        if (openThemesDirectly) {
            setSettingsOpen(true);
        }
        if (MODE_FAVORITES.equals(mode)) {
            loadFavorites(!openThemesDirectly);
        } else {
            restoreRemote(!openThemesDirectly);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        theme = LauncherTheme.byId(Prefs.theme(this));
        applyThemeUi();
        localAdapter.notifyDataSetChanged();

        if (MODE_FAVORITES.equals(mode)) {
            loadFavorites(false);
        } else if (!MODE_LOCAL.equals(mode)) {
            catalogAdapter.notifyDataSetChanged();
        }

        final String pendingTheme = pendingThemeId;
        final int pendingPos = pendingWallpaperPos;
        pendingThemeId = null;
        pendingWallpaperPos = -1;
        if (pendingTheme != null) {
            focusThemeCard(pendingTheme);
        } else if (settingsOpen) {
            View current = getCurrentFocus();
            if (current == null || current == btnSettings) {
                focusThemeCard(Prefs.theme(this));
            }
        } else if (pendingPos >= 0) {
            grid.post(() -> focusGridItem(pendingPos));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && settingsOpen) {
                setSettingsOpen(false);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                View focused = getCurrentFocus();
                RecyclerView.ViewHolder holder = focused == null
                        ? null : grid.findContainingViewHolder(focused);
                if (holder != null) {
                    int position = holder.getBindingAdapterPosition();
                    int span = grid.getLayoutManager() instanceof GridLayoutManager
                            ? ((GridLayoutManager) grid.getLayoutManager()).getSpanCount() : 1;
                    int target = position + (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                            ? span : -span);
                    RecyclerView.Adapter<?> adapter = grid.getAdapter();
                    if (position != RecyclerView.NO_POSITION && adapter != null
                            && target >= 0 && target < adapter.getItemCount()) {
                        focusGridItem(target);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(remoteSearch);
        if (remoteToken != null) remoteToken.cancel();
        scanExec.shutdownNow();
        catalogExec.shutdownNow();
    }

    // ---- Themes and interface --------------------------------------------------------------------

    private void applyThemeUi() {
        findViewById(R.id.personalizeRoot).setBackgroundColor(theme.screenBg);
        ((TextView) findViewById(R.id.title)).setTextColor(theme.textPrimary);
        for (int id : new int[]{R.id.labelThemes, R.id.labelInterface, R.id.labelWeather, R.id.labelDream,
                R.id.labelWallpapers}) {
            ((TextView) findViewById(id)).setTextColor(theme.textDim);
        }
        for (int id : new int[]{R.id.btnNone, R.id.btnScan, R.id.btnPick, R.id.btnDreamPick,
                R.id.btnDreamReset, R.id.chipDockBand, R.id.chipDockFloat, R.id.chipWeatherOn, R.id.chipWeatherOff, R.id.btnSettings,
                R.id.btnSearch, R.id.btnFilter, R.id.btnPrev, R.id.btnNext}) {
            View v = findViewById(id);
            if (v != null) ThemeKit.applyPill(v, theme);
        }
        status.setTextColor(theme.textDim);
        settingsStatus.setTextColor(theme.textDim);
        pageLabel.setTextColor(theme.textDim);
        localAdapter.setTheme(theme);
        catalogAdapter.setTheme(theme);
        updateDockChips();
        updateWeatherChips();
        updateChipStyles();
        updateActiveSearchChip();
        buildThemeRail();
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        catalogPanel.setVisibility(open ? View.GONE : View.VISIBLE);
        settingsPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(open ? View.GONE : View.VISIBLE);
        btnFilter.setVisibility(open ? View.GONE : View.VISIBLE);
        btnSearch.setFocusable(!open);
        btnFilter.setFocusable(!open);
        updateActiveSearchChip();
        btnSettings.setImageResource(open ? R.drawable.ic_image : R.drawable.ic_settings);
        btnSettings.setContentDescription(getString(open ? R.string.personalize_wallpapers : R.string.settings));
        if (open) {
            btnSettings.setNextFocusLeftId(R.id.btnSettings);
            btnSettings.setNextFocusRightId(R.id.btnSettings);
            if (MODE_LOCAL.equals(mode)) mode = MODE_REMOTE;
            settingsStatus.setText(localEmpty ? getString(R.string.wallpaper_empty) : "");
            if (themeRail.getChildCount() > 0) {
                String targetTheme = resolveInitialThemeFocusId(pendingThemeId, Prefs.theme(this));
                View targetCard = findThemeCardView(targetTheme);
                if (targetCard != null) btnSettings.setNextFocusDownId(targetCard.getId());
                focusThemeCard(targetTheme);
            } else {
                btnSettings.setNextFocusDownId(R.id.chipDockBand);
                chipDockBand.requestFocus();
            }
        } else {
            btnSettings.setNextFocusLeftId(R.id.btnFilter);
            btnSettings.setNextFocusRightId(R.id.btnSettings);
            if (catRow.getChildCount() > 0) {
                btnSettings.setNextFocusDownId(catRow.getChildAt(0).getId());
            }
            if (grid.getChildCount() > 0) focusGridItem(0);
            else if (catRow.getChildCount() > 0) catRow.getChildAt(0).requestFocus();
        }
    }

    static String resolveInitialThemeFocusId(String pendingThemeId, String activeThemeId) {
        if (pendingThemeId != null && !pendingThemeId.trim().isEmpty()) {
            return LauncherTheme.byId(pendingThemeId.trim()).id;
        }
        return LauncherTheme.byId(activeThemeId).id;
    }

    static boolean shouldApplySearchQuery(String newRawQuery, String currentQuery, String loadedQuery) {
        String normalized = newRawQuery == null ? "" : newRawQuery.trim();
        String current = currentQuery == null ? "" : currentQuery.trim();
        String loaded = loadedQuery == null ? "" : loadedQuery.trim();
        return !normalized.equals(current) || !normalized.equals(loaded);
    }

    private void updateActiveSearchChip() {
        if (chipActiveSearch != null) {
            boolean show = !settingsOpen && query != null && !query.isEmpty();
            chipActiveSearch.setVisibility(show ? View.VISIBLE : View.GONE);
            chipActiveSearch.setFocusable(show);
            if (show) {
                chipActiveSearch.setText(getString(R.string.search_active_chip, query));
                ThemeKit.applySelectableChip(chipActiveSearch, theme, true);
                chipActiveSearch.setNextFocusRightId(R.id.btnSearch);
                chipActiveSearch.setNextFocusLeftId(R.id.chipActiveSearch);
                btnSearch.setNextFocusLeftId(R.id.chipActiveSearch);
                if (catRow.getChildCount() > 0) {
                    chipActiveSearch.setNextFocusDownId(catRow.getChildAt(0).getId());
                }
            } else {
                btnSearch.setNextFocusLeftId(R.id.btnSearch);
            }
        }
        boolean filterActive = !RemoteRepository.QUALITY_ALL.equalsIgnoreCase(selectedQuality)
                || !RemoteRepository.PROVIDER_ALL.equalsIgnoreCase(selectedProvider)
                || !RemoteRepository.SORT_RELEVANCE.equalsIgnoreCase(selectedSort);
        if (btnFilter != null) {
            btnFilter.setBackground(filterActive
                    ? ThemeKit.chipSelected(this, theme)
                    : ThemeKit.pillBackground(this, theme));
            btnFilter.setImageTintList(filterActive
                    ? ThemeKit.chipSelectedTextColors(theme)
                    : ThemeKit.pillTextColors(theme));
        }
    }

    private void clearActiveSearch() {
        if (query.isEmpty()) return;
        applyQuery("");
        if (grid.getChildCount() > 0) {
            focusGridItem(0);
        } else {
            restoreSearchFocus();
        }
    }

    private void setWeatherEnabled(boolean enabled) {
        WeatherStore.setEnabled(this, enabled);
        updateWeatherChips();
        Toast.makeText(this, enabled ? "Clima activado" : "Clima desactivado", Toast.LENGTH_SHORT).show();
    }

    private void updateWeatherChips() {
        if (chipWeatherOn == null || chipWeatherOff == null) return;
        boolean enabled = WeatherStore.isEnabled(this);
        applyChipSelected(chipWeatherOn, enabled);
        applyChipSelected(chipWeatherOff, !enabled);
        if (weatherAttribution != null) weatherAttribution.setTextColor(theme.textDim);
    }
    private void setDockStyle(String style) {
        Prefs.setDockStyle(this, style);
        updateDockChips();
        updateWeatherChips();
        buildThemeRail();
        Toast.makeText(this, R.string.dock_style_applied, Toast.LENGTH_SHORT).show();
    }

    private void updateDockChips() {
        boolean band = Prefs.DOCK_BAND.equals(Prefs.dockStyle(this));
        applyChipSelected(chipDockBand, band);
        applyChipSelected(chipDockFloat, !band);
    }

    private void applyChipSelected(TextView v, boolean selected) {
        ThemeKit.applySelectableChip(v, theme, selected);
    }

    private void buildThemeRail() {
        View prevFocus = getCurrentFocus();
        String focusedThemeTag = (prevFocus != null && prevFocus.getTag() instanceof String)
                ? (String) prevFocus.getTag() : null;
        themeRail.removeAllViews();
        boolean band = Prefs.DOCK_BAND.equals(Prefs.dockStyle(this));
        String active = Prefs.theme(this);
        final int radiusPx = getResources().getDimensionPixelSize(R.dimen.tile_radius);
        List<LauncherTheme> allThemes = LauncherTheme.all();
        View[] cards = new View[allThemes.size()];
        for (int i = 0; i < allThemes.size(); i++) {
            LauncherTheme t = allThemes.get(i);
            View item = getLayoutInflater().inflate(R.layout.item_theme, themeRail, false);
            ThemeCardView mock = item.findViewById(R.id.themeMock);
            mock.setTheme(t, band);
            TextView name = item.findViewById(R.id.themeName);
            name.setText(t.nameRes);
            name.setTextColor(t.textPrimary);
            ImageView badge = item.findViewById(R.id.themeBadge);
            badge.setVisibility(t.id.equals(active) ? View.VISIBLE : View.GONE);
            View card = item.findViewById(R.id.themeCard);
            card.setId(View.generateViewId());
            ThemeKit.applyCard(card, theme, radiusPx);
            card.setNextFocusDownId(R.id.chipDockBand);
            card.setNextFocusUpId(R.id.btnSettings);
            card.setTag(t.id);
            card.setOnClickListener(v -> openThemePreview(t.id));
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? 1.045f : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 130 : 110)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (themeRail.getChildCount() > 0) lp.setMarginStart(dp(8));
            themeRail.addView(item, lp);
            cards[i] = card;
        }
        for (int i = 0; i < cards.length; i++) {
            View c = cards[i];
            c.setNextFocusLeftId(i > 0 ? cards[i - 1].getId() : c.getId());
            c.setNextFocusRightId(i < cards.length - 1 ? cards[i + 1].getId() : c.getId());
        }
        if (cards.length > 0) {
            int activeCardId = cards[0].getId();
            for (View c : cards) {
                if (active.equals(c.getTag())) {
                    activeCardId = c.getId();
                    break;
                }
            }
            chipDockBand.setNextFocusUpId(activeCardId);
            chipDockFloat.setNextFocusUpId(activeCardId);
            if (settingsOpen) {
                btnSettings.setNextFocusDownId(activeCardId);
                if (focusedThemeTag != null) {
                    focusThemeCard(focusedThemeTag);
                }
            }
        }
    }

    private void openThemePreview(String themeId) {
        pendingThemeId = themeId;
        startActivity(new Intent(this, ThemePreviewActivity.class).putExtra("theme", themeId));
    }

    // ---- Catalogue and category chips ------------------------------------------------------------

    private void buildChips() {
        catRow.removeAllViews();
        addChip(getString(R.string.cat_favorites), RemoteRepository.CAT_FAVORITES);
        addChip(getString(R.string.cat_all), RemoteRepository.CAT_ALL);
        addChip(getString(R.string.cat_anime), RemoteRepository.CAT_ANIME);
        addChip(getString(R.string.cat_dragonball), RemoteRepository.CAT_DRAGONBALL);
        addChip(getString(R.string.cat_naruto), RemoteRepository.CAT_NARUTO);
        addChip(getString(R.string.cat_onepiece), RemoteRepository.CAT_ONEPIECE);
        addChip(getString(R.string.cat_games), RemoteRepository.CAT_GAMES);
        addChip(getString(R.string.cat_cyberpunk), RemoteRepository.CAT_CYBERPUNK);
        addChip(getString(R.string.cat_fantasy), RemoteRepository.CAT_FANTASY);
        addChip(getString(R.string.cat_nature), RemoteRepository.CAT_NATURE);
        addChip(getString(R.string.cat_cinema), RemoteRepository.CAT_CINEMA);
        addChip(getString(R.string.cat_scifi), RemoteRepository.CAT_SCIFI);
        addChip(getString(R.string.cat_architecture), RemoteRepository.CAT_ARCHITECTURE);
        addChip(getString(R.string.cat_space), RemoteRepository.CAT_SPACE);

        if (catRow.getChildCount() > 0) {
            int first = catRow.getChildAt(0).getId();
            btnSearch.setNextFocusDownId(first);
            btnSettings.setNextFocusDownId(first);
            grid.setNextFocusUpId(first);
        }
        updateChipStyles();
    }

    private void addChip(String label, String tag) {
        TextView chip = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setTag(tag);
        chip.setOnClickListener(v -> selectChip((String) v.getTag()));
        chip.setNextFocusDownId(R.id.grid);
        chip.setNextFocusUpId(R.id.btnSearch);
        ThemeKit.applyPill(chip, theme);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                getResources().getDimensionPixelSize(R.dimen.pill_height));
        lp.setMarginStart(catRow.getChildCount() == 0 ? 0 : dp(10));
        catRow.addView(chip, lp);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void focusGridItem(int position) {
        RecyclerView.Adapter<?> a = grid.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, a.getItemCount() - 1));
        if (tryFocusGridItem(pos)) return;
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!tryFocusGridItem(pos)) grid.postDelayed(() -> tryFocusGridItem(pos), 250);
            }
        });
        grid.scrollToPosition(pos);
    }

    private boolean tryFocusGridItem(int pos) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh == null) return false;
        View card = vh.itemView.findViewById(R.id.card);
        return card != null && card.requestFocus();
    }

    private void focusSettingsItem(int position) {
        RecyclerView.Adapter<?> a = settingsGrid.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, a.getItemCount() - 1));
        settingsGrid.scrollToPosition(pos);
        settingsGrid.post(() -> {
            RecyclerView.ViewHolder vh = settingsGrid.findViewHolderForAdapterPosition(pos);
            View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
            if (card != null) card.requestFocus();
        });
    }

    private View findThemeCardView(String themeId) {
        String target = resolveInitialThemeFocusId(themeId, Prefs.theme(this));
        View fallback = null;
        for (int i = 0; i < themeRail.getChildCount(); i++) {
            View card = themeRail.getChildAt(i).findViewById(R.id.themeCard);
            if (card == null) continue;
            if (fallback == null) fallback = card;
            if (target.equals(card.getTag())) return card;
        }
        return fallback;
    }

    private void focusThemeCard(final String themeId) {
        if (tryFocusThemeCard(themeId)) return;
        themeRail.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                themeRail.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!tryFocusThemeCard(themeId)) {
                    themeRail.postDelayed(() -> tryFocusThemeCard(themeId), 80);
                }
            }
        });
        themeRail.post(() -> tryFocusThemeCard(themeId));
    }

    private boolean tryFocusThemeCard(String themeId) {
        if (!settingsOpen || settingsPanel.getVisibility() != View.VISIBLE) return false;
        View card = findThemeCardView(themeId);
        if (card == null) return false;
        chipDockBand.setNextFocusUpId(card.getId());
        chipDockFloat.setNextFocusUpId(card.getId());
        btnSettings.setNextFocusDownId(card.getId());
        return card.requestFocus();
    }

    private void selectChip(String tag) {
        main.removeCallbacks(remoteSearch);
        if (RemoteRepository.CAT_FAVORITES.equals(tag)) {
            mode = MODE_FAVORITES;
            activeCat = RemoteRepository.CAT_FAVORITES;
            remotePage = 1;
            updateChipStyles();
            loadFavorites(true);
            return;
        }
        mode = MODE_REMOTE;
        activeCat = RemoteRepository.CAT_ALL.equals(tag) ? null : tag;
        remotePage = 1;
        updateChipStyles();
        loadRemote(true);
    }

    private void updateChipStyles() {
        for (int i = 0; i < catRow.getChildCount(); i++) {
            TextView chip = (TextView) catRow.getChildAt(i);
            String tag = (String) chip.getTag();
            boolean selected;
            if (RemoteRepository.CAT_FAVORITES.equals(tag)) {
                selected = MODE_FAVORITES.equals(mode);
            } else if (RemoteRepository.CAT_ALL.equals(tag)) {
                selected = MODE_REMOTE.equals(mode) && activeCat == null;
            } else {
                selected = MODE_REMOTE.equals(mode) && tag.equals(activeCat);
            }
            applyChipSelected(chip, selected);
        }
    }

    private String categoryLabel(String cat) {
        if (cat == null || RemoteRepository.CAT_ALL.equals(cat)) return getString(R.string.cat_all);
        if (RemoteRepository.CAT_FAVORITES.equals(cat)) return getString(R.string.cat_favorites);
        if (RemoteRepository.CAT_ANIME.equals(cat)) return getString(R.string.cat_anime);
        if (RemoteRepository.CAT_DRAGONBALL.equals(cat)) return getString(R.string.cat_dragonball);
        if (RemoteRepository.CAT_NARUTO.equals(cat)) return getString(R.string.cat_naruto);
        if (RemoteRepository.CAT_ONEPIECE.equals(cat)) return getString(R.string.cat_onepiece);
        if (RemoteRepository.CAT_GAMES.equals(cat)) return getString(R.string.cat_games);
        if (RemoteRepository.CAT_CYBERPUNK.equals(cat)) return getString(R.string.cat_cyberpunk);
        if (RemoteRepository.CAT_FANTASY.equals(cat)) return getString(R.string.cat_fantasy);
        if (RemoteRepository.CAT_NATURE.equals(cat)) return getString(R.string.cat_nature);
        if (RemoteRepository.CAT_CINEMA.equals(cat)) return getString(R.string.cat_cinema);
        if (RemoteRepository.CAT_SCIFI.equals(cat)) return getString(R.string.cat_scifi);
        if (RemoteRepository.CAT_ARCHITECTURE.equals(cat)) return getString(R.string.cat_architecture);
        if (RemoteRepository.CAT_SPACE.equals(cat)) return getString(R.string.cat_space);
        return cat;
    }


    private void openFilterDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(24);
        int padV = dp(20);
        panel.setPadding(padH, padV, padH, padV);
        panel.setBackground(ThemeKit.dialogSurface(this, theme));

        TextView title = new TextView(this);
        title.setText(R.string.filter_title);
        title.setTextColor(theme.textPrimary);
        title.setTextSize(20f);
        title.setTypeface(getResources().getFont(R.font.outfit));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addFilterChipRow(panel, getString(R.string.filter_quality),
                new String[]{getString(R.string.filter_quality_all), getString(R.string.filter_quality_2k), getString(R.string.filter_quality_4k)},
                new String[]{RemoteRepository.QUALITY_ALL, RemoteRepository.QUALITY_2K, RemoteRepository.QUALITY_4K},
                selectedQuality,
                val -> {
                    selectedQuality = val;
                    Prefs.setPanelQuality(this, selectedQuality);
                    updateActiveSearchChip();
                    remotePage = 1;
                    loadRemote(false);
                });

        addFilterChipRow(panel, getString(R.string.filter_provider),
                new String[]{getString(R.string.filter_provider_all), getString(R.string.filter_provider_wallhaven), getString(R.string.filter_provider_commons)},
                new String[]{RemoteRepository.PROVIDER_ALL, RemoteRepository.PROVIDER_WALLHAVEN, RemoteRepository.PROVIDER_COMMONS},
                selectedProvider,
                val -> {
                    selectedProvider = val;
                    Prefs.setPanelProvider(this, selectedProvider);
                    updateActiveSearchChip();
                    remotePage = 1;
                    loadRemote(false);
                });

        addFilterChipRow(panel, getString(R.string.filter_sort),
                new String[]{getString(R.string.filter_sort_relevance), getString(R.string.filter_sort_date), getString(R.string.filter_sort_top)},
                new String[]{RemoteRepository.SORT_RELEVANCE, RemoteRepository.SORT_DATE, RemoteRepository.SORT_TOP},
                selectedSort,
                val -> {
                    selectedSort = val;
                    Prefs.setPanelSort(this, selectedSort);
                    updateActiveSearchChip();
                    remotePage = 1;
                    loadRemote(false);
                });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(18);

        TextView btnReset = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        btnReset.setId(View.generateViewId());
        btnReset.setText(R.string.filter_reset);
        ThemeKit.applyPill(btnReset, theme);
        btnReset.setOnClickListener(v -> {
            selectedQuality = RemoteRepository.QUALITY_ALL;
            selectedProvider = RemoteRepository.PROVIDER_ALL;
            selectedSort = RemoteRepository.SORT_RELEVANCE;
            Prefs.setPanelQuality(this, selectedQuality);
            Prefs.setPanelProvider(this, selectedProvider);
            Prefs.setPanelSort(this, selectedSort);
            updateActiveSearchChip();
            dialog.dismiss();
            remotePage = 1;
            loadRemote(true);
        });

        TextView btnClose = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        btnClose.setId(View.generateViewId());
        btnClose.setText(R.string.search_done);
        ThemeKit.applyPill(btnClose, theme);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnReset.setNextFocusRightId(btnClose.getId());
        btnClose.setNextFocusLeftId(btnReset.getId());

        int pillH = getResources().getDimensionPixelSize(R.dimen.pill_height);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
        closeLp.setMarginStart(dp(10));
        actions.addView(btnReset, resetLp);
        actions.addView(btnClose, closeLp);
        panel.addView(actions, actionsLp);

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        dialog.setOnDismissListener(d -> {
            btnFilter.post(() -> {
                if (!btnFilter.hasFocus() && !isFinishing() && !isDestroyed()) {
                    btnFilter.requestFocus();
                }
            });
        });
        dialog.show();
    }

    private interface FilterSelectListener {
        void onSelect(String value);
    }

    private void addFilterChipRow(LinearLayout parent, String labelText, String[] labels,
                                  String[] values, String currentVal, FilterSelectListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(12);

        TextView label = new TextView(new ContextThemeWrapper(this, R.style.SectionLabel));
        label.setText(labelText);
        label.setTextColor(theme.textDim);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                dp(130), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(label, labelLp);

        final TextView[] chips = new TextView[labels.length];
        int pillH = getResources().getDimensionPixelSize(R.dimen.pill_height);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView chip = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
            chip.setId(View.generateViewId());
            chip.setText(labels[i]);
            boolean isSelected = values[i].equalsIgnoreCase(currentVal);
            ThemeKit.applySelectableChip(chip, theme, isSelected);
            chip.setOnClickListener(v -> {
                for (int j = 0; j < chips.length; j++) {
                    ThemeKit.applySelectableChip(chips[j], theme, j == idx);
                }
                listener.onSelect(values[idx]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
            if (i > 0) lp.setMarginStart(dp(8));
            row.addView(chip, lp);
            chips[i] = chip;
        }
        for (int i = 0; i < chips.length; i++) {
            chips[i].setNextFocusLeftId(i > 0 ? chips[i - 1].getId() : chips[i].getId());
            chips[i].setNextFocusRightId(i < chips.length - 1 ? chips[i + 1].getId() : chips[i].getId());
        }
        parent.addView(row, rowLp);
    }

    // ---- Search dialog (top-anchored, IME-safe, high-contrast) -----------------------------------

    private void openSearchDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(24);
        int padV = dp(18);
        panel.setPadding(padH, padV, padH, padV);
        panel.setBackground(ThemeKit.dialogSurface(this, theme));

        TextView title = new TextView(this);
        title.setText(R.string.search_title);
        title.setTextColor(theme.textPrimary);
        title.setTextSize(18f);
        title.setTypeface(getResources().getFont(R.font.outfit));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText input = new EditText(this);
        input.setId(View.generateViewId());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSingleLine(true);
        input.setTextSize(16f);
        input.setTypeface(getResources().getFont(R.font.outfit));
        input.setHint(R.string.search_hint);
        ThemeKit.applyTextField(input, theme);
        input.setText(query);
        if (!query.isEmpty()) input.setSelection(query.length());

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        inputLp.topMargin = dp(10);
        panel.addView(input, inputLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(12);

        final TextView btnClear = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        btnClear.setId(View.generateViewId());
        btnClear.setText(R.string.search_clear);
        ThemeKit.applyPill(btnClear, theme);

        final TextView btnCancel = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        btnCancel.setId(View.generateViewId());
        btnCancel.setText(R.string.search_cancel);
        ThemeKit.applyPill(btnCancel, theme);

        final TextView btnDone = new TextView(new ContextThemeWrapper(this, R.style.TopButton));
        btnDone.setId(View.generateViewId());
        btnDone.setText(R.string.search_done);
        ThemeKit.applySelectableChip(btnDone, theme, true);

        int pillH = getResources().getDimensionPixelSize(R.dimen.pill_height);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
        cancelLp.setMarginStart(dp(10));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH);
        doneLp.setMarginStart(dp(10));

        actions.addView(btnClear, clearLp);
        actions.addView(btnCancel, cancelLp);
        actions.addView(btnDone, doneLp);
        panel.addView(actions, actionsLp);

        input.setNextFocusDownId(btnDone.getId());
        btnClear.setNextFocusUpId(input.getId());
        btnCancel.setNextFocusUpId(input.getId());
        btnDone.setNextFocusUpId(input.getId());
        btnClear.setNextFocusLeftId(btnClear.getId());
        btnClear.setNextFocusRightId(btnCancel.getId());
        btnCancel.setNextFocusLeftId(btnClear.getId());
        btnCancel.setNextFocusRightId(btnDone.getId());
        btnDone.setNextFocusLeftId(btnCancel.getId());
        btnDone.setNextFocusRightId(btnDone.getId());

        btnDone.setOnClickListener(v -> {
            commitQueryImmediate(input.getText().toString());
            dialog.dismiss();
            focusAfterSearchCommit();
        });
        btnClear.setOnClickListener(v -> {
            input.setText("");
            commitQueryImmediate("");
            dialog.dismiss();
            restoreSearchFocus();
        });
        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            restoreSearchFocus();
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyQuery(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                commitQueryImmediate(input.getText().toString());
                dialog.dismiss();
                focusAfterSearchCommit();
                return true;
            }
            return false;
        });

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams wlp = w.getAttributes();
            wlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            wlp.y = dp(28);
            wlp.width = dp(660);
            wlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            w.setAttributes(wlp);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        dialog.show();
        input.requestFocus();
    }

    private void commitQueryImmediate(String rawQuery) {
        String normalized = rawQuery == null ? "" : rawQuery.trim();
        main.removeCallbacks(remoteSearch);
        boolean needsFetch = shouldApplySearchQuery(normalized, query, loadedQuery);
        query = normalized;
        updateActiveSearchChip();
        if (!needsFetch) {
            updateChipStyles();
            return;
        }
        if (MODE_REMOTE.equals(mode)) {
            updateChipStyles();
            loadRemote(false);
        } else if (MODE_FAVORITES.equals(mode)) {
            updateChipStyles();
            loadFavorites(false);
        } else if (MODE_CATALOG.equals(mode)) {
            showCatalog();
        } else {
            updateChipStyles();
        }
    }

    private void focusAfterSearchCommit() {
        grid.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (catalogAdapter.getItemCount() > 0) {
                focusGridItem(0);
            } else if (chipActiveSearch != null && chipActiveSearch.getVisibility() == View.VISIBLE) {
                chipActiveSearch.requestFocus();
            } else {
                restoreSearchFocus();
            }
        });
    }

    private void applyQuery(String q) {
        String normalized = q == null ? "" : q.trim();
        if (normalized.equals(query)) return;
        query = normalized;
        updateActiveSearchChip();
        if (MODE_REMOTE.equals(mode)) {
            updateChipStyles();
            main.removeCallbacks(remoteSearch);
            main.postDelayed(remoteSearch, SEARCH_DEBOUNCE_MS);
        } else if (MODE_FAVORITES.equals(mode)) {
            updateChipStyles();
            main.removeCallbacks(remoteSearch);
            main.postDelayed(remoteSearch, SEARCH_DEBOUNCE_MS);
        } else if (MODE_CATALOG.equals(mode)) {
            showCatalog();
        } else {
            updateChipStyles();
        }
    }

    private void restoreSearchFocus() {
        btnSearch.post(() -> {
            if (!btnSearch.hasFocus() && !isFinishing() && !isDestroyed()) btnSearch.requestFocus();
        });
    }

    // ---- Favorites view --------------------------------------------------------------------------

    private void loadFavorites(boolean focusGrid) {
        mode = MODE_FAVORITES;
        loadedQuery = query;
        status.setText(R.string.remote_loading);
        catalogExec.execute(() -> {
            List<Catalog.Wallpaper> all = WallpaperStore.listFavorites(this);
            if (!query.isEmpty()) {
                String qLower = query.toLowerCase(Locale.ROOT);
                List<Catalog.Wallpaper> filtered = new ArrayList<>();
                for (Catalog.Wallpaper w : all) {
                    if (w.title.toLowerCase(Locale.ROOT).contains(qLower)
                            || w.category.toLowerCase(Locale.ROOT).contains(qLower)) {
                        filtered.add(w);
                    }
                }
                all = filtered;
            }
            final List<Catalog.Wallpaper> favs = all;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                remoteLoading = false;
                remoteItems.clear();
                remoteItems.addAll(favs);
                remoteHasMore = false;
                renderFavoritesPage(focusGrid);
            });
        });
    }

    private void renderFavoritesPage(boolean focusGrid) {
        int maxPage = Math.max(1, (remoteItems.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (remotePage > maxPage) remotePage = maxPage;
        int start = (remotePage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, remoteItems.size());
        List<Catalog.Wallpaper> page = start < end
                ? new ArrayList<>(remoteItems.subList(start, end))
                : Collections.emptyList();
        catalogAdapter.setItems(page);

        if (remoteItems.isEmpty()) {
            status.setText(query.isEmpty() ? getString(R.string.favorites_empty)
                    : getString(R.string.search_empty, query));
        } else if (!query.isEmpty()) {
            status.setText(getResources().getQuantityString(
                    R.plurals.search_results, remoteItems.size(), remoteItems.size(), query));
        } else {
            status.setText(getString(R.string.catalog_count, remoteItems.size())
                    + " · " + getString(R.string.cat_favorites));
        }
        updateChipStyles();
        updatePager();
        if (focusGrid) focusGridItem(0);
        savePanelPosition(page.isEmpty() ? "" : page.get(0).id);
    }

    // ---- Remote wallpapers and API pagination ----------------------------------------------------

    private void loadRemote(boolean focusGrid) {
        if (!MODE_REMOTE.equals(mode)) return;
        remotePage = 1;
        restoreWallpaperId = "";
        restoreRemote(focusGrid);
    }

    private void restoreRemote(boolean focusGrid) {
        CancelToken token = new CancelToken();
        if (remoteToken != null) remoteToken.cancel();
        remoteToken = token;
        mode = MODE_REMOTE;
        remoteItems.clear();
        remoteHasMore = true;
        remoteLoading = true;
        pendingNextPage = false;
        emptyFetchStreak = 0;
        remoteSources = "";
        loadedCat = activeCat == null ? RemoteRepository.CAT_ALL : activeCat;
        loadedQuery = query;
        final String cat = loadedCat;
        final String q = loadedQuery;
        final String label = categoryLabel(cat);
        final int target = Math.max(1, remotePage);
        final String restoreId = restoreWallpaperId;
        restoreWallpaperId = "";
        status.setText(R.string.remote_loading);
        catalogAdapter.setItems(Collections.emptyList());
        updatePager();

        catalogExec.execute(() -> {
            List<Catalog.Wallpaper> all = new ArrayList<>();
            String sources = "";
            int apiPage = 1;
            boolean more = true;
            int empty = 0;
            Throwable error = null;
            while (apiPage <= MAX_RESTORE_PAGES && all.size() < target * PAGE_SIZE && more) {
                if (token.isCancelled()) return;
                try {
                    RemoteRepository.Result result =
                            remoteRepo.search(cat, label, q, apiPage, selectedProvider, selectedQuality, selectedSort, token);
                    if (!result.sources.isEmpty()) sources = result.sources;
                    more = result.hasMore;
                    if (result.items.isEmpty()) empty++;
                    else empty = 0;
                    appendUnique(all, result.items);
                } catch (CatalogException e) {
                    if (token.isCancelled()) return;
                    error = e;
                    more = false;
                }
                apiPage++;
                if (empty >= 3) break;
            }
            final List<Catalog.Wallpaper> loaded = all;
            final String foundSources = sources;
            final int nextApiPage = apiPage;
            final boolean canContinue = more;
            final Throwable failure = error;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || token != remoteToken) return;
                remoteLoading = false;
                remoteItems.clear();
                remoteItems.addAll(loaded);
                remoteSources = foundSources;
                remoteApiPage = nextApiPage;
                remoteHasMore = canContinue;
                if (remoteItems.isEmpty() && failure != null) {
                    showRemoteError(failure, cat, q, focusGrid);
                    return;
                }
                renderRemotePage(focusGrid, restoreId);
            });
        });
    }

    private void loadNextApiPage() {
        if (remoteLoading || !remoteHasMore) return;
        remoteLoading = true;
        updatePager();
        loadApiPage(remoteApiPage, false, null);
    }

    private void loadApiPage(int providerPage, boolean focusGrid, String focusId) {
        final String cat = loadedCat;
        final String q = loadedQuery;
        final String label = categoryLabel(cat);
        final CancelToken token = remoteToken;
        if (token == null) return;
        catalogExec.execute(() -> {
            try {
                RemoteRepository.Result result =
                        remoteRepo.search(cat, label, q, providerPage, selectedProvider, selectedQuality, selectedSort, token);
                if (token.isCancelled()) return;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || token != remoteToken) return;
                    remoteLoading = false;
                    if (!result.sources.isEmpty()) remoteSources = result.sources;
                    remoteHasMore = result.hasMore;
                    remoteApiPage = providerPage + 1;
                    if (result.items.isEmpty()) emptyFetchStreak++;
                    else emptyFetchStreak = 0;
                    appendUnique(remoteItems, result.items);
                    if (pendingNextPage) {
                        pendingNextPage = false;
                        if (emptyFetchStreak >= 3) remoteHasMore = false;
                        if (canAdvancePage()) {
                            remotePage++;
                            renderRemotePage(true, null);
                        } else if (remoteHasMore) {
                            pendingNextPage = true;
                            loadNextApiPage();
                        } else {
                            renderRemotePage(false, null);
                        }
                    } else {
                        renderRemotePage(focusGrid, focusId);
                    }
                });
            } catch (Exception e) {
                if (token.isCancelled()) return;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || token != remoteToken) return;
                    remoteLoading = false;
                    pendingNextPage = false;
                    if (remoteItems.isEmpty()) showRemoteError(e, cat, q, focusGrid);
                    else {
                        status.setText(remoteErrorText(e));
                        updatePager();
                    }
                });
            }
        });
    }

    private void previousPage() {
        if (remoteLoading || remotePage <= 1) return;
        remotePage--;
        if (MODE_FAVORITES.equals(mode)) {
            renderFavoritesPage(true);
        } else {
            renderRemotePage(true, null);
        }
    }

    private void nextPage() {
        if (MODE_FAVORITES.equals(mode)) {
            if (canAdvancePage()) {
                remotePage++;
                renderFavoritesPage(true);
            }
            return;
        }
        if (remoteLoading) {
            pendingNextPage = true;
            return;
        }
        if (canAdvancePage()) {
            remotePage++;
            renderRemotePage(true, null);
            return;
        }
        if (!remoteHasMore) return;
        pendingNextPage = true;
        loadNextApiPage();
    }

    private boolean canAdvancePage() {
        int nextStart = remotePage * PAGE_SIZE;
        int size = remoteItems.size();
        return size >= nextStart + 1;
    }

    private int maxLoadedPage() {
        if (remoteItems.isEmpty()) return 1;
        return Math.max(1, (remoteItems.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void renderRemotePage(boolean focusGrid, String focusId) {
        int maxPage = maxLoadedPage();
        if (remotePage > maxPage) remotePage = maxPage;
        int start = (remotePage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, remoteItems.size());
        List<Catalog.Wallpaper> page = start < end
                ? new ArrayList<>(remoteItems.subList(start, end))
                : Collections.emptyList();
        catalogAdapter.setItems(page);

        if (remoteItems.isEmpty()) {
            status.setText(query.isEmpty() ? getString(R.string.catalog_empty_cat)
                    : getString(R.string.search_empty, query));
        } else if (!query.isEmpty()) {
            status.setText(getResources().getQuantityString(
                    R.plurals.search_results, remoteItems.size(), remoteItems.size(), query));
        } else {
            String sources = remoteSources.isEmpty() ? "Wallhaven" : remoteSources;
            status.setText(getString(R.string.remote_status, remoteItems.size(), sources));
        }
        updateChipStyles();
        updatePager();
        int focusIndex = focusId == null ? -1 : catalogAdapter.positionOf(focusId);
        if (focusGrid) focusGridItem(focusIndex >= 0 ? focusIndex : 0);
        String savedId = focusIndex >= 0 ? focusId
                : (page.isEmpty() ? "" : page.get(0).id);
        savePanelPosition(savedId);
    }

    private void updatePager() {
        int maxPage = maxLoadedPage();
        btnPrev.setEnabled(!remoteLoading && remotePage > 1);
        btnPrev.setAlpha(btnPrev.isEnabled() ? 1f : 0.38f);
        boolean canAdvance = canAdvancePage() || (remoteHasMore && !MODE_FAVORITES.equals(mode));
        btnNext.setEnabled(!remoteLoading && canAdvance);
        btnNext.setAlpha(btnNext.isEnabled() ? 1f : 0.38f);
        if (remoteLoading && remoteItems.isEmpty()) {
            pageLabel.setText(R.string.page_loading);
        } else if (remoteHasMore && !MODE_FAVORITES.equals(mode)) {
            pageLabel.setText(getString(R.string.page_of_more, remotePage, maxPage));
        } else {
            pageLabel.setText(getString(R.string.page_of, remotePage, maxPage));
        }
    }

    private void savePanelPosition(String wallpaperId) {
        String catToSave = MODE_FAVORITES.equals(mode)
                ? RemoteRepository.CAT_FAVORITES
                : (activeCat == null ? RemoteRepository.CAT_ALL : activeCat);
        Prefs.setPanelLastView(this, catToSave, query, remotePage, wallpaperId);
    }

    private static void appendUnique(List<Catalog.Wallpaper> target,
                                     List<Catalog.Wallpaper> fresh) {
        if (fresh == null || fresh.isEmpty()) return;
        HashSet<String> ids = new HashSet<>();
        HashSet<String> images = new HashSet<>();
        for (Catalog.Wallpaper w : target) {
            ids.add(w.id);
            images.add(w.imageUrl.toLowerCase(Locale.ROOT));
        }
        for (Catalog.Wallpaper w : fresh) {
            if (!ids.add(w.id)) continue;
            if (!images.add(w.imageUrl.toLowerCase(Locale.ROOT))) continue;
            target.add(w);
        }
    }

    private void showRemoteError(Throwable t, String cat, String q, boolean focusGrid) {
        String message = remoteErrorText(t);
        List<Catalog.Wallpaper> fallback = localFallback(cat, q);
        if (!fallback.isEmpty()) {
            catalogAdapter.setItems(fallback.size() > PAGE_SIZE
                    ? new ArrayList<>(fallback.subList(0, PAGE_SIZE)) : fallback);
            status.setText(message + " · "
                    + getString(R.string.remote_local_fallback, fallback.size()));
            if (focusGrid) focusGridItem(0);
        } else {
            catalogAdapter.setItems(Collections.emptyList());
            status.setText(message);
        }
        pageLabel.setText("");
        btnPrev.setEnabled(false);
        btnNext.setEnabled(false);
        updateChipStyles();
    }

    private List<Catalog.Wallpaper> localFallback(String cat, String q) {
        Catalog base = catalog != null ? catalog : demoCatalog;
        if (base == null) return Collections.emptyList();
        String localCat = RemoteRepository.CAT_ALL.equals(cat) ? null : cat;
        return base.query(localCat, q);
    }

    private String remoteErrorText(Throwable t) {
        if (!(t instanceof CatalogException)) return getString(R.string.remote_err_network);
        CatalogException e = (CatalogException) t;
        switch (e.code) {
            case OFFLINE:
                return getString(R.string.remote_err_offline);
            case HTTP:
                return e.httpCode == 429
                        ? getString(R.string.remote_err_rate)
                        : getString(R.string.remote_err_http, e.httpCode);
            case FORMAT:
            case TOO_BIG:
                return getString(R.string.remote_err_format);
            case BAD_URL:
            case NOT_HTTPS:
                return getString(R.string.err_url);
            default:
                return getString(R.string.remote_err_network);
        }
    }

    private void openPreview(Catalog.Wallpaper w) {
        View focused = grid.getFocusedChild();
        int pos = focused != null ? grid.getChildAdapterPosition(focused) : -1;
        pendingWallpaperPos = pos >= 0 ? pos : catalogAdapter.positionOf(w.id);
        savePanelPosition(w.id);
        Intent i = new Intent(this, WallpaperPreviewActivity.class)
                .putExtra("id", w.id)
                .putExtra("title", w.title)
                .putExtra("category", w.category)
                .putExtra("image", w.imageUrl)
                .putExtra("thumb", w.thumbUrl)
                .putExtra("author", w.author)
                .putExtra("license", w.license)
                .putExtra("source", w.source)
                .putExtra("width", w.width)
                .putExtra("height", w.height);
        startActivity(i);
    }

    // ---- Local wallpapers and settings actions ---------------------------------------------------

    private void showCatalog() {
        if (catalog == null) return;
        List<Catalog.Wallpaper> list = catalog.query(null, query);
        catalogAdapter.setItems(list);
        if (list.isEmpty()) {
            status.setText(query.isEmpty() ? getString(R.string.catalog_empty_cat)
                    : getString(R.string.search_empty, query));
        } else if (!query.isEmpty()) {
            status.setText(getResources().getQuantityString(
                    R.plurals.search_results, list.size(), list.size(), query));
        } else if (catalog.cached) {
            status.setText(catalog.updated.isEmpty()
                    ? getString(R.string.catalog_status_cached, list.size())
                    : getString(R.string.catalog_status_cached_date, list.size(), catalog.updated));
        } else if (catalogDemo) {
            status.setText(getString(R.string.catalog_status_demo, list.size()));
        } else {
            status.setText(getString(R.string.catalog_count, list.size()));
        }
        updateChipStyles();
    }

    private String readDemo() throws CatalogException {
        try (InputStream in = getAssets().open(DEMO_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new CatalogException(CatalogException.Code.STORAGE, e.getMessage());
        }
    }

    private void apply(WallpaperAdapter.Item item) {
        String uri = item.isNone ? "" : item.uri.toString();
        Prefs.setWallpaper(this, uri);
        Prefs.setWallpaperId(this, "");
        localAdapter.setSelected(uri);
        Toast.makeText(this, item.isNone ? R.string.wallpaper_cleared : R.string.wallpaper_applied,
                Toast.LENGTH_SHORT).show();
    }

    private void clear() {
        Prefs.setWallpaper(this, "");
        Prefs.setWallpaperId(this, "");
        localAdapter.setSelected("");
        Toast.makeText(this, R.string.wallpaper_cleared, Toast.LENGTH_SHORT).show();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_PICK) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        Prefs.setWallpaper(this, uri.toString());
        Prefs.setWallpaperId(this, "");
        localAdapter.setSelected(uri.toString());
        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
    }

    private void pickDreamPhoto() {
        startActivity(new Intent(this, DreamPhotoActivity.class));
    }

    private void useWallpaperForDream() {
        Prefs.setDreamWallpaper(this, "");
        Toast.makeText(this, R.string.dream_use_wallpaper_applied, Toast.LENGTH_SHORT).show();
    }

    private void scan() {
        settingsStatus.setText(R.string.wallpaper_scanning);
        localAdapter.setItems(Collections.emptyList());
        scanExec.execute(() -> {
            List<WallpaperAdapter.Item> found = new ArrayList<>();
            found.add(WallpaperAdapter.Item.none());
            for (File root : roots()) {
                walk(root, 0, found);
                if (found.size() >= MAX_ITEMS) break;
            }
            Collections.sort(found.subList(1, found.size()),
                    (a, b) -> a.name.compareToIgnoreCase(b.name));
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                localAdapter.setItems(found);
                scanned = true;
                localEmpty = found.size() <= 1;
                settingsStatus.setText(localEmpty ? getString(R.string.wallpaper_empty) : "");
                if (focusLocalAfterScan && settingsOpen && !localEmpty) {
                    focusSettingsItem(0);
                }
                focusLocalAfterScan = false;
            });
        });
    }

    private static List<File> roots() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("enc_emulated")) continue;
                if (v.isDirectory() && v.canRead()) out.add(v);
            }
        }
        return out;
    }

    private static void walk(File dir, int depth, List<WallpaperAdapter.Item> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_ITEMS) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_ITEMS) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (name.equals("Android")) continue;
                walk(f, depth + 1, out);
            } else if (f.isFile()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (isImage(lower)) {
                    out.add(WallpaperAdapter.Item.file(f, false));
                } else if (isVideo(lower)) {
                    out.add(WallpaperAdapter.Item.file(f, true));
                }
            }
        }
    }

    private static boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".bmp");
    }

    private static boolean isVideo(String lowerName) {
        return lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") || lowerName.endsWith(".mkv")
                || lowerName.endsWith(".m4v");
    }
}
