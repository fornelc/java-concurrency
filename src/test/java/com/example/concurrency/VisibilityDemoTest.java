package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class VisibilityDemoTest {

    /**
     * Tests the FIX: a worker spins on a {@code volatile boolean} flag, then the main thread sets it
     * to {@code false}.
     *
     * <p>Expected: the worker sees the write and stops within 2 seconds, on every repetition, because a
     * volatile write is always visible to later reads of the same field.
     */
    @RepeatedTest(3)
    @DisplayName("volatile flag: the worker always sees the write and stops")
    void volatileFlagIsVisible() throws InterruptedException {
        assertTrue(VisibilityDemo.volatileWorkerStops(2_000));
    }

    /**
     * Tests the BUG: the same experiment with a plain (non-volatile) flag.
     *
     * <p>Expected: nothing is asserted, because the Java Memory Model allows both outcomes. On a typical
     * HotSpot JVM the worker never sees the write and keeps spinning (prints "stuck"), but it may also
     * happen to stop. The test only prints which outcome happened.
     */
    @Test
    @DisplayName("plain flag: no guarantee (observational only, never fails)")
    void plainFlagHasNoGuarantee() throws InterruptedException {
        // We deliberately do NOT assert here: the JMM allows both outcomes, so asserting either
        // would make a flaky test. That uncertainty is exactly the lesson.
        boolean stopped = VisibilityDemo.plainWorkerStops(1_000);
        System.out.println("[VisibilityDemoTest] plain-flag worker stopped = " + stopped
                + (stopped ? " (got lucky this time)" : " (stuck: it never saw running=false)"));
    }
}
