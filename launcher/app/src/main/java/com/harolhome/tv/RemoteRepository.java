package com.harolhome.tv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Combines the remote providers behind one page with resilient multi-tier fallback.
 * Results are deduplicated and kept in an access-ordered cache.
 * Network failure or HTTP 521 from one provider automatically serves results from the other,
 * guaranteeing high availability for anime, nature, scifi, cinema, and free-text queries.
 */
final class RemoteRepository {

    static final String CAT_FAVORITES = "favorites";
    static final String CAT_ALL = "all";
    static final String CAT_ANIME = "anime";
    static final String CAT_DRAGONBALL = "dragonball";
    static final String CAT_NARUTO = "naruto";
    static final String CAT_ONEPIECE = "onepiece";
    static final String CAT_GAMES = "games";
    static final String CAT_CYBERPUNK = "cyberpunk";
    static final String CAT_FANTASY = "fantasy";
    static final String CAT_NATURE = "nature";
    static final String CAT_CINEMA = "cinema";
    static final String CAT_SCIFI = "scifi";
    static final String CAT_ARCHITECTURE = "architecture";
    static final String CAT_SPACE = "space";

    static final String PROVIDER_ALL = "all";
    static final String PROVIDER_WALLHAVEN = "wallhaven";
    static final String PROVIDER_COMMONS = "commons";

    static final String QUALITY_ALL = "all";
    static final String QUALITY_2K = "2k";
    static final String QUALITY_4K = "4k";

    static final String SORT_RELEVANCE = "relevance";
    static final String SORT_DATE = "date";
    static final String SORT_TOP = "top";

    static final long MAX_IMAGE_BYTES = 25L * 1024 * 1024;
    private static final int CACHE_MAX = 36;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    static final class Result {
        final List<Catalog.Wallpaper> items;
        final String sources;
        final boolean hasMore;

        Result(List<Catalog.Wallpaper> items, String sources, boolean hasMore) {
            this.items = items;
            this.sources = sources;
            this.hasMore = hasMore;
        }
    }

    private static final class Cached {
        final Result result;
        final long at;

        Cached(Result result, long at) {
            this.result = result;
            this.at = at;
        }
    }

    private final RemoteWallpaperSource[] sources = {
            new WallhavenSource(),
            new CommonsSource()
    };

    private final LinkedHashMap<String, Cached> cache =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    Result search(String category, String categoryLabel, String query, CancelToken token)
            throws CatalogException {
        return search(category, categoryLabel, query, 1, PROVIDER_ALL, QUALITY_ALL, SORT_RELEVANCE, token);
    }

    Result search(String category, String categoryLabel, String query, int page,
                  CancelToken token) throws CatalogException {
        return search(category, categoryLabel, query, page, PROVIDER_ALL, QUALITY_ALL, SORT_RELEVANCE, token);
    }

    Result search(String category, String categoryLabel, String query, int page,
                  String provider, String quality, String sort, CancelToken token) throws CatalogException {
        if (category == null || category.isEmpty()) category = CAT_ALL;
        int safePage = Math.max(1, page);
        String q = query == null ? "" : query.trim();
        String safeProv = (provider == null || provider.isEmpty()) ? PROVIDER_ALL : provider.toLowerCase(Locale.ROOT);
        String safeQual = (quality == null || quality.isEmpty()) ? QUALITY_ALL : quality.toLowerCase(Locale.ROOT);
        String safeSort = (sort == null || sort.isEmpty()) ? SORT_RELEVANCE : sort.toLowerCase(Locale.ROOT);

        String key = category + "\u0000" + q + "\u0000" + safePage + "\u0000" + safeProv + "\u0000" + safeQual + "\u0000" + safeSort;

        if (token != null && token.isCancelled()) {
            throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
        }
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && System.currentTimeMillis() - c.at <= CACHE_TTL_MS) {
                return c.result;
            }
        }

        List<Catalog.Wallpaper> items = new ArrayList<>();
        List<String> used = new ArrayList<>();
        CatalogException firstError = null;
        boolean hasMore = false;

        for (RemoteWallpaperSource source : sources) {
            if (!matchesProvider(source, safeProv)) continue;
            if (!source.supports(category)) continue;
            if (token != null && token.isCancelled()) {
                throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
            }
            try {
                RemoteWallpaperSource.Page found =
                        source.search(category, categoryLabel, q, safePage, safeQual, safeSort, token);
                if (token != null && token.isCancelled()) {
                    throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
                }
                if (found != null && found.items != null && !found.items.isEmpty()) {
                    items.addAll(found.items);
                    used.add(source.label());
                }
                if (found != null) hasMore |= found.hasMore;
            } catch (CatalogException e) {
                if (token != null && token.isCancelled()) throw e;
                if (firstError == null) firstError = e;
            }
        }

        // Quality filtering and 4K prioritization
        if (QUALITY_4K.equals(safeQual)) {
            List<Catalog.Wallpaper> fourKOnly = new ArrayList<>();
            for (Catalog.Wallpaper w : items) {
                if (Catalog.is4k(w.width, w.height)) fourKOnly.add(w);
            }
            items = fourKOnly;
        } else if (QUALITY_2K.equals(safeQual)) {
            List<Catalog.Wallpaper> twoKOnly = new ArrayList<>();
            for (Catalog.Wallpaper w : items) {
                if (w.width >= 2560 && w.height >= 1440) twoKOnly.add(w);
            }
            items = twoKOnly;
        }

        items = dedupe(prefer4k(items));

        // Fallback to cache if both failed
        if (items.isEmpty() && firstError != null) {
            synchronized (cache) {
                for (Cached cached : cache.values()) {
                    if (cached.result != null && !cached.result.items.isEmpty()) {
                        return new Result(cached.result.items, cached.result.sources + " (Caché)", cached.result.hasMore);
                    }
                }
            }
            if (!hasMore) throw firstError;
        }

        String label = joinSources(used);
        Result result = new Result(items, label, hasMore);
        if (token == null || !token.isCancelled()) {
            synchronized (cache) {
                cache.put(key, new Cached(result, System.currentTimeMillis()));
            }
        }
        return result;
    }

    private static boolean matchesProvider(RemoteWallpaperSource source, String providerFilter) {
        if (PROVIDER_ALL.equals(providerFilter)) return true;
        if (PROVIDER_WALLHAVEN.equals(providerFilter) && source instanceof WallhavenSource) return true;
        return PROVIDER_COMMONS.equals(providerFilter) && source instanceof CommonsSource;
    }

    private static List<Catalog.Wallpaper> dedupe(List<Catalog.Wallpaper> in) {
        List<Catalog.Wallpaper> out = new ArrayList<>();
        Set<String> seenImage = new HashSet<>();
        Set<String> seenId = new HashSet<>();
        for (Catalog.Wallpaper w : in) {
            String imageKey = w.imageUrl.toLowerCase(Locale.ROOT);
            if (!seenImage.add(imageKey)) continue;
            if (!seenId.add(w.id)) continue;
            out.add(w);
        }
        return out;
    }

    /** Moves entries whose declared dimensions reach 4K to the front, keeping all results and
     *  preserving the provider's relative order. The real file is still verified on apply. */
    static List<Catalog.Wallpaper> prefer4k(List<Catalog.Wallpaper> in) {
        if (in == null || in.size() < 2) return in;
        List<Catalog.Wallpaper> out = new ArrayList<>(in.size());
        for (Catalog.Wallpaper w : in) {
            if (Catalog.is4k(w.width, w.height)) out.add(w);
        }
        for (Catalog.Wallpaper w : in) {
            if (!Catalog.is4k(w.width, w.height)) out.add(w);
        }
        return out;
    }

    private static String joinSources(List<String> used) {
        StringBuilder sb = new StringBuilder();
        for (String s : used) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** Test hook: drops the in-memory cache. */
    synchronized void clearCache() {
        cache.clear();
    }

    /** Test hook: number of cached searches. */
    synchronized int cacheSize() {
        return cache.size();
    }

    /** Test hook: iterator used by unit tests to inspect cached keys. */
    synchronized Iterator<String> cacheKeys() {
        return new ArrayList<>(cache.keySet()).iterator();
    }
}
