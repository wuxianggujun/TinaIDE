package com.tinaide.template.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class TemplateRuntimeExitGuardTest {
    private final AtomicInteger terminations = new AtomicInteger();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final TemplateRuntimeExitGuard guard = new TemplateRuntimeExitGuard(
        terminations::incrementAndGet,
        (delay, action) -> {
            delays.add(delay);
            scheduled.add(action);
        }
    );

    @Test
    public void constructionAndNonFinishingDestructionDoNotLimitRuntime() {
        guard.onDestroyed();

        assertTrue(scheduled.isEmpty());
        assertEquals(0, terminations.get());
    }

    @Test
    public void repeatedExitRequestsScheduleOneBoundedShutdown() {
        guard.requestExit();
        guard.requestExit();

        assertEquals(1, scheduled.size());
        assertEquals(Long.valueOf(2_000L), delays.get(0));
        assertEquals(0, terminations.get());
    }

    @Test
    public void completedDestructionReclaimsProcessWithoutWaiting() {
        guard.requestExit();
        guard.onDestroyed();

        assertEquals(1, terminations.get());
    }

    @Test
    public void blockedNativeDestructionDoesNotPreventDeadline() {
        guard.requestExit();
        scheduled.get(0).run();

        assertEquals(1, terminations.get());
    }

    @Test
    public void lateDeadlineCannotTerminateTwice() {
        guard.requestExit();
        guard.onDestroyed();
        scheduled.get(0).run();
        guard.onDestroyed();

        assertEquals(1, terminations.get());
    }

    @Test
    public void racingDestructionAndDeadlineTerminateOnlyOnce() throws Exception {
        guard.requestExit();
        CountDownLatch start = new CountDownLatch(1);
        Thread timeout = new Thread(() -> {
            try {
                start.await();
                scheduled.get(0).run();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        });
        timeout.start();
        start.countDown();
        guard.onDestroyed();
        timeout.join(1_000L);

        assertTrue("Deadline thread did not finish", !timeout.isAlive());
        assertEquals(1, terminations.get());
    }
}
