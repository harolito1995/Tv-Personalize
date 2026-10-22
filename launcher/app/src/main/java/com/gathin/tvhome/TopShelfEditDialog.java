package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TV remote-navigable dialog for configuring a Top Shelf card:
 * Select destination app from installed applications or reset to auto-detection.
 */
public final class TopShelfEditDialog {

    public interface OnSlotUpdatedListener {
        void onSlotUpdated(int slot);
    }

    public static void show(Context context, int slot, OnSlotUpdatedListener listener) {
        if (context == null) return;

        Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout((int) (480 * context.getResources().getDisplayMetrics().density),
                    (int) (520 * context.getResources().getDisplayMetrics().density));
            window.setGravity(Gravity.CENTER);
        }

        float density = context.getResources().getDisplayMetrics().density;
        LauncherTheme theme = LauncherTheme.byId(Prefs.theme(context));

        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding((int) (24 * density), (int) (24 * density), (int) (24 * density), (int) (24 * density));

        // Dark glass background
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0121722);
        bg.setCornerRadius(20 * density);
        bg.setStroke((int) (1.5f * density), theme.accent);
        root.setBackground(bg);

        // Header Title
        TextView header = new TextView(context);
        header.setText((context.getString(R.string.top_shelf_card_empty) + " " + (slot + 1)).toUpperCase());
        header.setTextColor(Color.WHITE);
        header.setTextSize(18);
        header.setPadding(0, 0, 0, (int) (14 * density));
        root.addView(header);

        // Subtitle / instructions
        TextView subtitle = new TextView(context);
        subtitle.setText(R.string.top_shelf_edit_hint);
        subtitle.setTextColor(theme.textDim);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 0, 0, (int) (12 * density));
        root.addView(subtitle);

                // Demo cinematic button
        TextView btnDemo = new TextView(context);
        btnDemo.setText("▶  " + context.getString(R.string.top_shelf_load_demo));
        btnDemo.setTextColor(theme.accent);
        btnDemo.setTextSize(14);
        btnDemo.setFocusable(true);
        btnDemo.setClickable(true);
        btnDemo.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        ThemeKit.applyPill(btnDemo, theme);
        btnDemo.setOnClickListener(v -> {
            TopShelfStore.loadDemo(context);
            Toast.makeText(context, R.string.top_shelf_demo_loaded, Toast.LENGTH_SHORT).show();
            if (listener != null) {
                listener.onSlotUpdated(0);
                listener.onSlotUpdated(1);
            }
            dialog.dismiss();
        });
        root.addView(btnDemo);

        // Reset to auto button
        TextView btnReset = new TextView(context);
        btnReset.setText("↺  Restablecer a automático");
        btnReset.setTextColor(theme.textPrimary);
        btnReset.setTextSize(14);
        btnReset.setFocusable(true);
        btnReset.setClickable(true);
        btnReset.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        ThemeKit.applyPill(btnReset, theme);
        android.widget.LinearLayout.LayoutParams rParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rParams.setMargins(0, (int) (8 * density), 0, 0);
        btnReset.setLayoutParams(rParams);
        btnReset.setOnClickListener(v -> {
            TopShelfStore.resetSlot(context, slot);
            Toast.makeText(context, "Tarjeta restablecida a automático", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSlotUpdated(slot);
            dialog.dismiss();
        });
        root.addView(btnReset);

        // Divider
        View divider = new View(context);
        divider.setBackgroundColor(0x33FFFFFF);
        android.widget.LinearLayout.LayoutParams dParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density));
        dParams.setMargins(0, (int) (14 * density), 0, (int) (10 * density));
        root.addView(divider, dParams);

        // Section header for apps
        TextView appsLabel = new TextView(context);
        appsLabel.setText("ELEGIR APLICACIÓN");
        appsLabel.setTextColor(theme.textDim);
        appsLabel.setTextSize(12);
        appsLabel.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(appsLabel);

        // List of installed apps
        List<AppEntry> apps = getInstalledApps(context);
        ListView listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight((int) (4 * density));
        listView.setAdapter(new ArrayAdapter<AppEntry>(context, 0, apps) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(context);
                    rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    rowLayout.setGravity(Gravity.CENTER_VERTICAL);
                    rowLayout.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (10 * density));
                    rowLayout.setFocusable(true);
                    rowLayout.setClickable(true);

                    ImageView icon = new ImageView(context);
                    icon.setId(android.R.id.icon);
                    icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                            (int) (32 * density), (int) (32 * density)));
                    rowLayout.addView(icon);

                    TextView label = new TextView(context);
                    label.setId(android.R.id.text1);
                    label.setTextSize(14);
                    label.setTextColor(Color.WHITE);
                    label.setPadding((int) (12 * density), 0, 0, 0);
                    rowLayout.addView(label);

                    row = rowLayout;
                }

                AppEntry item = getItem(position);
                if (item != null) {
                    ImageView iv = row.findViewById(android.R.id.icon);
                    TextView tv = row.findViewById(android.R.id.text1);
                    if (iv != null) iv.setImageDrawable(item.icon);
                    if (tv != null) tv.setText(item.label);
                    ThemeKit.applyPill(row, theme);
                }
                return row;
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = apps.get(position);
            TopShelfStore.saveSlot(context, slot, entry.label, "Acceso directo", "", entry.pkg);
            Toast.makeText(context, entry.label + " asignada", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSlotUpdated(slot);
            dialog.dismiss();
        });

        android.widget.LinearLayout.LayoutParams listParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, listParams);

        dialog.setContentView(root);
        dialog.show();
    }

    private static class AppEntry {
        final String label;
        final String pkg;
        final android.graphics.drawable.Drawable icon;

        AppEntry(String label, String pkg, android.graphics.drawable.Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private static List<AppEntry> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<AppEntry> result = new ArrayList<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) {
                result.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
            }
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) {
                result.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
            }
        }

        result.sort(Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }
}
