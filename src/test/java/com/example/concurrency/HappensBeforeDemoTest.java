package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HappensBeforeDemoTest {

    @RepeatedTest(100)
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Plain data published through a volatile flag is always visible")
    void volatilePublication() throws InterruptedException {
        assertEquals(42, HappensBeforeDemo.publishViaVolatile());
    }

    @Test
    @DisplayName("Publication through a plain flag: no guarantee, outcome is only reported")
    void publicationWithoutVolatileHasNoGuarantee() throws InterruptedException {
        HappensBeforeDemo.Outcome outcome = HappensBeforeDemo.publishWithoutVolatile(1_000);
        System.out.println("[HappensBeforeDemoTest] publication without volatile -> " + outcome);
    }

    @RepeatedTest(100)
    @DisplayName("Thread.start() and Thread.join() make writes visible without volatile or locks")
    void startAndJoin() throws InterruptedException {
        assertArrayEquals(new int[] {7, 42}, HappensBeforeDemo.startAndJoinRules());
    }
}
