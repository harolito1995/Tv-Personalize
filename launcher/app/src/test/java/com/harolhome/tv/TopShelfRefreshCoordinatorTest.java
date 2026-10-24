package com.harolhome.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TopShelfRefreshCoordinatorTest {

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        boolean isEmpty() {
            return tasks.isEmpty();
        }

        void runNext() {
            Runnable task = tasks.poll();
            if (task == null) throw new AssertionError("No queued task");
            task.run();
        }
    }

    private static final class RecordingCallback
            implements TopShelfStore.RefreshCoordinator.Callback<String> {
        int calls;
        String result;
        boolean success;

        @Override
        public void onResult(String result, boolean success) {
            calls++;
            this.result = result;
            this.success = success;
        }
    }

    @Test
    public void testPendingRequestsShareOneFollowUpQuery() {
        ManualExecutor executor = new ManualExecutor();
        TopShelfStore.RefreshCoordinator<String> coordinator =
                new TopShelfStore.RefreshCoordinator<>(executor, Runnable::run);
        AtomicInteger queries = new AtomicInteger();
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();
        RecordingCallback third = new RecordingCallback();

        coordinator.request(() -> {
            queries.incrementAndGet();
            return "first";
        }, first);
        coordinator.request(() -> {
            queries.incrementAndGet();
            return "second";
        }, second);
        coordinator.request(() -> {
            queries.incrementAndGet();
            return "third";
        }, third);

        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(2, queries.get());
        assertEquals(1, first.calls);
        assertEquals("first", first.result);
        assertTrue(first.success);
        assertEquals(1, second.calls);
        assertEquals("third", second.result);
        assertTrue(second.success);
        assertEquals(1, third.calls);
        assertEquals("third", third.result);
        assertTrue(third.success);
        assertTrue(executor.isEmpty());
    }

    @Test
    public void testOnlyOneQueryRunsAtATime() {
        ManualExecutor executor = new ManualExecutor();
        TopShelfStore.RefreshCoordinator<String> coordinator =
                new TopShelfStore.RefreshCoordinator<>(executor, Runnable::run);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        coordinator.request(() -> {
            queries.incrementAndGet();
            maxActive.set(Math.max(maxActive.get(), active.incrementAndGet()));
            active.decrementAndGet();
            return "first";
        }, first);
        coordinator.request(() -> {
            queries.incrementAndGet();
            maxActive.set(Math.max(maxActive.get(), active.incrementAndGet()));
            active.decrementAndGet();
            return "second";
        }, second);

        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(2, queries.get());
        assertEquals(1, maxActive.get());
        assertEquals("first", first.result);
        assertEquals("second", second.result);
    }

    @Test
    public void testRequestDuringBlockedQueryRunsOneFollowUp() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();

        try {
            TopShelfStore.RefreshCoordinator<String> coordinator =
                    new TopShelfStore.RefreshCoordinator<>(executor, Runnable::run);

            coordinator.request(() -> {
                queries.incrementAndGet();
                maxActive.set(Math.max(maxActive.get(), active.incrementAndGet()));
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                active.decrementAndGet();
                return "first";
            }, first);

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            coordinator.request(() -> {
                queries.incrementAndGet();
                maxActive.set(Math.max(maxActive.get(), active.incrementAndGet()));
                active.decrementAndGet();
                return "second";
            }, second);

            releaseFirst.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(2, queries.get());
            assertEquals(1, maxActive.get());
            assertEquals(1, first.calls);
            assertEquals("first", first.result);
            assertEquals(1, second.calls);
            assertEquals("second", second.result);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void testExceptionReleasesGateAndNotifiesFailure() {
        ManualExecutor executor = new ManualExecutor();
        TopShelfStore.RefreshCoordinator<String> coordinator =
                new TopShelfStore.RefreshCoordinator<>(executor, Runnable::run);
        RecordingCallback failed = new RecordingCallback();

        coordinator.request(() -> {
            throw new IllegalStateException("provider unavailable");
        }, failed);
        executor.runNext();

        assertEquals(1, failed.calls);
        assertFalse(failed.success);
        assertNull(failed.result);
        assertTrue(executor.isEmpty());

        RecordingCallback recovered = new RecordingCallback();
        coordinator.request(() -> "recovered", recovered);
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(1, recovered.calls);
        assertTrue(recovered.success);
        assertEquals("recovered", recovered.result);
    }

    @Test
    public void testPendingRequestSurvivesFailedQuery() {
        ManualExecutor executor = new ManualExecutor();
        TopShelfStore.RefreshCoordinator<String> coordinator =
                new TopShelfStore.RefreshCoordinator<>(executor, Runnable::run);
        AtomicInteger queries = new AtomicInteger();
        RecordingCallback failed = new RecordingCallback();
        RecordingCallback recovered = new RecordingCallback();

        coordinator.request(() -> {
            queries.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        }, failed);
        coordinator.request(() -> {
            queries.incrementAndGet();
            return "recovered";
        }, recovered);

        executor.runNext();

        assertEquals(2, queries.get());
        assertEquals(1, failed.calls);
        assertFalse(failed.success);
        assertEquals(1, recovered.calls);
        assertTrue(recovered.success);
        assertEquals("recovered", recovered.result);
    }

    @Test
    public void testCallbacksAreDispatchedOnce() {
        ManualExecutor executor = new ManualExecutor();
        List<Runnable> posted = new ArrayList<>();
        TopShelfStore.RefreshCoordinator<String> coordinator =
                new TopShelfStore.RefreshCoordinator<>(executor, action -> posted.add(action));
        RecordingCallback callback = new RecordingCallback();

        coordinator.request(() -> "value", callback);
        executor.runNext();

        assertEquals(0, callback.calls);
        assertEquals(1, posted.size());
        posted.remove(0).run();

        assertEquals(1, callback.calls);
        assertTrue(callback.success);
        assertEquals("value", callback.result);
        assertTrue(posted.isEmpty());
    }

    @Test
    public void testEmptyPolicyKeepsValidCardsDuringGrace() {
        long now = 1_000_000L;
        long grace = 30_000L;

        assertEquals(TopShelfStore.EmptyAction.KEEP_CACHE,
                TopShelfStore.emptyAction(true, 0L, now, grace));
        assertEquals(TopShelfStore.EmptyAction.KEEP_CACHE,
                TopShelfStore.emptyAction(true, now - 10_000L, now, grace));
        assertEquals(TopShelfStore.EmptyAction.ACCEPT_EMPTY,
                TopShelfStore.emptyAction(true, now - grace, now, grace));
        assertEquals(TopShelfStore.EmptyAction.ACCEPT_EMPTY,
                TopShelfStore.emptyAction(false, 0L, now, grace));
    }

    @Test
    public void testProviderEmptyIsDistinctFromError() {
        TopShelfStore.ProviderResult empty = TopShelfStore.ProviderResult.success(
                new TopShelfStore.Item[]{
                        new TopShelfStore.Item("slot_0", "", "", "", "", 0, false),
                        new TopShelfStore.Item("slot_1", "", "", "", "", 1, false)
                });
        TopShelfStore.ProviderResult error = TopShelfStore.ProviderResult.error();

        assertTrue(empty.success);
        assertEquals(0, TopShelfStore.populatedCount(empty.items));
        assertFalse(error.success);
        assertNull(error.items);
    }
}
