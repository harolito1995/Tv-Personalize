package com.harolhome.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Versioned wallpaper catalogue, typically fetched from a GitHub Pages manifest. Parsing is
 * deliberately strict: unknown format versions, unsafe ids and non-HTTPS resource URLs are
 * rejected or skipped so a malformed catalogue can never point the launcher somewhere odd.
 */
final class Catalog {

    static final int FORMAT_VERSION = 1;
    static final int MAX_ITEMS = 500;

    private static final Pattern SAFE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    final String updated;
    final List<Wallpaper> wallpapers;
    final boolean cached;

    static final class Wallpaper {
        final String id;
        final String title;
        final String category;
        final String imageUrl;
        final String thumbUrl;   // empty when the catalogue has no thumbnail for this entry
        final int width;
        final int height;
        final String author;
        final String license;
        final String source;
        final String alt;

        Wallpaper(String id, String title, String category, String imageUrl, String thumbUrl,
                  int width, int height, String author, String license, String source, String alt) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.imageUrl = imageUrl;
            this.thumbUrl = thumbUrl;
            this.width = width;
            this.height = height;
            this.author = author;
            this.license = license;
            this.source = source;
            this.alt = alt;
        }
    }

    private Catalog(String updated, List<Wallpaper> wallpapers, boolean cached) {
        this.updated = updated;
        this.wallpapers = wallpapers;
        this.cached = cached;
    }

    /** Same catalogue, flagged as served from the saved copy (offline fallback). */
    Catalog asCached() {
        return new Catalog(updated, wallpapers, true);
    }

    /** Parses the manifest; entries with invalid ids, titles or image URLs are skipped. */
    static Catalog parse(String json) throws CatalogException {
        if (json == null || json.trim().isEmpty()) {
            throw new CatalogException(CatalogException.Code.FORMAT, "empty catalogue");
        }
        String text = json.strip();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1).strip();

        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new CatalogException(CatalogException.Code.FORMAT, "invalid json: " + e.getMessage());
        }
        int version = root.optInt("version", -1);
        if (version != FORMAT_VERSION) {
            throw new CatalogException(CatalogException.Code.FORMAT, "unsupported version " + version);
        }
        JSONArray arr = root.optJSONArray("wallpapers");
        if (arr == null) {
            throw new CatalogException(CatalogException.Code.FORMAT, "missing wallpapers array");
        }

        List<Wallpaper> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length() && out.size() < MAX_ITEMS; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            String title = o.optString("title", "").trim();
            String image = o.optString("image", "").trim();
            if (!isSafeId(id) || title.isEmpty() || !isHttpsUrl(image)) continue;
            if (!seen.add(id)) continue;

            String thumb = o.optString("thumb", "").trim();
            if (!isHttpsUrl(thumb)) thumb = "";
            String category = o.optString("category", "").trim().toLowerCase(Locale.ROOT);
            if (category.isEmpty()) category = "otros";
            int width = Math.max(0, o.optInt("width", 0));
            int height = Math.max(0, o.optInt("height", 0));
            String source = o.optString("source", "").trim();
            if (!isHttpsUrl(source)) source = "";

            out.add(new Wallpaper(id, cap(title, 120), cap(category, 40), image, thumb,
                    width, height,
                    cap(o.optString("author", "").trim(), 80),
                    cap(o.optString("license", "").trim(), 80),
                    cap(source, 300),
                    cap(o.optString("alt", "").trim(), 300)));
        }
        return new Catalog(root.optString("updated", "").trim(), out, false);
    }

    /** Distinct categories in the catalogue, alphabetically sorted. */
    List<String> categories() {
        Set<String> cats = new TreeSet<>();
        for (Wallpaper w : wallpapers) cats.add(w.category);
        return new ArrayList<>(cats);
    }

    List<Wallpaper> inCategory(String category) {
        if (category == null) return Collections.unmodifiableList(wallpapers);
        List<Wallpaper> out = new ArrayList<>();
        for (Wallpaper w : wallpapers) {
            if (w.category.equals(category)) out.add(w);
        }
        return out;
    }

    /** Entries of one category (null = all) whose text matches the search query, if any. */
    List<Wallpaper> query(String category, String q) {
        String needle = normalize(q);
        List<Wallpaper> out = new ArrayList<>();
        for (Wallpaper w : wallpapers) {
            if (category != null && !w.category.equals(category)) continue;
            if (!needle.isEmpty() && !matches(w, needle)) continue;
            out.add(w);
        }
        return out;
    }

    private static boolean matches(Wallpaper w, String needle) {
        return normalize(w.title).contains(needle)
                || normalize(w.id).contains(needle)
                || normalize(w.category).contains(needle)
                || normalize(w.author).contains(needle);
    }

    /** Lowercase, accent-stripped text used for catalogue searches. */
    static String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).trim();
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /** HTTPS-only URL check (also used for each image/thumbnail URL in the catalogue). */
    static boolean isHttpsUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            return scheme != null && scheme.equalsIgnoreCase("https")
                    && u.getHost() != null && !u.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** UHD/4K metadata threshold; the preview still verifies the downloaded file before using it. */
    static boolean is4k(int width, int height) {
        return width >= 3840 && height >= 2160;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
