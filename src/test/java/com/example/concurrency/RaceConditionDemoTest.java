package com.example.concurrency;

import static com.example.concurrency.RaceConditionDemo.INCREMENTS_PER_THREAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.concurrency.RaceConditionDemo.AtomicCounter;
import com.example.concurrency.RaceConditionDemo.SynchronizedCounter;
import com.example.concurrency.RaceConditionDemo.UnsafeCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RaceConditionDemoTest {

    private static final int EXPECTED = 2 * INCREMENTS_PER_THREAD;

    /**
     * Tests the BUG: two threads increment a plain {@code int} 100,000 times each with no
     * synchronization.
     *
     * <p>Expected: at least one run ends BELOW 200,000, because {@code count++} is a non-atomic
     * read-modify-write and concurrent increments overwrite each other (lost updates).
     */
    @Test
    @DisplayName("Unsafe counter loses updates (bug is reproducible)")
    void unsafeCounterLosesUpdates() throws InterruptedException {
        // A race is non-deterministic, so a single run could get lucky. We try several times and
        // only require that the bug shows up at least once — in practice it shows up almost every run.
        int worst = EXPECTED;
        for (int attempt = 0; attempt < 20 && worst == EXPECTED; attempt++) {
            worst = Math.min(worst, RaceConditionDemo.runTwoThreads(new UnsafeCounter(), INCREMENTS_PER_THREAD));
        }
        assertTrue(worst < EXPECTED, "expected lost updates, but every run reached " + EXPECTED);
    }

    /**
     * Tests FIX #1: the same two-thread workload on a counter whose methods are {@code synchronized}.
     *
     * <p>Expected: exactly 200,000 on every repetition, because the mutex lets only one thread at a
     * time perform the increment, so no update can be lost.
     */
    @RepeatedTest(10)
    @DisplayName("synchronized counter always reaches 200,000")
    void synchronizedCounterIsCorrect() throws InterruptedException {
        assertEquals(EXPECTED, RaceConditionDemo.runTwoThreads(new SynchronizedCounter(), INCREMENTS_PER_THREAD));
    }

    /**
     * Tests FIX #2: the same two-thread workload on an {@link java.util.concurrent.atomic.AtomicInteger}.
     *
     * <p>Expected: exactly 200,000 on every repetition, because {@code incrementAndGet()} performs the
     * read-modify-write as one atomic compare-and-swap, without any lock.
     */
    @RepeatedTest(10)
    @DisplayName("AtomicInteger counter always reaches 200,000")
    void atomicCounterIsCorrect() throws InterruptedException {
        assertEquals(EXPECTED, RaceConditionDemo.runTwoThreads(new AtomicCounter(), INCREMENTS_PER_THREAD));
    }

    /**
     * Control test: the unsafe counter incremented 200,000 times by a SINGLE thread.
     *
     * <p>Expected: exactly 200,000. This shows the code itself is not wrong; the lost updates only
     * appear when two threads share the counter.
     */
    @Test
    @DisplayName("Single-threaded, even the unsafe counter is correct: the bug needs concurrency")
    void unsafeCounterIsFineSingleThreaded() {
        UnsafeCounter counter = new UnsafeCounter();
        for (int i = 0; i < EXPECTED; i++) {
            counter.increment();
        }
        assertEquals(EXPECTED, counter.get());
    }

    /**
     * Tests the step-by-step trace mode: two threads each do 5 explicit READ / WRITE increments
     * with a {@code Thread.yield()} in between.
     *
     * <p>Expected: a result between 1 and 10. It may be lower than 10 (updates lost when both threads
     * read the same value), but it can never be higher, since a race loses updates and never invents them.
     */
    @Test
    @DisplayName("Trace mode never exceeds the expected value (updates can only be lost, never invented)")
    void traceNeverExceedsExpected() throws InterruptedException {
        int result = RaceConditionDemo.traceInterleaving(5);
        assertTrue(result <= 10 && result >= 1, "result was " + result);
    }
}
