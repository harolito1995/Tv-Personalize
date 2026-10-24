package com.harolhome.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen wallpaper preview with actions to apply as Home wallpaper, apply as screensaver
 * (idle background), or save permanently to Favorites. The full resolution file is fetched only
 * when requested, and stored safely in the app''s private directories.
 */
public class WallpaperPreviewActivity extends Activity {

    private static final long PROGRESS_INTERVAL_MS = 250;

    private View rootView;
    private View scrimView;
    private ImageView preview;
    private TextView title;
    private TextView meta;
    private TextView source;
    private TextView status;
    private TextView btnApply;
    private TextView btnApplyDream;
    private TextView btnFavorite;
    private TextView btnBack;
    private LauncherTheme theme;

    private String id = "";
    private String titleText = "";
    private String imageUrl = "";
    private String thumbUrl = "";
    private String author = "";
    private String license = "";
    private String sourceUrl = "";
    private String category = "";
    private int width;
    private int height;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;
    private boolean applied;
    private boolean dreamApplied;
    private long lastProgress;
    private int[] realDims;
    private String realFormat = "";
    private long realBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_preview);

        rootView = findViewById(R.id.previewRoot);
        scrimView = findViewById(R.id.previewScrim);
        preview = findViewById(R.id.preview);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        source = findViewById(R.id.source);
        status = findViewById(R.id.status);
        btnApply = findViewById(R.id.btnApply);
        btnApplyDream = findViewById(R.id.btnApplyDream);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnBack = findViewById(R.id.btnBack);

        theme = LauncherTheme.byId(Prefs.theme(this));
        if (rootView != null) {
            rootView.setBackground(ThemeKit.fallback(this, theme));
        }
        if (scrimView != null) {
            scrimView.setBackground(ThemeKit.scrimBottom(this, theme));
        }
        title.setTextColor(theme.textPrimary);
        meta.setTextColor(theme.textSecondary);
        source.setTextColor(theme.textDim);
        status.setTextColor(theme.textPrimary);
        ThemeKit.applySelectableChip(btnApply, theme, false);
        ThemeKit.applySelectableChip(btnApplyDream, theme, false);
        ThemeKit.applySelectableChip(btnFavorite, theme, false);
        ThemeKit.applyPill(btnBack, theme);

        btnApply.setNextFocusRightId(R.id.btnApplyDream);
        btnApplyDream.setNextFocusLeftId(R.id.btnApply);
        btnApplyDream.setNextFocusRightId(R.id.btnFavorite);
        btnFavorite.setNextFocusLeftId(R.id.btnApplyDream);
        btnFavorite.setNextFocusRightId(R.id.btnBack);
        btnBack.setNextFocusLeftId(R.id.btnFavorite);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            id = extras.getString("id", "");
            titleText = extras.getString("title", "");
            imageUrl = extras.getString("image", "");
            thumbUrl = extras.getString("thumb", "");
            author = extras.getString("author", "");
            license = extras.getString("license", "");
            sourceUrl = extras.getString("source", "");
            category = extras.getString("category", "");
            width = extras.getInt("width", 0);
            height = extras.getInt("height", 0);
        }

        title.setText(titleText.isEmpty() ? getString(R.string.app_name) : titleText);
        updateMeta();
        updateSource();

        File local = findLocalFile();
        if (local != null) {
            showImage(local);
            realDims = WallpaperStore.dimensions(local);
            realFormat = WallpaperStore.format(local);
            realBytes = local.length();
            updateMeta();
        } else if (!thumbUrl.isEmpty()) {
            loadThumb();
        }

        String currentWallpaperId = Prefs.wallpaperId(this);
        String currentWallpaperUri = Prefs.wallpaper(this);
        if ((!id.isEmpty() && id.equals(currentWallpaperId))
                || (local != null && Uri.fromFile(local).toString().equals(currentWallpaperUri))) {
            markApplied();
        }

        String currentDreamUri = Prefs.dreamWallpaper(this);
        if (local != null && Uri.fromFile(local).toString().equals(currentDreamUri)) {
            markDreamApplied();
        }

        updateFavoriteButton();

        btnApply.setOnClickListener(v -> applyWallpaper());
        btnApplyDream.setOnClickListener(v -> applyDreamWallpaper());
        btnFavorite.setOnClickListener(v -> toggleFavorite());
        btnBack.setOnClickListener(v -> finish());
        btnApply.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    private File findLocalFile() {
        File fav = WallpaperStore.getFavoriteFile(this, id);
        if (fav != null && fav.exists()) return fav;
        File cached = WallpaperStore.find(this, id);
        if (cached != null && cached.exists()) return cached;
        return null;
    }

    private Catalog.Wallpaper asWallpaper() {
        return new Catalog.Wallpaper(id, titleText, category, imageUrl, thumbUrl,
                width, height, author, license, sourceUrl, "");
    }

    private void updateFavoriteButton() {
        boolean fav = WallpaperStore.isFavorite(this, id);
        btnFavorite.setText(fav ? R.string.preview_favorite_remove : R.string.preview_favorite_add);
        if (theme != null) {
            ThemeKit.applySelectableChip(btnFavorite, theme, fav);
        }
    }

    private void updateMeta() {
        StringBuilder b = new StringBuilder();
        if (realDims != null) {
            b.append(getString(R.string.preview_dims_verified, realDims[0], realDims[1]));
            if (!realFormat.isEmpty()) b.append(" \u00b7 ").append(realFormat);
            if (realBytes > 0) b.append(" \u00b7 ").append(formatMb(realBytes));
            if (Catalog.is4k(realDims[0], realDims[1])) {
                b.append(" \u00b7 ").append(getString(R.string.preview_4k));
            }
        } else if (width > 0 && height > 0) {
            b.append(getString(R.string.preview_dims_declared, width, height));
            if (Catalog.is4k(width, height)) {
                b.append(" \u00b7 ").append(getString(R.string.preview_4k));
            }
        }
        if (!author.isEmpty()) {
            if (b.length() > 0) b.append(" \u00b7 ");
            b.append(author);
        }
        if (!license.isEmpty()) {
            if (b.length() > 0) b.append(" \u00b7 ");
            b.append(license);
        }
        meta.setText(b.toString());
    }

    private void updateSource() {
        if (!sourceUrl.isEmpty()) {
            source.setText(sourceUrl);
            source.setVisibility(View.VISIBLE);
        } else {
            source.setVisibility(View.GONE);
        }
    }

    private void loadThumb() {
        exec.execute(() -> {
            try {
                File f = WallpaperStore.thumbFile(this, id);
                if (f == null) f = WallpaperStore.downloadThumb(this, asWallpaper(), null);
                if (f == null) return;
                Bitmap b = WallpaperStore.decode(f,
                        getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
                if (b == null) return;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (preview.getDrawable() == null) preview.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void showImage(File f) {
        exec.execute(() -> {
            Bitmap b = WallpaperStore.decode(f,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            if (b == null) return;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                preview.setImageBitmap(b);
            });
        });
    }

    // ---- Apply Home Wallpaper --------------------------------------------------------------------

    private void applyWallpaper() {
        if (busy) return;
        File existing = findLocalFile();
        if (existing != null) {
            doApply(existing);
            return;
        }
        busy = true;
        status.setText(getString(R.string.preview_downloading, "0 MB"));
        btnApply.setText(R.string.preview_apply);
        exec.execute(() -> {
            try {
                File downloaded = WallpaperStore.downloadWallpaper(this, asWallpaper(), this::onProgress);
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showImage(downloaded);
                    realDims = WallpaperStore.dimensions(downloaded);
                    realFormat = WallpaperStore.format(downloaded);
                    realBytes = downloaded.length();
                    updateMeta();
                    doApply(downloaded);
                });
            } catch (CatalogException e) {
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showError(e);
                });
            }
        });
    }

    private void doApply(File f) {
        Prefs.setWallpaper(this, Uri.fromFile(f).toString());
        Prefs.setWallpaperId(this, id);
        WallpaperStore.cleanup(this, id);
        markApplied();
        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
    }

    private void markApplied() {
        applied = true;
        btnApply.setText(R.string.preview_applied);
        if (theme != null) {
            ThemeKit.applySelectableChip(btnApply, theme, true);
        }
        status.setText("");
    }

    // ---- Apply Screensaver / Repose Wallpaper -----------------------------------------------------

    private void applyDreamWallpaper() {
        if (busy) return;
        File existing = findLocalFile();
        if (existing != null) {
            doApplyDream(existing);
            return;
        }
        busy = true;
        status.setText(getString(R.string.preview_downloading, "0 MB"));
        btnApplyDream.setText(R.string.preview_apply_dream);
        exec.execute(() -> {
            try {
                File downloaded = WallpaperStore.downloadWallpaper(this, asWallpaper(), this::onProgress);
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showImage(downloaded);
                    realDims = WallpaperStore.dimensions(downloaded);
                    realFormat = WallpaperStore.format(downloaded);
                    realBytes = downloaded.length();
                    updateMeta();
                    doApplyDream(downloaded);
                });
            } catch (CatalogException e) {
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showError(e);
                });
            }
        });
    }

    private void doApplyDream(File f) {
        Prefs.setDreamWallpaper(this, Uri.fromFile(f).toString());
        markDreamApplied();
        Toast.makeText(this, R.string.preview_applied_dream, Toast.LENGTH_SHORT).show();
    }

    private void markDreamApplied() {
        dreamApplied = true;
        btnApplyDream.setText(R.string.preview_applied_dream);
        if (theme != null) {
            ThemeKit.applySelectableChip(btnApplyDream, theme, true);
        }
        status.setText("");
    }

    // ---- Favorites -------------------------------------------------------------------------------

    private void toggleFavorite() {
        if (busy) return;
        if (WallpaperStore.isFavorite(this, id)) {
            WallpaperStore.removeFavorite(this, id);
            updateFavoriteButton();
            Toast.makeText(this, R.string.preview_favorite_removed, Toast.LENGTH_SHORT).show();
            return;
        }

        File existing = findLocalFile();
        if (existing != null) {
            try {
                WallpaperStore.saveFavorite(this, asWallpaper(), existing);
                updateFavoriteButton();
                Toast.makeText(this, R.string.preview_favorite_saved, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.err_network, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        busy = true;
        status.setText(getString(R.string.preview_downloading, "0 MB"));
        exec.execute(() -> {
            try {
                File downloaded = WallpaperStore.downloadWallpaper(this, asWallpaper(), this::onProgress);
                WallpaperStore.saveFavorite(this, asWallpaper(), downloaded);
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showImage(downloaded);
                    realDims = WallpaperStore.dimensions(downloaded);
                    realFormat = WallpaperStore.format(downloaded);
                    realBytes = downloaded.length();
                    updateMeta();
                    updateFavoriteButton();
                    status.setText("");
                    Toast.makeText(this, R.string.preview_favorite_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    busy = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (e instanceof CatalogException) showError((CatalogException) e);
                    else status.setText(R.string.err_network);
                });
            }
        });
    }

    private void onProgress(long bytes) {
        long now = System.currentTimeMillis();
        if (now - lastProgress < PROGRESS_INTERVAL_MS) return;
        lastProgress = now;
        main.post(() -> {
            if (!busy || isFinishing() || isDestroyed()) return;
            status.setText(getString(R.string.preview_downloading, formatMb(bytes)));
        });
    }

    private void showError(CatalogException e) {
        String message;
        switch (e.code) {
            case OFFLINE:
                message = getString(R.string.preview_err_offline);
                break;
            case TOO_BIG:
                message = getString(R.string.preview_err_too_big, formatMb(WallpaperStore.MAX_IMAGE_BYTES));
                break;
            case HTTP:
                message = e.httpCode == 429
                        ? getString(R.string.preview_err_rate)
                        : getString(R.string.err_http, e.httpCode);
                break;
            case BAD_URL:
            case NOT_HTTPS:
                message = getString(R.string.err_url);
                break;
            default:
                message = getString(R.string.preview_err_invalid);
                break;
        }
        status.setText(message + "\n" + getString(R.string.preview_kept_previous));
        btnApply.setText(applied ? R.string.preview_applied : R.string.preview_apply);
        btnApplyDream.setText(dreamApplied ? R.string.preview_applied_dream : R.string.preview_apply_dream);
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
