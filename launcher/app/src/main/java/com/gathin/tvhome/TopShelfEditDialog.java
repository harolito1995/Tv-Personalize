package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV remote-navigable dialog for configuring a Top Shelf card:
 * Select destination app from installed applications or reset to auto-detection.
 */
public final class TopShelfEditDialog {

    private static final ExecutorService APP_LOAD_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

        root.setBackground(ThemeKit.dialogSurface(context, theme));

        TextView header = new TextView(context);
        header.setText((context.getString(R.string.top_shelf_card_empty) + " " + (slot + 1)).toUpperCase());
        header.setTextColor(theme.textPrimary);
        header.setTextSize(18);
        header.setPadding(0, 0, 0, (int) (14 * density));
        root.addView(header);

        TextView subtitle = new TextView(context);
        subtitle.setText(R.string.top_shelf_edit_hint);
        subtitle.setTextColor(theme.textDim);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, 0, 0, (int) (12 * density));
        root.addView(subtitle);

        TextView btnReset = new TextView(context);
        btnReset.setId(View.generateViewId());
        btnReset.setText("\u21BA  Restablecer a autom\u00E1tico");
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
            Toast.makeText(context, "Tarjeta restablecida a autom\u00E1tico", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSlotUpdated(slot);
            dialog.dismiss();
        });
        root.addView(btnReset);

        View divider = new View(context);
        divider.setBackgroundColor(theme.cardBorderIdle);
        android.widget.LinearLayout.LayoutParams dParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (1 * density));
        dParams.setMargins(0, (int) (14 * density), 0, (int) (10 * density));
        root.addView(divider, dParams);

        TextView appsLabel = new TextView(context);
        appsLabel.setText("ELEGIR APLICACI\u00D3N");
        appsLabel.setTextColor(theme.textDim);
        appsLabel.setTextSize(12);
        appsLabel.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(appsLabel);

        final List<AppEntry> apps = new ArrayList<>();
        ListView listView = new ListView(context);
        listView.setId(View.generateViewId());
        listView.setDivider(null);
        listView.setDividerHeight((int) (4 * density));
        listView.setItemsCanFocus(false);
        listView.setFocusable(true);
        listView.setFocusableInTouchMode(true);
        btnReset.setNextFocusDownId(listView.getId());
        listView.setNextFocusUpId(btnReset.getId());

        GradientDrawable selector = new GradientDrawable();
        selector.setColor(theme.controlFocusFill);
        selector.setCornerRadius(Math.round(12f * density));
        selector.setStroke(Math.max(1, Math.round(1.25f * density)), theme.controlFocusStroke);
        listView.setSelector(selector);
        listView.setDrawSelectorOnTop(false);

        final ArrayAdapter<AppEntry> adapter = new ArrayAdapter<AppEntry>(context, 0, apps) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(context);
                    rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    rowLayout.setGravity(Gravity.CENTER_VERTICAL);
                    rowLayout.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (10 * density));
                    // Crucial for Android TV ListView: child rows must NOT be focusable or clickable,
                    // otherwise they swallow KEYCODE_DPAD_CENTER and prevent OnItemClickListener from firing!
                    rowLayout.setFocusable(false);
                    rowLayout.setClickable(false);
                    rowLayout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

                    ImageView icon = new ImageView(context);
                    icon.setId(android.R.id.icon);
                    icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                            (int) (32 * density), (int) (32 * density)));
                    rowLayout.addView(icon);

                    TextView label = new TextView(context);
                    label.setId(android.R.id.text1);
                    label.setTextSize(14);
                    label.setTextColor(theme.textPrimary);
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
                }
                return row;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= apps.size()) return;
            AppEntry entry = apps.get(position);
            TopShelfStore.saveSlot(context, slot, entry.label, "", "", entry.pkg);
            Toast.makeText(context, entry.label + " asignada", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onSlotUpdated(slot);
            dialog.dismiss();
        });

        android.widget.LinearLayout.LayoutParams listParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(listView, listParams);

        dialog.setContentView(root);
        dialog.show();
        btnReset.requestFocus();

        final Context appCtx = context.getApplicationContext();
        APP_LOAD_EXEC.execute(() -> {
            final List<AppEntry> loaded = getInstalledApps(appCtx);
            MAIN.post(() -> {
                if (!dialog.isShowing()) return;
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
                if (!apps.isEmpty()) {
                    listView.setSelection(0);
                    listView.requestFocus();
                }
            });
        });
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
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName())) continue;
            if (seen.add(pkg)) {
                result.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
            }
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName())) continue;
            if (seen.add(pkg)) {
                result.add(new AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
            }
        }

        result.sort(Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }
}