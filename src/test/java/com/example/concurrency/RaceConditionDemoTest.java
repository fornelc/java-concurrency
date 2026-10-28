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

    @Test
    @DisplayName("Unsafe counter loses updates")
    void unsafeCounterLosesUpdates() throws InterruptedException {
        // Races are non-deterministic: retry until the bug shows up at least once.
        int worst = EXPECTED;
        for (int attempt = 0; attempt < 20 && worst == EXPECTED; attempt++) {
            worst = Math.min(worst, RaceConditionDemo.runTwoThreads(new UnsafeCounter(), INCREMENTS_PER_THREAD));
        }
        assertTrue(worst < EXPECTED, "expected lost updates, but every run reached " + EXPECTED);
    }

    @RepeatedTest(10)
    @DisplayName("synchronized counter always reaches the expected value")
    void synchronizedCounterIsCorrect() throws InterruptedException {
        assertEquals(EXPECTED, RaceConditionDemo.runTwoThreads(new SynchronizedCounter(), INCREMENTS_PER_THREAD));
    }

    @RepeatedTest(10)
    @DisplayName("AtomicInteger counter always reaches the expected value")
    void atomicCounterIsCorrect() throws InterruptedException {
        assertEquals(EXPECTED, RaceConditionDemo.runTwoThreads(new AtomicCounter(), INCREMENTS_PER_THREAD));
    }

    @Test
    @DisplayName("Unsafe counter is correct when used by a single thread")
    void unsafeCounterIsFineSingleThreaded() {
        UnsafeCounter counter = new UnsafeCounter();
        for (int i = 0; i < EXPECTED; i++) {
            counter.increment();
        }
        assertEquals(EXPECTED, counter.get());
    }

    @Test
    @DisplayName("Trace mode can lose updates but never exceeds the expected value")
    void traceNeverExceedsExpected() throws InterruptedException {
        int result = RaceConditionDemo.traceInterleaving(5);
        assertTrue(result >= 1 && result <= 10, "result was " + result);
    }
}
