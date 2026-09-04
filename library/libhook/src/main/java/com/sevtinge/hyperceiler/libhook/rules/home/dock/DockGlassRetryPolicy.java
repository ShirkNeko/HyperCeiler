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
}
