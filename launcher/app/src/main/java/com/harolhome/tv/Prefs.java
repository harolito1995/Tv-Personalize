package com.harolhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runtime settings owned by the user: dock favourites and wallpaper. Seeded from
 * res/values/config.xml the first time, then edited on the TV with the remote.
 */
final class Prefs {

    private static final String FILE = "tvhome";
    private static final String KEY_FAVOURITES = "favourites";
    private static final String KEY_DOCK_V2 = "dock_v2";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_WALLPAPER_ID = "wallpaper_id";
    private static final String KEY_DREAM_WALLPAPER = "dream_wallpaper";
    private static final String KEY_THEME = "theme";
    private static final String KEY_DOCK_STYLE = "dock_style";
    private static final String KEY_USB_TREE = "wallpaper_usb_tree";
    private static final String KEY_USB_DIR = "wallpaper_usb_dir";
    private static final String KEY_PANEL_CATEGORY = "panel_category";
    private static final String KEY_PANEL_QUERY = "panel_query";
    private static final String KEY_PANEL_PAGE = "panel_page";
    private static final String KEY_PANEL_WALLPAPER_ID = "panel_wallpaper_id";
    private static final String KEY_PANEL_PROVIDER = "panel_provider";
    private static final String KEY_PANEL_QUALITY = "panel_quality";
    private static final String KEY_PANEL_SORT = "panel_sort";

    /** Dock styles for the Interface section: the lighter band, or floating cards. */
    static final String DOCK_BAND = "band";
    static final String DOCK_FLOATING = "floating";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * One-time upgrade to the 7-slot dock: the apps from config.xml go first, then whatever the
     * user had pinned before (so nothing they chose is lost, it only moves later in the list).
     */
    private static void migrateDock(Context c, SharedPreferences p) {
        if (p.getBoolean(KEY_DOCK_V2, false)) return;
        String stored = p.getString(KEY_FAVOURITES, null);
        if (stored != null) {
            List<String> merged = new ArrayList<>(
                    Arrays.asList(c.getResources().getStringArray(R.array.dock_apps)));
            for (String pkg : TextUtils.split(stored, ",")) {
                String t = pkg.trim();
                if (!t.isEmpty() && !merged.contains(t)) merged.add(t);
            }
            p.edit().putString(KEY_FAVOURITES, TextUtils.join(",", merged)).apply();
        }
        p.edit().putBoolean(KEY_DOCK_V2, true).apply();
    }

    static List<String> favourites(Context c) {
        SharedPreferences p = sp(c);
        migrateDock(c, p);
        String stored = p.getString(KEY_FAVOURITES, null);
        if (stored == null) {
            List<String> seed = new ArrayList<>(
                    Arrays.asList(c.getResources().getStringArray(R.array.dock_apps)));
            setFavourites(c, seed);
            return seed;
        }
        List<String> out = new ArrayList<>();
        for (String pkg : TextUtils.split(stored, ",")) {
            if (!pkg.trim().isEmpty()) out.add(pkg.trim());
        }
        return out;
    }

    static void setFavourites(Context c, List<String> packages) {
        sp(c).edit().putString(KEY_FAVOURITES, TextUtils.join(",", packages)).apply();
    }

    static boolean isFavourite(Context c, String pkg) {
        return favourites(c).contains(pkg);
    }

    /** Adds or removes the package; returns false when the dock is already full. */
    static boolean toggleFavourite(Context c, String pkg, int dockSize) {
        List<String> list = favourites(c);
        if (list.remove(pkg)) {
            setFavourites(c, list);
            return true;
        }
        if (list.size() >= dockSize) return false;
        list.add(pkg);
        setFavourites(c, list);
        return true;
    }

    /** Moves a favourite one slot earlier or later; false when it cannot move (edge of the dock). */
    static boolean moveFavourite(Context c, String pkg, int delta) {
        List<String> list = favourites(c);
        int from = list.indexOf(pkg);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= list.size()) return false;
        list.remove(from);
        list.add(to, pkg);
        setFavourites(c, list);
        return true;
    }

    /** Empty when no wallpaper is set (black background). */
    static String wallpaper(Context c) {
        return sp(c).getString(KEY_WALLPAPER, "");
    }

    static void setWallpaper(Context c, String uri) {
        sp(c).edit().putString(KEY_WALLPAPER, uri).apply();
    }

    /** Catalogue id of the wallpaper in use; empty for local files or no wallpaper. */
    static String wallpaperId(Context c) {
        return sp(c).getString(KEY_WALLPAPER_ID, "");
    }

    static void setWallpaperId(Context c, String id) {
        sp(c).edit().putString(KEY_WALLPAPER_ID, id).apply();
    }

    /** Photo chosen for the screensaver; empty falls back to the Home wallpaper. */
    static String dreamWallpaper(Context c) {
        return sp(c).getString(KEY_DREAM_WALLPAPER, "");
    }

    static void setDreamWallpaper(Context c, String uri) {
        sp(c).edit().putString(KEY_DREAM_WALLPAPER, uri == null ? "" : uri).apply();
    }

    /** Id of the launcher theme in use (see LauncherTheme). */
    static String theme(Context c) {
        return sp(c).getString(KEY_THEME, LauncherTheme.DEFAULT_ID);
    }

    static void setTheme(Context c, String id) {
        sp(c).edit().putString(KEY_THEME, id).apply();
    }

    /** Dock style: DOCK_BAND or DOCK_FLOATING; unknown values fall back to the band. */
    static String dockStyle(Context c) {
        String v = sp(c).getString(KEY_DOCK_STYLE, DOCK_BAND);
        return DOCK_FLOATING.equals(v) ? DOCK_FLOATING : DOCK_BAND;
    }

    static void setDockStyle(Context c, String style) {
        sp(c).edit().putString(KEY_DOCK_STYLE, DOCK_FLOATING.equals(style) ? DOCK_FLOATING : DOCK_BAND).apply();
    }

    /** Persisted SAF tree URI selected by the user for wallpaper exports. */
    static String usbTreeUri(Context c) {
        return sp(c).getString(KEY_USB_TREE, "");
    }

    static void setUsbTreeUri(Context c, String uri) {
        sp(c).edit().putString(KEY_USB_TREE, uri == null ? "" : uri).apply();
    }

    /** Filesystem path selected on devices without a system document picker. */
    static String usbDir(Context c) {
        return sp(c).getString(KEY_USB_DIR, "");
    }

    static void setUsbDir(Context c, String path) {
        sp(c).edit().putString(KEY_USB_DIR, path == null ? "" : path).apply();
    }

    /** Catalogue manifest URL; empty means the bundled demo catalogue is used. */
    static String catalogUrl(Context c) {
        return c.getString(R.string.catalog_url).trim();
    }

    /** Last remote category shown in the wallpaper panel; "all" on first use. */
    static String panelCategory(Context c) {
        String v = sp(c).getString(KEY_PANEL_CATEGORY, RemoteRepository.CAT_ALL);
        return v == null || v.isEmpty() ? RemoteRepository.CAT_ALL : v;
    }

    static String panelQuery(Context c) {
        String v = sp(c).getString(KEY_PANEL_QUERY, "");
        return v == null ? "" : v;
    }

    static int panelPage(Context c) {
        return Math.max(1, sp(c).getInt(KEY_PANEL_PAGE, 1));
    }

    static String panelWallpaperId(Context c) {
        String v = sp(c).getString(KEY_PANEL_WALLPAPER_ID, "");
        return v == null ? "" : v;
    }

    /** Remembers the category, search, page and last opened tile across app restarts. */
    static String panelProvider(Context c) {
        return sp(c).getString(KEY_PANEL_PROVIDER, RemoteRepository.PROVIDER_ALL);
    }

    static void setPanelProvider(Context c, String provider) {
        sp(c).edit().putString(KEY_PANEL_PROVIDER, provider == null ? RemoteRepository.PROVIDER_ALL : provider).apply();
    }

    static String panelQuality(Context c) {
        return sp(c).getString(KEY_PANEL_QUALITY, RemoteRepository.QUALITY_ALL);
    }

    static void setPanelQuality(Context c, String quality) {
        sp(c).edit().putString(KEY_PANEL_QUALITY, quality == null ? RemoteRepository.QUALITY_ALL : quality).apply();
    }

    static String panelSort(Context c) {
        return sp(c).getString(KEY_PANEL_SORT, RemoteRepository.SORT_RELEVANCE);
    }

    static void setPanelSort(Context c, String sort) {
        sp(c).edit().putString(KEY_PANEL_SORT, sort == null ? RemoteRepository.SORT_RELEVANCE : sort).apply();
    }

    static void setPanelLastView(Context c, String category, String query, int page,
                                 String wallpaperId) {
        sp(c).edit()
                .putString(KEY_PANEL_CATEGORY,
                        category == null || category.isEmpty()
                                ? RemoteRepository.CAT_ALL : category)
                .putString(KEY_PANEL_QUERY, query == null ? "" : query)
                .putInt(KEY_PANEL_PAGE, Math.max(1, page))
                .putString(KEY_PANEL_WALLPAPER_ID, wallpaperId == null ? "" : wallpaperId)
                .apply();
    }
}
