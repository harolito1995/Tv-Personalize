package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Persistent store and model for the two cinematic 16:9 Top Shelf cards.
 * Implements real Android TV Watch Next content resolution (content://android.media.tv/watch_next_program),
 * custom item overrides, and honest, non-deceptive cinema empty states.
 */
public final class TopShelfStore {

    private static final String TAG = "TopShelfStore";
    private static final String PREFS_NAME = "top_shelf_prefs";
    private static final String KEY_RECENTS = "recent_packages";
    private static final int MAX_RECENTS = 6;

    static final String WATCH_NEXT_URI_STRING = "content://android.media.tv/watch_next_program";
    public static final String WATCH_NEXT_PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final String KEY_CACHE_AT = "watch_next_cache_at";
    private static final String KEY_CACHE_PREFIX = "watch_next_cache_";
    private static final String KEY_EMPTY_SINCE = "watch_next_empty_since";
    private static final long CACHE_TTL_MS = 10_000L;
    private static final long EMPTY_GRACE_MS = 30_000L;
    private static final long PROVIDER_TIMEOUT_MS = 5_000L;
    private static final Pattern MINUTES_REMAINING_PATTERN = Pattern.compile(
            "(?i)(?:\\s*[\u00B7\\-|,]\\s*)?(?:(?:quedan|faltan|restan)\\s+\\d+\\s*(?:h\\s*\\d*\\s*)?(?:min(?:utos)?|m)\\b|\\b\\d+\\s*(?:min(?:utos)?|m)(?:\\s+(?:left|remaining|restantes?))?\\b)"
    );
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService WATCH_NEXT_IO = Executors.newSingleThreadExecutor();
    private static final RefreshCoordinator<Item[]> REFRESH_COORDINATOR =
            new RefreshCoordinator<>(REFRESH_EXECUTOR,
                    action -> MainThread.HANDLER.post(action));
    private static final Object CACHE_LOCK = new Object();
    private static Item[] memoryCache;
    private static long memoryCacheAt;
    private static long memoryEmptySince;
    private static boolean memoryEmptySinceLoaded;

    public interface SlotsCallback {
        void onSlots(Item[] slots);
    }

    private static final class MainThread {
        static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    public static final class Item {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String backdropUri;
        public final String targetPackage;
        public final int order;
        public final boolean isCustom;
        public final int progressPercent; // -1 if unknown / no progress bar, 0..100
        public final String intentUri;

        public Item(String id, String title, String subtitle, String backdropUri,
                    String targetPackage, int order, boolean isCustom) {
            this(id, title, subtitle, backdropUri, targetPackage, order, isCustom, -1, "");
        }

        public Item(String id, String title, String subtitle, String backdropUri,
                    String targetPackage, int order, boolean isCustom, int progressPercent,
                    String intentUri) {
            this.id = id != null ? id : ("slot_" + order);
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.backdropUri = backdropUri != null ? backdropUri : "";
            this.targetPackage = targetPackage != null ? targetPackage : "";
            this.order = order;
            this.isCustom = isCustom;
            this.progressPercent = progressPercent;
            this.intentUri = intentUri != null ? intentUri : "";
        }

        public boolean hasTarget() {
            return (targetPackage != null && !targetPackage.trim().isEmpty())
                    || (intentUri != null && !intentUri.trim().isEmpty());
        }

        public boolean hasBackdrop() {
            return backdropUri != null && !backdropUri.trim().isEmpty();
        }
    }

    static final class ProviderResult {
        final boolean success;
        final Item[] items;

        private ProviderResult(boolean success, Item[] items) {
            this.success = success;
            this.items = items;
        }

        static ProviderResult success(Item[] items) {
            return new ProviderResult(true, items);
        }

        static ProviderResult error() {
            return new ProviderResult(false, null);
        }
    }

    enum EmptyAction {
        KEEP_CACHE,
        ACCEPT_EMPTY
    }

    static final class RefreshCoordinator<T> {
        interface Task<T> {
            T run() throws Exception;
        }

        interface Callback<T> {
            void onResult(T result, boolean success);
        }

        interface Dispatcher {
            void dispatch(Runnable action);
        }

        private final Executor executor;
        private final Dispatcher dispatcher;
        private final Object lock = new Object();
        private final List<Callback<T>> activeCallbacks = new ArrayList<>();
        private final List<Callback<T>> pendingCallbacks = new ArrayList<>();

        private boolean running;
        private boolean pending;
        private Task<T> activeTask;
        private Task<T> pendingTask;
        private long nextWorkerId;
        private long ownerId;

        RefreshCoordinator(Executor executor, Dispatcher dispatcher) {
            this.executor = executor;
            this.dispatcher = dispatcher;
        }

        void request(Task<T> task, Callback<T> callback) {
            if (task == null || callback == null) return;
            long workerId = 0L;
            boolean start = false;
            synchronized (lock) {
                if (!running) {
                    running = true;
                    ownerId = ++nextWorkerId;
                    workerId = ownerId;
                    activeTask = task;
                    activeCallbacks.add(callback);
                    start = true;
                } else {
                    pending = true;
                    pendingTask = task;
                    pendingCallbacks.add(callback);
                }
            }
            if (start) launch(workerId);
        }

        private void launch(long workerId) {
            try {
                executor.execute(() -> runWorker(workerId));
            } catch (RuntimeException e) {
                failWorker(workerId);
            }
        }

        private void runWorker(long workerId) {
            try {
                while (true) {
                    Task<T> task;
                    List<Callback<T>> callbacks;
                    synchronized (lock) {
                        if (ownerId != workerId) return;
                        task = activeTask;
                        activeTask = null;
                        callbacks = new ArrayList<>(activeCallbacks);
                        activeCallbacks.clear();
                    }

                    T result = null;
                    boolean success = false;
                    if (task != null) {
                        try {
                            result = task.run();
                            success = true;
                        } catch (Exception ignored) {
                        }
                    }

                    for (Callback<T> callback : callbacks) {
                        final T finalResult = result;
                        final boolean finalSuccess = success;
                        dispatcher.dispatch(() -> callback.onResult(finalResult, finalSuccess));
                    }

                    synchronized (lock) {
                        if (ownerId != workerId) return;
                        if (!pending) return;
                        pending = false;
                        activeTask = pendingTask;
                        pendingTask = null;
                        activeCallbacks.addAll(pendingCallbacks);
                        pendingCallbacks.clear();
                    }
                }
            } finally {
                boolean restart = false;
                long restartId = 0L;
                synchronized (lock) {
                    if (ownerId == workerId) {
                        if (pending) {
                            pending = false;
                            activeTask = pendingTask;
                            pendingTask = null;
                            activeCallbacks.addAll(pendingCallbacks);
                            pendingCallbacks.clear();
                            ownerId = ++nextWorkerId;
                            restartId = ownerId;
                            restart = true;
                        } else {
                            running = false;
                            activeTask = null;
                            activeCallbacks.clear();
                        }
                    }
                }
                if (restart) launch(restartId);
            }
        }

        private void failWorker(long workerId) {
            List<Callback<T>> callbacks = new ArrayList<>();
            synchronized (lock) {
                if (ownerId != workerId) return;
                running = false;
                ownerId = 0L;
                activeTask = null;
                pendingTask = null;
                pending = false;
                callbacks.addAll(activeCallbacks);
                callbacks.addAll(pendingCallbacks);
                activeCallbacks.clear();
                pendingCallbacks.clear();
            }
            for (Callback<T> callback : callbacks) {
                dispatcher.dispatch(() -> callback.onResult(null, false));
            }
        }
    }

    private TopShelfStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Records an app package launched from the launcher in an LRU history. */
    public static void recordAppLaunch(Context context, String pkg) {
        if (context == null || pkg == null || pkg.trim().isEmpty()) return;
        String clean = pkg.trim();
        List<String> list = getRecentApps(context);
        list.remove(clean);
        list.add(0, clean);
        while (list.size() > MAX_RECENTS) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        prefs(context).edit().putString(KEY_RECENTS, sb.toString()).apply();
    }

    /** Returns the list of packages recently launched from the launcher. */
    public static List<String> getRecentApps(Context context) {
        if (context == null) return new ArrayList<>();
        String raw = prefs(context).getString(KEY_RECENTS, "");
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String[] parts = raw.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String clean = p.trim();
            if (!clean.isEmpty() && !list.contains(clean)) {
                list.add(clean);
            }
        }
        return list;
    }

    /** True when the app may read the system Watch Next history. */
    public static boolean hasWatchNextPermission(Context context) {
        return context != null && context.checkSelfPermission(WATCH_NEXT_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Last valid items for immediate painting, custom slots included. Never touches the provider. */
    public static Item[] getCachedSlots(Context context) {
        Item[] cached = readCachedSlots(context);
        Item[] result = new Item[2];
        for (int slot = 0; slot < result.length; slot++) {
            if (context != null && isCustom(context, slot)) {
                result[slot] = readCustomSlot(context, slot);
            } else if (cached != null) {
                result[slot] = cached[slot];
            } else {
                result[slot] = emptySlot(slot);
            }
        }
        return result;
    }

    /**
     * Refreshes both slots through a single-flight provider query. Callers paint the cached rows
     * first; a short cache and one coalesced follow-up keep repeated resumes cheap.
     */
    public static void refreshSlots(Context context, boolean force, SlotsCallback callback) {
        if (context == null || callback == null) return;
        final Context app = context.getApplicationContext();
        if (!hasAutoSlot(app)) {
            Log.i(TAG, "refresh skipped: both slots are custom");
            postSlots(callback, getCachedSlots(app));
            return;
        }
        if (!hasWatchNextPermission(app)) {
            Log.w(TAG, "refresh skipped: READ_TV_LISTINGS not granted");
            postSlots(callback, getCachedSlots(app));
            return;
        }
        if (!force && isCacheFresh(app)) {
            Log.d(TAG, "refresh skipped: cache is fresh");
            postSlots(callback, getCachedSlots(app));
            return;
        }
        REFRESH_COORDINATOR.request(
                () -> performRefresh(app),
                (items, success) -> callback.onSlots(items != null ? items : getCachedSlots(app)));
    }

    private static Item[] performRefresh(Context app) {
        final long startedAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "refresh start");
        ProviderResult result = queryWatchNextBoth(app);
        Item[] out;

        if (!result.success) {
            out = getCachedSlots(app);
            Log.w(TAG, "refresh preserved cache: provider error items=" + populatedCount(out));
        } else {
            Item[] cachedSlots = readCachedSlots(app);
            int cachedCount = populatedCount(cachedSlots);
            int usable = populatedCount(result.items);
            if (usable == 0) {
                long now = System.currentTimeMillis();
                long emptySince = readEmptySince(app);
                boolean cachePopulated = cachedCount > 0;
                EmptyAction action = emptyAction(cachePopulated, emptySince, now, EMPTY_GRACE_MS);
                if (action == EmptyAction.KEEP_CACHE) {
                    if (emptySince <= 0L) writeEmptySince(app, now);
                    out = getCachedSlots(app);
                    Log.i(TAG, "provider empty; keeping valid cache within grace items="
                            + populatedCount(out));
                } else {
                    writeEmptySince(app, 0L);
                    cacheSlots(app, result.items);
                    out = mergeCustom(app, result.items);
                    Log.i(TAG, "provider empty confirmed; cache cleared");
                }
            } else if (usable < cachedCount) {
                long now = System.currentTimeMillis();
                long emptySince = readEmptySince(app);
                EmptyAction action = emptyAction(true, emptySince, now, EMPTY_GRACE_MS);
                if (action == EmptyAction.KEEP_CACHE) {
                    if (emptySince <= 0L) writeEmptySince(app, now);
                    Item[] reconciled = reconcilePartialDrop(result.items, cachedSlots);
                    out = mergeCustom(app, reconciled);
                    Log.i(TAG, "provider partial drop (" + cachedCount + "->" + usable
                            + "); preserving missing slot within grace");
                } else {
                    writeEmptySince(app, 0L);
                    cacheSlots(app, result.items);
                    out = mergeCustom(app, result.items);
                    Log.i(TAG, "provider partial drop confirmed; cache updated items=" + usable);
                }
            } else {
                writeEmptySince(app, 0L);
                cacheSlots(app, result.items);
                out = mergeCustom(app, result.items);
                Log.i(TAG, "provider ok items=" + usable);
            }
        }

        Log.i(TAG, "refresh done items=" + populatedCount(out)
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
        return out;
    }

    private static void postSlots(SlotsCallback callback, Item[] slots) {
        MainThread.HANDLER.post(() -> callback.onSlots(slots));
    }

    private static boolean hasAutoSlot(Context context) {
        return !isCustom(context, 0) || !isCustom(context, 1);
    }

    private static boolean isCustom(Context context, int slot) {
        return prefs(context).getBoolean("slot_" + slot + "_custom", false);
    }

    private static boolean isCacheFresh(Context context) {
        long at;
        synchronized (CACHE_LOCK) {
            at = memoryCacheAt;
        }
        if (at <= 0) at = prefs(context).getLong(KEY_CACHE_AT, 0);
        return shouldReuseCache(false, at, System.currentTimeMillis(), CACHE_TTL_MS);
    }

    /** Pure policy used by the refresh path so cache behavior is unit-testable. */
    static boolean shouldReuseCache(boolean force, long cachedAt, long now, long ttlMs) {
        return !force && cachedAt > 0 && now - cachedAt < ttlMs;
    }

    static EmptyAction emptyAction(boolean cachePopulated, long emptySince, long now,
                                   long graceMs) {
        if (!cachePopulated) return EmptyAction.ACCEPT_EMPTY;
        if (emptySince <= 0L || now < emptySince) return EmptyAction.KEEP_CACHE;
        return now - emptySince >= graceMs ? EmptyAction.ACCEPT_EMPTY : EmptyAction.KEEP_CACHE;
    }

    private static long readEmptySince(Context context) {
        synchronized (CACHE_LOCK) {
            if (memoryEmptySinceLoaded) return memoryEmptySince;
        }
        long value = prefs(context).getLong(KEY_EMPTY_SINCE, 0L);
        synchronized (CACHE_LOCK) {
            if (!memoryEmptySinceLoaded) {
                memoryEmptySince = value;
                memoryEmptySinceLoaded = true;
            }
            return memoryEmptySince;
        }
    }

    private static void writeEmptySince(Context context, long value) {
        synchronized (CACHE_LOCK) {
            memoryEmptySince = value;
            memoryEmptySinceLoaded = true;
        }
        prefs(context).edit().putLong(KEY_EMPTY_SINCE, value).apply();
    }

    private static Item readCustomSlot(Context context, int slot) {
        SharedPreferences p = prefs(context);
        String title = p.getString("slot_" + slot + "_title", "");
        String subtitle = p.getString("slot_" + slot + "_subtitle", "");
        String backdrop = p.getString("slot_" + slot + "_backdrop", "");
        String pkg = p.getString("slot_" + slot + "_package", "");
        int progress = p.getInt("slot_" + slot + "_progress", -1);
        String intentUri = p.getString("slot_" + slot + "_intent", "");
        return new Item("slot_" + slot, title, subtitle, backdrop, pkg, slot, true, progress, intentUri);
    }

    private static Item emptySlot(int slot) {
        return new Item("slot_" + slot, "", "", "", "", slot, false, -1, "");
    }

    private static Item[] mergeCustom(Context context, Item[] watchNext) {
        Item[] out = new Item[2];
        for (int slot = 0; slot < out.length; slot++) {
            if (isCustom(context, slot)) {
                out[slot] = readCustomSlot(context, slot);
            } else {
                out[slot] = watchNext[slot] != null ? watchNext[slot] : emptySlot(slot);
            }
        }
        return out;
    }

    private static void cacheSlots(Context context, Item[] slots) {
        long at = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putLong(KEY_CACHE_AT, at);
        for (int slot = 0; slot < 2; slot++) {
            Item item = slots[slot] != null ? slots[slot] : emptySlot(slot);
            String prefix = KEY_CACHE_PREFIX + slot + "_";
            editor.putString(prefix + "id", item.id);
            editor.putString(prefix + "title", item.title);
            editor.putString(prefix + "subtitle", item.subtitle);
            editor.putString(prefix + "backdrop", item.backdropUri);
            editor.putString(prefix + "package", item.targetPackage);
            editor.putInt(prefix + "progress", item.progressPercent);
            editor.putString(prefix + "intent", item.intentUri);
        }
        editor.apply();
        synchronized (CACHE_LOCK) {
            memoryCache = slots;
            memoryCacheAt = at;
        }
    }

    private static Item[] readCachedSlots(Context context) {
        if (context == null) return null;
        synchronized (CACHE_LOCK) {
            if (memoryCache != null) return memoryCache;
        }
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_CACHE_AT)) return null;
        Item[] cached = new Item[2];
        for (int slot = 0; slot < cached.length; slot++) {
            String prefix = KEY_CACHE_PREFIX + slot + "_";
            cached[slot] = new Item(
                    p.getString(prefix + "id", "slot_" + slot),
                    p.getString(prefix + "title", ""),
                    sanitizeSubtitle(p.getString(prefix + "subtitle", "")),
                    p.getString(prefix + "backdrop", ""),
                    p.getString(prefix + "package", ""),
                    slot,
                    false,
                    p.getInt(prefix + "progress", -1),
                    p.getString(prefix + "intent", ""));
        }
        synchronized (CACHE_LOCK) {
            if (memoryCache == null) {
                memoryCache = cached;
                memoryCacheAt = p.getLong(KEY_CACHE_AT, 0);
            }
        }
        return cached;
    }

    /** Retrieves the Item for slot 0 or slot 1, using real Watch Next data unless a custom slot exists. */
    public static Item getSlot(Context context, int slot) {
        if (context == null) return emptySlot(slot);
        if (isCustom(context, slot)) return readCustomSlot(context, slot);
        Item watched = queryWatchNext(context, slot);
        return watched != null ? watched : emptySlot(slot);
    }

    /** Queries the standard Android TV WatchNextPrograms content provider. */
    private static Item queryWatchNext(Context context, int slot) {
        if (context == null || slot < 0 || !hasWatchNextPermission(context)) return null;
        try {
            Cursor c = queryWatchNextCursor(context);
            if (c != null) {
                try {
                    if (c.moveToPosition(slot)) return fromCursor(c, slot);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** One provider round-trip for both cards; success is distinct from an empty result. */
    private static ProviderResult queryWatchNextBoth(Context context) {
        if (context == null || !hasWatchNextPermission(context)) return ProviderResult.error();

        CancellationSignal cancellation = new CancellationSignal();
        Future<ProviderResult> future;
        try {
            future = WATCH_NEXT_IO.submit(
                    () -> queryWatchNextProvider(context, cancellation));
        } catch (RuntimeException e) {
            Log.w(TAG, "provider query failed to start");
            return ProviderResult.error();
        }

        try {
            return future.get(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "provider query timeout; requesting cancellation");
            cancellation.cancel();
            future.cancel(false);
            return ProviderResult.error();
        } catch (InterruptedException e) {
            cancellation.cancel();
            future.cancel(false);
            Thread.currentThread().interrupt();
            return ProviderResult.error();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Log.w(TAG, "provider query failed: "
                    + (cause != null ? cause.getClass().getSimpleName() : "execution"));
            return ProviderResult.error();
        } catch (CancellationException e) {
            return ProviderResult.error();
        }
    }

    private static ProviderResult queryWatchNextProvider(Context context,
                                                         CancellationSignal cancellation) {
        Cursor c = null;
        try {
            c = queryWatchNextCursor(context, cancellation);
            if (c == null) {
                Log.w(TAG, "provider query failed: null cursor");
                return ProviderResult.error();
            }
            final int rowCount = c.getCount();
            List<Item> candidates = new ArrayList<>(Math.min(rowCount, 6));
            int limit = Math.min(rowCount, 6);
            for (int pos = 0; pos < limit; pos++) {
                if (c.moveToPosition(pos)) {
                    Item parsed = fromCursor(c, candidates.size());
                    if (parsed != null) candidates.add(parsed);
                }
            }
            Item[] raw = candidates.toArray(new Item[0]);
            Item[] out = normalizeWatchNext(raw);
            final int usable = populatedCount(out);
            Log.i(TAG, "provider rows=" + rowCount + " usable=" + usable
                    + " discarded=" + Math.max(0, populatedCount(raw) - usable));
            return ProviderResult.success(out);
        } catch (OperationCanceledException e) {
            Log.w(TAG, "provider query cancelled");
            return ProviderResult.error();
        } catch (Exception e) {
            Log.w(TAG, "provider query failed: " + e.getClass().getSimpleName());
            return ProviderResult.error();
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Keeps provider order, drops malformed or duplicate rows, and always returns exactly two slots.
     * Pure so the Watch Next normalization rules can be verified on the JVM.
     */
    static Item[] normalizeWatchNext(Item[] raw) {
        Item[] out = {emptySlot(0), emptySlot(1)};
        if (raw == null) return out;
        List<String> seenIds = new ArrayList<>();
        int target = 0;
        for (Item item : raw) {
            if (target >= out.length) break;
            if (!isPopulated(item) || seenIds.contains(item.id)) continue;
            seenIds.add(item.id);
            out[target] = new Item(item.id, item.title, sanitizeSubtitle(item.subtitle),
                    item.backdropUri, item.targetPackage, target, item.isCustom,
                    item.progressPercent, item.intentUri);
            target++;
        }
        return out;
    }

    static boolean isPopulated(Item item) {
        return item != null && (!item.title.trim().isEmpty() || item.hasBackdrop() || item.hasTarget());
    }

    static int populatedCount(Item[] items) {
        if (items == null) return 0;
        int count = 0;
        for (Item item : items) if (isPopulated(item)) count++;
        return count;
    }

    private static Cursor queryWatchNextCursor(Context context) {
        return queryWatchNextCursor(context, null);
    }

    private static Cursor queryWatchNextCursor(Context context, CancellationSignal cancellation) {
        String[] projection = {
                "_id",
                "title",
                "episode_title",
                "season_number",
                "episode_number",
                "poster_art_uri",
                "thumbnail_uri",
                "last_playback_position_millis",
                "duration_millis",
                "package_name",
                "intent_uri"
        };
        return context.getContentResolver().query(
                Uri.parse(WATCH_NEXT_URI_STRING),
                projection,
                null,
                null,
                "last_engagement_time_utc_millis DESC, _id DESC",
                cancellation
        );
    }

    private static Item fromCursor(Cursor c, int slot) {
        long id = number(c, "_id");
        String title = string(c, "title");
        String epTitle = string(c, "episode_title");
        String season = string(c, "season_number");
        String episode = string(c, "episode_number");
        String poster = string(c, "poster_art_uri");
        String thumb = string(c, "thumbnail_uri");
        long pos = number(c, "last_playback_position_millis");
        long dur = number(c, "duration_millis");
        String pkg = string(c, "package_name");
        String intentUri = string(c, "intent_uri");

        String mainTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : epTitle;
        if (mainTitle == null || mainTitle.trim().isEmpty()) return null;
        mainTitle = sanitizeSubtitle(mainTitle);
        if (mainTitle.isEmpty()) return null;

        String sub = buildCleanSubtitle(season, episode, epTitle, mainTitle);

        return new Item("wn_" + id, mainTitle, sub, pickArtwork(thumb, poster),
                pkg, slot, false, progressPercent(pos, dur), intentUri);
    }

    static String buildCleanSubtitle(String season, String episode, String epTitle, String mainTitle) {
        String s = season != null ? season.trim() : "";
        String e = episode != null ? episode.trim() : "";
        if (!s.isEmpty() && !e.isEmpty()) {
            return "T" + s + " E" + e;
        }
        String cleanEp = sanitizeSubtitle(epTitle);
        if (!cleanEp.isEmpty() && !cleanEp.equalsIgnoreCase(mainTitle != null ? mainTitle.trim() : "")) {
            return cleanEp;
        }
        return "";
    }

    static String sanitizeSubtitle(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String cleaned = raw.replace("\u00C3\u0192\u00E2\u20AC\u0161\u00C3\u201A\u00C2\u00B7", "\u00B7")
                .replace("\u00C3\u201A\u00C2\u00B7", "\u00B7");
        cleaned = MINUTES_REMAINING_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("^\\s*[\u00B7\\-|,]+\\s*", "")
                .replaceAll("\\s*[\u00B7\\-|,]+\\s*$", "")
                .trim();
        return cleaned;
    }

    static Item[] reconcilePartialDrop(Item[] fresh, Item[] cached) {
        Item[] base = normalizeWatchNext(fresh);
        if (cached == null) return base;
        int freshCount = populatedCount(base);
        int cachedCount = populatedCount(cached);
        if (freshCount >= cachedCount || freshCount >= base.length) return base;

        List<Item> combined = new ArrayList<>(4);
        for (Item item : base) {
            if (isPopulated(item)) combined.add(item);
        }
        for (Item cachedItem : cached) {
            if (!isPopulated(cachedItem)) continue;
            if (!containsSameSeriesOrId(combined, cachedItem)) {
                combined.add(cachedItem);
            }
        }
        Item[] merged = new Item[2];
        for (int i = 0; i < merged.length; i++) {
            if (i < combined.size()) {
                Item src = combined.get(i);
                merged[i] = new Item(src.id, src.title, src.subtitle, src.backdropUri,
                        src.targetPackage, i, src.isCustom, src.progressPercent, src.intentUri);
            } else {
                merged[i] = emptySlot(i);
            }
        }
        return merged;
    }

    private static boolean containsSameSeriesOrId(List<Item> list, Item candidate) {
        for (Item existing : list) {
            if (existing.id.equals(candidate.id)) return true;
            if (!existing.title.isEmpty() && existing.title.equalsIgnoreCase(candidate.title)
                    && existing.targetPackage.equals(candidate.targetPackage)) {
                return true;
            }
        }
        return false;
    }

    /** 16:9 cards prefer the landscape thumbnail, then fall back to the poster. */
    static String pickArtwork(String thumbnailUri, String posterUri) {
        String thumb = thumbnailUri != null ? thumbnailUri.trim() : "";
        if (!thumb.isEmpty()) return thumb;
        return posterUri != null ? posterUri.trim() : "";
    }

    /** Real playback percentage, or -1 when the provider has no usable position/duration. */
    static int progressPercent(long positionMillis, long durationMillis) {
        if (positionMillis <= 0 || durationMillis <= 0) return -1;
        return (int) Math.min(100, Math.max(0, (positionMillis * 100L) / durationMillis));
    }

    private static String string(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index < 0 || c.isNull(index) ? null : c.getString(index);
    }

    private static long number(Cursor c, String column) {
        int index = c.getColumnIndex(column);
        return index < 0 || c.isNull(index) ? 0 : c.getLong(index);
    }

    /** Saves custom values for a slot. */
    public static void saveSlot(Context context, int slot, String title, String subtitle,
                                String backdropUri, String targetPackage, int progressPercent,
                                String intentUri) {
        if (context == null) return;
        prefs(context).edit()
                .putBoolean("slot_" + slot + "_custom", true)
                .putString("slot_" + slot + "_title", title != null ? title.trim() : "")
                .putString("slot_" + slot + "_subtitle", subtitle != null ? subtitle.trim() : "")
                .putString("slot_" + slot + "_backdrop", backdropUri != null ? backdropUri.trim() : "")
                .putString("slot_" + slot + "_package", targetPackage != null ? targetPackage.trim() : "")
                .putInt("slot_" + slot + "_progress", progressPercent)
                .putString("slot_" + slot + "_intent", intentUri != null ? intentUri.trim() : "")
                .apply();
    }

    public static void saveSlot(Context context, int slot, String title, String subtitle,
                                String backdropUri, String targetPackage) {
        saveSlot(context, slot, title, subtitle, backdropUri, targetPackage, -1, "");
    }

    /** Clears custom values for a slot to restore auto-detection. */
    public static void resetSlot(Context context, int slot) {
        if (context == null) return;
        prefs(context).edit()
                .remove("slot_" + slot + "_custom")
                .remove("slot_" + slot + "_title")
                .remove("slot_" + slot + "_subtitle")
                .remove("slot_" + slot + "_backdrop")
                .remove("slot_" + slot + "_package")
                .remove("slot_" + slot + "_progress")
                .remove("slot_" + slot + "_intent")
                .apply();
    }

    /** Clears recent app history (used in tests). */
    public static void clearRecents(Context context) {
        if (context == null) return;
        prefs(context).edit().remove(KEY_RECENTS).apply();
    }
}

