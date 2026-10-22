package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local photo picker for the screensaver. Reuses the wallpaper grid and thumbnail cache, but
 * selects a private screensaver photo instead of changing the Home wallpaper.
 */
public class DreamPhotoActivity extends Activity {

    private static final int REQ_PERM = 20;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_ITEMS = 400;

    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WallpaperAdapter adapter;
    private RecyclerView grid;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dream_photo);

        LauncherTheme theme = LauncherTheme.byId(Prefs.theme(this));
        findViewById(R.id.dreamPickRoot).setBackgroundColor(theme.screenBg);
        ((TextView) findViewById(R.id.dreamPickTitle)).setTextColor(theme.textPrimary);
        ((TextView) findViewById(R.id.dreamPickHint)).setTextColor(theme.textDim);
        status = findViewById(R.id.dreamPickStatus);
        status.setTextColor(theme.textDim);

        TextView useWallpaper = findViewById(R.id.btnDreamUseWallpaper);
        ThemeKit.applyPill(useWallpaper, theme);
        useWallpaper.setNextFocusDownId(R.id.dreamPickGrid);
        useWallpaper.setOnClickListener(v -> {
            Prefs.setDreamWallpaper(this, "");
            Toast.makeText(this, R.string.dream_use_wallpaper_applied, Toast.LENGTH_SHORT).show();
            finish();
        });

        grid = findViewById(R.id.dreamPickGrid);
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        grid.setHasFixedSize(true);
        grid.setNextFocusUpId(R.id.btnDreamUseWallpaper);
        adapter = new WallpaperAdapter(this, this::apply);
        adapter.setTheme(theme);
        adapter.setSelected(Prefs.dreamWallpaper(this));
        grid.setAdapter(adapter);

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            scan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scan();
        } else {
            status.setText(R.string.wallpaper_empty);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanExec.shutdownNow();
    }

    private void apply(WallpaperAdapter.Item item) {
        if (item.uri == null) return;
        Prefs.setDreamWallpaper(this, item.uri.toString());
        Toast.makeText(this, R.string.dream_applied, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void scan() {
        status.setText(R.string.dream_pick_scanning);
        adapter.setItems(Collections.emptyList());
        scanExec.execute(() -> {
            List<WallpaperAdapter.Item> found = new ArrayList<>();
            for (File root : roots()) {
                walk(root, 0, found);
                if (found.size() >= MAX_ITEMS) break;
            }
            Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setItems(found);
                status.setText(found.isEmpty() ? getString(R.string.dream_pick_empty) : "");
                if (!found.isEmpty()) focusFirst();
            });
        });
    }

    private void focusFirst() {
        if (tryFocusFirst()) return;
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!tryFocusFirst()) grid.postDelayed(DreamPhotoActivity.this::tryFocusFirst, 250);
            }
        });
        grid.scrollToPosition(0);
    }

    private boolean tryFocusFirst() {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
        if (holder == null) return false;
        View card = holder.itemView.findViewById(R.id.card);
        return card != null && card.requestFocus();
    }

    private static List<File> roots() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File volume : volumes) {
                String name = volume.getName();
                if (name.equals("emulated") || name.equals("self") || name.equals("enc_emulated")) {
                    continue;
                }
                if (volume.isDirectory() && volume.canRead()) out.add(volume);
            }
        }
        return out;
    }

    private static void walk(File dir, int depth, List<WallpaperAdapter.Item> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_ITEMS) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File file : kids) {
            if (out.size() >= MAX_ITEMS) return;
            String name = file.getName();
            if (name.startsWith(".")) continue;
            if (file.isDirectory()) {
                if (!name.equals("Android")) walk(file, depth + 1, out);
            } else if (file.isFile() && isImage(name.toLowerCase(Locale.ROOT))) {
                out.add(WallpaperAdapter.Item.file(file, false));
            }
        }
    }

    private static boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif") || lowerName.endsWith(".bmp");
    }
}
