package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * "All apps": every launchable app in a grid. Favourites come first (they are the dock on the home
 * screen); long-press any tile to add it to the dock, move it, remove it or open its app info.
 */
public class AllAppsActivity extends Activity implements AppAdapter.TileListener {

    private FrameLayout rootView;
    private TextView titleView;
    private TextView countView;
    private RecyclerView grid;
    private AppAdapter adapter;
    private int firstFocusRetries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);

        rootView = findViewById(R.id.allAppsRoot);
        titleView = findViewById(R.id.allAppsTitle);
        countView = findViewById(R.id.allAppsCount);

        int columns = Math.max(1, getResources().getInteger(R.integer.columns));
        grid = findViewById(R.id.allAppsGrid);
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // The dock holds DockBar.APP_SLOTS favourites; every tile shows its name when focused here.
        adapter = new AppAdapter(this, this, DockBar.APP_SLOTS, 0);
        grid.setAdapter(adapter);
        applyTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
        adapter.reload();
        updateCount();
        focusFirstTile();
    }

    private void applyTheme() {
        LauncherTheme theme = LauncherTheme.byId(Prefs.theme(this));
        if (rootView != null) {
            rootView.setBackground(ThemeKit.fallback(this, theme));
        }
        if (titleView != null) {
            titleView.setTextColor(theme.textPrimary);
        }
        if (countView != null) {
            countView.setBackground(ThemeKit.weatherCapsuleBackground(this, theme));
            countView.setTextColor(theme.textSecondary);
        }
        if (adapter != null) {
            adapter.setTheme(theme);
        }
    }

    private void updateCount() {
        if (countView == null || adapter == null) return;
        int n = adapter.getItemCount();
        countView.setText(getString(R.string.all_apps_count, n));
        countView.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    private void focusFirstTile() {
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
            View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
            if (card != null) {
                card.requestFocus();
            } else if (firstFocusRetries++ < 5) {
                grid.postDelayed(this::focusFirstTile, 120);   // tiles are laid out a frame later
            }
        });
    }

    // ---- AppAdapter.TileListener ------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        // Nothing to animate here: the tile scales itself and shows its own name.
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        return false;   // the grid's own focus search moves one row at a time
    }

    @Override
    public void onFavouriteChanged(String key) {
        adapter.reload();
        updateCount();
        final int pos = adapter.positionOf(key);
        if (pos < 0) return;
        grid.post(() -> {
            grid.scrollToPosition(pos);
            grid.postDelayed(() -> {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
                View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
                if (card != null) card.requestFocus();
            }, 120);
        });
    }
}
