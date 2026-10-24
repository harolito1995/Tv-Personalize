package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grid of remote and favorite wallpapers. Thumbnails are decoded at 960x540 for razor-sharp
 * rendering on 16:9 cards, cached in memory (32 MB LRU) and on disk. Cards scale smoothly
 * on focus and display badges for active wallpapers, screensavers or favorites.
 */
class CatalogAdapter extends RecyclerView.Adapter<WallpaperAdapter.Holder> {

    interface Listener {
        void onOpen(Catalog.Wallpaper w);
    }

    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(32 * 1024 * 1024) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final Context ctx;
    private final Listener listener;
    private final List<Catalog.Wallpaper> items = new ArrayList<>();
    private final Set<String> failed = new HashSet<>();
    private LauncherTheme theme;
    private volatile int epoch;

    CatalogAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.theme = LauncherTheme.byId(Prefs.theme(ctx));
    }

    void setTheme(LauncherTheme t) {
        theme = t;
    }

    void setItems(List<Catalog.Wallpaper> fresh) {
        epoch++;
        items.clear();
        items.addAll(fresh);
        failed.clear();
        notifyDataSetChanged();
        prefetch(fresh, epoch);
    }

    private void prefetch(List<Catalog.Wallpaper> list, int currentEpoch) {
        for (Catalog.Wallpaper w : list) {
            if (CACHE.get(w.id) != null) continue;
            final String id = w.id;
            final Catalog.Wallpaper item = w;
            THUMBS.execute(() -> {
                Bitmap b = loadBitmap(item);
                if (b != null) {
                    MAIN.post(() -> {
                        if (currentEpoch != epoch) return;
                        CACHE.put(id, b);
                        int p = positionOf(id);
                        if (p >= 0) notifyItemChanged(p);
                    });
                }
            });
        }
    }

    private Bitmap loadBitmap(Catalog.Wallpaper w) {
        try {
            File f = WallpaperStore.getFavoriteFile(ctx, w.id);
            if (f == null || !f.exists()) {
                f = WallpaperStore.thumbFile(ctx, w.id);
            }
            if ((f == null || !f.exists()) && !w.thumbUrl.isEmpty()) {
                f = WallpaperStore.downloadThumb(ctx, w, null);
            }
            if (f != null && f.exists()) {
                return WallpaperStore.decode(f, 960, 540);
            }
        } catch (Exception | OutOfMemoryError ignored) {
        }
        return null;
    }

    @NonNull
    @Override
    public WallpaperAdapter.Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wallpaper, parent, false);
        return new WallpaperAdapter.Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WallpaperAdapter.Holder h, int position) {
        Catalog.Wallpaper w = items.get(position);
        ThemeKit.applyCard(h.card, theme);
        h.name.setText(w.title);
        h.name.setTextColor(theme.textSecondary);

        String activeWallpaperId = Prefs.wallpaperId(ctx);
        String activeDreamUri = Prefs.dreamWallpaper(ctx);
        boolean isWallpaper = w.id.equals(activeWallpaperId);
        boolean isDream = !activeDreamUri.isEmpty() && activeDreamUri.contains(w.id);
        boolean isFav = WallpaperStore.isFavorite(ctx, w.id);

        if (isWallpaper) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setImageResource(R.drawable.ic_star);
        } else if (isDream) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setImageResource(R.drawable.ic_moon);
        } else if (isFav) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setImageResource(R.drawable.ic_star);
        } else {
            h.badge.setVisibility(View.GONE);
        }

        h.thumb.setImageDrawable(null);
        Bitmap cached = CACHE.get(w.id);
        if (cached != null) {
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.thumb.setAlpha(1f);
            h.thumb.setImageBitmap(cached);
        } else if (!failed.contains(w.id)) {
            showPlaceholder(h);
            final String id = w.id;
            final int myEpoch = epoch;
            THUMBS.execute(() -> {
                Bitmap b = loadBitmap(w);
                MAIN.post(() -> {
                    if (myEpoch != epoch) return;
                    if (b == null) {
                        failed.add(id);
                    } else {
                        CACHE.put(id, b);
                    }
                    int p = positionOf(id);
                    if (p >= 0) notifyItemChanged(p);
                });
            });
        } else {
            showPlaceholder(h);
        }

        h.card.setOnClickListener(v -> listener.onOpen(w));
        h.card.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? 1.045f : 1.0f;
            v.animate().scaleX(s).scaleY(s)
                    .setDuration(hasFocus ? 140 : 110)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            h.name.animate().alpha(hasFocus ? 1f : 0f)
                    .translationY(hasFocus ? 0 : 4 * ctx.getResources().getDisplayMetrics().density)
                    .setDuration(120).start();
        });
    }

    private static void showPlaceholder(WallpaperAdapter.Holder h) {
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        h.thumb.setAlpha(0.22f);
        h.thumb.setImageResource(R.drawable.ic_image);
    }

    int positionOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
