package com.example.concurrency;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two threads acquiring two locks in opposite order, and two ways to avoid the resulting deadlock:
 * a global lock order, and {@code tryLock} with a timeout and random back-off.
 *
 * <p>Running {@code main} without arguments hangs on purpose so the deadlock can be inspected with
 * {@code jstack}. Use {@code fixed} or {@code trylock} to run the fixed versions.
 */
public class DeadlockDemo {

    /** How long each thread holds its first lock, so that the conflicting interleaving is reliable. */
    static final long PAUSE_MS = 100;

    static final long TRY_LOCK_TIMEOUT_MS = 50;

    private final Object lockA = new Object();
    private final Object lockB = new Object();

    private final ReentrantLock reentrantLockA = new ReentrantLock();
    private final ReentrantLock reentrantLockB = new ReentrantLock();

    private final AtomicInteger tryLockRetries = new AtomicInteger();

    /**
     * Starts two threads that take the locks in opposite order and deadlock.
     *
     * @param daemon whether the threads should be daemon threads, so they can't keep the JVM alive
     */
    public Thread[] startDeadlock(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBoth(lockB, "lockB", lockA, "lockA"), "Thread-2 (B then A)");
        return start(daemon, t1, t2);
    }

    /** Both threads take the locks in the same order, so no circular wait can form. */
    public Thread[] startWithLockOrdering(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-2 (A then B)");
        return start(daemon, t1, t2);
    }

    /**
     * Keeps the opposite lock order, but each thread gives up its first lock if it can't get the
     * second one in time, then retries.
     */
    public Thread[] startWithTryLock(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBothWithTryLock(reentrantLockA, "lockA", reentrantLockB, "lockB"),
                "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBothWithTryLock(reentrantLockB, "lockB", reentrantLockA, "lockA"),
                "Thread-2 (B then A)");
        return start(daemon, t1, t2);
    }

    public int tryLockRetries() {
        return tryLockRetries.get();
    }

    private void acquireBothWithTryLock(Lock first, String firstName, Lock second, String secondName) {
        String me = Thread.currentThread().getName();
        boolean firstAttempt = true;
        while (true) {
            first.lock();
            try {
                System.out.printf("%s: holding %s%n", me, firstName);
                if (firstAttempt) {
                    sleep(PAUSE_MS);
                }
                System.out.printf("%s: trying %s for %d ms...%n", me, secondName, TRY_LOCK_TIMEOUT_MS);
                if (second.tryLock(TRY_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        System.out.printf("%s: holding %s and %s -> work done%n", me, firstName, secondName);
                        return;
                    } finally {
                        second.unlock();
                    }
                }
                tryLockRetries.incrementAndGet();
                System.out.printf("%s: timed out on %s -> releasing %s and retrying%n", me, secondName, firstName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                first.unlock();
            }
            firstAttempt = false;
            // Random back-off, so the threads don't retry in lockstep (livelock).
            sleep(ThreadLocalRandom.current().nextLong(10, 100));
        }
    }

    private static void acquireBoth(Object first, String firstName, Object second, String secondName) {
        String me = Thread.currentThread().getName();
        synchronized (first) {
            System.out.printf("%s: holding %s%n", me, firstName);
            sleep(PAUSE_MS);
            System.out.printf("%s: waiting for %s...%n", me, secondName);
            synchronized (second) {
                System.out.printf("%s: holding %s and %s -> work done%n", me, firstName, secondName);
            }
        }
    }

    private static Thread[] start(boolean daemon, Thread... threads) {
        for (Thread t : threads) {
            t.setDaemon(daemon);
            t.start();
        }
        return threads;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return ids of the threads the JVM reports as deadlocked, or an empty array if there are none */
    public static long[] findDeadlockedThreadIds() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        return ids == null ? new long[0] : ids;
    }

    public static void printDeadlockReport() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = findDeadlockedThreadIds();
        if (ids.length == 0) {
            System.out.println("No deadlock detected.");
            return;
        }
        System.out.println("=== DEADLOCK DETECTED ===");
        for (ThreadInfo info : mx.getThreadInfo(ids)) {
            System.out.printf("'%s' is %s waiting for %s held by '%s'%n",
                    info.getThreadName(), info.getThreadState(), info.getLockName(), info.getLockOwnerName());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        DeadlockDemo demo = new DeadlockDemo();
        String mode = args.length > 0 ? args[0] : "deadlock";

        Thread[] threads = switch (mode) {
            case "fixed" -> demo.startWithLockOrdering(false);
            case "trylock" -> demo.startWithTryLock(false);
            default -> demo.startDeadlock(false);
        };

        Thread.sleep(3 * PAUSE_MS);
        printDeadlockReport();

        if (mode.equals("deadlock")) {
            long pid = ProcessHandle.current().pid();
            System.out.printf("The program now hangs forever. PID = %d -> try: jstack %d%n", pid, pid);
            System.out.println("Press Ctrl+C to stop it.");
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Both threads finished."
                + (mode.equals("trylock") ? " Back-offs needed: " + demo.tryLockRetries() : ""));
    }
}
