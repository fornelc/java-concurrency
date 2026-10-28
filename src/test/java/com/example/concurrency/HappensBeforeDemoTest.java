package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HappensBeforeDemoTest {

    /**
     * Tests publication through a volatile flag: the writer sets a PLAIN field {@code data = 42} and
     * then the volatile {@code ready = true}; the reader waits for {@code ready} and reads {@code data}.
     *
     * <p>Expected: the reader always sees 42 (never 0) in all 100 repetitions, because program order +
     * the volatile rule + transitivity make the write to {@code data} happen-before the read.
     */
    @RepeatedTest(100)
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Plain data published through a volatile flag is always seen as 42")
    void volatilePublication() throws InterruptedException {
        assertEquals(42, HappensBeforeDemo.publishViaVolatile());
    }

    /**
     * Tests the BUG: the same writer/reader pair, but the {@code ready} flag is a plain field, so there
     * is no happens-before edge between the writer's writes and the reader's reads.
     *
     * <p>Expected: nothing is asserted, because the Java Memory Model allows every outcome: the reader may
     * see 42 (by luck), see {@code ready} but a stale {@code data == 0}, or never see {@code ready} at all.
     * On a typical HotSpot JVM it never sees {@code ready} (prints NEVER_SAW_READY). The test only prints
     * the outcome; compare it with {@link #volatilePublication()}, where 42 is guaranteed.
     */
    @Test
    @DisplayName("Plain flag publication: no guarantee (observational only, never fails)")
    void publicationWithoutVolatileHasNoGuarantee() throws InterruptedException {
        // Asserting any outcome here would make the test flaky: the lack of a guarantee is the lesson.
        HappensBeforeDemo.Outcome outcome = HappensBeforeDemo.publishWithoutVolatile(1_000);
        System.out.println("[HappensBeforeDemoTest] publication without volatile -> " + outcome);
    }

    /**
     * Tests the Thread.start() and Thread.join() happens-before rules: the parent writes 7 before
     * starting the child; the child reads it and writes 7 * 6; the parent reads that after join().
     *
     * <p>Expected: {@code [7, 42]} in all 100 repetitions, with no locks or volatile, because start()
     * makes the parent's earlier writes visible to the child and join() makes the child's writes
     * visible to the parent.
     */
    @RepeatedTest(100)
    @DisplayName("Thread.start() and Thread.join() make writes visible without volatile or locks")
    void startAndJoin() throws InterruptedException {
        assertArrayEquals(new int[] {7, 42}, HappensBeforeDemo.startAndJoinRules());
    }
}
