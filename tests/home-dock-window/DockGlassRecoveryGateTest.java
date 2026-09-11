package com.sevtinge.hyperceiler.tests.dock;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassRecoveryGate;
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassRecoveryGate.Outcome;
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassRetryPolicy;

/**
 * Lifecycle regressions for the glass recovery state machine.
 *
 * <p>Drives {@link DockGlassRecoveryGate} on the JVM exactly the way the serial IPC worker
 * does, so every rule that keeps system_server from being killed by a glass failure is
 * covered without an Android runtime:
 *
 * <ul>
 *   <li>a temporarily unresolvable HyperCeiler package is a retryable dependency outage and
 *       never consumes the compatibility budget, and never exhausts it</li>
 *   <li>a burst of callbacks for one failed generation cannot queue parallel retries</li>
 *   <li>a retired ticket admits no retry and no stale callback</li>
 *   <li>a repeated release is reported and skipped instead of tearing down twice</li>
 *   <li>a callback for an older generation, or for a loop that was already cancelled, is
 *       rejected before it can touch a released resource</li>
 * </ul>
 */
public final class DockGlassRecoveryGateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * {@code DockGlassProcessGuard.acquire} reports an unresolvable package as a failure
     * result instead of throwing {@code PackageManager.NameNotFoundException}. This is the
     * state machine half of that contract: the ticket must survive a package replacement
     * that, in the field, lasted longer than the whole compatibility ladder.
     */
    private static void dependencyOutageIsRetryableAndBounded() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "first attempt admitted");
        check(gate.getAttempts() == 1, "generation opened");

        for (int i = 1; i <= 40; i++) {
            check(gate.beginAttempt(false), "retry admitted while the package is unavailable");
            Outcome outcome = gate.requestDependencyDefer(false);
            check(outcome == Outcome.RETRY_LATER, "a dependency outage schedules a retry");
            long delay = gate.getRetryDelayMs();
            check(delay >= 1000, "a dependency retry is never a hot loop (i=" + i + ")");
            check(delay <= 30000, "a dependency retry stays bounded (i=" + i + ")");
        }
        check(gate.getRetryDelayMs() == 30000, "a long outage settles at the capped cadence");
        check(gate.getConsecutiveFailures() == 0,
                "an external outage does not consume the compatibility budget");
        check(!gate.isPreviouslyReady(), "an outage does not claim the host is compatible");
        check(!gate.isRetired(), "the ticket survives the outage");

        // The first four intervals ramp up, then hold at the cap.
        DockGlassRecoveryGate ramp = new DockGlassRecoveryGate();
        long[] expected = {1000, 2000, 4000, 8000, 16000, 30000};
        for (int i = 0; i < expected.length; i++) {
            check(ramp.beginAttempt(false), "ramp attempt admitted");
            ramp.requestDependencyDefer(false);
            check(ramp.getRetryDelayMs() == expected[i],
                    "dependency backoff step " + (i + 1) + " was " + ramp.getRetryDelayMs());
        }
    }

    /** A routine package replacement must not flood the system log. */
    private static void dependencyOutageDoesNotFloodTheLog() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        int reported = 0;
        for (int i = 0; i < 60; i++) {
            check(gate.beginAttempt(false), "attempt admitted");
            gate.requestDependencyDefer(false);
            if (gate.shouldReportDependencyDefer()) reported++;
        }
        check(reported == 3, "only the first few outages are logged, got " + reported);

        // A successful generation resets the throttle for the next outage.
        gate.markReady();
        check(gate.shouldReportDependencyDefer(), "a later outage is reported again");
    }

    private static void repeatedRecoveryIsSingleFlight() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "attempt admitted");
        check(gate.requestRecovery(false, false) == Outcome.RETRY_LATER,
                "the first failure schedules one retry");
        check(gate.getRetryDelayMs() == DockGlassRetryPolicy.delayAfterFailure(1),
                "the first failure uses the bounded ladder");

        // Death recipient + readiness loop + attachment probe all describe one failure.
        for (int i = 0; i < 8; i++) {
            check(gate.requestRecovery(false, false) == Outcome.IGNORED,
                    "a burst of callbacks cannot queue parallel retries");
        }
        check(gate.getConsecutiveFailures() == 1,
                "deduplicated callbacks do not consume the retry budget");
        check(gate.getDeduplicatedRecoveries() == 8, "deduplicated recoveries are counted");

        check(gate.beginAttempt(false), "the scheduled retry is admitted once");
        check(gate.requestRecovery(false, false) == Outcome.RETRY_LATER,
                "a failure of the next generation recovers again");
        check(gate.getConsecutiveFailures() == 2, "the ladder advances once per generation");
    }

    private static void exhaustedBudgetStopsRecovering() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        for (int i = 1; i <= 5; i++) {
            check(gate.beginAttempt(false), "attempt " + i + " admitted");
            check(gate.getAttempts() == i, "generation advances to " + i);
            check(gate.requestRecovery(false, false) != Outcome.EXHAUSTED,
                    "attempt " + i + " is within the budget");
        }
        check(gate.beginAttempt(false), "an extra attempt is admitted");
        check(gate.requestRecovery(false, false) == Outcome.EXHAUSTED,
                "an unsupported host keeps the compositor fallback instead of looping");

        // A host that already rendered is known to be compatible and keeps recovering.
        DockGlassRecoveryGate ready = new DockGlassRecoveryGate();
        check(ready.beginAttempt(false), "attempt admitted");
        ready.markReady();
        for (int i = 0; i < 12; i++) {
            check(ready.beginAttempt(false), "recovery attempt admitted");
            check(ready.requestRecovery(false, false) == Outcome.RETRY_LATER,
                    "a compatible host never permanently loses recovery");
            long delay = ready.getRetryDelayMs();
            check(delay >= 2000 && delay <= 30000, "recovery stays bounded, got " + delay);
        }
        check(ready.getRetryDelayMs() == 30000, "sustained recovery settles at the capped cadence");
    }

    /** Losing a renderer that was already live must recreate it immediately. */
    private static void liveRendererLossRetriesImmediately() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "attempt admitted");
        gate.markReady();
        check(gate.beginAttempt(false), "recovery generation admitted");
        check(gate.requestRecovery(false, true) == Outcome.RETRY_NOW,
                "a live renderer loss retries at once");
        check(gate.getRetryDelayMs() == 0, "no delay for a live renderer loss");
        check(gate.getConsecutiveFailures() == 0,
                "a ready generation does not consume the retry budget");
    }

    private static void staleGenerationsAreRejected() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "attempt admitted");
        int oldGeneration = gate.getAttempts();
        int oldEpoch = gate.openReadinessEpoch();
        check(gate.isAttemptCurrent(oldGeneration), "the live generation is current");
        check(gate.isReadinessCurrent(oldGeneration, oldEpoch), "the live loop is current");

        check(gate.beginAttempt(false), "the next generation is admitted");
        check(!gate.isAttemptCurrent(oldGeneration), "the older generation is stale");
        check(!gate.isReadinessCurrent(oldGeneration, oldEpoch), "the older loop is stale");
        check(gate.isAttemptCurrent(gate.getAttempts()), "the newest generation is current");

        int newEpoch = gate.openReadinessEpoch();
        check(newEpoch != oldEpoch, "a fresh loop gets a fresh epoch");
        check(!gate.isReadinessCurrent(gate.getAttempts(), oldEpoch),
                "opening a loop invalidates the previous one");
        check(gate.isReadinessCurrent(gate.getAttempts(), newEpoch), "the newest loop is current");
        check(!gate.isAttemptCurrent(oldGeneration), "the older generation stays stale");

        gate.invalidateReadiness();
        check(!gate.isReadinessCurrent(gate.getAttempts(), newEpoch),
                "pausing invalidates pending readiness loops");
    }

    /**
     * A released ticket must be inert: no retry admitted, no recovery, and no stale callback
     * may consider itself current enough to touch a resource teardown already released.
     */
    private static void retirementIsFinalAndIdempotent() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "attempt admitted");
        int generation = gate.getAttempts();
        int readiness = gate.openReadinessEpoch();
        int refresh = gate.requestRefresh(1000L);
        check(refresh != DockGlassRecoveryGate.REFRESH_DEDUPLICATED, "refresh registered");
        gate.markReady();

        check(gate.markRetired(), "the first release retires the ticket");
        check(gate.isRetired() && gate.isCancelled(), "retirement blocks every later task");
        check(!gate.markRetired(), "a repeated release is reported and skipped");

        check(!gate.beginAttempt(false), "no retry may be admitted after release");
        check(gate.requestRecovery(false, true) == Outcome.IGNORED,
                "recovery after release is ignored");
        check(gate.requestDependencyDefer(false) == Outcome.IGNORED,
                "a dependency defer after release is ignored");
        check(!gate.isAttemptCurrent(generation), "the retired generation is stale");
        check(!gate.isReadinessCurrent(generation, readiness),
                "a readiness callback for the retired generation is stale");
        check(!gate.isRefreshCurrent(refresh), "a refresh probe for the retired ticket is stale");
    }

    private static void refreshProbesAreDeduplicated() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(gate.beginAttempt(false), "attempt admitted");

        int first = gate.requestRefresh(1000L);
        check(first != DockGlassRecoveryGate.REFRESH_DEDUPLICATED, "the first probe is admitted");
        check(gate.isRefreshCurrent(first), "the admitted probe is current");
        check(gate.requestRefresh(1100L) == DockGlassRecoveryGate.REFRESH_DEDUPLICATED,
                "visibility and wallpaper callbacks describing one return are collapsed");
        check(gate.isRefreshCurrent(first), "the collapsed probe does not invalidate the first");

        int second = gate.requestRefresh(1500L);
        check(second != DockGlassRecoveryGate.REFRESH_DEDUPLICATED, "a later probe is admitted");
        check(!gate.isRefreshCurrent(first), "the newer probe supersedes the older one");
        check(gate.isRefreshCurrent(second), "the newest probe is current");

        gate.pauseRefresh();
        check(!gate.isRefreshCurrent(second), "a hidden parent cancels pending probes");
        int resumed = gate.requestRefresh(9000L);
        check(resumed != DockGlassRecoveryGate.REFRESH_DEDUPLICATED, "resuming admits a probe");
        check(gate.isRefreshCurrent(resumed), "the resumed probe is current");
    }

    private static void closedClientAdmitsNothing() {
        DockGlassRecoveryGate gate = new DockGlassRecoveryGate();
        check(!gate.beginAttempt(true), "a closing client rejects a new attempt");
        check(gate.beginAttempt(false), "an open client admits the first attempt");
        check(!gate.beginAttempt(true), "a closing client rejects a scheduled retry");
        check(gate.requestRecovery(true, false) == Outcome.IGNORED,
                "a closing client ignores recovery");
        check(gate.requestDependencyDefer(true) == Outcome.IGNORED,
                "a closing client ignores a dependency defer");
        gate.markReady();
        check(gate.requestRecovery(false, true) != Outcome.IGNORED,
                "an open client still recovers");
    }

    public static void main(String[] args) {
        dependencyOutageIsRetryableAndBounded();
        dependencyOutageDoesNotFloodTheLog();
        repeatedRecoveryIsSingleFlight();
        exhaustedBudgetStopsRecovering();
        liveRendererLossRetriesImmediately();
        staleGenerationsAreRejected();
        retirementIsFinalAndIdempotent();
        refreshProbesAreDeduplicated();
        closedClientAdmitsNothing();
        System.out.println("DockGlassRecoveryGate tests passed");
    }
}
