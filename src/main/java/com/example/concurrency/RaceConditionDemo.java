package com.example.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lost updates on a shared counter, fixed with {@code synchronized} and with {@link AtomicInteger}.
 */
public class RaceConditionDemo {

    public static final int INCREMENTS_PER_THREAD = 100_000;

    public interface Counter {
        void increment();

        int get();
    }

    /** Not thread-safe: {@code count++} is a non-atomic read-modify-write. */
    public static class UnsafeCounter implements Counter {
        private int count;

        @Override
        public void increment() {
            count++;
        }

        @Override
        public int get() {
            return count;
        }
    }

    public static class SynchronizedCounter implements Counter {
        private int count;

        @Override
        public synchronized void increment() {
            count++;
        }

        @Override
        public synchronized int get() {
            return count;
        }
    }

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
     * Runs two threads that each increment {@code counter} {@code incrementsPerThread} times and
     * returns the final value.
     */
    public static int runTwoThreads(Counter counter, int incrementsPerThread) throws InterruptedException {
        // Release both threads at once so their work actually overlaps.
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
        t1.join();
        t2.join();
        return counter.get();
    }

    /**
     * Performs the increment as explicit read and write steps, logging each one, so that lost updates
     * show up in the output.
     *
     * @return the final value, at most {@code 2 * incrementsPerThread}
     */
    public static int traceInterleaving(int incrementsPerThread) throws InterruptedException {
        int[] shared = {0};

        Runnable task = () -> {
            String name = Thread.currentThread().getName();
            for (int i = 0; i < incrementsPerThread; i++) {
                int read = shared[0];
                System.out.printf("%s READ  %d%n", name, read);
                Thread.yield();
                shared[0] = read + 1;
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

        System.out.println("Expected value: " + 2 * INCREMENTS_PER_THREAD);
        for (int run = 1; run <= 5; run++) {
            System.out.printf("Run %d -> unsafe: %,7d | synchronized: %,7d | atomic: %,7d%n",
                    run,
                    runTwoThreads(new UnsafeCounter(), INCREMENTS_PER_THREAD),
                    runTwoThreads(new SynchronizedCounter(), INCREMENTS_PER_THREAD),
                    runTwoThreads(new AtomicCounter(), INCREMENTS_PER_THREAD));
        }
    }
}
