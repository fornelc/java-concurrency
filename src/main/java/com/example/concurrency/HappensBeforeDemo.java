package com.example.concurrency;

/**
 * Happens-before in practice: publishing a plain field through a volatile flag, the
 * {@code Thread.start()} / {@code Thread.join()} rules, and the same publication without
 * {@code volatile}, which has no guarantees.
 */
public class HappensBeforeDemo {

    private int data;
    private volatile boolean ready;

    static class UnsafePublication {
        int data;
        boolean ready;
    }

    /** What the reader observed when the flag is not volatile. */
    public enum Outcome {
        SAW_42,
        SAW_STALE_DATA,
        NEVER_SAW_READY
    }

    /**
     * The writer sets {@code data} and then the volatile {@code ready}; the reader waits for
     * {@code ready} and reads {@code data}.
     *
     * @return the value the reader observed, always 42
     */
    public static int publishViaVolatile() throws InterruptedException {
        HappensBeforeDemo shared = new HappensBeforeDemo();
        int[] observed = new int[1];

        Thread reader = new Thread(() -> {
            while (!shared.ready) {
                Thread.onSpinWait();
            }
            observed[0] = shared.data;
        }, "reader");

        Thread writer = new Thread(() -> {
            shared.data = 42;
            shared.ready = true;
        }, "writer");

        reader.start();
        writer.start();
        reader.join();
        writer.join();
        return observed[0];
    }

    /**
     * Same as {@link #publishViaVolatile()} with a plain {@code ready} flag.
     *
     * @param timeoutMs how long to wait for the reader before reporting {@link Outcome#NEVER_SAW_READY}
     */
    public static Outcome publishWithoutVolatile(long timeoutMs) throws InterruptedException {
        UnsafePublication shared = new UnsafePublication();
        int[] observed = {-1};

        Thread reader = new Thread(() -> {
            while (!shared.ready) {
            }
            observed[0] = shared.data;
        }, "unsafe-reader");
        reader.setDaemon(true);

        Thread writer = new Thread(() -> {
            shared.data = 42;
            shared.ready = true;
        }, "unsafe-writer");

        reader.start();
        Thread.sleep(500); // give the JIT time to compile the reader's loop
        writer.start();
        writer.join();
        reader.join(timeoutMs);

        if (reader.isAlive()) {
            return Outcome.NEVER_SAW_READY;
        }
        return observed[0] == 42 ? Outcome.SAW_42 : Outcome.SAW_STALE_DATA;
    }

    /**
     * Shares values with a child thread using only {@code start()} and {@code join()}.
     *
     * @return {@code [value seen by the child, value written by the child]}
     */
    public static int[] startAndJoinRules() throws InterruptedException {
        int[] box = new int[2];
        box[0] = 7;

        Thread child = new Thread(() -> box[1] = box[0] * 6);
        child.start();
        child.join();
        return box;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Reader observed data = " + publishViaVolatile());
        int[] box = startAndJoinRules();
        System.out.println("Child saw " + box[0] + " and main sees child's write " + box[1]);
        System.out.println("Without volatile the reader: " + publishWithoutVolatile(2_000));
    }
}
