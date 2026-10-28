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
 * KEY CONCEPT: executors / thread pools and graceful shutdown
 * (Java Concurrency in Practice, Chapter 7 "Cancellation and Shutdown").
 *
 * <p>Creating a thread per task is expensive and unbounded. An {@link ExecutorService} keeps a
 * fixed set of worker threads and feeds them tasks from a queue. Stopping it properly matters:
 * <ul>
 *   <li>{@code shutdown()} — stop accepting new tasks, but finish everything already submitted.</li>
 *   <li>{@code awaitTermination(timeout)} — block until all tasks finished or the timeout expired.</li>
 *   <li>{@code shutdownNow()} — interrupt running tasks and return the ones that never started.
 *       Tasks only stop if they respond to interruption!</li>
 * </ul>
 *
 * <p>{@link #shutdownNowWithTaskIgnoringInterrupts()} shows that last point going wrong: a CPU-bound
 * task that never checks its interrupt flag keeps running after {@code shutdownNow()}, so the pool
 * does not terminate. {@link #shutdownNowWithInterruptAwareTask()} is the fix: the same loop checks
 * {@code Thread.currentThread().isInterrupted()} and exits.
 *
 * <p>Run: {@code java -cp target/classes com.example.concurrency.ExecutorDemo}
 */
public class ExecutorDemo {

    /** Result of a pool run, so tests can check what actually happened. */
    public record Result(int completed, int interrupted, int neverStarted, Set<String> threadNames,
                         boolean terminated) {
    }

    /**
     * Submits {@code taskCount} short tasks to a pool of {@code poolSize} threads and shuts the pool
     * down gracefully: every submitted task is completed.
     */
    public static Result gracefulShutdown(int poolSize, int taskCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completed = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                threadNames.add(Thread.currentThread().getName()); // the same few threads are reused
                sleepQuietly(10);
                completed.incrementAndGet();
            });
        }

        pool.shutdown(); // no new tasks accepted; queued tasks still run
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
        return new Result(completed.get(), 0, 0, threadNames, terminated);
    }

    /**
     * Submits long tasks, waits briefly, then gives up: {@code shutdown()} + a short
     * {@code awaitTermination} fails, so we escalate to {@code shutdownNow()} — the standard
     * two-phase shutdown pattern.
     */
    public static Result forcedShutdown(int poolSize, int taskCount) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(10_000); // a "long" task; sleep() responds to interruption
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt(); // restore the flag: never swallow interrupts
                }
            });
        }

        Thread.sleep(200); // let the first poolSize tasks start running

        // Phase 1: polite request
        pool.shutdown();
        boolean terminated = pool.awaitTermination(100, TimeUnit.MILLISECONDS);
        int neverStarted = 0;
        if (!terminated) {
            // Phase 2: interrupt running tasks and drain the queue
            List<Runnable> notStarted = pool.shutdownNow();
            neverStarted = notStarted.size();
            terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        return new Result(completed.get(), interrupted.get(), neverStarted, threadNames, terminated);
    }

    /** How long the CPU-bound task runs if nobody stops it. */
    static final long BUSY_TASK_MILLIS = 2_000;

    /** How long we wait for the pool to terminate after {@code shutdownNow()}. */
    static final long SHUTDOWN_NOW_WAIT_MILLIS = 500;

    /**
     * Result of the {@code shutdownNow()} experiments.
     *
     * @param terminatedAfterShutdownNow whether the pool terminated within {@link #SHUTDOWN_NOW_WAIT_MILLIS}
     * @param interruptRequested         whether the task's interrupt flag was set, i.e. shutdownNow() did ask it to stop
     * @param stoppedEarly               whether the task reacted and exited before {@link #BUSY_TASK_MILLIS}
     */
    public record ShutdownNowResult(boolean terminatedAfterShutdownNow, boolean interruptRequested,
                                    boolean stoppedEarly) {
    }

    /**
     * BROKEN: a CPU-bound task that never checks for interruption. {@code shutdownNow()} sets its
     * interrupt flag, but nothing in the loop looks at it (and there is no blocking call like
     * {@code sleep()} that would throw {@link InterruptedException}), so the task keeps running and
     * the pool does not terminate in time.
     */
    public static ShutdownNowResult shutdownNowWithTaskIgnoringInterrupts() throws InterruptedException {
        return runBusyTaskAndShutdownNow(false);
    }

    /** FIX: the same loop checks {@code Thread.currentThread().isInterrupted()} and exits when asked. */
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
            while (System.nanoTime() < deadline) {      // busy CPU work, no blocking calls
                if (checksInterrupt && Thread.currentThread().isInterrupted()) {
                    stoppedEarly.set(true);             // cooperative cancellation: we were asked, we stop
                    break;
                }
            }
            // Either way, the flag tells us whether shutdownNow() asked this task to stop.
            interruptRequested.set(Thread.currentThread().isInterrupted());
        });

        started.await(); // make sure the task is running before we try to stop it
        pool.shutdownNow();
        boolean terminated = pool.awaitTermination(SHUTDOWN_NOW_WAIT_MILLIS, TimeUnit.MILLISECONDS);

        if (!terminated) {
            // Nothing else can stop the task, so wait for it to finish by itself. A real application
            // would log this and move on; here we wait so no thread is leaked into the next demo/test.
            pool.awaitTermination(BUSY_TASK_MILLIS + 1_000, TimeUnit.MILLISECONDS);
        }
        return new ShutdownNowResult(terminated, interruptRequested.get(), stoppedEarly.get());
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
    }
}
