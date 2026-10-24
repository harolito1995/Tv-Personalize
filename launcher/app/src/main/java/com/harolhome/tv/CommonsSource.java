package com.harolhome.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Wikimedia Commons search (openly licensed photography and art). Supports all categories
 * and free-text queries as an independent, resilient source. Only landscape files with a
 * generated thumbnail are accepted, so browsing never downloads an original. Every entry carries
 * the author and licence reported by the file's own metadata, plus its description page.
 */
final class CommonsSource implements RemoteWallpaperSource {

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final long MAX_IMAGE_BYTES = RemoteRepository.MAX_IMAGE_BYTES;

    @Override
    public String label() {
        return "Wikimedia Commons";
    }

    @Override
    public boolean supports(String category) {
        // Commons supports all categories as a universal fallback and catalog provider
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
        String url = buildUrl(category, query, page);
        JSONObject root = RemoteHttp.getJson(url, token);
        if (token != null && token.isCancelled()) {
            throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
        }
        List<Catalog.Wallpaper> items = parse(root, category);
        if ("4k".equalsIgnoreCase(quality)) {
            List<Catalog.Wallpaper> filtered = new ArrayList<>();
            for (Catalog.Wallpaper w : items) {
                if (Catalog.is4k(w.width, w.height)) filtered.add(w);
            }
            if (!filtered.isEmpty()) items = filtered;
        } else if ("2k".equalsIgnoreCase(quality)) {
            List<Catalog.Wallpaper> filtered = new ArrayList<>();
            for (Catalog.Wallpaper w : items) {
                if (w.width >= 2560 && w.height >= 1440) filtered.add(w);
            }
            if (!filtered.isEmpty()) items = filtered;
        }
        return new Page(items, root != null && root.optJSONObject("continue") != null);
    }

    static String baseQuery(String category) {
        if (RemoteRepository.CAT_ANIME.equals(category)) return "anime";
        if (RemoteRepository.CAT_DRAGONBALL.equals(category)) return "dragon ball";
        if (RemoteRepository.CAT_NARUTO.equals(category)) return "naruto";
        if (RemoteRepository.CAT_ONEPIECE.equals(category)) return "one piece";
        if (RemoteRepository.CAT_GAMES.equals(category)) return "video game";
        if (RemoteRepository.CAT_CYBERPUNK.equals(category)) return "cyberpunk";
        if (RemoteRepository.CAT_FANTASY.equals(category)) return "fantasy";
        if (RemoteRepository.CAT_NATURE.equals(category)) return "nature landscape";
        if (RemoteRepository.CAT_CINEMA.equals(category)) return "cinema movie";
        if (RemoteRepository.CAT_SCIFI.equals(category)) return "science fiction";
        if (RemoteRepository.CAT_ARCHITECTURE.equals(category)) return "architecture cityscape";
        if (RemoteRepository.CAT_SPACE.equals(category)) return "space galaxy";
        return "wallpaper landscape";
    }

    /** Search URL for one provider page. Package-private for unit tests. */
    static String buildUrl(String category, String query, int page) {
        String base = baseQuery(category);
        String q = query == null ? "" : query.trim();
        String term;
        if (!q.isEmpty()) {
            if (RemoteRepository.CAT_ALL.equals(category) || base.isEmpty()) {
                term = q;
            } else {
                String qLower = q.toLowerCase(Locale.ROOT);
                String bLower = base.toLowerCase(Locale.ROOT);
                term = (qLower.contains(bLower) || bLower.contains(qLower)) ? q : (base + " " + q);
            }
        } else {
            term = base;
        }
        int offset = Math.max(0, page - 1) * 24;
        return API + "?action=query&format=json&generator=search&gsrsearch="
                + encode(term + " filetype:bitmap")
                + "&gsrnamespace=6&gsrlimit=24"
                + (offset > 0 ? "&gsroffset=" + offset : "")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=960";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Parses a MediaWiki generator=search response, keeping only usable landscape images. */
    static List<Catalog.Wallpaper> parse(JSONObject root, String category) {
        List<Catalog.Wallpaper> out = new ArrayList<>();
        JSONObject query = root == null ? null : root.optJSONObject("query");
        JSONObject pages = query == null ? null : query.optJSONObject("pages");
        if (pages == null) return out;

        Iterator<String> keys = pages.keys();
        while (keys.hasNext() && out.size() < 24) {
            JSONObject page = pages.optJSONObject(keys.next());
            if (page == null || page.optInt("ns", -1) != 6) continue;
            JSONArray infos = page.optJSONArray("imageinfo");
            JSONObject info = infos == null || infos.length() == 0 ? null : infos.optJSONObject(0);
            if (info == null) continue;

            int w = info.optInt("width", 0);
            int h = info.optInt("height", 0);
            double ratio = h <= 0 ? 0 : w / (double) h;
            if (w < 1600 || h < 900 || ratio < 1.55 || ratio > 1.9) continue;

            String mime = info.optString("mime", "").toLowerCase(Locale.ROOT);
            if (!mime.equals("image/jpeg") && !mime.equals("image/png")
                    && !mime.equals("image/webp")) {
                continue;
            }
            long size = info.optLong("size", -1);
            if (size <= 0 || size > MAX_IMAGE_BYTES) continue;

            String image = info.optString("url", "").trim();
            String thumb = info.optString("thumburl", "").trim();
            String pageUrl = info.optString("descriptionurl", "").trim();
            if (!Catalog.isHttpsUrl(image) || !Catalog.isHttpsUrl(thumb)
                    || !Catalog.isHttpsUrl(pageUrl)) {
                continue;
            }
            long pageId = page.optLong("pageid", 0);
            if (pageId <= 0) continue;

            String title = cleanTitle(page.optString("title", ""));
            if (title.isEmpty()) title = "Wikimedia Commons";
            String author = meta(info, "Artist");
            if (author.isEmpty()) author = "Wikimedia Commons";
            String license = meta(info, "LicenseShortName");

            out.add(new Catalog.Wallpaper("wc-" + pageId, title, category, image, thumb,
                    w, h, author, license, pageUrl, ""));
        }
        return out;
    }

    private static String cleanTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("File:")) t = t.substring(5);
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        t = t.replace('_', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String meta(JSONObject info, String key) {
        JSONObject all = info.optJSONObject("extmetadata");
        JSONObject v = all == null ? null : all.optJSONObject(key);
        if (v == null) return "";
        String s = v.optString("value", "");
        s = s.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
