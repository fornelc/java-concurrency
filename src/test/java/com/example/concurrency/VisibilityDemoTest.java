package com.example.concurrency;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class VisibilityDemoTest {

    @RepeatedTest(3)
    @DisplayName("volatile flag: the worker always sees the write and stops")
    void volatileFlagIsVisible() throws InterruptedException {
        assertTrue(VisibilityDemo.volatileWorkerStops(2_000));
    }

    @Test
    @DisplayName("plain flag: no guarantee, outcome is only reported")
    void plainFlagHasNoGuarantee() throws InterruptedException {
        // The JMM allows both outcomes, so asserting either one would make the test flaky.
        boolean stopped = VisibilityDemo.plainWorkerStops(1_000);
        System.out.println("[VisibilityDemoTest] plain-flag worker stopped = " + stopped);
    }
}
