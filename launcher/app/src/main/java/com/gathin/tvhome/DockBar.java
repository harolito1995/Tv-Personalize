package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single row of tiles on the home screen: Home, up to {@link #APP_SLOTS} favourite apps, and
 * "All apps". Favourites come from {@link Prefs} (edit them with a long-press on a tile, or from the
 * All apps screen). Tiles are plain views in a horizontal LinearLayout, so the D-pad moves between
 * them with the normal focus search; {@link MainActivity} wires up/down/ends.
 */
final class DockBar {

    static final int APP_SLOTS = 8;
    static final float FOCUS_SCALE = 1.08f;

    private static final int KIND_HOME = 0;
    private static final int KIND_APP = 1;
    private static final int KIND_ALL = 2;

    interface Callbacks {
        /** A tile got focus; index is its position in the row (0 = Home). */
        void onTileFocused(int index);

        void onAllApps();

        /** The row was rebuilt: tile views and ids changed, so re-apply any focus wiring. */
        void onRebuilt();
    }

    private static final class Item {
        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final String key;
        Drawable banner;
        Drawable icon;
        View tile;
        ImageView art;
        ImageView glyph;
        boolean loading;

        Item(int kind, String label, ComponentName component) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component != null ? component.getPackageName() : "";
            this.key = component != null ? component.flattenToShortString() : "";
        }
    }

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Activity act;
    private final LinearLayout row;
    private final Callbacks callbacks;
    private final float density;
    private final List<Item> items = new ArrayList<>();
    private LauncherTheme theme;

    DockBar(Activity act, LinearLayout row, Callbacks callbacks) {
        this.act = act;
        this.row = row;
        this.callbacks = callbacks;
        this.density = act.getResources().getDisplayMetrics().density;
        this.theme = LauncherTheme.byId(Prefs.theme(act));
    }

    void setTheme(LauncherTheme t) {
        theme = t;
    }

    int size() {
        return items.size();
    }

    View tile(int index) {
        return index >= 0 && index < items.size() ? items.get(index).tile : null;
    }

    /** Index of the app with this key (component), or -1. */
    int indexOfKey(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key)) return i;
        }
        return -1;
    }

    /** Focuses a tile (clamped to the row). False when the row is empty. */
    boolean focus(int index) {
        View t = tile(Math.max(0, Math.min(index, items.size() - 1)));
        return t != null && t.requestFocus();
    }

    /** Rebuilds the row from the current favourites and installed apps. */
    void reload() {
        items.clear();
        row.removeAllViews();

        items.add(new Item(KIND_HOME, act.getString(R.string.nav_home), null));
        PackageManager pm = act.getPackageManager();
        int apps = 0;
        for (String stored : Prefs.favourites(act)) {
            if (apps >= APP_SLOTS) break;
            String pkg = stored.contains("/") ? stored.substring(0, stored.indexOf('/')) : stored;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null || launch.getComponent() == null) continue;
            ComponentName cn = launch.getComponent();
            if (indexOfKey(cn.flattenToShortString()) >= 0) continue;
            items.add(new Item(KIND_APP, labelOf(pm, cn, pkg), cn));
            apps++;
        }

        LayoutInflater inflater = LayoutInflater.from(act);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            View cell = inflater.inflate(R.layout.item_dock, row, false);
            bindCell(cell, it, i);
            row.addView(cell);
            if (it.kind == KIND_APP) loadArt(it);
        }
        callbacks.onRebuilt();
    }

    private static String labelOf(PackageManager pm, ComponentName cn, String pkg) {
        try {
            return String.valueOf(pm.getActivityInfo(cn, 0).loadLabel(pm));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---- tiles -----------------------------------------------------------------------------------

    private void bindCell(View cell, Item it, int index) {
        FrameLayout tile = cell.findViewById(R.id.tile);
        ImageView art = cell.findViewById(R.id.art);
        ImageView glyph = cell.findViewById(R.id.glyph);
        TextView inner = cell.findViewById(R.id.inner);
        TextView label = cell.findViewById(R.id.label);
        it.tile = tile;
        it.art = art;
        it.glyph = glyph;
        tile.setId(View.generateViewId());   // unique per tile so focus wiring can point at it

        final float radius = 12f * density;
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);

        if (it.kind == KIND_APP) {
            label.setText(it.label);
            label.setTextColor(theme.textSecondary);
            inner.setVisibility(View.GONE);
            Drawable[] cached = AppAdapter.cacheGet(it.key);
            if (cached != null) {
                it.banner = cached[0];
                it.icon = cached[1];
            }
            bindArt(it);
            tile.setOnClickListener(v -> launch(it));
            tile.setOnLongClickListener(v -> {
                showMenu(v, it);
                return true;
            });
        } else {
            // Home: a glyph with its name inside the tile, no label underneath.
            label.setVisibility(View.GONE);
            art.setVisibility(View.GONE);
            glyph.setImageResource(R.drawable.ic_home);
            glyph.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            glyph.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) glyph.getLayoutParams();
            glp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            glp.topMargin = (int) (6 * density);
            glyph.setLayoutParams(glp);
            inner.setText(it.label);
            inner.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams lp = tile.getLayoutParams();
            lp.height = (int) (52 * density);   // Home is the taller, "selected" tile
            tile.setLayoutParams(lp);
        }

        tile.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s)
                    .setDuration(focused ? 130 : 100)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            v.setElevation(focused ? 10f * density : 0f);
            if (focused) {
                // Elevation controls Z-ordering. DO NOT call bringToFront() because
                // in LinearLayout it removes and appends the child, reordering items!
                callbacks.onTileFocused(items.indexOf(it));
            }
        });
    }

    private void bindArt(Item it) {
        if (it.art == null) return;
        if (it.banner != null) {
            it.art.setImageDrawable(it.banner);
            it.art.setVisibility(View.VISIBLE);
            it.glyph.setVisibility(View.GONE);
        } else if (it.icon != null) {
            it.art.setVisibility(View.GONE);
            it.glyph.setImageTintList(null);
            it.glyph.setImageDrawable(it.icon);
            FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) it.glyph.getLayoutParams();
            glp.gravity = Gravity.CENTER;
            glp.width = glp.height = (int) (34 * density);
            it.glyph.setLayoutParams(glp);
            it.glyph.setVisibility(View.VISIBLE);
        }
    }

    private void loadArt(final Item it) {
        if ((it.banner != null || it.icon != null) || it.loading) return;
        it.loading = true;
        EXEC.execute(() -> {
            final Drawable[] art = AppAdapter.loadArtwork(act, it.component);
            MAIN.post(() -> {
                it.loading = false;
                if (art == null || !items.contains(it)) return;   // gone, or the row was rebuilt
                it.banner = art[0];
                it.icon = art[1];
                bindArt(it);
            });
        });
    }

    private void launch(Item it) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(it.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            TopShelfStore.recordAppLaunch(act, it.pkg);
            act.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(act, act.getString(R.string.cant_open, it.label), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- long-press menu -------------------------------------------------------------------------

    private void showMenu(View anchor, Item it) {
        View panel = LayoutInflater.from(act).inflate(R.layout.popup_inputs, null);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (230 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        List<String> order = Prefs.favourites(act);
        int index = order.indexOf(it.pkg);
        if (index > 0) {
            addRow(rows, popup, R.drawable.ic_move_left, act.getString(R.string.move_left), () -> {
                if (Prefs.moveFavourite(act, it.pkg, -1)) reloadAndFocus(it.key);
            });
        }
        if (index >= 0 && index < order.size() - 1) {
            addRow(rows, popup, R.drawable.ic_move_right, act.getString(R.string.move_right), () -> {
                if (Prefs.moveFavourite(act, it.pkg, 1)) reloadAndFocus(it.key);
            });
        }
        addRow(rows, popup, R.drawable.ic_star, act.getString(R.string.remove_favourite), () -> {
            Prefs.toggleFavourite(act, it.pkg, Integer.MAX_VALUE);   // it is a favourite, so this removes it
            reload();
            focus(1);
        });
        addRow(rows, popup, R.drawable.ic_settings, act.getString(R.string.app_info), () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
        });

        // The dock sits at the bottom of the screen, so open the menu above the tile.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int panelH = panel.getMeasuredHeight();
        int margin = (int) (8 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int x = Math.max(0, Math.min(loc[0], dm.widthPixels - popup.getWidth() - margin));
        int y = loc[1] - panelH - margin;
        if (y < margin) y = loc[1] + anchor.getHeight() + margin;
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        View first = rows.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private void reloadAndFocus(String key) {
        reload();
        final int idx = indexOfKey(key);
        row.post(() -> focus(idx >= 0 ? idx : 1));
    }

    private void addRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View r = LayoutInflater.from(act).inflate(R.layout.item_menu, rows, false);
        ((ImageView) r.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        r.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(r);
    }
}


