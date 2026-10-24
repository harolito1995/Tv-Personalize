package com.harolhome.tv;

import java.util.List;

/**
 * A remote wallpaper provider. Implementations only talk HTTPS, never include private keys and
 * return the same lightweight entry type as the JSON catalogue so the grid, the preview and the
 * download-on-apply path stay shared.
 */
interface RemoteWallpaperSource {

    /** One provider page plus whether the provider reports another page after it. */
    final class Page {
        final List<Catalog.Wallpaper> items;
        final boolean hasMore;

        Page(List<Catalog.Wallpaper> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }

    /** Human readable provider name shown in the results status (a brand, not translated). */
    String label();

    /** Whether this provider contributes to the given local category id. */
    boolean supports(String category);

    /**
     * Blocking search. Implementations must check {@code token} between network steps and return
     * metadata plus thumbnail/original URLs only: the original image is never downloaded here.
     * {@code page} is one-based and follows the provider's own pagination.
     */
    Page search(String category, String categoryLabel, String query, int page,
                CancelToken token) throws CatalogException;

    /** Extended search with quality and sort criteria. Default implementation falls back to basic search. */
    default Page search(String category, String categoryLabel, String query, int page,
                        String quality, String sort, CancelToken token) throws CatalogException {
        return search(category, categoryLabel, query, page, token);
    }
}
