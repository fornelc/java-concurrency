package com.example.concurrency;

/**
 * A worker spinning on a flag that another thread clears. With a plain field the worker may never
 * see the change; with {@code volatile} it always does.
 */
public class VisibilityDemo {

    static class PlainFlag {
        boolean running = true;
    }

    static class VolatileFlag {
        volatile boolean running = true;
    }

    /** @return whether a worker spinning on a plain flag stopped within {@code timeoutMs} */
    public static boolean plainWorkerStops(long timeoutMs) throws InterruptedException {
        PlainFlag flag = new PlainFlag();
        Thread worker = new Thread(() -> {
            // Intentionally empty: any synchronization in the loop body would hide the problem.
            while (flag.running) {
            }
        }, "plain-worker");
        return stopsAfterClearing(worker, () -> flag.running = false, timeoutMs);
    }

    /** @return whether a worker spinning on a volatile flag stopped within {@code timeoutMs} */
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
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(500); // give the JIT time to compile the loop
        clearFlag.run();
        worker.join(timeoutMs);
        return !worker.isAlive();
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("volatile flag -> worker stopped: " + volatileWorkerStops(2_000));
        System.out.println("plain flag    -> worker stopped: " + plainWorkerStops(2_000));
    }
}
