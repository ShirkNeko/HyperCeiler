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
        check(DockGlassRetryPolicy.delayAfterFailure(6, false) == -1,
                "an unsupported host retains the bounded retry budget");
        check(DockGlassRetryPolicy.delayAfterFailure(6, true) == 30000,
                "a previously ready host keeps recovering at the capped cadence");
        check(DockGlassRetryPolicy.delayAfterFailure(Integer.MAX_VALUE, true) == 30000,
                "runtime recovery never requires a launcher restart");
        check(DockGlassRetryPolicy.delayAfterRuntimeFailure(0, true, true) == 0,
                "the first loss of a live renderer retries immediately");
        check(DockGlassRetryPolicy.delayAfterRuntimeFailure(1, false, true) == 2000,
                "a failed immediate recreation resumes bounded backoff");
        check(DockGlassRetryPolicy.delayAfterRuntimeFailure(6, false, true) == 30000,
                "repeated runtime failures retain the capped cadence");
        check(DockGlassRetryPolicy.delayAfterRuntimeFailure(6, false, false) == -1,
                "an unsupported host does not gain an unbounded retry budget");
        check(DockGlassRetryPolicy.BACKGROUND_CHECKS * 500 == 10000,
                "allow ten seconds for initial texture per attempt");
        System.out.println("DockGlassRetryPolicy tests passed");
    }
}
