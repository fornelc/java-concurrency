package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DeadlockDemoTest {

    /**
     * Tests the DEADLOCK: thread 1 takes lockA then lockB, thread 2 takes lockB then lockA.
     *
     * <p>Expected: the JVM's deadlock detector ({@code ThreadMXBean.findDeadlockedThreads()}) reports
     * both threads, both are still alive and both are in state {@code BLOCKED}, each waiting for the
     * monitor the other one holds. They will never finish.
     *
     * <p>A PASSING test means the deadlock was reproduced. If it fails, the deadlock did NOT happen
     * (e.g. a thread finished), so the demo is not showing the problem.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // safety net: if the deadlock is never detected, fail instead of hanging
    @DisplayName("Opposite lock order -> JVM detects a deadlock and both threads are BLOCKED")
    void oppositeLockOrderDeadlocks() throws InterruptedException {
        // Step 1 - create the deadlock:
        //   Thread-1: takes lockA -> sleeps 100 ms -> wants lockB (held by Thread-2) -> waits forever
        //   Thread-2: takes lockB -> sleeps 100 ms -> wants lockA (held by Thread-1) -> waits forever
        // Daemon threads: once deadlocked they can never be stopped (a thread BLOCKED on synchronized
        // ignores interrupt(), see README "Why a synchronized deadlock can't be interrupted"), so they
        // must not be able to stop the JVM from exiting, whichever tool runs the test.
        Thread[] threads = new DeadlockDemo().startDeadlock(true);

        // Step 2 - wait until the JVM itself reports the deadlock (the same check jstack does when
        // it prints "Found one Java-level deadlock"). It forms after ~100 ms, so we poll every 50 ms.
        long[] deadlocked = waitForDeadlock(threads);

        // Step 3 - prove each thread is really deadlocked, not just slow:
        for (Thread t : threads) {
            // it never finished...
            assertTrue(t.isAlive(), t.getName() + " should still be stuck");
            // ...because it is waiting to enter a synchronized block whose lock another thread holds...
            assertEquals(Thread.State.BLOCKED, t.getState(), t.getName() + " should be BLOCKED on a monitor");
            // ...and the JVM confirms it is part of the deadlock cycle.
            assertTrue(Arrays.stream(deadlocked).anyMatch(id -> id == t.getId()),
                    t.getName() + " should be reported by ThreadMXBean.findDeadlockedThreads()");
        }
    }

    /**
     * Tests the FIX (lock ordering): both threads acquire lockA first and then lockB.
     *
     * <p>Expected: both threads finish within 5 seconds and no deadlock occurs, because with a single
     * global lock order a circular wait cannot form.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Same lock order -> both threads finish, no deadlock")
    void consistentLockOrderFinishes() throws InterruptedException {
        Thread[] threads = new DeadlockDemo().startWithLockOrdering(true);
        for (Thread t : threads) {
            t.join(5_000);
            assertFalse(t.isAlive(), t.getName() + " should have finished");
        }
    }

    /**
     * Tests FIX #2 (tryLock with timeout): the threads keep the OPPOSITE lock order that deadlocks in
     * {@link #oppositeLockOrderDeadlocks()}, but wait for their second lock with
     * {@code tryLock(50 ms)} and, on timeout, release the first lock and retry after a random back-off.
     *
     * <p>Expected: both threads finish within 5 seconds and the JVM never reports a deadlock. The
     * number of back-offs is printed: each one is a deadlock that was avoided by giving up and retrying.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Opposite lock order + tryLock(timeout) -> threads back off and both finish")
    void tryLockAvoidsDeadlock() throws InterruptedException {
        DeadlockDemo demo = new DeadlockDemo();
        Thread[] threads = demo.startWithTryLock(true);
        for (Thread t : threads) {
            t.join(5_000);
            assertFalse(t.isAlive(), t.getName() + " should have finished");
        }
        assertEquals(0, DeadlockDemo.findDeadlockedThreadIds().length, "no thread should be deadlocked");
        System.out.println("[DeadlockDemoTest] tryLock back-offs needed: " + demo.tryLockRetries());
    }

    /** Polls the JVM until it reports a deadlock involving our threads (or the @Timeout fires). */
    private static long[] waitForDeadlock(Thread[] threads) throws InterruptedException {
        while (true) {
            long[] ids = DeadlockDemo.findDeadlockedThreadIds();
            boolean allOurs = Arrays.stream(threads)
                    .allMatch(t -> Arrays.stream(ids).anyMatch(id -> id == t.getId()));
            if (allOurs) {
                return ids;
            }
            Thread.sleep(50);
        }
    }
}
