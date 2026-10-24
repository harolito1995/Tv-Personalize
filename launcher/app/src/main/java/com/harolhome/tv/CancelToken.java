package com.harolhome.tv;

import java.net.HttpURLConnection;

/**
 * Cancellation handle for one remote search. Cancelling disconnects the active HTTPS request so
 * an obsolete query stops reading immediately instead of holding the only network worker until
 * the read timeout expires.
 */
final class CancelToken {

    private volatile boolean cancelled;
    private volatile HttpURLConnection active;

    void cancel() {
        cancelled = true;
        HttpURLConnection c = active;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    void attach(HttpURLConnection c) {
        active = c;
        if (cancelled) c.disconnect();
    }

    void detach(HttpURLConnection c) {
        if (active == c) active = null;
    }
}
