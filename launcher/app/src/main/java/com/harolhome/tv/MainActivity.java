package com.harolhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.VideoView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home screen: clock, date and weather on top, a "continue watching" shelf, and one row of tiles
 * (favourite apps) with the quick actions underneath. Everything is reached with the
 * D-pad: header, shelf, dock and bottom bar are four rows, and Up/Down move exactly one row.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final long MINUTE_MS = 60_000L;
    /** Give the playback app time to publish its Watch Next update after Home resumes. */
    private static final long SHELF_RETURN_REFRESH_MS = 2_500L;
    private static final long WATCH_NEXT_OBSERVER_DEBOUNCE_MS = 450L;
    private static final int REQ_WATCH_NEXT = 30;
    /** When Home returns to the front: check now, then again once slower apps have finished stopping. */
    private static final long[] AUDIO_GUARD_DELAYS_MS = {0L, 700L, 2000L};

    /** Last focused dock index. Static so it survives the activity being destroyed while an app is open. */
    private static int lastDock = 0;
    private static long lastLaunchTapRealtimeMs = 0L;
    private static String lastLaunchTarget = "";

    static void markAppLaunchStart(String target, long tapMs, long dispatchMs) {
        lastLaunchTapRealtimeMs = tapMs;
        lastLaunchTarget = target != null ? target : "";
        Log.i("LaunchPerf", "event=dispatch target=" + lastLaunchTarget + " dispatchMs=" + dispatchMs);
    }

    private static final class ShelfCard {
        final View root;
        final View frame;
        final ImageView image;
        final View emptyState;
        final ImageView emptyIcon;
        final TextView emptyHint;
        final View watermark;
        final ImageView badge;
        final TextView title;
        final TextView subtitle;
        final ProgressBar progress;
        TopShelfStore.Item item;

        ShelfCard(View root) {
            this.root = root;
            View f = root.findViewById(R.id.cardFrame);
            frame = f != null ? f : root;
            image = root.findViewById(R.id.cardImage);
            emptyState = root.findViewById(R.id.cardEmptyState);
            emptyIcon = root.findViewById(R.id.cardEmptyIcon);
            emptyHint = root.findViewById(R.id.cardEmptyHint);
            watermark = null;
            badge = root.findViewById(R.id.cardBadge);
            title = root.findViewById(R.id.cardTitle);
            subtitle = root.findViewById(R.id.cardSubtitle);
            progress = root.findViewById(R.id.cardProgress);
        }
    }

    private String liveTvPkg;
    private BackgroundAudioGuard audioGuard;
    private DockBar dock;
    private static final ExecutorService TOP_SHELF_IO = Executors.newFixedThreadPool(2);
    private final ShelfCard[] shelf = new ShelfCard[2];

    private android.widget.FrameLayout root;
    private View ambient;
    private int ambientColor;
    private ValueAnimator ambientAnimator;
    private View scrimTop;
    private View scrimBottom;
    private android.widget.TextClock clock;
    private TextView ampm;
    private TextView date;
    // brandTitle removed
    private TextView topShelfLabel;
    private View btnInputs;
    private View btnSettings;
    private View btnWifi;
    private View weatherPill;
    private ImageView weatherIcon;
    private TextView weatherText;
    private TextView btnApps;
    private View btnSearch;
    private View btnPersonalize;
    private View btnThemes;
    private View btnWeather;
    private View btnSearchCircle;
    private View btnPersonalizeCircle;
    private View btnThemesCircle;
    private View btnWeatherCircle;
    private ImageView btnSearchIcon;
    private ImageView btnPersonalizeIcon;
    private ImageView btnThemesIcon;
    private TextView btnSearchLabel;
    private TextView btnPersonalizeLabel;
    private TextView btnThemesLabel;
    private TextView btnWeatherLabel;
    private ImageView btnWeatherIcon;
    private View bottomDivider1;
    private View bottomDivider2;
    private String appliedThemeId;
    private boolean appsDirty = true;
    private boolean wasStopped;
    private boolean watchNextObserverRegistered;
    private final Runnable shelfReturnRefresh = () -> updateTopShelf(true);
    private final Runnable watchNextObserverRefresh = () -> {
        Log.d(TAG, "watch-next observer debounced trigger -> updateTopShelf(true)");
        updateTopShelf(true);
    };
    private ContentObserver watchNextObserver;

    // Wallpaper layer: a still/animated image or a looping muted video, behind everything.
    private ImageView wallpaper;
    private VideoView wallpaperVideo;
    private View wallpaperDim;
    private Bitmap wallpaperBase;
    private String currentWallpaper;
    private boolean wallpaperReady;
    private String wallpaperNoticeUri;
    private String videoWallpaper;
    private boolean videoPrepared;
    private boolean mediaReceiverRegistered;
    private boolean packageReceiverRegistered;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network button when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateDate();
            updateWifiLabel();
            WeatherStore.fetch(MainActivity.this, false, MainActivity.this::updateWeatherUi);
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    private final Runnable wallpaperRecheck = () -> {
        currentWallpaper = null;
        wallpaperReady = false;
        applyWallpaper();
    };
    private final Runnable audioGuardRun = () -> audioGuard.pauseBackgroundPlayback();
    /** USB mounts/unmounts do not always produce a resume; re-evaluate the saved wallpaper. */
    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(wallpaperRecheck);
            handler.postDelayed(wallpaperRecheck, 600);
        }
    };
    /** Package changes while Home is stopped make the dock stale; rebuild it on the next resume. */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        liveTvPkg = getString(R.string.live_tv_package).trim();
        audioGuard = new BackgroundAudioGuard(this);

        root = findViewById(R.id.root);
        ambient = findViewById(R.id.ambient);
        scrimTop = findViewById(R.id.scrimTop);
        scrimBottom = findViewById(R.id.scrimBottom);
        clock = findViewById(R.id.clock);
        // ampm = findViewById(R.id.ampm);`n        ampm = null;
        date = findViewById(R.id.date);
        // brandTitle removed
        topShelfLabel = findViewById(R.id.topShelfLabel);
        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        btnWifi = findViewById(R.id.btnWifi);
        weatherPill = findViewById(R.id.weatherPill);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherText = findViewById(R.id.weatherText);
        btnApps = findViewById(R.id.btnApps);
        btnSearch = findViewById(R.id.btnSearch);
        btnPersonalize = findViewById(R.id.btnPersonalize);
        btnThemes = findViewById(R.id.btnThemes);
        btnWeather = findViewById(R.id.btnWeather);
        btnSearchCircle = findViewById(R.id.btnSearchCircle);
        btnPersonalizeCircle = findViewById(R.id.btnPersonalizeCircle);
        btnThemesCircle = findViewById(R.id.btnThemesCircle);
        btnWeatherCircle = findViewById(R.id.btnWeatherCircle);
        btnSearchIcon = findViewById(R.id.btnSearchIcon);
        btnPersonalizeIcon = findViewById(R.id.btnPersonalizeIcon);
        btnThemesIcon = findViewById(R.id.btnThemesIcon);
        btnSearchLabel = findViewById(R.id.btnSearchLabel);
        btnPersonalizeLabel = findViewById(R.id.btnPersonalizeLabel);
        btnThemesLabel = findViewById(R.id.btnThemesLabel);
        btnWeatherLabel = findViewById(R.id.btnWeatherLabel);
        btnWeatherIcon = findViewById(R.id.btnWeatherIcon);
        bottomDivider1 = findViewById(R.id.bottomDivider1);
        bottomDivider2 = findViewById(R.id.bottomDivider2);

        // ---- wallpaper layer ----
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperVideo = findViewById(R.id.wallpaperVideo);
        wallpaperDim = findViewById(R.id.wallpaperDim);
        // A muted decorative video must never take audio focus: VideoView asks for it by default,
        // which pauses or ducks the music and videos you actually want to hear.
        wallpaperVideo.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        wallpaperVideo.setOnPreparedListener(mp -> {
            if (videoWallpaper == null) return;
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            videoPrepared = true;
            wallpaperDim.setVisibility(View.VISIBLE);
            wallpaperVideo.start();
        });
        wallpaperVideo.setOnErrorListener((mp, what, extra) -> {
            wallpaperVideo.setVisibility(View.GONE);
            wallpaperDim.setVisibility(View.GONE);
            videoPrepared = false;
            String failed = videoWallpaper;
            videoWallpaper = null;
            if (failed != null && failed.equals(currentWallpaper)) {
                currentWallpaper = null;
                wallpaperReady = false;
                recoverWallpaper(failed);
            }
            return true;
        });

        // ---- continue-watching shelf ----
        LinearLayout shelfRow = findViewById(R.id.topShelfRow);
        for (int slot = 0; slot < shelf.length; slot++) {
            View card = getLayoutInflater().inflate(R.layout.item_continue_card, shelfRow, false);
            card.setId(View.generateViewId());
            shelfRow.addView(card);
            shelf[slot] = new ShelfCard(card);
            roundClip(shelf[slot].frame, getResources().getDimension(R.dimen.top_shelf_card_radius));
            setupShelfCard(shelf[slot], slot);
        }

        // ---- dock ----
        dock = new DockBar(this, (LinearLayout) findViewById(R.id.dockRow), new DockBar.Callbacks() {
            @Override
            public void onTileFocused(int index, int color) {
                if (index >= 0) lastDock = index;
                transitionAmbient(color);
            }

            @Override
            public void onRebuilt() {
                wireDockNavigation();
            }
        });

        // ---- header and bottom actions ----
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        btnApps.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        btnSearch.setOnClickListener(v -> openSearch());
        btnPersonalize.setOnClickListener(v -> startActivity(new Intent(this, PersonalizeActivity.class)));
        btnThemes.setOnClickListener(v -> startActivity(new Intent(this, PersonalizeActivity.class)
                .putExtra(PersonalizeActivity.EXTRA_FOCUS_THEMES, true)));
        btnWeather.setOnClickListener(v -> showWeather());
        View.OnFocusChangeListener ambientFadeListener = (v, focused) -> {
            if (focused) fadeOutAmbient();
        };
        btnInputs.setOnFocusChangeListener(ambientFadeListener);
        btnWifi.setOnFocusChangeListener(ambientFadeListener);
        btnSettings.setOnFocusChangeListener(ambientFadeListener);
        if (weatherPill != null) weatherPill.setOnFocusChangeListener(ambientFadeListener);
        btnApps.setOnFocusChangeListener(ambientFadeListener);
        btnSearch.setOnFocusChangeListener(ambientFadeListener);
        btnPersonalize.setOnFocusChangeListener(ambientFadeListener);
        btnThemes.setOnFocusChangeListener(ambientFadeListener);
        btnWeather.setOnFocusChangeListener(ambientFadeListener);
        wireStaticNavigation();

        if (!TopShelfStore.hasWatchNextPermission(this)) {
            requestPermissions(new String[]{TopShelfStore.WATCH_NEXT_PERMISSION}, REQ_WATCH_NEXT);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        registerPackageReceiver();
    }

    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(packageReceiver, filter);
            }
            packageReceiverRegistered = true;
        } catch (Exception ignored) {
            packageReceiverRegistered = false;
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    private void registerWatchNextObserverIfPermitted() {
        if (watchNextObserverRegistered || !TopShelfStore.hasWatchNextPermission(this)) return;
        try {
            if (watchNextObserver == null) {
                watchNextObserver = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onChange(selfChange, null);
                    }

                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        handler.removeCallbacks(watchNextObserverRefresh);
                        handler.postDelayed(watchNextObserverRefresh, WATCH_NEXT_OBSERVER_DEBOUNCE_MS);
                    }
                };
            }
            getContentResolver().registerContentObserver(
                    Uri.parse(TopShelfStore.WATCH_NEXT_URI_STRING), true, watchNextObserver);
            watchNextObserverRegistered = true;
            Log.d(TAG, "watch-next ContentObserver registered");
        } catch (Exception e) {
            watchNextObserverRegistered = false;
            Log.w(TAG, "watch-next ContentObserver registration failed: " + e.getMessage());
        }
    }

    private void unregisterWatchNextObserver() {
        handler.removeCallbacks(watchNextObserverRefresh);
        if (!watchNextObserverRegistered || watchNextObserver == null) return;
        try {
            getContentResolver().unregisterContentObserver(watchNextObserver);
        } catch (Exception ignored) {
        }
        watchNextObserverRegistered = false;
        Log.d(TAG, "watch-next ContentObserver unregistered");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WATCH_NEXT) {
            registerWatchNextObserverIfPermitted();
            updateTopShelf(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerWatchNextObserverIfPermitted();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        filter.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mediaReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mediaReceiver, filter);
            }
            mediaReceiverRegistered = true;
        } catch (Exception ignored) {
            mediaReceiverRegistered = false;
        }
    }

    @Override
    protected void onStop() {
        if (lastLaunchTapRealtimeMs > 0L) {
            long stopDelta = SystemClock.elapsedRealtime() - lastLaunchTapRealtimeMs;
            Log.i("LaunchPerf", "event=launcher_onStop target=" + lastLaunchTarget + " sinceTapMs=" + stopDelta);
            lastLaunchTapRealtimeMs = 0L;
        }
        wasStopped = true;
        stopAmbientAnimation();
        unregisterWatchNextObserver();
        handler.removeCallbacks(wallpaperRecheck);
        if (mediaReceiverRegistered) {
            try {
                unregisterReceiver(mediaReceiver);
            } catch (Exception ignored) {
            }
            mediaReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        final boolean returningFromApp = wasStopped;
        wasStopped = false;
        applyTheme();
        if (appsDirty) {
            dock.reload();
            appsDirty = false;
        }
        updateDate();
        updateWifiLabel();
        applyWallpaper();
        updateTopShelf(returningFromApp);
        if (returningFromApp) {
            handler.removeCallbacks(shelfReturnRefresh);
            handler.postDelayed(shelfReturnRefresh, SHELF_RETURN_REFRESH_MS);
            Log.d(TAG, "watch-next recheck scheduled after app return");
        }
        WeatherStore.fetch(this, false, this::updateWeatherUi);
        handler.postDelayed(minuteTick, MINUTE_MS);

        handler.removeCallbacks(audioGuardRun);
        for (long delay : AUDIO_GUARD_DELAYS_MS) handler.postDelayed(audioGuardRun, delay);

        if (returningFromApp || getCurrentFocus() == null) {
            root.post(() -> {
                if (returningFromApp || getCurrentFocus() == null) {
                    dock.focus(lastDock);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        if (lastLaunchTapRealtimeMs > 0L) {
            long pauseDelta = SystemClock.elapsedRealtime() - lastLaunchTapRealtimeMs;
            Log.i("LaunchPerf", "event=launcher_onPause target=" + lastLaunchTarget + " sinceTapMs=" + pauseDelta);
        }
        super.onPause();
        handler.removeCallbacks(audioGuardRun);
        handler.removeCallbacks(shelfReturnRefresh);
        handler.removeCallbacks(minuteTick);
        if (wallpaperVideo.getVisibility() == View.VISIBLE) wallpaperVideo.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        if (packageReceiverRegistered) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (Exception ignored) {
            }
            packageReceiverRegistered = false;
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- D-pad navigation ------------------------------------------------------------------------

    private static void link(View v, int up, int left, int right, int down) {
        v.setNextFocusUpId(up);
        v.setNextFocusLeftId(left);
        v.setNextFocusRightId(right);
        v.setNextFocusDownId(down);
    }

    /**
     * Fixed rows: header buttons -> shelf cards -> dock -> bottom actions. Up/Down move exactly one
     * row, and the ends of every row point at themselves so focus never falls through to a far tile.
     */
    private void wireStaticNavigation() {
        int in = btnInputs.getId(), se = btnSettings.getId(), wi = btnWifi.getId();
        int c0 = shelf[0].root.getId(), c1 = shelf[1].root.getId();
        link(btnInputs, in, in, se, c0);
        link(btnSettings, se, in, wi, c0);
        link(btnWifi, wi, se, wi, c1);
        link(shelf[0].root, in, c0, c1, c0);
        link(shelf[1].root, wi, c0, c1, c1);

        // Down from the shelf enters the dock on the tile we last used.
        View.OnKeyListener shelfKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            dock.focus(lastDock);
            return true;
        };
        shelf[0].root.setOnKeyListener(shelfKeys);
        shelf[1].root.setOnKeyListener(shelfKeys);

        int ap = btnApps.getId(), se2 = btnSearch.getId(), pe = btnPersonalize.getId(),
                th = btnThemes.getId(), we = btnWeather.getId();
        link(btnApps, ap, ap, se2, ap);
        link(btnSearch, se2, ap, pe, se2);
        link(btnPersonalize, pe, se2, th, pe);
        link(btnThemes, th, pe, we, th);
        link(btnWeather, we, th, we, we);
        View.OnKeyListener bottomKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            dock.focus(lastDock);
            return true;
        };
        for (View b : new View[]{btnApps, btnSearch, btnPersonalize, btnThemes, btnWeather}) {
            b.setOnKeyListener(bottomKeys);
        }
    }

    /** Called whenever the dock is rebuilt: its tiles are new views with new ids. */
    private void wireDockNavigation() {
        final int n = dock.size();
        for (int i = 0; i < n; i++) {
            View t = dock.tile(i);
            if (t == null) continue;
            int self = t.getId();
            View prev = dock.tile(i - 1);
            View next = dock.tile(i + 1);
            t.setNextFocusLeftId(prev != null ? prev.getId() : self);
            t.setNextFocusRightId(next != null ? next.getId() : self);
            t.setNextFocusDownId(btnApps.getId());
            final int index = i;
            t.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
                (index < n / 2 ? shelf[0].root : shelf[1].root).requestFocus();
                return true;
            });
        }
    }

    // ---- continue-watching shelf -----------------------------------------------------------------

    private static void roundClip(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    private void applyShelfCardColors(ShelfCard card, LauncherTheme t, boolean focused) {
        if (card == null || t == null) return;
        if (card.title != null) {
            card.title.setTextColor(focused ? t.textPrimary : t.textSecondary);
        }
        if (card.subtitle != null) {
            card.subtitle.setTextColor(t.textDim);
        }
        if (card.emptyIcon != null) {
            card.emptyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    focused ? t.accent : t.textSecondary));
            card.emptyIcon.setAlpha(focused ? 0.90f : 0.56f);
        }
        if (card.emptyHint != null) {
            card.emptyHint.setTextColor(focused ? t.textSecondary : t.textDim);
        }
    }

    private void setupShelfCard(ShelfCard card, int slot) {
        roundClip(card.frame, getResources().getDimension(R.dimen.top_shelf_card_radius));
        card.root.setOnFocusChangeListener((v, focused) -> {
            if (focused) fadeOutAmbient();
            float scale = focused ? 1.035f : 1.0f;
            card.frame.animate().scaleX(scale).scaleY(scale)
                    .translationZ(focused ? 6f * density : 0f)
                    .setDuration(focused ? 130 : 100)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
            LauncherTheme activeTheme = LauncherTheme.byId(Prefs.theme(this));
            applyShelfCardColors(card, activeTheme, focused);
        });

        card.root.setOnClickListener(v -> {
            TopShelfStore.Item item = card.item;
            if (item == null) item = TopShelfStore.getCachedSlots(this)[slot];
            if (item.intentUri != null && !item.intentUri.isEmpty()) {
                try {
                    Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    final String pkg = item.targetPackage;
                    open(i);
                    if (pkg != null && !pkg.isEmpty()) {
                        handler.post(() -> TopShelfStore.recordAppLaunch(getApplicationContext(), pkg));
                    }
                    return;
                } catch (Exception ignored) {
                }
            }
            if (item.hasTarget()) {
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(item.targetPackage);
                if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(item.targetPackage);
                if (launch != null) {
                    final String pkg = item.targetPackage;
                    open(launch);
                    handler.post(() -> TopShelfStore.recordAppLaunch(getApplicationContext(), pkg));
                    return;
                }
            }
            TopShelfEditDialog.show(this, slot, s -> updateTopShelf(true));
        });

        card.root.setOnLongClickListener(v -> {
            TopShelfEditDialog.show(this, slot, s -> updateTopShelf(true));
            return true;
        });
    }

    private void updateTopShelf() {
        updateTopShelf(false);
    }

    private void updateTopShelf(boolean force) {
        TopShelfStore.Item[] cached = TopShelfStore.getCachedSlots(this);
        for (int slot = 0; slot < shelf.length; slot++) bindShelfSlot(slot, shelf[slot], cached[slot]);
        TopShelfStore.refreshSlots(this, force, items -> {
            if (isFinishing() || isDestroyed()) return;
            int changed = 0;
            for (int slot = 0; slot < shelf.length; slot++) {
                if (bindShelfSlot(slot, shelf[slot], items[slot])) changed++;
            }
            Log.d(TAG, "shelf callback force=" + force + " changed=" + changed);
        });
    }

    private static boolean sameShelfItem(TopShelfStore.Item a, TopShelfStore.Item b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.id.equals(b.id)
                && a.title.equals(b.title)
                && a.subtitle.equals(b.subtitle)
                && a.backdropUri.equals(b.backdropUri)
                && a.targetPackage.equals(b.targetPackage)
                && a.isCustom == b.isCustom
                && a.progressPercent == b.progressPercent
                && a.intentUri.equals(b.intentUri);
    }

    private boolean bindShelfSlot(int slot, ShelfCard card, TopShelfStore.Item item) {
        if (item == null) return false;
        if (sameShelfItem(card.item, item)) return false;
        card.item = item;

        boolean populated = !item.title.trim().isEmpty() || item.hasBackdrop() || item.hasTarget();
        int cardW = getResources().getDimensionPixelSize(populated
                ? R.dimen.top_shelf_card_width : R.dimen.top_shelf_empty_card_width);
        int cardH = getResources().getDimensionPixelSize(populated
                ? R.dimen.top_shelf_card_height : R.dimen.top_shelf_empty_card_height);

        ViewGroup.LayoutParams frameLp = card.frame.getLayoutParams();
        if (frameLp != null && (frameLp.width != cardW || frameLp.height != cardH)) {
            frameLp.width = cardW;
            frameLp.height = cardH;
            card.frame.setLayoutParams(frameLp);
        }
        ViewGroup.LayoutParams rootLp = card.root.getLayoutParams();
        if (rootLp != null && rootLp.width != cardW) {
            rootLp.width = cardW;
            rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            card.root.setLayoutParams(rootLp);
        }
        ViewGroup.LayoutParams titleLp = card.title.getLayoutParams();
        if (titleLp != null && titleLp.width != cardW) {
            titleLp.width = cardW;
            card.title.setLayoutParams(titleLp);
        }
        ViewGroup.LayoutParams subLp = card.subtitle.getLayoutParams();
        if (subLp != null && subLp.width != cardW) {
            subLp.width = cardW;
            card.subtitle.setLayoutParams(subLp);
        }

        card.badge.setVisibility(View.GONE);

        if (!populated) {
            card.subtitle.setVisibility(View.GONE);
            card.image.setTag(null);
            card.image.setImageDrawable(null);
            card.image.setVisibility(View.GONE);
            card.progress.setVisibility(View.GONE);
            if (card.emptyState != null) card.emptyState.setVisibility(View.VISIBLE);
            if (card.emptyHint != null) card.emptyHint.setText(R.string.top_shelf_empty_title);
            card.title.setText(R.string.top_shelf_subtitle_empty);
            return true;
        }

        String title = item.title != null ? item.title.trim() : "";
        String cleanSub = TopShelfStore.sanitizeSubtitle(item.subtitle);
        card.title.setText(title);

        if (!cleanSub.isEmpty() && !cleanSub.equalsIgnoreCase(title)) {
            card.subtitle.setText(cleanSub);
            card.subtitle.setVisibility(View.VISIBLE);
        } else {
            card.subtitle.setVisibility(View.GONE);
        }

        if (item.progressPercent > 0 && item.progressPercent < 100) {
            card.progress.setProgress(item.progressPercent);
            card.progress.setVisibility(View.VISIBLE);
        } else {
            card.progress.setVisibility(View.GONE);
        }

        if (item.hasBackdrop()) {
            String loadedUri = card.image.getTag() instanceof String ? (String) card.image.getTag() : null;
            if (!item.backdropUri.equals(loadedUri)) {
                loadTopShelfBackdrop(item.backdropUri, card.image, card.emptyState);
            } else if (card.image.getDrawable() != null) {
                card.image.setVisibility(View.VISIBLE);
                if (card.emptyState != null) card.emptyState.setVisibility(View.GONE);
            }
        } else {
            card.image.setTag(null);
            card.image.setImageDrawable(null);
            card.image.setVisibility(View.GONE);
            if (card.emptyState != null) {
                card.emptyState.setVisibility(View.VISIBLE);
                if (card.emptyHint != null) card.emptyHint.setText(title);
            }
        }
        return true;
    }

    private void loadTopShelfBackdrop(String uriStr, ImageView imgView, View watermarkView) {
        if (uriStr == null || uriStr.trim().isEmpty()) {
            imgView.setImageDrawable(null);
            imgView.setVisibility(View.GONE);
            if (watermarkView != null) watermarkView.setVisibility(View.VISIBLE);
            return;
        }

        imgView.setTag(uriStr);
        TOP_SHELF_IO.execute(() -> {
            Bitmap bmp = null;
            try {
                Uri uri = Uri.parse(uriStr);
                String scheme = uri.getScheme();
                if ("package".equalsIgnoreCase(scheme) || "app".equalsIgnoreCase(scheme)) {
                    String pkgName = uri.getSchemeSpecificPart();
                    if (pkgName == null || pkgName.isEmpty()) pkgName = uri.getHost();
                    if (pkgName != null && !pkgName.isEmpty()) {
                        try {
                            PackageManager pm = getPackageManager();
                            Intent intent = pm.getLeanbackLaunchIntentForPackage(pkgName);
                            if (intent == null) intent = pm.getLaunchIntentForPackage(pkgName);
                            if (intent != null && intent.getComponent() != null) {
                                Drawable[] art = AppAdapter.loadArtwork(this, intent.getComponent());
                                Drawable d = (art != null && art[0] != null) ? art[0] : (art != null ? art[1] : null);
                                if (d instanceof BitmapDrawable) {
                                    bmp = ((BitmapDrawable) d).getBitmap();
                                } else if (d != null) {
                                    int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : Math.round(296f * density));
                                    int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : Math.round(166f * density));
                                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                    Canvas canvas = new Canvas(bmp);
                                    d.setBounds(0, 0, w, h);
                                    d.draw(canvas);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    File cache = new File(getCacheDir(), "ts_" + Integer.toHexString(uriStr.hashCode()) + ".jpg");
                    if (!cache.exists() || cache.length() == 0) {
                        HttpURLConnection conn = (HttpURLConnection) new URL(uriStr).openConnection();
                        conn.setConnectTimeout(6000);
                        conn.setReadTimeout(6000);
                        conn.setRequestProperty("User-Agent", "HarolHomeTV/1.0");
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            try (InputStream in = conn.getInputStream();
                                 FileOutputStream out = new FileOutputStream(cache)) {
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = in.read(buf)) > 0) {
                                    out.write(buf, 0, len);
                                }
                            }
                        }
                    }
                    if (cache.exists() && cache.length() > 0) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2; // scale down for 16:9 card to save RAM
                        bmp = BitmapFactory.decodeFile(cache.getAbsolutePath(), opts);
                    }
                } else if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "android.resource".equalsIgnoreCase(scheme)) {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        bmp = BitmapFactory.decodeStream(in, null, opts);
                    }
                }
            } catch (Exception ignored) {
            }

            final Bitmap res = bmp;
            runOnUiThread(() -> {
                if (uriStr.equals(imgView.getTag())) {
                    if (res != null) {
                        boolean isPortrait = res.getHeight() > (res.getWidth() * 1.15f);
                        imgView.setScaleType(isPortrait ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                        imgView.setImageBitmap(res);
                        imgView.setVisibility(View.VISIBLE);
                        if (watermarkView != null) watermarkView.setVisibility(View.GONE);
                    } else {
                        imgView.setTag(null);
                        imgView.setImageDrawable(null);
                        imgView.setVisibility(View.GONE);
                        if (watermarkView != null) watermarkView.setVisibility(View.VISIBLE);
                    }
                }
            });
        });
    }

    // ---- weather, date ---------------------------------------------------------------------------

    private void updateWeatherUi(WeatherStore.WeatherData data) {
        if (weatherPill == null) return;
        if (!WeatherStore.isEnabled(this) || data == null || !data.isValid()) {
            weatherPill.setVisibility(View.GONE);
            return;
        }
        weatherPill.setVisibility(View.VISIBLE);
        weatherIcon.setImageResource(data.getIconResId());
        btnWeatherIcon.setImageResource(data.getIconResId());
        weatherText.setText(Math.round(data.temperature) + "\u00B0C · " + data.cityName);
    }

    /** "SÃƒÂ¡b, 26 Abr": short day, day of month and short month, each word capitalised, no dots. */
    private void updateDate() {
        String s = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date()).replace(".", "");
        StringBuilder b = new StringBuilder(s.length());
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = ch == ' ';
        }
        date.setText(b);
    }

    /** The Weather button: turns the pill on if it was off, shows cached weather immediately, and refreshes in background. */
    private void showWeather() {
        if (!WeatherStore.isEnabled(this)) WeatherStore.setEnabled(this, true);
        final WeatherStore.WeatherData cached = WeatherStore.getCached(this);
        final boolean showedCached;
        if (cached != null && cached.isValid()) {
            updateWeatherUi(cached);
            Toast.makeText(this, cached.cityName + "  " + Math.round(cached.temperature) + "\u00B0C",
                    Toast.LENGTH_SHORT).show();
            showedCached = true;
        } else {
            showedCached = false;
        }
        WeatherStore.fetch(this, true, data -> {
            updateWeatherUi(data);
            if (data != null && data.isValid()) {
                boolean changed = !showedCached
                        || Math.round(data.temperature) != Math.round(cached.temperature)
                        || !data.cityName.equals(cached.cityName);
                if (changed) {
                    Toast.makeText(this, data.cityName + "  " + Math.round(data.temperature) + "\u00B0C",
                            Toast.LENGTH_LONG).show();
                }
            } else if (!showedCached) {
                Toast.makeText(this, R.string.err_offline, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** The Search button: the TV's global search / assistant, whichever this TV has. */
    private void openSearch() {
        Intent[] candidates = {
                new Intent("android.search.action.GLOBAL_SEARCH"),
                new Intent(Intent.ACTION_ASSIST)
        };
        for (Intent i : candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) != null) {
                try {
                    startActivity(i);
                    return;
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    // ---- theme -----------------------------------------------------------------------------------

    /** Paints the home with the chosen theme. Called on every resume: the theme may have changed in Personalize. */
    private void applyTheme() {
        LauncherTheme t = LauncherTheme.byId(Prefs.theme(this));
        if (t.id.equals(appliedThemeId)) return;
        appliedThemeId = t.id;
        dock.setTheme(t);
        appsDirty = true;   // the dock labels take the theme's text colour when rebuilt

        scrimTop.setBackground(ThemeKit.scrimTop(this, t));
        scrimBottom.setBackground(ThemeKit.scrimBottom(this, t));
        clock.setTextColor(t.textPrimary);
        if (ampm != null) ampm.setTextColor(t.textSecondary);
        date.setTextColor(t.textSecondary);
        topShelfLabel.setTextColor(t.textPrimary);
        ThemeKit.applySphere(btnInputs, t);
        ThemeKit.applySphere(btnSettings, t);
        ThemeKit.applySphere(btnWifi, t);
        if (weatherPill != null) {
            weatherPill.setBackground(ThemeKit.weatherCapsuleBackground(this, t));
        }
        if (weatherText != null) {
            weatherText.setTextColor(t.textPrimary);
        }
        weatherIcon.setImageTintList(null);

        int shelfRadiusPx = getResources().getDimensionPixelSize(R.dimen.top_shelf_card_radius);
        for (ShelfCard card : shelf) {
            if (card != null && card.frame != null) {
                ThemeKit.applyCard(card.frame, t, shelfRadiusPx);
                applyShelfCardColors(card, t, card.root.isFocused());
            }
        }

        ThemeKit.applyPill(btnApps, t);
        ThemeKit.applyToolButton(btnSearchCircle, btnSearchIcon, btnSearchLabel, t, true);
        ThemeKit.applyToolButton(btnPersonalizeCircle, btnPersonalizeIcon, btnPersonalizeLabel, t, true);
        ThemeKit.applyToolButton(btnThemesCircle, btnThemesIcon, btnThemesLabel, t, true);
        if (btnThemesIcon != null) btnThemesIcon.setImageTintList(ThemeKit.toolAccentIconColors(t));
        ThemeKit.applyToolButton(btnWeatherCircle, btnWeatherIcon, btnWeatherLabel, t, false);
        int dividerColor = t.cardBorderIdle;
        bottomDivider1.setBackgroundColor(dividerColor);
        bottomDivider2.setBackgroundColor(dividerColor);
        if (wallpaperBase != null && showComposedWallpaper(t)) {
            // Static wallpaper already carries the dim and both scrims.
        } else {
            scrimTop.setVisibility(View.VISIBLE);
            scrimBottom.setVisibility(View.VISIBLE);
        }
        updateRootBackground();
    }

    /** The aurora only needs to paint when no opaque wallpaper covers it. */
    private boolean isWallpaperCovered() {
        return (wallpaper != null && wallpaper.getVisibility() == View.VISIBLE)
                || (wallpaperVideo != null && wallpaperVideo.getVisibility() == View.VISIBLE);
    }

    private void updateRootBackground() {
        boolean covered = isWallpaperCovered();
        root.setBackground(covered ? null
                : ThemeKit.fallback(this, LauncherTheme.byId(Prefs.theme(this))));
        if (covered && ambient != null) {
            stopAmbientAnimation();
            ambient.animate().cancel();
            ambient.setAlpha(0f);
        }
    }

    private void setAmbient(int color) {
        if (ambient == null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.50f, h * 0.65f, w * 0.70f,
                    new int[]{0x85FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        if (isWallpaperCovered()) {
            if (ambient != null) ambient.setAlpha(0f);
            return;
        }
        if (ambient == null) return;
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor && ambient.getAlpha() > 0.95f) return;

        if (ambientColor == 0 || ambient.getAlpha() < 0.05f) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(260).start();
            return;
        }

        ambient.animate().alpha(1f).setDuration(200).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(350);
        ambientAnimator.addUpdateListener(anim -> setAmbient((int) anim.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void fadeOutAmbient() {
        stopAmbientAnimation();
        if (ambient != null) {
            ambient.animate().cancel();
            ambient.animate().alpha(0f).setDuration(300).start();
        }
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    // ---- wallpaper -------------------------------------------------------------------------------

    /** Applies the wallpaper chosen in PersonalizeActivity; the aurora fallback shows when none is set.
     *  If a catalogue wallpaper was saved to USB and the drive is gone, its verified private copy is
     *  promoted to the current wallpaper instead of leaving a dim/blank Home. */
    private void applyWallpaper() {
        String uriStr = Prefs.wallpaper(this);
        if (uriStr.equals(currentWallpaper) && wallpaperReady && sourceAvailable(uriStr)) {
            if (wallpaperVideo.getVisibility() == View.VISIBLE && videoPrepared
                    && !wallpaperVideo.isPlaying()) {
                wallpaperVideo.start();
            }
            return;
        }
        if (!sourceAvailable(uriStr)) {
            currentWallpaper = null;
            wallpaperReady = false;
            recoverWallpaper(uriStr);
            return;
        }
        if (displayWallpaper(uriStr)) {
            currentWallpaper = uriStr;
            wallpaperReady = true;
            wallpaperNoticeUri = "";
            return;
        }
        currentWallpaper = null;
        wallpaperReady = false;
        recoverWallpaper(uriStr);
    }

    /** Resets all wallpaper views, then shows the requested source. False means it could not be read. */
    private boolean displayWallpaper(String uriStr) {
        videoPrepared = false;
        videoWallpaper = null;
        wallpaperBase = null;
        wallpaperVideo.stopPlayback();
        wallpaperVideo.setVisibility(View.GONE);
        wallpaper.setImageDrawable(null);
        wallpaper.clearColorFilter();
        wallpaper.setVisibility(View.GONE);
        wallpaperDim.setVisibility(View.GONE);
        scrimTop.setVisibility(View.VISIBLE);
        scrimBottom.setVisibility(View.VISIBLE);
        if (uriStr.isEmpty()) {
            updateRootBackground();
            return true;
        }
        try {
            Uri uri = Uri.parse(uriStr);
            if (isVideo(uri)) {
                videoWallpaper = uriStr;
                wallpaperVideo.setVisibility(View.VISIBLE);
                wallpaperVideo.setVideoURI(uri);
                wallpaperVideo.start();
                updateRootBackground();
                return true;
            }
            Drawable d = loadWallpaperDrawable(uri);
            if (d == null) {
                updateRootBackground();
                return false;
            }
            if (d instanceof BitmapDrawable) {
                Bitmap base = ((BitmapDrawable) d).getBitmap();
                if (base != null && !base.isRecycled()) {
                    wallpaperBase = base;
                    if (showComposedWallpaper(LauncherTheme.byId(Prefs.theme(this)))) {
                        updateRootBackground();
                        return true;
                    }
                    wallpaperBase = null;
                }
            }
            wallpaper.setImageDrawable(d);
            // Same 40% black as the old full-screen dim view, folded into the image draw.
            wallpaper.setColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP);
            wallpaper.setVisibility(View.VISIBLE);
            scrimTop.setVisibility(View.VISIBLE);
            scrimBottom.setVisibility(View.VISIBLE);
            updateRootBackground();
            return true;
        } catch (Exception e) {
            videoWallpaper = null;
            wallpaperVideo.setVisibility(View.GONE);
            wallpaperDim.setVisibility(View.GONE);
            wallpaperBase = null;
            scrimTop.setVisibility(View.VISIBLE);
            scrimBottom.setVisibility(View.VISIBLE);
            updateRootBackground();
            return false;
        }
    }

    /**
     * Bakes the static wallpaper, its 40% dim and both theme scrims into one texture. The GPU then
     * composites a single full-screen layer instead of three translucent full-screen passes.
     */
    private boolean showComposedWallpaper(LauncherTheme theme) {
        Bitmap base = wallpaperBase;
        if (base == null || base.isRecycled()) return false;
        Bitmap composed = composeWallpaper(base, theme);
        if (composed == null) return false;
        wallpaper.setImageBitmap(composed);
        wallpaper.clearColorFilter();
        wallpaper.setVisibility(View.VISIBLE);
        scrimTop.setVisibility(View.GONE);
        scrimBottom.setVisibility(View.GONE);
        return true;
    }

    private Bitmap composeWallpaper(Bitmap base, LauncherTheme theme) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (width <= 0 || height <= 0 || base.getWidth() <= 0 || base.getHeight() <= 0) return null;
        try {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.BLACK);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            Rect src = centerCropRect(base.getWidth(), base.getHeight(), width, height);
            canvas.drawBitmap(base, src, new Rect(0, 0, width, height), paint);
            paint.setColor(0x66000000);
            canvas.drawRect(0, 0, width, height, paint);
            Drawable top = ThemeKit.scrimTop(this, theme);
            top.setBounds(0, 0, width, getResources().getDimensionPixelSize(R.dimen.scrim_top_height));
            top.draw(canvas);
            Drawable bottom = ThemeKit.scrimBottom(this, theme);
            int bottomHeight = getResources().getDimensionPixelSize(R.dimen.scrim_bottom_height);
            bottom.setBounds(0, height - bottomHeight, width, height);
            bottom.draw(canvas);
            return out;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static Rect centerCropRect(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        float scale = Math.max((float) targetWidth / sourceWidth, (float) targetHeight / sourceHeight);
        int width = Math.min(sourceWidth, Math.round(targetWidth / scale));
        int height = Math.min(sourceHeight, Math.round(targetHeight / scale));
        int left = (sourceWidth - width) / 2;
        int top = (sourceHeight - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private boolean sourceAvailable(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return true;
        try {
            Uri uri = Uri.parse(uriStr);
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                return path != null && new File(path).isFile();
            }
            if ("content".equals(uri.getScheme())) {
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                    return pfd != null;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void recoverWallpaper(String failedUri) {
        File local = localRecovery(failedUri);
        if (local != null) {
            String localUri = Uri.fromFile(local).toString();
            if (displayWallpaper(localUri)) {
                Prefs.setWallpaper(this, localUri);
                currentWallpaper = localUri;
                wallpaperReady = true;
                wallpaperNoticeUri = "";
                Toast.makeText(this, R.string.wallpaper_recovered_local, Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (!failedUri.isEmpty() && !failedUri.equals(wallpaperNoticeUri)) {
            wallpaperNoticeUri = failedUri;
            Toast.makeText(this, R.string.wallpaper_unavailable, Toast.LENGTH_LONG).show();
        }
        updateRootBackground();
    }

    /** The private original is kept after a USB export, so it can stand in for a removed drive. */
    private File localRecovery(String failedUri) {
        String id = Prefs.wallpaperId(this);
        if (!Catalog.isSafeId(id)) return null;
        File f = WallpaperStore.find(this, id);
        if (f == null || !f.isFile() || f.length() == 0) return null;
        if (failedUri != null && Uri.fromFile(f).toString().equals(failedUri)) return null;
        return WallpaperStore.dimensions(f) == null ? null : f;
    }

    private boolean isVideo(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null) {
            String l = path.toLowerCase(Locale.ROOT);
            if (l.endsWith(".mp4") || l.endsWith(".webm") || l.endsWith(".mkv") || l.endsWith(".m4v")) {
                return true;
            }
        }
        String type = getContentResolver().getType(uri);
        return type != null && type.startsWith("video/");
    }

    /** Downsampled to screen size; animated GIF/WebP loop on their own (API 28+). */
    private Drawable loadWallpaperDrawable(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = "file".equals(uri.getScheme())
                        ? ImageDecoder.createSource(new File(uri.getPath()))
                        : ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (largest > max) decoder.setTargetSampleSize(Math.max(1, largest / max));
                });
            }
            int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            Bitmap b;
            if ("file".equals(uri.getScheme())) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(uri.getPath(), o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                b = BitmapFactory.decodeFile(uri.getPath(), o);
            } else {
                InputStream is = getContentResolver().openInputStream(uri);
                try {
                    b = BitmapFactory.decodeStream(is);
                } finally {
                    if (is != null) is.close();
                }
            }
            return b == null ? null : new BitmapDrawable(getResources(), b);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setContentDescription(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        long t0 = SystemClock.elapsedRealtime();
        String target = i.getComponent() != null
                ? i.getComponent().flattenToShortString()
                : (i.getPackage() != null ? i.getPackage() : String.valueOf(i.getAction()));
        try {
            startActivity(i);
            long t1 = SystemClock.elapsedRealtime();
            markAppLaunchStart(target, t0, t1 - t0);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        LauncherTheme activeTheme = LauncherTheme.byId(Prefs.theme(this));
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        ThemeKit.applyPopupPanel(panel, activeTheme);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row and clamp inside screen bounds so right-aligned header buttons never clip.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int finalW = Math.max(width, panel.getMeasuredWidth());
        int finalH = panel.getMeasuredHeight();
        popup.setWidth(finalW);

        int margin = Math.round(12f * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = Math.max(margin, Math.min(loc[0], dm.widthPixels - finalW - margin));
        int y = loc[1] + anchor.getHeight() + Math.round(10f * density);
        if (y + finalH > dm.heightPixels - margin) {
            y = Math.max(margin, dm.heightPixels - finalH - margin);
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        LauncherTheme activeTheme = LauncherTheme.byId(Prefs.theme(this));
        View line = new View(this);
        line.setBackgroundColor(activeTheme.cardBorderIdle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        LauncherTheme activeTheme = LauncherTheme.byId(Prefs.theme(this));
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ThemeKit.applyPopupRow(row, activeTheme);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
