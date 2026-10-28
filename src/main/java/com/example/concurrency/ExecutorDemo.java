package com.example.concurrency;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pool lifecycle: graceful shutdown, two-phase forced shutdown, and cooperative cancellation
 * of CPU-bound tasks.
 */
public class ExecutorDemo {

    static final long BUSY_TASK_MILLIS = 2_000;
    static final long SHUTDOWN_NOW_WAIT_MILLIS = 500;

    public record Result(int completed, int interrupted, int neverStarted, Set<String> threadNames,
                         boolean terminated) {
    }

    /**
     * @param loopMillis   time the caller spent in the submit loop
     * @param tasksStarted tasks a pool thread had started shortly after the loop
     */
    public record SubmitTimeline(long loopMillis, int tasksStarted) {
    }

    /**
     * @param terminatedAfterShutdownNow whether the pool terminated within {@link #SHUTDOWN_NOW_WAIT_MILLIS}
     * @param interruptRequested         whether the task's interrupt flag was set
     * @param stoppedEarly               whether the task exited before {@link #BUSY_TASK_MILLIS}
     */
    public record ShutdownNowResult(boolean terminatedAfterShutdownNow, boolean interruptRequested,
                                    boolean stoppedEarly) {
    }

    /** Runs {@code taskCount} short tasks, then {@code shutdown()} and waits for all of them. */
    public static Result gracefulShutdown(int poolSize, int taskCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completed = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                sleepQuietly(10);
                completed.incrementAndGet();
            });
        }

        pool.shutdown();
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
        return new Result(completed.get(), 0, 0, threadNames, terminated);
    }

    /**
     * Runs long tasks and stops the pool with the two-phase pattern from the {@link ExecutorService}
     * Javadoc: {@code shutdown()} first, then {@code shutdownNow()} if the pool doesn't terminate in time.
     */
    public static Result forcedShutdown(int poolSize, int taskCount) {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(10_000);
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean terminated = false;
        int neverStarted = 0;
        try {
            Thread.sleep(200); // let the first tasks start
            pool.shutdown();
            terminated = pool.awaitTermination(100, TimeUnit.MILLISECONDS);
            if (!terminated) {
                List<Runnable> notStarted = pool.shutdownNow();
                neverStarted = notStarted.size();
                terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
                if (!terminated) {
                    System.err.println("Pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            neverStarted = pool.shutdownNow().size();
            Thread.currentThread().interrupt();
        }
        return new Result(completed.get(), interrupted.get(), neverStarted, threadNames, terminated);
    }

    /** Submits six 10-second tasks to a 2-thread pool and logs when each submit returns. */
    public static SubmitTimeline showSubmitDoesNotBlock() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger started = new AtomicInteger();
        long start = System.nanoTime();

        for (int i = 1; i <= 6; i++) {
            int taskNumber = i;
            pool.submit(() -> {
                started.incrementAndGet();
                System.out.printf("  [%s] task %d starts sleeping 10 s at %4d ms%n",
                        Thread.currentThread().getName(), taskNumber, millisSince(start));
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            System.out.printf("[main] submit #%d returned at %4d ms%n", taskNumber, millisSince(start));
        }
        long loopMillis = millisSince(start);
        System.out.printf("[main] loop finished at %4d ms%n", loopMillis);

        Thread.sleep(200);
        int tasksStarted = started.get();

        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        return new SubmitTimeline(loopMillis, tasksStarted);
    }

    /** A CPU-bound task that never checks its interrupt flag, so {@code shutdownNow()} can't stop it. */
    public static ShutdownNowResult shutdownNowWithTaskIgnoringInterrupts() throws InterruptedException {
        return runBusyTaskAndShutdownNow(false);
    }

    /** The same task, checking {@code isInterrupted()} on every iteration. */
    public static ShutdownNowResult shutdownNowWithInterruptAwareTask() throws InterruptedException {
        return runBusyTaskAndShutdownNow(true);
    }

    private static ShutdownNowResult runBusyTaskAndShutdownNow(boolean checksInterrupt) throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interruptRequested = new AtomicBoolean();
        AtomicBoolean stoppedEarly = new AtomicBoolean();

        pool.submit(() -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_TASK_MILLIS);
            while (System.nanoTime() < deadline) {
                if (checksInterrupt && Thread.currentThread().isInterrupted()) {
                    stoppedEarly.set(true);
                    break;
                }
            }
            interruptRequested.set(Thread.currentThread().isInterrupted());
        });

        started.await();
        pool.shutdownNow();
        boolean terminated = pool.awaitTermination(SHUTDOWN_NOW_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        if (!terminated) {
            // Let the task finish so it doesn't leak into the next run.
            pool.awaitTermination(BUSY_TASK_MILLIS + 1_000, TimeUnit.MILLISECONDS);
        }
        return new ShutdownNowResult(terminated, interruptRequested.get(), stoppedEarly.get());
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Result graceful = gracefulShutdown(4, 20);
        System.out.println("Graceful: " + graceful.completed() + " tasks completed using only "
                + graceful.threadNames().size() + " threads " + graceful.threadNames()
                + ", terminated=" + graceful.terminated());

        Result forced = forcedShutdown(2, 6);
        System.out.println("Forced:   completed=" + forced.completed() + ", interrupted=" + forced.interrupted()
                + ", neverStarted=" + forced.neverStarted() + ", terminated=" + forced.terminated());

        System.out.println("shutdownNow(), task ignores interrupts: " + shutdownNowWithTaskIgnoringInterrupts());
        System.out.println("shutdownNow(), task checks interrupts:  " + shutdownNowWithInterruptAwareTask());

        System.out.println("submit() does not wait for the task:");
        SubmitTimeline timeline = showSubmitDoesNotBlock();
        System.out.println("-> loop took " + timeline.loopMillis() + " ms, tasks started: " + timeline.tasksStarted());
    }
}
