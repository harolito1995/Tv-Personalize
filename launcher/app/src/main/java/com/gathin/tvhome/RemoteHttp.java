package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small JSON-over-HTTPS helper for the remote providers: same TLS-only redirect policy and
 * User-Agent as the catalogue client, a hard payload cap, and cooperative cancellation while the
 * body is being read.
 */
final class RemoteHttp {

    static final int MAX_JSON_BYTES = 512 * 1024;

    private RemoteHttp() {
    }

    static JSONObject getJson(String url, CancelToken token) throws CatalogException {
        if (token != null && token.isCancelled()) {
            throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
        }
        HttpsURLConnection c = null;
        try {
            c = CatalogClient.open(url, 10_000, 15_000);
            if (token != null) token.attach(c);
            try (InputStream in = c.getInputStream()) {
                byte[] body = readLimited(in, token);
                return new JSONObject(new String(body, StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new CatalogException(CatalogException.Code.FORMAT, "json: " + e.getMessage());
        } catch (IOException e) {
            if (token != null && token.isCancelled()) {
                throw new CatalogException(CatalogException.Code.OFFLINE, "cancelled");
            }
            throw new CatalogException(CatalogException.Code.OFFLINE, e.getMessage());
        } finally {
            if (c != null) {
                if (token != null) token.detach(c);
                c.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream in, CancelToken token)
            throws IOException, CatalogException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (token != null && token.isCancelled()) throw new IOException("cancelled");
            if (out.size() + n > MAX_JSON_BYTES) {
                throw new CatalogException(CatalogException.Code.TOO_BIG,
                        "over " + MAX_JSON_BYTES + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
