package com.harolhome.tv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches the catalogue manifest over HTTPS only: bounded connect/read timeouts, a cap on the
 * JSON size and manual redirect handling that refuses to leave HTTPS. All calls are blocking and
 * must run off the main thread.
 */
final class CatalogClient {

    static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 3;
    static final String USER_AGENT = "tvhome-launcher/2.3 (Android TV launcher; personal use)";

    private CatalogClient() {
    }

    static Catalog fetch(String url) throws CatalogException {
        return fetch(url, null);
    }

    /**
     * Fetches the manifest and keeps the last good copy in {@code cacheFile}. When the network
     * fails (offline, HTTP error or redirect loop) the saved copy is served instead, flagged as
     * cached so the UI can say so; with no saved copy the original error is raised.
     */
    static Catalog fetch(String url, File cacheFile) throws CatalogException {
        HttpsURLConnection c = null;
        try {
            c = open(url, 10_000, 15_000);
            long declared = c.getContentLengthLong();
            if (declared > MAX_JSON_BYTES) {
                throw new CatalogException(CatalogException.Code.TOO_BIG, "content-length " + declared);
            }
            byte[] body;
            try (InputStream in = c.getInputStream()) {
                body = readLimited(in, MAX_JSON_BYTES);
            }
            Catalog parsed = Catalog.parse(new String(body, charset(c.getContentType())));
            writeCache(cacheFile, body);
            return parsed;
        } catch (IOException e) {
            Catalog cached = readCache(cacheFile);
            if (cached != null) return cached;
            throw new CatalogException(CatalogException.Code.OFFLINE, e.getMessage());
        } catch (CatalogException e) {
            if (e.code == CatalogException.Code.OFFLINE || e.code == CatalogException.Code.HTTP
                    || e.code == CatalogException.Code.REDIRECTS) {
                Catalog cached = readCache(cacheFile);
                if (cached != null) return cached;
            }
            throw e;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void writeCache(File f, byte[] body) {
        if (f == null) return;
        try {
            File tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException ignored) {
        }
    }

    private static Catalog readCache(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return Catalog.parse(new String(data, StandardCharsets.UTF_8)).asCached();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens an HTTPS URL, following at most three redirects (each one must also be HTTPS) and
     * returns a live 200 connection; the caller reads the body and disconnects.
     */
    static HttpsURLConnection open(String url, int connectTimeoutMs, int readTimeoutMs)
            throws CatalogException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!Catalog.isHttpsUrl(current)) {
                throw new CatalogException(hop == 0 ? CatalogException.Code.BAD_URL
                        : CatalogException.Code.NOT_HTTPS, "not https: " + current);
            }
            HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
            } catch (MalformedURLException | ClassCastException e) {
                throw new CatalogException(CatalogException.Code.BAD_URL, e.getMessage());
            } catch (IOException e) {
                throw new CatalogException(CatalogException.Code.OFFLINE, e.getMessage());
            }
            if (!(conn instanceof HttpsURLConnection)) {
                conn.disconnect();
                throw new CatalogException(CatalogException.Code.NOT_HTTPS, "no TLS: " + current);
            }
            HttpsURLConnection hc = (HttpsURLConnection) conn;
            hc.setConnectTimeout(connectTimeoutMs);
            hc.setReadTimeout(readTimeoutMs);
            hc.setInstanceFollowRedirects(false);
            hc.setRequestProperty("User-Agent", USER_AGENT);
            try {
                int code = hc.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = hc.getHeaderField("Location");
                    hc.disconnect();
                    if (location == null || location.isEmpty()) {
                        throw new CatalogException(CatalogException.Code.REDIRECTS, "missing location");
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code != 200) {
                    hc.disconnect();
                    throw new CatalogException(CatalogException.Code.HTTP, "http " + code, code);
                }
                return hc;
            } catch (IOException e) {
                hc.disconnect();
                throw new CatalogException(CatalogException.Code.OFFLINE, e.getMessage());
            }
        }
        throw new CatalogException(CatalogException.Code.REDIRECTS, "too many redirects");
    }

    /** Reads at most {@code cap} bytes; anything longer raises TOO_BIG. */
    static byte[] readLimited(InputStream in, int cap) throws IOException, CatalogException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > cap) {
                throw new CatalogException(CatalogException.Code.TOO_BIG, "over " + cap + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Charset charset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int i = lower.indexOf("charset=");
            if (i >= 0) {
                String name = lower.substring(i + 8).split(";")[0].trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
