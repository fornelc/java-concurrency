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
 * KEY CONCEPT: deadlock.
 *
 * <p>Thread 1 takes {@code lockA} then wants {@code lockB}; thread 2 takes {@code lockB} then wants
 * {@code lockA}. Each holds the lock the other needs, so both wait forever. A deadlock needs all
 * four Coffman conditions: mutual exclusion, hold-and-wait, no preemption, circular wait.
 *
 * <p>Two fixes, each breaking a different condition:
 * <ul>
 *   <li>{@link #startWithLockOrdering(boolean)} breaks the <b>circular wait</b>: every thread
 *       acquires the locks in the same global order (A before B), so a cycle is impossible.</li>
 *   <li>{@link #startWithTryLock(boolean)} breaks <b>no preemption</b>: threads keep the opposite
 *       order, but use {@link ReentrantLock#tryLock(long, TimeUnit)}. A thread that cannot get its
 *       second lock in time gives up, releases the first one and retries later.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp target/classes com.example.concurrency.DeadlockDemo}
 * — the program hangs. In another terminal run {@code jps} to find the PID and then
 * {@code jstack <pid>}: both threads show as {@code BLOCKED} and the dump ends with
 * "Found one Java-level deadlock". Pass the argument {@code fixed} to run the lock-ordering version,
 * or {@code trylock} to run the tryLock version.
 */
public class DeadlockDemo {

    /** Time each thread sleeps while holding its first lock, so the bad interleaving is reliable. */
    static final long PAUSE_MS = 100;

    /** How long {@code tryLock} waits for the second lock before giving up. */
    static final long TRY_LOCK_TIMEOUT_MS = 50;

    private final Object lockA = new Object();
    private final Object lockB = new Object();

    // tryLock() exists only on explicit locks, so the tryLock fix uses ReentrantLocks instead of monitors.
    private final ReentrantLock reentrantLockA = new ReentrantLock();
    private final ReentrantLock reentrantLockB = new ReentrantLock();

    /** How many times a thread had to back off in the tryLock version (each one = an avoided deadlock). */
    private final AtomicInteger tryLockRetries = new AtomicInteger();

    /**
     * Starts two threads that acquire the locks in OPPOSITE order -> deadlock.
     *
     * @param daemon daemon threads do not keep the JVM alive; tests use {@code true} so the test
     *               JVM can exit even though these threads can never finish.
     */
    public Thread[] startDeadlock(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBoth(lockB, "lockB", lockA, "lockA"), "Thread-2 (B then A)");
        return start(daemon, t1, t2);
    }

    /** FIX: both threads acquire the locks in the SAME order (A then B) -> no circular wait. */
    public Thread[] startWithLockOrdering(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBoth(lockA, "lockA", lockB, "lockB"), "Thread-2 (A then B)");
        return start(daemon, t1, t2);
    }

    /**
     * FIX #2: the threads still acquire the locks in OPPOSITE order (the same situation that
     * deadlocks in {@link #startDeadlock(boolean)}), but they wait for the second lock with a timeout
     * instead of forever. On timeout they release what they hold and retry, so both eventually finish.
     */
    public Thread[] startWithTryLock(boolean daemon) {
        Thread t1 = new Thread(() -> acquireBothWithTryLock(reentrantLockA, "lockA", reentrantLockB, "lockB"),
                "Thread-1 (A then B)");
        Thread t2 = new Thread(() -> acquireBothWithTryLock(reentrantLockB, "lockB", reentrantLockA, "lockA"),
                "Thread-2 (B then A)");
        return start(daemon, t1, t2);
    }

    /** Number of back-offs so far in the tryLock version. */
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
                    sleep(PAUSE_MS); // same pause as the deadlock version: provoke the conflict once
                }
                System.out.printf("%s: trying %s for %d ms...%n", me, secondName, TRY_LOCK_TIMEOUT_MS);
                if (second.tryLock(TRY_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        System.out.printf("%s: holding %s and %s -> work done%n", me, firstName, secondName);
                        return;
                    } finally {
                        second.unlock(); // explicit locks must ALWAYS be released in a finally block
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
            // RANDOM back-off: if both threads always waited the same time they could retry in lockstep
            // and keep colliding forever (a LIVELOCK: threads are running, but nobody makes progress).
            sleep(ThreadLocalRandom.current().nextLong(10, 100));
        }
    }

    private static void acquireBoth(Object first, String firstName, Object second, String secondName) {
        String me = Thread.currentThread().getName();
        synchronized (first) {
            System.out.printf("%s: holding %s%n", me, firstName);
            sleep(PAUSE_MS); // give the other thread time to grab its first lock
            System.out.printf("%s: waiting for %s...%n", me, secondName);
            synchronized (second) { // in the deadlock version this line never completes
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

    /**
     * Programmatic equivalent of reading a {@code jstack} dump: asks the JVM which threads are
     * deadlocked on monitors / ownable synchronizers.
     *
     * @return ids of deadlocked threads, or an empty array when there is no deadlock
     */
    public static long[] findDeadlockedThreadIds() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        return ids == null ? new long[0] : ids;
    }

    /** Prints a mini thread dump of the deadlocked threads: state, lock wanted and its owner. */
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
        boolean fixed = !mode.equals("deadlock");

        // Non-daemon threads: in the deadlock case the JVM keeps running (hangs) so you can jstack it.
        Thread[] threads = switch (mode) {
            case "fixed" -> demo.startWithLockOrdering(false);
            case "trylock" -> demo.startWithTryLock(false);
            default -> demo.startDeadlock(false);
        };

        Thread.sleep(3 * PAUSE_MS);
        printDeadlockReport();

        if (!fixed) {
            System.out.printf("The program now hangs forever. PID = %d -> try: jstack %d%n",
                    ProcessHandle.current().pid(), ProcessHandle.current().pid());
            System.out.println("Press Ctrl+C to stop it.");
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Both threads finished."
                + (mode.equals("trylock") ? " Back-offs needed: " + demo.tryLockRetries() : ""));
    }
}
