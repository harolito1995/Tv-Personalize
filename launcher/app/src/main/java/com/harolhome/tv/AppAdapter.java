package com.harolhome.tv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App tiles for the "All apps" screen. Favourites (the dock, edited on the TV: long-press a tile to
 * add or remove it) come first, the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** A tile was added to or removed from the dock; refocus that app. Argument is its key. */
        void onFavouriteChanged(String key);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final String key;
        Drawable banner;
        Drawable icon;
        boolean loading;
        int ambient;

        Entry(String label, ComponentName component) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.key = component.flattenToShortString();
        }
    }

    static final float FOCUS_SCALE = 1.045f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** The first this-many tiles show no name (0 = every tile shows its name when focused). */
    private final int unlabelledCount;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private LauncherTheme theme;

    // Icons and banners load off the main thread and are cached for the process lifetime, so
    // returning from another app is instant instead of re-decoding every icon.
    private static final ExecutorService ICON_EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Drawable[]> ICON_CACHE = new HashMap<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this(ctx, listener, dockSize, dockSize);
    }

    AppAdapter(Context ctx, TileListener listener, int dockSize, int unlabelledCount) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.unlabelledCount = unlabelledCount;
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.theme = LauncherTheme.byId(Prefs.theme(ctx));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void setTheme(LauncherTheme t) {
        theme = t;
    }

    void reload() {
        List<String> favourites = Prefs.favourites(ctx);
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Entry e = new Entry(String.valueOf(ri.loadLabel(pm)),
                    new ComponentName(pkg, ri.activityInfo.name));
            Drawable[] cached = cacheGet(e.key);
            if (cached != null) {
                e.banner = cached[0];
                e.icon = cached[1];
            } else {
                scheduleLoad(e);
            }
            fresh.add(e);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    // ---- icons -----------------------------------------------------------------------------------

    /**
     * Loads the banner and icon of a launcher activity. Blocking: call it off the main thread.
     * Returns {banner, icon} (either may be null), or null when the app is gone.
     */
    static Drawable[] loadArtwork(Context ctx, ComponentName component) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ResolveInfo ri = pm.resolveActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            .setComponent(component), 0);
            if (ri == null || ri.activityInfo == null) return null;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            if (banner == null && icon == null) return null;
            cachePut(component.flattenToShortString(), banner, icon);
            return new Drawable[]{banner, icon};
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized Drawable[] cacheGet(String key) {
        return ICON_CACHE.get(key);
    }

    static synchronized void cachePut(String key, Drawable banner, Drawable icon) {
        ICON_CACHE.put(key, new Drawable[]{banner, icon});
    }

    private void scheduleLoad(final Entry e) {
        if (e.loading) return;
        e.loading = true;
        ICON_EXEC.execute(() -> {
            Drawable banner = null, icon = null;
            try {
                PackageManager pm = ctx.getPackageManager();
                ResolveInfo ri = pm.resolveActivity(
                        new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                                .setComponent(e.component), 0);
                if (ri != null && ri.activityInfo != null) {
                    banner = ri.activityInfo.loadBanner(pm);
                    if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                    icon = ri.loadIcon(pm);
                }
            } catch (Exception ignored) {
            }
            if (banner == null && icon == null) {
                e.loading = false;
                return;
            }
            cachePut(e.key, banner, icon);
            final Drawable fBanner = banner, fIcon = icon;
            MAIN.post(() -> {
                int pos = positionOf(e.key);
                if (pos < 0) return;
                Entry t = items.get(pos);
                t.banner = fBanner;
                t.icon = fIcon;
                t.loading = false;
                t.ambient = 0;   // the ambient colour is sampled once the artwork is there
                notifyItemChanged(pos);
            });
        });
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        if (e.banner == null && e.icon == null) {
            Drawable[] cached = cacheGet(e.key);
            if (cached != null) {
                e.banner = cached[0];
                e.icon = cached[1];
            } else {
                scheduleLoad(e);
            }
        }
        ThemeKit.applyCard(h.card, theme);
        h.label.setTextColor(theme.textPrimary);
        h.name.setTextColor(theme.textPrimary);
        int textShadow = theme.isLight() ? 0x00000000 : 0x80000000;
        float shadowRadius = theme.isLight() ? 0f : 6f;
        float shadowDy = theme.isLight() ? 0f : 2f;
        h.label.setShadowLayer(shadowRadius, 0f, shadowDy, textShadow);
        h.name.setShadowLayer(shadowRadius, 0f, shadowDy, textShadow);
        h.name.setText(e.label);
        h.name.setVisibility(position < unlabelledCount ? View.INVISIBLE : View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(e.icon != null ? View.VISIBLE : View.GONE);
            h.label.setText(e.label);
            h.label.setVisibility(e.icon != null ? View.GONE : View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            showMenu(v, e);
            return true;
        });
    }

    /** Long-press menu: add/remove the dock favourite, or open the app's system settings. */
    private void showMenu(View anchor, Entry e) {
        float density = ctx.getResources().getDisplayMetrics().density;
        View panel = LayoutInflater.from(ctx).inflate(R.layout.popup_inputs, null);
        ThemeKit.applyPopupPanel(panel, theme);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (230 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        boolean favourite = Prefs.isFavourite(ctx, e.pkg);
        addMenuRow(rows, popup, R.drawable.ic_star,
                ctx.getString(favourite ? R.string.remove_favourite : R.string.add_favourite), () -> {
                    if (Prefs.toggleFavourite(ctx, e.pkg, dockSize)) {
                        if (listener != null) listener.onFavouriteChanged(e.key);
                    } else {
                        Toast.makeText(ctx, ctx.getString(R.string.dock_full, dockSize),
                                Toast.LENGTH_SHORT).show();
                    }
                });
        if (favourite) {
            List<String> order = Prefs.favourites(ctx);
            int index = order.indexOf(e.pkg);
            if (index > 0) {
                addMenuRow(rows, popup, R.drawable.ic_move_left, ctx.getString(R.string.move_left), () -> {
                    if (Prefs.moveFavourite(ctx, e.pkg, -1) && listener != null) {
                        listener.onFavouriteChanged(e.key);
                    }
                });
            }
            if (index >= 0 && index < order.size() - 1) {
                addMenuRow(rows, popup, R.drawable.ic_move_right, ctx.getString(R.string.move_right), () -> {
                    if (Prefs.moveFavourite(ctx, e.pkg, 1) && listener != null) {
                        listener.onFavouriteChanged(e.key);
                    }
                });
            }
        }
        addMenuRow(rows, popup, R.drawable.ic_settings, ctx.getString(R.string.app_info), () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
        });

        // Below the tile by default; flip above when it would run off the screen (dock row,
        // bottom grid row), keeping the menu fully visible either way.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int panelH = panel.getMeasuredHeight();
        int margin = (int) (8 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int x = Math.max(0, Math.min(loc[0], dm.widthPixels - popup.getWidth() - margin));
        int y = loc[1] + anchor.getHeight() + margin;
        if (y + panelH > dm.heightPixels - margin) y = loc[1] - panelH - margin;
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        View first = rows.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private void addMenuRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View row = LayoutInflater.from(ctx).inflate(R.layout.item_menu, rows, false);
        ThemeKit.applyPopupRow(row, theme);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        long t0 = SystemClock.elapsedRealtime();
        try {
            ctx.startActivity(i);
            long t1 = SystemClock.elapsedRealtime();
            MainActivity.markAppLaunchStart(e.key, t0, t1 - t0);
            final String pkg = e.pkg;
            MAIN.post(() -> TopShelfStore.recordAppLaunch(ctx.getApplicationContext(), pkg));
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key.hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return theme.accent;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            e.ambient = e.banner == null && e.icon == null
                    ? theme.accent
                    : vividColor(e.banner != null ? e.banner : e.icon);
        }
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        try {
            // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
            Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
            Rect saved = new Rect(d.getBounds());
            int n = 12;
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(saved);
            float[] hsv = new float[3];
            int best = 0xFF444444;
            float bestScore = -1f;
            long r = 0, g = 0, b = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int p = bmp.getPixel(x, y);
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
            bmp.recycle();
            if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
                int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
                Color.colorToHSV(avg, hsv);
                hsv[2] = Math.max(hsv[2], 0.55f);
                return Color.HSVToColor(hsv);
            }
            Color.colorToHSV(best, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.5f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception | OutOfMemoryError e2) {
            return 0xFF444444;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = v.getResources().getDimension(R.dimen.tile_radius);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 140 : 110)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 3f : -density * 4f)
                        .setDuration(130)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
