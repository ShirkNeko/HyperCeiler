package com.sevtinge.hyperceiler.tests.dock;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassRetryPolicy;

public final class DockGlassRetryPolicyTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long[] expected = {2000, 4000, 8000, 16000, 30000};
        long total = 0;
        for (int i = 0; i < expected.length; i++) {
            long delay = DockGlassRetryPolicy.delayAfterFailure(i + 1);
            check(delay == expected[i], "backoff for attempt " + (i + 1));
            check(delay >= 2000, "no frame-rate retries");
            total += delay;
        }
        check(total == 60000, "bounded total backoff");
        for (int exhausted : new int[]{-1, 0, 6, 7, Integer.MAX_VALUE}) {
            check(DockGlassRetryPolicy.delayAfterFailure(exhausted) == -1, "no unbounded retries");
        }
        check(DockGlassRetryPolicy.BACKGROUND_CHECKS * 500 == 10000,
                "allow ten seconds for initial texture per attempt");
        System.out.println("DockGlassRetryPolicy tests passed");
    }
}
