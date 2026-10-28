package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExecutorDemoTest {

    @Test
    @DisplayName("shutdown() + awaitTermination(): every submitted task completes on the pooled threads")
    void gracefulShutdownCompletesAllTasks() throws InterruptedException {
        ExecutorDemo.Result result = ExecutorDemo.gracefulShutdown(4, 20);

        assertTrue(result.terminated(), "the pool should terminate after shutdown()");
        assertEquals(20, result.completed(), "every submitted task should complete");
        assertEquals(4, result.threadNames().size(),
                "20 tasks must run on exactly the 4 pooled threads, got " + result.threadNames());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // shorter than one task, so tasks must really be interrupted
    @DisplayName("shutdownNow(): running tasks are interrupted and queued tasks are returned")
    void forcedShutdownInterruptsAndDrains() {
        ExecutorDemo.Result result = ExecutorDemo.forcedShutdown(2, 6);

        assertTrue(result.terminated(), "the pool should terminate after shutdownNow()");
        assertEquals(0, result.completed(), "no task should have completed");
        assertEquals(2, result.interrupted(), "the 2 running tasks should be interrupted");
        assertEquals(4, result.neverStarted(), "the 4 queued tasks should be returned");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("submit() returns immediately and the task runs on a pool thread")
    void submitDoesNotWaitForTheTask() throws InterruptedException {
        ExecutorDemo.SubmitTimeline timeline = ExecutorDemo.showSubmitDoesNotBlock();

        assertTrue(timeline.loopMillis() < 1_000,
                "the submit loop should not wait for the tasks, but took " + timeline.loopMillis() + " ms");
        assertEquals(2, timeline.tasksStarted(), "only one task per pool thread should have started");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("shutdownNow() cannot stop a task that ignores interruption")
    void shutdownNowCannotStopTaskIgnoringInterrupts() throws InterruptedException {
        ExecutorDemo.ShutdownNowResult result = ExecutorDemo.shutdownNowWithTaskIgnoringInterrupts();

        assertFalse(result.terminatedAfterShutdownNow(), "the pool should still be running after shutdownNow()");
        assertTrue(result.interruptRequested(), "shutdownNow() should have set the task's interrupt flag");
        assertFalse(result.stoppedEarly(), "the task should ignore the flag and run to the end");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("shutdownNow() stops a task that checks its interrupt flag")
    void shutdownNowStopsInterruptAwareTask() throws InterruptedException {
        ExecutorDemo.ShutdownNowResult result = ExecutorDemo.shutdownNowWithInterruptAwareTask();

        assertTrue(result.terminatedAfterShutdownNow(), "the pool should terminate right after shutdownNow()");
        assertTrue(result.interruptRequested(), "shutdownNow() should have set the task's interrupt flag");
        assertTrue(result.stoppedEarly(), "the task should exit before its time is up");
    }
}
