package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent store and model for the two cinematic 16:9 Top Shelf cards.
 * Implements real Android TV Watch Next content resolution (content://android.media.tv/watch_next_program),
 * custom item overrides, and honest, non-deceptive cinema empty states.
 */
public final class TopShelfStore {

    private static final String PREFS_NAME = "top_shelf_prefs";
    private static final String KEY_RECENTS = "recent_packages";
    private static final int MAX_RECENTS = 6;

    private static final Uri WATCH_NEXT_URI = Uri.parse("content://android.media.tv/watch_next_program");

    public static final class Item {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String backdropUri;
        public final String targetPackage;
        public final int order;
        public final boolean isCustom;
        public final int progressPercent; // -1 if unknown / no progress bar, 0..100
        public final String intentUri;

        public Item(String id, String title, String subtitle, String backdropUri,
                    String targetPackage, int order, boolean isCustom) {
            this(id, title, subtitle, backdropUri, targetPackage, order, isCustom, -1, "");
        }

        public Item(String id, String title, String subtitle, String backdropUri,
                    String targetPackage, int order, boolean isCustom, int progressPercent,
                    String intentUri) {
            this.id = id != null ? id : ("slot_" + order);
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.backdropUri = backdropUri != null ? backdropUri : "";
            this.targetPackage = targetPackage != null ? targetPackage : "";
            this.order = order;
            this.isCustom = isCustom;
            this.progressPercent = progressPercent;
            this.intentUri = intentUri != null ? intentUri : "";
        }

        public boolean hasTarget() {
            return (targetPackage != null && !targetPackage.trim().isEmpty())
                    || (intentUri != null && !intentUri.trim().isEmpty());
        }

        public boolean hasBackdrop() {
            return backdropUri != null && !backdropUri.trim().isEmpty();
        }
    }

    private TopShelfStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Records an app package launched from the launcher in an LRU history. */
    public static void recordAppLaunch(Context context, String pkg) {
        if (context == null || pkg == null || pkg.trim().isEmpty()) return;
        String clean = pkg.trim();
        List<String> list = getRecentApps(context);
        list.remove(clean);
        list.add(0, clean);
        while (list.size() > MAX_RECENTS) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        prefs(context).edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    /** Returns the list of packages recently launched from the launcher. */
    public static List<String> getRecentApps(Context context) {
        if (context == null) return new ArrayList<>();
        String raw = prefs(context).getString(KEY_RECENTS, "");
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String[] parts = raw.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String clean = p.trim();
            if (!clean.isEmpty() && !list.contains(clean)) {
                list.add(clean);
            }
        }
        return list;
    }

    /** Retrieves the Item for slot 0 or slot 1. */
    public static Item getSlot(Context context, int slot) {
        if (context == null) return new Item("slot_" + slot, "", "", "", "", slot, false);
        SharedPreferences p = prefs(context);
        boolean custom = p.getBoolean("slot_" + slot + "_custom", false);
        if (custom) {
            String title = p.getString("slot_" + slot + "_title", "");
            String subtitle = p.getString("slot_" + slot + "_subtitle", "");
            String backdrop = p.getString("slot_" + slot + "_backdrop", "");
            String pkg = p.getString("slot_" + slot + "_package", "");
            int progress = p.getInt("slot_" + slot + "_progress", -1);
            String intentUri = p.getString("slot_" + slot + "_intent", "");
            return new Item("slot_" + slot, title, subtitle, backdrop, pkg, slot, true, progress, intentUri);
        }

        return new Item("slot_" + slot, "", "", "", "", slot, false, -1, "");
    }

    /** Queries the standard Android TV WatchNextPrograms content provider. */
    private static Item queryWatchNext(Context context, int slot) {
        if (context == null) return null;
        try {
            String[] projection = {
                    "_id",
                    "title",
                    "episode_title",
                    "season_number",
                    "episode_number",
                    "poster_art_uri",
                    "thumbnail_uri",
                    "last_playback_position_millis",
                    "duration_millis",
                    "package_name",
                    "intent_uri"
            };
            Cursor c = context.getContentResolver().query(
                    WATCH_NEXT_URI,
                    projection,
                    null,
                    null,
                    "last_engagement_time_utc_millis DESC, _id DESC"
            );
            if (c != null) {
                try {
                    if (c.moveToPosition(slot)) {
                        long id = c.getLong(c.getColumnIndexOrThrow("_id"));
                        String title = c.getString(c.getColumnIndexOrThrow("title"));
                        String epTitle = c.getString(c.getColumnIndexOrThrow("episode_title"));
                        String season = c.getString(c.getColumnIndexOrThrow("season_number"));
                        String episode = c.getString(c.getColumnIndexOrThrow("episode_number"));
                        String poster = c.getString(c.getColumnIndexOrThrow("poster_art_uri"));
                        String thumb = c.getString(c.getColumnIndexOrThrow("thumbnail_uri"));
                        long pos = c.getLong(c.getColumnIndexOrThrow("last_playback_position_millis"));
                        long dur = c.getLong(c.getColumnIndexOrThrow("duration_millis"));
                        String pkg = c.getString(c.getColumnIndexOrThrow("package_name"));
                        String intentUri = c.getString(c.getColumnIndexOrThrow("intent_uri"));

                        String mainTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : epTitle;
                        if (mainTitle == null || mainTitle.isEmpty()) mainTitle = "Contenido";

                        StringBuilder sub = new StringBuilder();
                        if (season != null && !season.isEmpty() && episode != null && !episode.isEmpty()) {
                            sub.append("T").append(season).append(" E").append(episode);
                        } else if (epTitle != null && !epTitle.isEmpty() && !epTitle.equals(mainTitle)) {
                            sub.append(epTitle);
                        }
                        if (dur > 0) {
                            long mins = dur / 60000;
                            if (mins > 0) {
                                if (sub.length() > 0) sub.append(" \u00B7 ");
                                sub.append(mins).append(" min");
                            }
                        }

                        int progress = -1;
                        if (dur > 0 && pos > 0) {
                            progress = (int) Math.min(100, Math.max(0, (pos * 100L) / dur));
                        }

                        String backdrop = (poster != null && !poster.trim().isEmpty()) ? poster.trim() : thumb;

                        return new Item("wn_" + id, mainTitle, sub.toString(), backdrop, pkg, slot, false, progress, intentUri);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Saves custom values for a slot. */
    public static void saveSlot(Context context, int slot, String title, String subtitle,
                                String backdropUri, String targetPackage, int progressPercent,
                                String intentUri) {
        if (context == null) return;
        prefs(context).edit()
                .putBoolean("slot_" + slot + "_custom", true)
                .putString("slot_" + slot + "_title", title != null ? title.trim() : "")
                .putString("slot_" + slot + "_subtitle", subtitle != null ? subtitle.trim() : "")
                .putString("slot_" + slot + "_backdrop", backdropUri != null ? backdropUri.trim() : "")
                .putString("slot_" + slot + "_package", targetPackage != null ? targetPackage.trim() : "")
                .putInt("slot_" + slot + "_progress", progressPercent)
                .putString("slot_" + slot + "_intent", intentUri != null ? intentUri.trim() : "")
                .apply();
    }

    public static void saveSlot(Context context, int slot, String title, String subtitle,
                                String backdropUri, String targetPackage) {
        saveSlot(context, slot, title, subtitle, backdropUri, targetPackage, -1, "");
    }

    /** Populates sample cinematic demo cards matching modern streaming feeds. */
    public static void loadDemo(Context context) {
        saveSlot(context, 0, "The Last of Us", "T1 E3 \u00B7 48 min",
                "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=960&auto=format&fit=crop",
                "com.hbo.broadband", 42, "");
        saveSlot(context, 1, "Demon Slayer: Kimetsu no Yaiba", "T2 E5 \u00B7 24 min",
                "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=960&auto=format&fit=crop",
                "com.crunchyroll.crunchyroid", 58, "");
    }

    /** Clears custom values for a slot to restore auto-detection. */
    public static void resetSlot(Context context, int slot) {
        if (context == null) return;
        prefs(context).edit()
                .remove("slot_" + slot + "_custom")
                .remove("slot_" + slot + "_title")
                .remove("slot_" + slot + "_subtitle")
                .remove("slot_" + slot + "_backdrop")
                .remove("slot_" + slot + "_package")
                .remove("slot_" + slot + "_progress")
                .remove("slot_" + slot + "_intent")
                .apply();
    }

    /** Clears recent app history (used in tests). */
    public static void clearRecents(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_RECENTS).apply();
    }
}

