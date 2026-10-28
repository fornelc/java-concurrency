package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExecutorDemoTest {

    /**
     * Tests graceful shutdown: 20 short tasks run on a fixed pool of 4 threads, then
     * {@code shutdown()} + {@code awaitTermination()}.
     *
     * <p>Expected: the pool terminates, all 20 tasks complete (shutdown() still runs everything already
     * submitted) and they ran on at most 4 distinct threads, because the pool reuses its threads.
     */
    @Test
    @DisplayName("shutdown() + awaitTermination(): every submitted task completes, threads are reused")
    void gracefulShutdownCompletesAllTasks() throws InterruptedException {
        ExecutorDemo.Result result = ExecutorDemo.gracefulShutdown(4, 20);

        assertTrue(result.terminated());
        assertEquals(20, result.completed());
        assertTrue(result.threadNames().size() <= 4,
                "20 tasks must run on at most 4 pooled threads, got " + result.threadNames());
    }

    /**
     * Tests forced (two-phase) shutdown: 6 tasks that each sleep 10 seconds run on a pool of 2
     * threads; a short {@code awaitTermination()} fails, so {@code shutdownNow()} is called.
     *
     * <p>Expected: the pool terminates quickly, 0 tasks complete, the 2 running tasks are interrupted
     * and the 4 tasks still waiting in the queue are returned without ever starting.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("shutdownNow(): running tasks are interrupted and queued tasks are returned")
    void forcedShutdownInterruptsAndDrains() throws InterruptedException {
        ExecutorDemo.Result result = ExecutorDemo.forcedShutdown(2, 6);

        assertTrue(result.terminated());
        assertEquals(0, result.completed(), "no 10s task should have completed");
        assertEquals(2, result.interrupted(), "the 2 running tasks get interrupted");
        assertEquals(4, result.neverStarted(), "the 4 queued tasks are handed back");
    }

    /**
     * Tests the BUG: a CPU-bound task (a busy loop with no blocking calls) that never checks its
     * interrupt flag, followed by {@code shutdownNow()} and a 500 ms {@code awaitTermination()}.
     *
     * <p>Expected: the pool does NOT terminate in time, even though shutdownNow() did set the task's
     * interrupt flag. The task simply ignores it and runs its full 2 seconds: interruption is only a
     * request, and a task that never checks it cannot be cancelled.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("shutdownNow() cannot stop a task that ignores interruption")
    void shutdownNowCannotStopTaskIgnoringInterrupts() throws InterruptedException {
        ExecutorDemo.ShutdownNowResult result = ExecutorDemo.shutdownNowWithTaskIgnoringInterrupts();

        assertFalse(result.terminatedAfterShutdownNow(), "the pool should still be running after shutdownNow()");
        assertTrue(result.interruptRequested(), "shutdownNow() should have set the task's interrupt flag");
        assertFalse(result.stoppedEarly(), "the task ignores the flag, so it runs to the end");
    }

    /**
     * Tests the FIX: the same busy loop, but it checks {@code Thread.currentThread().isInterrupted()}
     * on every iteration and exits when the flag is set (cooperative cancellation).
     *
     * <p>Expected: the task notices the interrupt and stops early, and the pool terminates within the
     * 500 ms {@code awaitTermination()} that follows {@code shutdownNow()}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("shutdownNow() stops a task that checks its interrupt flag")
    void shutdownNowStopsInterruptAwareTask() throws InterruptedException {
        ExecutorDemo.ShutdownNowResult result = ExecutorDemo.shutdownNowWithInterruptAwareTask();

        assertTrue(result.terminatedAfterShutdownNow(), "the pool should terminate right after shutdownNow()");
        assertTrue(result.interruptRequested(), "shutdownNow() should have set the task's interrupt flag");
        assertTrue(result.stoppedEarly(), "the task should notice the flag and exit before its 2 seconds");
    }
}
