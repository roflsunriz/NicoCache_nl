package nicocache.launcher;

import java.io.IOException;

/** Keeps the launcher resident while core stop actions include diagnostics. */
final class LauncherLifecycle {
    @FunctionalInterface
    interface StopAction {
        void run() throws IOException;
    }

    private final StopAction gracefulStopCore;
    private final StopAction forceStopCore;
    private final Runnable exitLauncher;

    LauncherLifecycle(StopAction gracefulStopCore,
            StopAction forceStopCore, Runnable exitLauncher) {
        this.gracefulStopCore = gracefulStopCore;
        this.forceStopCore = forceStopCore;
        this.exitLauncher = exitLauncher;
    }

    void gracefulStopCore() throws IOException {
        gracefulStopCore.run();
    }

    void forceStopCore() throws IOException {
        forceStopCore.run();
    }

    void exitLauncher() {
        exitLauncher.run();
    }
}
