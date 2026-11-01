package com.tinaide.template.common;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds explicit shutdown of a standalone graphical APK, not the game's runtime.
 * Templates cannot depend on the IDE host, so they share this small Java counterpart
 * of GraphicalRuntimeExitGuard instead of packaging the IDE's runtime/UI dependencies.
 */
public final class TemplateRuntimeExitGuard {
    static final long EXIT_GRACE_PERIOD_MILLIS = 2_000L;

    interface Scheduler {
        void schedule(long delayMillis, Runnable action);
    }

    private final Runnable terminateProcess;
    private final Scheduler scheduler;
    private final AtomicBoolean exitRequested = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    public TemplateRuntimeExitGuard(Runnable terminateProcess) {
        this(terminateProcess, TemplateRuntimeExitGuard::scheduleOnWorker);
    }

    TemplateRuntimeExitGuard(Runnable terminateProcess, Scheduler scheduler) {
        this.terminateProcess = terminateProcess;
        this.scheduler = scheduler;
    }

    public void requestExit() {
        if (exitRequested.compareAndSet(false, true)) {
            scheduler.schedule(EXIT_GRACE_PERIOD_MILLIS, this::terminateOnce);
        }
    }

    public void onDestroyed() {
        if (exitRequested.get()) {
            terminateOnce();
        }
    }

    private void terminateOnce() {
        if (terminated.compareAndSet(false, true)) {
            terminateProcess.run();
        }
    }

    private static void scheduleOnWorker(long delayMillis, Runnable action) {
        // NativeActivity / SDL destruction can block the main Looper in a native join.
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "template-runtime-exit");
            thread.setDaemon(true);
            return thread;
        });
        executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
        executor.shutdown();
    }
}
