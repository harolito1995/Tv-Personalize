package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Private storage for catalogue wallpapers, their thumbnails, and user favorites. Downloads are
 * size-capped, validated by decoding the header before they replace anything, written atomically
 * (".part" + rename) and the visible cache is trimmed so old files never pile up. Favorited
 * wallpapers are kept permanently inside a favorites subfolder. Nothing leaves the app's private
 * directories, so no storage permission is required.
 */
final class WallpaperStore {

    static final long MAX_IMAGE_BYTES = 25L * 1024 * 1024;
    static final long MAX_THUMB_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CACHED_IMAGES = 24;
    private static final int MAX_THUMBS = 400;
    /** A .part file younger than this may still be an active download. */
    private static final long PART_GRACE_MS = 60L * 60 * 1000;
    private static final String[] KNOWN_EXTS = {"jpg", "jpeg", "png", "webp", "gif", "bmp", "img"};

    interface Progress {
        void onBytes(long read);
    }

    private WallpaperStore() {
    }

    static File wallpaperDir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File thumbsDir(Context c) {
        File d = new File(c.getCacheDir(), "thumbs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Downloaded file for a catalogue id, or null when it is not on disk yet. */
    static File find(Context c, String id) {
        return find(wallpaperDir(c), id);
    }

    /** Directory-only lookup used by cleanup tests and recovery code. */
    static File find(File dir, String id) {
        if (dir == null || id == null || id.isEmpty()) return null;
        File inFav = findWithPrefix(new File(dir, "favorites"), id + ".");
        if (inFav != null) return inFav;
        return findWithPrefix(dir, id + ".");
    }

    /** Cached thumbnail file for a catalogue id, or null when it has not been fetched yet. */
    static File thumbFile(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        return findWithPrefix(thumbsDir(c), id + ".thumb.");
    }

    // ---- Favorites -------------------------------------------------------------------------------

    static File favoritesDir(Context c) {
        return favoritesDir(wallpaperDir(c));
    }

    static File favoritesDir(File wallpaperDir) {
        File d = new File(wallpaperDir, "favorites");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean isFavorite(Context c, String id) {
        return isFavorite(wallpaperDir(c), id);
    }

    static boolean isFavorite(File wallpaperDir, String id) {
        if (id == null || id.isEmpty()) return false;
        File dir = new File(wallpaperDir, "favorites");
        return findWithPrefix(dir, id + ".") != null;
    }

    static File getFavoriteFile(Context c, String id) {
        return getFavoriteFile(wallpaperDir(c), id);
    }

    static File getFavoriteFile(File wallpaperDir, String id) {
        if (id == null || id.isEmpty()) return null;
        File dir = new File(wallpaperDir, "favorites");
        return findWithPrefix(dir, id + ".");
    }

    static void saveFavorite(Context c, Catalog.Wallpaper w, File source) throws IOException {
        saveFavorite(wallpaperDir(c), w, source);
    }

    static void saveFavorite(File wallpaperDir, Catalog.Wallpaper w, File source) throws IOException {
        if (w == null || w.id == null || w.id.isEmpty() || source == null || !source.exists()) return;
        File dir = favoritesDir(wallpaperDir);
        String ext = fileExtension(source);
        if (ext == null || ext.isEmpty()) ext = sniffExt(source);
        if (ext == null) ext = "jpg";
        File dest = new File(dir, w.id + "." + ext);
        if (!dest.exists() || dest.length() != source.length()) {
            copy(source, dest);
        }
        File meta = new File(dir, w.id + ".json");
        try {
            JSONObject j = new JSONObject();
            j.put("id", w.id);
            j.put("title", w.title);
            j.put("category", w.category);
            j.put("image", w.imageUrl);
            j.put("thumb", w.thumbUrl);
            j.put("width", w.width);
            j.put("height", w.height);
            j.put("author", w.author);
            j.put("license", w.license);
            j.put("source", w.source);
            j.put("page", w.source);
            try (FileOutputStream fos = new FileOutputStream(meta)) {
                fos.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    static boolean removeFavorite(Context c, String id) {
        return removeFavorite(wallpaperDir(c), id);
    }

    static boolean removeFavorite(File wallpaperDir, String id) {
        if (id == null || id.isEmpty()) return false;
        File dir = new File(wallpaperDir, "favorites");
        File[] files = dir.listFiles();
        boolean removed = false;
        if (files != null) {
            String prefix = id + ".";
            for (File f : files) {
                if (f.getName().startsWith(prefix)) {
                    if (f.delete()) removed = true;
                }
            }
        }
        return removed;
    }

    static List<Catalog.Wallpaper> listFavorites(Context c) {
        return listFavorites(wallpaperDir(c));
    }

    static List<Catalog.Wallpaper> listFavorites(File wallpaperDir) {
        List<Catalog.Wallpaper> out = new ArrayList<>();
        File dir = new File(wallpaperDir, "favorites");
        File[] files = dir.listFiles();
        if (files == null) return out;
        Map<String, File> images = new HashMap<>();
        Map<String, File> metas = new HashMap<>();
        for (File f : files) {
            if (f.isDirectory() || f.getName().endsWith(".part")) continue;
            String name = f.getName();
            if (name.endsWith(".json")) {
                metas.put(name.substring(0, name.length() - 5), f);
            } else {
                int dot = name.lastIndexOf('.');
                String id = dot > 0 ? name.substring(0, dot) : name;
                images.put(id, f);
            }
        }
        List<File> sortedImages = new ArrayList<>(images.values());
        sortedImages.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File img : sortedImages) {
            int dot = img.getName().lastIndexOf('.');
            String id = dot > 0 ? img.getName().substring(0, dot) : img.getName();
            File meta = metas.get(id);
            Catalog.Wallpaper item = null;
            if (meta != null && meta.exists()) {
                try {
                    byte[] data = readAll(meta);
                    JSONObject j = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    item = new Catalog.Wallpaper(
                            j.optString("id", id),
                            j.optString("title", img.getName()),
                            j.optString("category", "favorites"),
                            j.optString("image", "file://" + img.getAbsolutePath()),
                            j.optString("thumb", "file://" + img.getAbsolutePath()),
                            j.optInt("width", 1920),
                            j.optInt("height", 1080),
                            j.optString("author", ""),
                            j.optString("license", ""),
                            j.optString("page", ""),
                            ""
                    );
                } catch (Exception ignored) {
                }
            }
            if (item == null) {
                int[] dims = dimensions(img);
                int w = dims != null ? dims[0] : 1920;
                int h = dims != null ? dims[1] : 1080;
                item = new Catalog.Wallpaper(
                        id,
                        img.getName(),
                        "favorites",
                        "file://" + img.getAbsolutePath(),
                        "file://" + img.getAbsolutePath(),
                        w,
                        h,
                        "Favoritos",
                        "",
                        "",
                        ""
                );
            }
            out.add(item);
        }
        return out;
    }

    // ---- Downloads -------------------------------------------------------------------------------

    static File downloadWallpaper(Context c, Catalog.Wallpaper w, Progress cb)
            throws CatalogException {
        return download(wallpaperDir(c), w.id, w.imageUrl, MAX_IMAGE_BYTES, cb, 2);
    }

    static File downloadThumb(Context c, Catalog.Wallpaper w, Progress cb) throws CatalogException {
        if (w.thumbUrl.isEmpty()) {
            throw new CatalogException(CatalogException.Code.FORMAT, "no thumbnail url");
        }
        return download(thumbsDir(c), w.id + ".thumb", w.thumbUrl, MAX_THUMB_BYTES, cb, 2);
    }

    private static File download(File dir, String base, String url, long maxBytes, Progress cb,
                                 int attempts) throws CatalogException {
        File part = new File(dir, base + ".part");
        CatalogException last = null;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
                }
            }
            part.delete();
            HttpsURLConnection conn = null;
            try {
                conn = CatalogClient.open(url, 10_000, 30_000);
                long declared = conn.getContentLengthLong();
                if (declared > maxBytes) {
                    throw new CatalogException(CatalogException.Code.TOO_BIG, "content-length " + declared);
                }
                long total = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > maxBytes) {
                            throw new CatalogException(CatalogException.Code.TOO_BIG, "over limit");
                        }
                        out.write(buf, 0, n);
                        if (cb != null) cb.onBytes(total);
                    }
                }
                if (total == 0) throw new CatalogException(CatalogException.Code.FORMAT, "empty file");
                String ext = sniffExt(part);
                if (ext == null) throw new CatalogException(CatalogException.Code.FORMAT, "not an image");
                File dest = new File(dir, base + "." + ext);
                if (!part.renameTo(dest)) {
                    throw new CatalogException(CatalogException.Code.STORAGE, "rename failed");
                }
                // Only discard the previous valid version after the new one is in place.
                deleteOtherVersions(dir, base, dest);
                return dest;
            } catch (CatalogException e) {
                part.delete();
                if (e.code == CatalogException.Code.BAD_URL || e.code == CatalogException.Code.NOT_HTTPS
                        || e.code == CatalogException.Code.TOO_BIG || e.code == CatalogException.Code.FORMAT
                        || e.code == CatalogException.Code.STORAGE) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                part.delete();
                last = new CatalogException(CatalogException.Code.OFFLINE, e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new CatalogException(CatalogException.Code.STORAGE, "download failed");
    }

    private static void deleteOtherVersions(File dir, String base, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        String prefix = base + ".";
        for (File f : files) {
            if (f.equals(keep) || f.isDirectory()) continue;
            String name = f.getName();
            if (name.startsWith(prefix) && !name.endsWith(".part")) f.delete();
        }
    }

    /** Header decode of the downloaded file; returns the extension or null when undecodable. */
    static String sniffExt(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        String mime = o.outMimeType == null ? "" : o.outMimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("jpeg")) return "jpg";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("bmp")) return "bmp";
        return "img";
    }

    /**
     * Trims the download cache: the active wallpaper (current selection and the one being
     * applied), the newest few other files and at most MAX_THUMBS thumbnails survive.
     * User favorites are never touched.
     */
    static void cleanup(Context c, String keepId) {
        cleanupFiles(wallpaperDir(c), thumbsDir(c), Prefs.wallpaperId(c), keepId,
                System.currentTimeMillis());
    }

    /** Directory-based cleanup, package-private so the retention policy can be tested on the JVM. */
    static void cleanupFiles(File wallpapers, File thumbs, String currentId, String keepId, long now) {
        try {
            int protectedCount = 0;
            List<File> keepSorted = new ArrayList<>();
            File[] files = wallpapers == null ? null : wallpapers.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) continue; // Skip subdirectories like favorites
                    if (f.getName().endsWith(".part")) {
                        if (isStalePart(f, now)) f.delete();
                        continue;
                    }
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    String id = dot > 0 ? name.substring(0, dot) : name;
                    if (id.equals(keepId) || id.equals(currentId)) {
                        protectedCount++;
                        continue;
                    }
                    keepSorted.add(f);
                }
                keepSorted.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                int allowedOthers = Math.max(0, MAX_CACHED_IMAGES - protectedCount);
                for (int i = allowedOthers; i < keepSorted.size(); i++) {
                    keepSorted.get(i).delete();
                }
            }
            File[] thumbFiles = thumbs == null ? null : thumbs.listFiles();
            if (thumbFiles != null) {
                List<File> sorted = new ArrayList<>();
                for (File f : thumbFiles) {
                    if (f.isDirectory()) continue;
                    if (f.getName().endsWith(".part")) {
                        if (isStalePart(f, now)) f.delete();
                    } else {
                        sorted.add(f);
                    }
                }
                if (sorted.size() > MAX_THUMBS) {
                    sorted.sort(Comparator.comparingLong(File::lastModified));
                    for (int i = 0; i < sorted.size() - MAX_THUMBS; i++) {
                        sorted.get(i).delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isStalePart(File f, long now) {
        return now - f.lastModified() > PART_GRACE_MS;
    }

    /** Short format label read from the real file header (never from the provider metadata). */
    static String format(File f) {
        if (f == null) return "";
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        String mime = o.outMimeType == null ? "" : o.outMimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("jpeg")) return "JPG";
        if (mime.contains("png")) return "PNG";
        if (mime.contains("webp")) return "WEBP";
        if (mime.contains("gif")) return "GIF";
        if (mime.contains("bmp")) return "BMP";
        return "";
    }

    /** Real pixel dimensions read from the file header, or null when it cannot be decoded. */
    static int[] dimensions(File f) {
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return o.outWidth > 0 && o.outHeight > 0 ? new int[]{o.outWidth, o.outHeight} : null;
    }

    /** Bitmap downsampled to roughly maxW x maxH; null when the file cannot be decoded. */
    static Bitmap decode(File f, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static File findWithPrefix(File dir, String prefix) {
        if (dir == null || !dir.exists()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File newest = null;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.startsWith(prefix) || name.endsWith(".part") || name.endsWith(".json")) continue;
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String fileExtension(File f) {
        if (f == null) return null;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            for (String known : KNOWN_EXTS) {
                if (known.equals(ext)) return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
