package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thumbnail grid for the wallpaper picker. The first item is always "Black" (no wallpaper). */
class WallpaperAdapter extends RecyclerView.Adapter<WallpaperAdapter.Holder> {

    interface Listener {
        void onPick(Item item);
    }

    static final class Item {
        final Uri uri;          // file:// for scanned files, content:// for picked ones
        final String name;
        final boolean video;
        final boolean isNone;

        private Item(Uri uri, String name, boolean video, boolean isNone) {
            this.uri = uri;
            this.name = name;
            this.video = video;
            this.isNone = isNone;
        }

        static Item none() {
            return new Item(null, "", false, true);
        }

        static Item file(File f, boolean video) {
            return new Item(Uri.fromFile(f), f.getName(), video, false);
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Item> items = new ArrayList<>();
    private String selectedUri;
    private LauncherTheme theme;

    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                    return value.getByteCount();
                }
            };

    WallpaperAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.selectedUri = Prefs.wallpaper(ctx);
        this.theme = LauncherTheme.byId(Prefs.theme(ctx));
    }

    void setTheme(LauncherTheme t) {
        theme = t;
    }

    void setItems(List<Item> fresh) {
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    void setSelected(String uri) {
        selectedUri = uri;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wallpaper, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Item it = items.get(position);
        h.card.setBackground(ThemeKit.cardBackground(ctx, theme));
        h.name.setText(it.isNone ? ctx.getString(R.string.wallpaper_none) : it.name);
        h.name.setTextColor(theme.textSecondary);
        boolean selected = it.uri != null && it.uri.toString().equals(selectedUri);

        // The badge doubles as the "currently applied" marker.
        if (selected) {
            h.badge.setImageResource(R.drawable.ic_star);
            h.badge.setVisibility(View.VISIBLE);
        } else if (it.video) {
            h.badge.setImageResource(R.drawable.ic_play);
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }

        h.thumb.setImageDrawable(null);
        if (it.isNone) {
            h.thumb.setBackgroundColor(0xFF101010);
        } else {
            h.thumb.setBackground(null);
            String key = it.uri.toString();
            Bitmap cached = CACHE.get(key);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                THUMBS.execute(() -> {
                    Bitmap b = loadThumb(it);
                    if (b == null) return;
                    CACHE.put(key, b);
                    MAIN.post(() -> {
                        int p = positionOf(key);
                        if (p >= 0) notifyItemChanged(p);
                    });
                });
            }
        }
        h.card.setOnClickListener(v -> listener.onPick(it));
    }

    private int positionOf(String key) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.uri != null && it.uri.toString().equals(key)) return i;
        }
        return -1;
    }

    private static Bitmap loadThumb(Item it) {
        try {
            if (it.video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(it.uri.getPath());
                    Bitmap f = r.getFrameAtTime(0);
                    if (f == null) return null;
                    Bitmap small = Bitmap.createScaledBitmap(f, 640, 360, true);
                    if (small != f) f.recycle();
                    return small;
                } finally {
                    r.release();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(it.uri.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 640 && o.outHeight / (sample * 2) >= 360) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(it.uri.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView thumb;
        final ImageView badge;
        final TextView name;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            thumb = v.findViewById(R.id.thumb);
            badge = v.findViewById(R.id.badge);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? 1.045f : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 140 : 110)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? 0f : density * 4f)
                        .setDuration(180).start();
            });
        }
    }
}
