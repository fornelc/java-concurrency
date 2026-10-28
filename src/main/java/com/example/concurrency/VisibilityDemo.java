package com.example.concurrency;

/**
 * KEY CONCEPT: memory visibility and {@code volatile}.
 *
 * <p>A worker thread spins on a {@code running} flag; the main thread sets it to {@code false} and
 * expects the worker to stop.
 * <ul>
 *   <li>With a plain field the JMM gives no guarantee the worker ever sees the write. In practice the
 *       JIT compiler hoists the read out of the loop ({@code if (running) while (true) {}}) or the
 *       value stays in a register/cache, and the worker spins forever.</li>
 *   <li>With a {@code volatile} field every write is visible to every subsequent read, so the
 *       worker stops promptly.</li>
 * </ul>
 *
 * <p>Note: the broken version is <b>not guaranteed</b> to hang — "no guarantee" also means it may
 * happen to work. On a typical HotSpot JVM it hangs once the loop has been JIT-compiled.
 *
 * <p>Run: {@code java -cp target/classes com.example.concurrency.VisibilityDemo}
 */
public class VisibilityDemo {

    /** BROKEN: plain field, writes from other threads may never become visible. */
    static class PlainFlag {
        boolean running = true;
    }

    /** FIX: volatile field, a write happens-before every subsequent read of the same field. */
    static class VolatileFlag {
        volatile boolean running = true;
    }

    /**
     * Starts a worker spinning on a plain (non-volatile) flag, clears the flag and reports whether
     * the worker noticed within {@code timeoutMs}.
     */
    public static boolean plainWorkerStops(long timeoutMs) throws InterruptedException {
        PlainFlag flag = new PlainFlag();
        Thread worker = new Thread(() -> {
            // Empty body on purpose: a println or sleep here would add synchronization
            // and accidentally "fix" the bug.
            while (flag.running) {
            }
        }, "plain-worker");
        return stopsAfterClearing(worker, () -> flag.running = false, timeoutMs);
    }

    /** Same experiment with a volatile flag: the worker always stops. */
    public static boolean volatileWorkerStops(long timeoutMs) throws InterruptedException {
        VolatileFlag flag = new VolatileFlag();
        Thread worker = new Thread(() -> {
            while (flag.running) {
            }
        }, "volatile-worker");
        return stopsAfterClearing(worker, () -> flag.running = false, timeoutMs);
    }

    private static boolean stopsAfterClearing(Thread worker, Runnable clearFlag, long timeoutMs)
            throws InterruptedException {
        worker.setDaemon(true); // a stuck worker must not keep the JVM alive
        worker.start();
        Thread.sleep(500);      // let the loop run long enough to be JIT-compiled
        clearFlag.run();
        worker.join(timeoutMs);
        return !worker.isAlive();
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("volatile flag -> worker stopped: " + volatileWorkerStops(2_000));
        System.out.println("plain flag    -> worker stopped: " + plainWorkerStops(2_000)
                + "   (false = the worker never saw the write)");
    }
}
