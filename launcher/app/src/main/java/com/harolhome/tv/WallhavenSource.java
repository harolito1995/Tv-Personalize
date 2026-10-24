package com.harolhome.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wallhaven public API v1 (https://wallhaven.cc/api/v1/search).
 * Restricted to SFW results (purity=100), 16:9 landscape wallpapers.
 * Supports free-text query, category mapping, quality filters, and sorting.
 */
final class WallhavenSource implements RemoteWallpaperSource {

    private static final String API = "https://wallhaven.cc/api/v1/search";
    private static final long MAX_IMAGE_BYTES = RemoteRepository.MAX_IMAGE_BYTES;

    @Override
    public String label() {
        return "Wallhaven";
    }

    @Override
    public boolean supports(String category) {
        return true;
    }

    @Override
    public Page search(String category, String categoryLabel, String query, int page,
                       CancelToken token) throws CatalogException {
        return search(category, categoryLabel, query, page, "all", "relevance", token);
    }

    @Override
    public Page search(String category, String categoryLabel, String query, int page,
                        String quality, String sort, CancelToken token) throws CatalogException {
        JSONObject root = RemoteHttp.getJson(buildUrl(category, query, page, quality, sort), token);
        if (token != null && token.isCancelled()) {
            throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
        }
        List<Catalog.Wallpaper> items = parse(root, category, categoryLabel, query);
        return new Page(items, hasMore(root, items));
    }

    /** Search URL for one category + optional text. Package-private for unit tests. */
    static String buildUrl(String category, String query) {
        return buildUrl(category, query, 1, "all", "relevance");
    }

    /** Search URL for one provider page. Package-private for unit tests. */
    static String buildUrl(String category, String query, int page) {
        return buildUrl(category, query, page, "all", "relevance");
    }

    static String buildUrl(String category, String query, int page, String quality, String sort) {
        String anime = isAnime(category) ? "010" : "111";
        String base = baseQuery(category);
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) q = base.isEmpty() ? q : base + " " + q;
        else q = base;
        boolean top = q.isEmpty() && (sort == null || "relevance".equals(sort) || "top".equals(sort));

        StringBuilder b = new StringBuilder(API).append('?');
        if (!q.isEmpty()) b.append("q=").append(encode(q)).append('&');
        b.append("categories=").append(anime)
                .append("&purity=100");

        if ("4k".equalsIgnoreCase(quality)) {
            b.append("&atleast=3840x2160");
        } else if ("2k".equalsIgnoreCase(quality)) {
            b.append("&atleast=2560x1440");
        } else {
            b.append("&atleast=1920x1080");
        }

        b.append("&ratios=16x9");

        if ("date".equalsIgnoreCase(sort)) {
            b.append("&sorting=date_added");
        } else if ("top".equalsIgnoreCase(sort) || top) {
            b.append("&sorting=toplist&topRange=1M");
        } else {
            b.append("&sorting=relevance");
        }

        if (page > 1) b.append("&page=").append(page);
        return b.toString();
    }

    private static boolean hasMore(JSONObject root, List<Catalog.Wallpaper> items) {
        JSONObject meta = root == null ? null : root.optJSONObject("meta");
        int current = meta == null ? 1 : meta.optInt("current_page", 1);
        int last = meta == null ? 0 : meta.optInt("last_page", 0);
        if (last > 0) return current < last;
        JSONArray data = root == null ? null : root.optJSONArray("data");
        return data != null && data.length() >= 24;
    }

    private static boolean isAnime(String category) {
        return RemoteRepository.CAT_ANIME.equals(category)
                || RemoteRepository.CAT_DRAGONBALL.equals(category)
                || RemoteRepository.CAT_NARUTO.equals(category)
                || RemoteRepository.CAT_ONEPIECE.equals(category);
    }

    static String baseQuery(String category) {
        if (RemoteRepository.CAT_DRAGONBALL.equals(category)) return "dragon ball";
        if (RemoteRepository.CAT_NARUTO.equals(category)) return "naruto";
        if (RemoteRepository.CAT_ONEPIECE.equals(category)) return "one piece";
        if (RemoteRepository.CAT_GAMES.equals(category)) return "video game";
        if (RemoteRepository.CAT_CYBERPUNK.equals(category)) return "cyberpunk";
        if (RemoteRepository.CAT_FANTASY.equals(category)) return "fantasy";
        if (RemoteRepository.CAT_NATURE.equals(category)) return "nature";
        if (RemoteRepository.CAT_CINEMA.equals(category)) return "movie";
        if (RemoteRepository.CAT_SCIFI.equals(category)) return "science fiction";
        if (RemoteRepository.CAT_ARCHITECTURE.equals(category)) return "architecture";
        if (RemoteRepository.CAT_SPACE.equals(category)) return "space";
        return "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Parses the search listing, skipping anything that is not a safe SFW 16:9 image. */
    static List<Catalog.Wallpaper> parse(JSONObject root, String category, String categoryLabel,
                                         String query) throws CatalogException {
        JSONArray arr = root == null ? null : root.optJSONArray("data");
        if (arr == null) {
            throw new CatalogException(CatalogException.Code.FORMAT, "missing data array");
        }
        List<Catalog.Wallpaper> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < 24; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"sfw".equalsIgnoreCase(o.optString("purity", ""))) continue;
            String remoteId = o.optString("id", "").trim().toLowerCase(Locale.ROOT);
            if (!remoteId.matches("[a-z0-9]{3,16}")) continue;

            String mime = o.optString("file_type", "").toLowerCase(Locale.ROOT);
            if (!mime.equals("image/jpeg") && !mime.equals("image/png") && !mime.equals("image/webp")) {
                continue;
            }
            long size = o.optLong("file_size", -1);
            if (size <= 0 || size > MAX_IMAGE_BYTES) continue;

            int w = o.optInt("dimension_x", 0);
            int h = o.optInt("dimension_y", 0);
            double ratio = h <= 0 ? 0 : w / (double) h;
            if (w < 1600 || h < 900 || ratio < 1.6 || ratio > 1.85) continue;

            String image = o.optString("path", "").trim();
            String page = o.optString("url", "").trim();
            JSONObject thumbs = o.optJSONObject("thumbs");
            String thumb = thumbs == null ? "" : thumbs.optString("large", "").trim();
            if (!Catalog.isHttpsUrl(thumb)) {
                thumb = thumbs == null ? "" : thumbs.optString("original", "").trim();
            }
            if (!Catalog.isHttpsUrl(image) || !Catalog.isHttpsUrl(thumb)
                    || !Catalog.isHttpsUrl(page)) {
                continue;
            }

            String title = title(query, categoryLabel, category);
            out.add(new Catalog.Wallpaper("wh-" + remoteId, title, category, image, thumb,
                    w, h, "Wallhaven", "", page, ""));
        }
        return out;
    }

    private static String title(String query, String categoryLabel, String category) {
        String provider = RemoteRepository.CAT_ALL.equals(category) ? "Wallhaven"
                : (categoryLabel == null ? "" : categoryLabel.trim());
        if (provider.isEmpty()) provider = "Wallhaven";
        String q = query == null ? "" : query.trim();
        String title = q.isEmpty() ? provider : q + " \u00b7 " + provider;
        if (title.length() > 120) title = title.substring(0, 120);
        if (!title.isEmpty()) {
            title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
        }
        return title;
    }
}
