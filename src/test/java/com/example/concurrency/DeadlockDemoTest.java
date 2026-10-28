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

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Opposite lock order: the JVM detects a deadlock and both threads are BLOCKED")
    void oppositeLockOrderDeadlocks() throws InterruptedException {
        // Daemon threads: deadlocked threads can't be stopped and must not keep the JVM alive.
        Thread[] threads = new DeadlockDemo().startDeadlock(true);

        long[] deadlocked = waitForDeadlock(threads);

        for (Thread t : threads) {
            assertTrue(t.isAlive(), t.getName() + " should still be stuck");
            assertEquals(Thread.State.BLOCKED, t.getState(), t.getName() + " should be BLOCKED on a monitor");
            assertTrue(Arrays.stream(deadlocked).anyMatch(id -> id == t.getId()),
                    t.getName() + " should be reported by ThreadMXBean.findDeadlockedThreads()");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Same lock order: both threads finish")
    void consistentLockOrderFinishes() throws InterruptedException {
        Thread[] threads = new DeadlockDemo().startWithLockOrdering(true);
        for (Thread t : threads) {
            t.join(5_000);
            assertFalse(t.isAlive(), t.getName() + " should have finished");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Opposite lock order with tryLock(timeout): threads back off and both finish")
    void tryLockAvoidsDeadlock() throws InterruptedException {
        DeadlockDemo demo = new DeadlockDemo();
        Thread[] threads = demo.startWithTryLock(true);
        for (Thread t : threads) {
            t.join(5_000);
            assertFalse(t.isAlive(), t.getName() + " should have finished");
        }
        assertEquals(0, DeadlockDemo.findDeadlockedThreadIds().length, "no thread should be deadlocked");
    }

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
