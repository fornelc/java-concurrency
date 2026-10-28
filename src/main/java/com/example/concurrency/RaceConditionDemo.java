package com.example.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KEY CONCEPTS: race condition, mutex / synchronized, atomic variables.
 *
 * <p>Two threads increment a shared counter 100,000 times each. The expected result is 200,000.
 * <ul>
 *   <li>{@link UnsafeCounter} uses a plain {@code int}: {@code count++} is NOT one operation, it is
 *       three (read, add 1, write). When two threads interleave those steps, one update overwrites
 *       the other and increments are "lost".</li>
 *   <li>{@link SynchronizedCounter} wraps the increment in a {@code synchronized} block (a mutex):
 *       only one thread at a time can execute read-modify-write, so nothing is lost.</li>
 *   <li>{@link AtomicCounter} uses {@link AtomicInteger}, which performs the read-modify-write as a
 *       single atomic CPU instruction (compare-and-swap) without taking a lock.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp target/classes com.example.concurrency.RaceConditionDemo}
 * (add the argument {@code trace} to see a step-by-step interleaving).
 */
public class RaceConditionDemo {

    public static final int INCREMENTS_PER_THREAD = 100_000;

    /** Common interface so the same two-thread harness can run every implementation. */
    public interface Counter {
        void increment();

        int get();
    }

    /** BROKEN: no synchronization, so concurrent increments get lost. */
    public static class UnsafeCounter implements Counter {
        private int count;

        @Override
        public void increment() {
            count++; // read count -> add 1 -> write count : another thread can run between these steps
        }

        @Override
        public int get() {
            return count;
        }
    }

    /** FIX #1: a mutex. The intrinsic lock of {@code this} makes the increment mutually exclusive. */
    public static class SynchronizedCounter implements Counter {
        private int count;

        @Override
        public synchronized void increment() {
            count++; // only one thread at a time can be inside this method for the same object
        }

        @Override
        public synchronized int get() {
            // Reading under the same lock also guarantees we see the latest value (visibility).
            return count;
        }
    }

    /** FIX #2: lock-free atomic read-modify-write using compare-and-swap. */
    public static class AtomicCounter implements Counter {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void increment() {
            count.incrementAndGet();
        }

        @Override
        public int get() {
            return count.get();
        }
    }

    /**
     * Starts two threads that each call {@code counter.increment()} {@code incrementsPerThread}
     * times, waits for both to finish and returns the final value.
     */
    public static int runTwoThreads(Counter counter, int incrementsPerThread) throws InterruptedException {
        // A latch releases both threads at the same moment so their work really overlaps;
        // otherwise thread 1 might finish before thread 2 has even started.
        CountDownLatch startSignal = new CountDownLatch(1);

        Runnable task = () -> {
            try {
                startSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < incrementsPerThread; i++) {
                counter.increment();
            }
        };

        Thread t1 = new Thread(task, "incrementer-1");
        Thread t2 = new Thread(task, "incrementer-2");
        t1.start();
        t2.start();
        startSignal.countDown();

        // join() waits for the thread to die. It also creates a happens-before edge:
        // everything the thread wrote is visible to us after join() returns.
        t1.join();
        t2.join();
        return counter.get();
    }

    /**
     * Makes the lost update visible by splitting {@code count++} into explicit steps and printing
     * each one. {@link Thread#yield()} between read and write invites the scheduler to switch
     * threads at the worst possible moment.
     *
     * <p>In the output look for two lines where both threads READ the same value: one of the two
     * WRITEs is then lost.
     *
     * @return the final counter value (expected {@code 2 * incrementsPerThread}, usually lower)
     */
    public static int traceInterleaving(int incrementsPerThread) throws InterruptedException {
        int[] shared = {0}; // array so the lambda can mutate it

        Runnable task = () -> {
            String name = Thread.currentThread().getName();
            for (int i = 0; i < incrementsPerThread; i++) {
                int read = shared[0];                       // 1. READ
                System.out.printf("%s READ  %d%n", name, read);
                Thread.yield();                             //    (let the other thread run here)
                shared[0] = read + 1;                       // 2. MODIFY + 3. WRITE
                System.out.printf("%s WRITE %d%n", name, read + 1);
            }
        };

        Thread t1 = new Thread(task, "T1");
        Thread t2 = new Thread(task, "T2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        return shared[0];
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0 && args[0].equals("trace")) {
            int result = traceInterleaving(5);
            System.out.println("Final value with trace: " + result + " (expected 10)");
            return;
        }

        int expected = 2 * INCREMENTS_PER_THREAD;
        System.out.println("Expected value: " + expected);
        for (int run = 1; run <= 5; run++) {
            System.out.printf("Run %d -> unsafe: %,7d | synchronized: %,7d | atomic: %,7d%n",
                    run,
                    runTwoThreads(new UnsafeCounter(), INCREMENTS_PER_THREAD),
                    runTwoThreads(new SynchronizedCounter(), INCREMENTS_PER_THREAD),
                    runTwoThreads(new AtomicCounter(), INCREMENTS_PER_THREAD));
        }
    }
}
