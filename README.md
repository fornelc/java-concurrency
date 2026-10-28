# Java Concurrency Demo

Small, self-contained classes (no framework) that each teach one core concurrency concept,
plus JUnit 5 tests that **prove** the behaviour: the bug shows up, and the fix works.

| Class | Concept it teaches | Test |
|---|---|---|
| `RaceConditionDemo` | Race condition, mutex / `synchronized`, `AtomicInteger` | `RaceConditionDemoTest` |
| `DeadlockDemo` | Deadlock, lock ordering, `tryLock` with timeout, livelock, thread dumps (`jstack`) | `DeadlockDemoTest` |
| `VisibilityDemo` | Memory visibility, `volatile` | `VisibilityDemoTest` |
| `HappensBeforeDemo` | Happens-before rules of the Java Memory Model, unsafe publication | `HappensBeforeDemoTest` |
| `ExecutorDemo` | `ExecutorService`, thread pools, `shutdown()` / `awaitTermination()` / `shutdownNow()`, cooperative cancellation | `ExecutorDemoTest` |

All sources are in `src/main/java/com/example/concurrency/`, tests in `src/test/java/com/example/concurrency/`.

## How to run

Requires JDK 17+ and Maven.

```bash
mvn test                 # run all tests (~10 s)
mvn compile              # then run any demo:

java -cp target/classes com.example.concurrency.RaceConditionDemo
java -cp target/classes com.example.concurrency.RaceConditionDemo trace   # step-by-step interleaving
java -cp target/classes com.example.concurrency.DeadlockDemo              # HANGS on purpose (Ctrl+C)
java -cp target/classes com.example.concurrency.DeadlockDemo fixed        # fix #1: lock ordering
java -cp target/classes com.example.concurrency.DeadlockDemo trylock      # fix #2: tryLock(timeout)
java -cp target/classes com.example.concurrency.VisibilityDemo
java -cp target/classes com.example.concurrency.HappensBeforeDemo
java -cp target/classes com.example.concurrency.ExecutorDemo
```

In an IDE you can also just run each class's `main` method.

---

## 1. `RaceConditionDemo` — race condition and mutual exclusion

**The concept.** A *race condition* happens when two threads read-modify-write shared state without
synchronization, so the result depends on how the scheduler interleaves them.

`count++` looks like one operation but is really three:

```
1. READ  count        (e.g. 41)
2. ADD   1            (42)
3. WRITE count        (42)
```

If thread T1 and T2 both READ 41 before either WRITEs, both write 42: one increment is **lost**.

**What the class does.** Two threads each call `increment()` 100,000 times on a shared counter.
The expected result is 200,000. Three implementations of the same `Counter` interface:

| Implementation | How it works | Result |
|---|---|---|
| `UnsafeCounter` | plain `int`, `count++` | almost never 200,000 (e.g. 110,445) |
| `SynchronizedCounter` | `synchronized` methods: the object's intrinsic lock (a **mutex**) lets only one thread at a time execute the increment | always 200,000 |
| `AtomicCounter` | `AtomicInteger.incrementAndGet()`: the whole read-modify-write is one atomic CPU instruction (compare-and-swap), **no lock** | always 200,000 |

A `CountDownLatch` releases both threads at the same instant, so their work really overlaps.

Sample output:

```
Expected value: 200000
Run 1 -> unsafe: 153,061 | synchronized: 200,000 | atomic: 200,000
Run 2 -> unsafe: 116,855 | synchronized: 200,000 | atomic: 200,000
```

**Observing the interleaving (`trace` mode).** `traceInterleaving()` splits `count++` into explicit
READ / WRITE steps, prints each one, and calls `Thread.yield()` in between to invite a context switch:

```
T1 READ  0
T1 WRITE 1
T1 READ  1
T2 READ  0     <- T2 read a stale 0 ...
T2 WRITE 1     <- ... and overwrote T1's work: one increment lost
```

> Why not just `println` inside the 100,000-iteration loop? `System.out.println` is itself
> `synchronized`, so adding it to every increment serializes the threads and *hides* the bug.
> That is a classic "Heisenbug": observing it changes it. For a breakpoint in the IDE, put it on
> the `shared[0] = read + 1` line of `traceInterleaving` and set it to suspend **Thread** (not All),
> then step the two threads alternately.

**What the tests prove** (`RaceConditionDemoTest`):
- `unsafeCounterLosesUpdates` — the bug is reproducible (it tries up to 20 runs and requires at least one lost update; because races are non-deterministic, a single run is not a reliable assertion).
- `synchronizedCounterIsCorrect` / `atomicCounterIsCorrect` — repeated 10 times, always exactly 200,000.
- `unsafeCounterIsFineSingleThreaded` — the same code is correct with one thread: the bug needs concurrency.
- `traceNeverExceedsExpected` — updates can be lost, never invented.

---

## 2. `DeadlockDemo` — deadlock

**The concept.** A *deadlock* occurs when two threads each hold a lock the other one needs, so both
wait forever. It requires four conditions at once (Coffman conditions): mutual exclusion,
hold-and-wait, no preemption, and **circular wait**.

**What the class does.**

```
Thread-1:  lock(A)  ... sleep 100ms ...  lock(B)  <- waits for Thread-2
Thread-2:  lock(B)  ... sleep 100ms ...  lock(A)  <- waits for Thread-1
```

The sleep while holding the first lock makes the bad interleaving practically certain.
After a moment `main` prints a mini thread dump using `ThreadMXBean.findDeadlockedThreads()`
(the same detection `jstack` does) and then hangs:

```
Thread-1 (A then B): holding lockA
Thread-2 (B then A): holding lockB
Thread-1 (A then B): waiting for lockB...
Thread-2 (B then A): waiting for lockA...
=== DEADLOCK DETECTED ===
'Thread-1 (A then B)' is BLOCKED waiting for java.lang.Object@1f32e575 held by 'Thread-2 (B then A)'
'Thread-2 (B then A)' is BLOCKED waiting for java.lang.Object@3f99bd52 held by 'Thread-1 (A then B)'
The program now hangs forever. PID = 89289 -> try: jstack 89289
```

**Confirm with a thread dump.** While it hangs, in another terminal: `jps` (find the PID) then
`jstack <pid>`. You will see:

```
"Thread-1 (A then B)" ... waiting for monitor entry
   java.lang.Thread.State: BLOCKED (on object monitor)
"Thread-2 (B then A)" ... waiting for monitor entry
   java.lang.Thread.State: BLOCKED (on object monitor)
...
Found one Java-level deadlock:
```

In IntelliJ: *Run → Debug*, then the camera icon "Get Thread Dump" in the Debug tool window.

**Fix #1: lock ordering.** `startWithLockOrdering()` makes both threads acquire A **then** B.
With one global order a cycle cannot form, so the circular-wait condition is broken.

**Fix #2: `tryLock` with a timeout.** Sometimes you can't impose a global order (for example, the
locks are chosen at runtime). `startWithTryLock()` keeps the **opposite** order that deadlocks, but
uses `ReentrantLock` instead of `synchronized`:

```java
first.lock();
try {
    if (second.tryLock(50, TimeUnit.MILLISECONDS)) {   // wait, but not forever
        try { /* work */ return; } finally { second.unlock(); }
    }
    // timed out -> fall through, release `first`, back off, retry
} finally {
    first.unlock();
}
sleep(random 10..100 ms);
```

This breaks **no preemption**: a thread that can't get its second lock gives up the one it holds.
Explicit locks must always be released in a `finally`, otherwise an exception leaves them locked forever.

```
Thread-1 (A then B): holding lockA
Thread-2 (B then A): holding lockB
Thread-1 (A then B): trying lockB for 50 ms...
Thread-2 (B then A): trying lockA for 50 ms...
Thread-2 (B then A): timed out on lockA -> releasing lockB and retrying
Thread-1 (A then B): timed out on lockB -> releasing lockA and retrying
Thread-2 (B then A): holding lockB
Thread-2 (B then A): trying lockA for 50 ms...
Thread-2 (B then A): holding lockB and lockA -> work done
Thread-1 (A then B): holding lockA
Thread-1 (A then B): trying lockB for 50 ms...
Thread-1 (A then B): holding lockA and lockB -> work done
No deadlock detected.
Both threads finished. Back-offs needed: 2
```

**Why the back-off is random: livelock.** If both threads always waited the same time, they could
release and re-acquire in lockstep and keep colliding forever. That is a *livelock*: unlike a
deadlock, the threads are running (state `RUNNABLE` / `TIMED_WAITING`, not `BLOCKED`), but nobody
makes progress. A random delay breaks the symmetry.

**Note on `BLOCKED` vs `WAITING`.** `BLOCKED` is specific to `synchronized` monitors. A thread stuck on a
`ReentrantLock` shows as `WAITING` (parking) instead.

**Why a synchronized deadlock can't be interrupted**

1. **`interrupt()` doesn't stop a thread, it only sets a flag.** The flag is checked only by specific
   methods, which then throw `InterruptedException`: `sleep()`, `wait()`, `join()`,
   `lockInterruptibly()`, and so on. A thread that is `BLOCKED` waiting to *enter* a `synchronized`
   block is not inside any of them: the JVM just waits for the monitor and never looks at the flag.
   The interrupt is recorded, but the thread keeps waiting.
2. **Nothing else can stop it either.** `Thread.stop()` is deprecated because it is unsafe (it releases
   locks in the middle of whatever the thread was doing), and since Java 20 it just throws
   `UnsupportedOperationException`. Short of ending the JVM, a `synchronized` deadlock is permanent.
3. **Explicit locks are different.** `ReentrantLock.lockInterruptibly()` gives up with
   `InterruptedException` when interrupted, and `tryLock(timeout)` gives up when the time runs out.
   That is why fix #2 above uses `ReentrantLock` instead of `synchronized`.
4. **So the tests use daemon threads.** The JVM exits on its own only once every *non-daemon* thread
   has finished; daemon threads still running are simply discarded. The deadlocked threads can never
   finish, so they are made daemon (`startDeadlock(true)`) so they can't stop the JVM from exiting.
   Maven happens to end the test JVM with `System.exit()` anyway, which kills every thread, so
   `mvn test` would finish either way. But the test shouldn't depend on that: any runner that lets the
   JVM end normally would hang forever on two non-daemon deadlocked threads. `DeadlockDemo.main` does
   the opposite on purpose: it uses non-daemon threads so the program hangs and you can `jstack` it.

**What the tests prove** (`DeadlockDemoTest`):
- `oppositeLockOrderDeadlocks` — the JVM reports both threads as deadlocked and both are in state `BLOCKED`.
- `consistentLockOrderFinishes` — with lock ordering both threads finish.
- `tryLockAvoidsDeadlock` — with the opposite order plus `tryLock(timeout)`, both threads finish and the
  JVM reports no deadlock. It prints how many back-offs were needed (usually 1–2).

---

## 3. `VisibilityDemo` — memory visibility and `volatile`

**The concept.** Without `volatile` or synchronization, one thread is **not guaranteed** to ever see
another thread's write. Values may sit in CPU registers/caches, and the JIT compiler is allowed to
transform

```java
while (flag.running) { }
```

into

```java
if (flag.running) { while (true) { } }   // read once, loop forever
```

because, from the point of view of a single thread, nothing in the loop changes `running`.

**What the class does.** A worker spins on a `running` flag; main sleeps 500 ms (so the loop gets
JIT-compiled), sets `running = false`, and waits up to 2 s for the worker to stop.

```
volatile flag -> worker stopped: true
plain flag    -> worker stopped: false   (false = the worker never saw the write)
```

- `PlainFlag` (plain `boolean`) — the worker usually spins forever.
- `VolatileFlag` (`volatile boolean`) — every write is visible to later reads; the worker stops.

The loop body is intentionally empty: putting `println` or `sleep` inside adds synchronization/
memory effects and accidentally "fixes" the bug.

`volatile` gives **visibility**, not **atomicity**: `volatile int count; count++` would still race
(see section 1). Use it for flags and for publishing values, not for compound updates.

**What the tests prove** (`VisibilityDemoTest`):
- `volatileFlagIsVisible` — the volatile worker always stops.
- `plainFlagHasNoGuarantee` — runs the broken version and only *prints* the outcome. It does not assert,
  because the Java Memory Model allows both outcomes: asserting either one would make the test flaky.
  That uncertainty is the lesson.

---

## 4. `HappensBeforeDemo` — the happens-before relation

**The concept.** *Happens-before* is the Java Memory Model rule that makes visibility predictable:
if action X happens-before action Y, everything written before X is visible to whoever performs Y.
The main rules:

| Rule | Guarantee |
|---|---|
| Program order | each action in a thread hb later actions in the same thread |
| Monitor lock | unlocking a monitor hb every later lock of the same monitor (why `synchronized` gives visibility) |
| Volatile | a write to a volatile field hb every later read of that field |
| Thread start | `t.start()` hb every action in `t` |
| Thread join | every action in `t` hb `t.join()` returning |
| Transitivity | A hb B and B hb C ⇒ A hb C |

**What the class does.**

`publishViaVolatile()` — "piggybacking" on a volatile write:

```java
// writer                         // reader
data = 42;        // (1) plain    while (!ready) {}   // (3) volatile read sees true
ready = true;     // (2) volatile int x = data;       // (4) guaranteed 42
```

(1) hb (2) by program order, (2) hb (3) by the volatile rule, (3) hb (4) by program order, so by
transitivity (1) hb (4): `data` is visible even though it is **not** volatile.
Without `volatile` on `ready`, the reader could see `ready == true` and still read `data == 0`
(reordering), or never see `ready` change at all (section 3).

`startAndJoinRules()` — a value written before `start()` is seen by the child; the child's write is
seen by the parent after `join()`. No locks or `volatile` needed. This is also why
`RaceConditionDemo.runTwoThreads` can safely read the counter after `join()`.

**The broken version: `publishWithoutVolatile()`.** Exactly the same writer and reader, but `ready` is
a plain `boolean` (class `UnsafePublication`). Without the volatile rule, the chain (1) → (4) is cut:
there is **no** happens-before edge between the writer and the reader, so the JMM allows three outcomes,
reported as an `Outcome` enum:

| Outcome | Meaning |
|---|---|
| `SAW_42` | worked, but only by luck |
| `SAW_STALE_DATA` | saw `ready == true` but `data == 0`: the two writes became visible **out of order** |
| `NEVER_SAW_READY` | never saw the write at all (the visibility problem from section 3) |

```
Reader observed data = 42 (guaranteed 42)
Child saw 7 and main sees child's write 42
Without volatile the reader: NEVER_SAW_READY (nothing guaranteed)
```

On HotSpot you will almost always get `NEVER_SAW_READY`: the reader's loop is JIT-compiled before the
writer runs (the demo waits 500 ms on purpose), and the read of `ready` is hoisted out of the loop.
`SAW_STALE_DATA` is rare on x86, whose hardware keeps stores in order, but it is allowed by the JMM, and it
does happen on weaker CPUs (ARM, e.g. Apple Silicon) or after compiler reordering. Correct code must not
depend on which CPU it runs on, so the only safe version is the volatile one.

**What the tests prove** (`HappensBeforeDemoTest`):
- `volatilePublication` — with `volatile`, the reader sees 42 in all 100 repetitions.
- `startAndJoin` — `[7, 42]` in all 100 repetitions, without locks or `volatile`.
- `publicationWithoutVolatileHasNoGuarantee` — runs the broken version and only *prints* the outcome. Like the
  plain-flag test in section 3, it can't assert anything, because every outcome is allowed.

---

## 5. `ExecutorDemo` — thread pools and graceful shutdown

**The concept.** Creating a raw `Thread` per task is expensive and unbounded. An `ExecutorService`
keeps a fixed pool of worker threads that take tasks from a queue. Stopping it correctly is the
topic of *Java Concurrency in Practice*, Chapter 7 (Cancellation and Shutdown):

| Method | Effect |
|---|---|
| `shutdown()` | stop accepting new tasks; already-submitted tasks still run |
| `awaitTermination(timeout)` | block until all tasks finish, or the timeout expires (returns `false`) |
| `shutdownNow()` | interrupt running tasks and return the list of tasks that never started |

**What the class does.**

- `gracefulShutdown(4, 20)` — 20 short tasks on 4 threads, then `shutdown()` + `awaitTermination()`.
  All 20 complete, and they ran on only 4 distinct threads (thread reuse).
- `forcedShutdown(2, 6)` — 6 tasks that sleep 10 s on 2 threads. The **two-phase shutdown** pattern:
  `shutdown()` + short `awaitTermination()` fails, so it escalates to `shutdownNow()`.
  The 2 running tasks are interrupted and the 4 queued tasks are returned unstarted.

```
Graceful: 20 tasks completed using only 4 threads [...], terminated=true
Forced:   completed=0, interrupted=2, neverStarted=4, terminated=true
```

`shutdownNow()` only *requests* cancellation via interruption. The tasks above use `Thread.sleep()`,
which responds to interruption by throwing `InterruptedException`, and they **restore** the interrupt
flag in the `catch` instead of swallowing it.

**The broken version: a task that ignores interruption.** Interruption is *cooperative*: Java never
forcibly kills a thread, it just sets a flag. Blocking calls like `sleep()`, `wait()` and `join()` check
it for you, but a CPU-bound loop has to check it itself.

- `shutdownNowWithTaskIgnoringInterrupts()` — a busy loop that runs for 2 s and never looks at the flag.
  `shutdownNow()` sets the flag, but the task ignores it, so `awaitTermination(500 ms)` returns `false`:
  the pool is still running.
- `shutdownNowWithInterruptAwareTask()` — **the fix**: the same loop checks
  `Thread.currentThread().isInterrupted()` on every iteration and exits, so the pool terminates right away.

```java
while (System.nanoTime() < deadline) {
    if (Thread.currentThread().isInterrupted()) {   // the fix: one line
        break;
    }
    // CPU-bound work
}
```

```
shutdownNow(), task ignores interrupts: ShutdownNowResult[terminatedAfterShutdownNow=false, interruptRequested=true, stoppedEarly=false]
shutdownNow(), task checks interrupts:  ShutdownNowResult[terminatedAfterShutdownNow=true, interruptRequested=true, stoppedEarly=true]
```

`interruptRequested=true` in both lines shows that `shutdownNow()` did its job both times. The only
difference is whether the task listened. Use `isInterrupted()` (which leaves the flag set) rather than
`Thread.interrupted()` (which clears it) unless you then handle or restore the interrupt yourself.

**What the tests prove** (`ExecutorDemoTest`):
- `gracefulShutdownCompletesAllTasks` — all 20 tasks completed, pool terminated, at most 4 threads used.
- `forcedShutdownInterruptsAndDrains` — 0 completed, 2 interrupted, 4 never started, pool terminated.
- `shutdownNowCannotStopTaskIgnoringInterrupts` — the pool does **not** terminate after `shutdownNow()`,
  even though the task's interrupt flag was set: the task ran to the end.
- `shutdownNowStopsInterruptAwareTask` — the task notices the flag, stops early and the pool terminates.

---

## Testing concurrent code: lessons used in this project

- **A passing test does not prove thread safety.** Concurrency bugs are probabilistic, so the "fixed"
  tests are repeated (`@RepeatedTest`), and the "bug" tests retry until the bug appears.
- **Never assert on behaviour the JMM leaves undefined** (see the plain-flag visibility test).
- **Always bound the wait**: `@Timeout`, `join(timeout)`, `awaitTermination(timeout)`. A concurrency test that can
  hang forever will eventually hang your CI.
- **Use daemon threads for threads you can't stop** (deadlocked or spinning), so the test JVM can exit.
- **Start threads together** (`CountDownLatch`) to maximize overlap and make races reproducible.
