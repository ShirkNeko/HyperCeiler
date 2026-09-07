/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** Bounded per-ticket recovery; never driven by the window's frame rate. */
public final class DockGlassRetryPolicy {
    public static final int BACKGROUND_CHECKS = 20;
    private static final long[] DELAYS_MS = {2000, 4000, 8000, 16000, 30000};

    private DockGlassRetryPolicy() {}

    public static long delayAfterFailure(int failedAttempts) {
        if (failedAttempts < 1 || failedAttempts > DELAYS_MS.length) return -1;
        return DELAYS_MS[failedAttempts - 1];
    }

    /**
     * A host that has rendered native glass successfully is known to be compatible. Keep
     * recovering it at the capped cadence after transient process death instead of making a
     * launcher restart the only way to reset the retry budget.
     */
    public static long delayAfterFailure(int failedAttempts, boolean previouslyReady) {
        if (failedAttempts < 1) return -1;
        if (failedAttempts <= DELAYS_MS.length) return DELAYS_MS[failedAttempts - 1];
        return previouslyReady ? DELAYS_MS[DELAYS_MS.length - 1] : -1;
    }

    /**
     * Recreate a lost live renderer immediately. Failed initialization still uses the ordinary
     * bounded backoff, preventing a provider crash loop.
     */
    public static long delayAfterRuntimeFailure(
            int consecutiveFailures, boolean wasReady, boolean previouslyReady) {
        if (wasReady) return 0;
        return delayAfterFailure(consecutiveFailures, previouslyReady);
    }
}
