package com.example.concurrency;

/**
 * KEY CONCEPT: happens-before (Java Memory Model).
 *
 * <p>If action X <i>happens-before</i> action Y, then everything written before X is guaranteed to
 * be visible to the thread performing Y. The most useful rules:
 * <ol>
 *   <li><b>Program order</b>: each action in a thread happens-before later actions in that thread.</li>
 *   <li><b>Monitor lock</b>: unlocking a monitor happens-before every later lock of that monitor.</li>
 *   <li><b>Volatile</b>: a write to a volatile field happens-before every later read of it.</li>
 *   <li><b>Thread start</b>: {@code t.start()} happens-before any action inside {@code t}.</li>
 *   <li><b>Thread join</b>: every action in {@code t} happens-before {@code t.join()} returns.</li>
 *   <li><b>Transitivity</b>: if A hb B and B hb C, then A hb C.</li>
 * </ol>
 *
 * <p>This class shows "safe publication by piggybacking": {@code data} is a PLAIN field, yet the
 * reader is guaranteed to see 42 because of program order + volatile rule + transitivity.
 *
 * <p>{@link #publishWithoutVolatile(long)} is the BROKEN version: the same code with a plain {@code ready}
 * flag. There is no happens-before edge between writer and reader, so the JMM allows the reader to
 * never see {@code ready == true}, or to see it and still read a stale {@code data == 0}.
 *
 * <p>Run: {@code java -cp target/classes com.example.concurrency.HappensBeforeDemo}
 */
public class HappensBeforeDemo {

    private int data;                // plain field — protected only by the happens-before chain
    private volatile boolean ready;  // volatile "guard" field

    /** BROKEN: the same two fields, but the guard flag is NOT volatile, so there is no happens-before edge. */
    static class UnsafePublication {
        int data;
        boolean ready;
    }

    /** What the reader observed in the broken (no volatile) version. */
    public enum Outcome {
        /** Saw {@code ready == true} and {@code data == 42}: worked by luck, nothing guaranteed it. */
        SAW_42,
        /** Saw {@code ready == true} but {@code data == 0}: the writes became visible out of order. */
        SAW_STALE_DATA,
        /** Never saw {@code ready == true} before the timeout: the write was never visible (as in VisibilityDemo). */
        NEVER_SAW_READY
    }

    /**
     * Writer publishes {@code data} through the volatile {@code ready} flag; the reader waits for
     * the flag and then reads {@code data}.
     *
     * @return the value the reader observed (always 42)
     */
    public static int publishViaVolatile() throws InterruptedException {
        HappensBeforeDemo shared = new HappensBeforeDemo();
        int[] observed = new int[1];

        Thread reader = new Thread(() -> {
            while (!shared.ready) {  // (3) volatile READ sees true ...
                Thread.onSpinWait();
            }
            observed[0] = shared.data; // (4) ... so (1) is visible here: data == 42
        }, "reader");

        Thread writer = new Thread(() -> {
            shared.data = 42;     // (1) plain write
            shared.ready = true;  // (2) volatile write: (1) hb (2) by program order
        }, "writer");

        reader.start();
        writer.start();
        reader.join(); // join rule: everything the reader wrote (observed[0]) is visible to us now
        writer.join();
        return observed[0];
    }

    /**
     * BROKEN: the same writer/reader pair as {@link #publishViaVolatile()} with a plain {@code ready}
     * flag. The writer is delayed so the reader's loop gets JIT-compiled first, which is when the
     * missing happens-before edge usually shows up.
     *
     * @param timeoutMs how long to wait for the reader before reporting {@link Outcome#NEVER_SAW_READY}
     */
    public static Outcome publishWithoutVolatile(long timeoutMs) throws InterruptedException {
        UnsafePublication shared = new UnsafePublication();
        int[] observed = {-1}; // -1 = the reader never got past the loop

        Thread reader = new Thread(() -> {
            // Empty body on purpose: anything with memory effects (println, sleep, a volatile read)
            // could accidentally add the missing ordering and hide the bug.
            while (!shared.ready) {
            }
            observed[0] = shared.data;
        }, "unsafe-reader");
        reader.setDaemon(true); // a stuck reader must not keep the JVM alive

        Thread writer = new Thread(() -> {
            shared.data = 42;     // plain write
            shared.ready = true;  // plain write: NOT ordered or made visible for the reader
        }, "unsafe-writer");

        reader.start();
        Thread.sleep(500); // let the reader's loop be JIT-compiled before the writer runs
        writer.start();
        writer.join();
        reader.join(timeoutMs);

        if (reader.isAlive()) {
            return Outcome.NEVER_SAW_READY;
        }
        // The reader has terminated, so join() gives us a happens-before edge to read observed[0] safely.
        return observed[0] == 42 ? Outcome.SAW_42 : Outcome.SAW_STALE_DATA;
    }

    /**
     * Thread start/join rules: no volatile and no locks, yet the values are guaranteed visible.
     *
     * @return the value seen inside the child thread plus the value it wrote, as {@code [seen, written]}
     */
    public static int[] startAndJoinRules() throws InterruptedException {
        int[] box = new int[2];
        box[0] = 7; // written BEFORE start() -> visible inside the child (start rule)

        Thread child = new Thread(() -> {
            box[1] = box[0] * 6; // reads 7, writes 42
        });
        child.start();
        child.join();            // child's write is visible after join() (join rule)
        return box;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Reader observed data = " + publishViaVolatile() + " (guaranteed 42)");
        int[] box = startAndJoinRules();
        System.out.println("Child saw " + box[0] + " and main sees child's write " + box[1]);
        System.out.println("Without volatile the reader: " + publishWithoutVolatile(2_000) + " (nothing guaranteed)");
    }
}
