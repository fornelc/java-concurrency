# java-concurrency

[![CI](https://github.com/fornelc/java-concurrency/actions/workflows/ci.yml/badge.svg)](https://github.com/fornelc/java-concurrency/actions/workflows/ci.yml)

Runnable examples of common Java concurrency problems and their fixes. Each example has JUnit 5
tests that reproduce the problem and verify the fix.

## Examples

| Class | Topic |
|---|---|
| `RaceConditionDemo` | Lost updates on a shared counter; `synchronized` vs `AtomicInteger` |
| `DeadlockDemo` | Lock-order deadlock, detection with `ThreadMXBean`; fixes with lock ordering and `tryLock` + back-off |
| `VisibilityDemo` | Stale reads of a non-`volatile` flag |
| `HappensBeforeDemo` | Safe publication through `volatile`, and the `Thread.start()` / `join()` rules |
| `ExecutorDemo` | `ExecutorService` lifecycle: graceful and two-phase shutdown, cooperative cancellation |

## Requirements

- JDK 17+
- Maven 3.8+

## Build and test

```bash
mvn test
```

The full suite takes about 10 seconds.

## Running the examples

```bash
mvn compile
java -cp target/classes com.example.concurrency.RaceConditionDemo [trace]
java -cp target/classes com.example.concurrency.DeadlockDemo [fixed|trylock]
java -cp target/classes com.example.concurrency.VisibilityDemo
java -cp target/classes com.example.concurrency.HappensBeforeDemo
java -cp target/classes com.example.concurrency.ExecutorDemo
```

`DeadlockDemo` without arguments hangs on purpose. Inspect it with `jstack <pid>` and stop it with Ctrl+C.

## Testing approach

- Behaviour that the Java Memory Model guarantees is asserted, and tests of the fixes are repeated with `@RepeatedTest`.
- Tests that reproduce a bug retry until it shows up, because races are non-deterministic.
- Outcomes that the JMM leaves undefined (e.g. reading a non-`volatile` flag) are reported, not asserted, to avoid flaky tests.
- Every blocking wait is bounded (`@Timeout`, `join(timeout)`, `awaitTermination`), and threads that can't be stopped are daemon threads.
