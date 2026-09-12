package com.sevtinge.hyperceiler.tests.dock;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockUnlockReveal;

public final class DockUnlockRevealTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        joinsTransitionEpoch();
        sharesEpochWithLateLayer();
        expiresWithoutVisibleProgress();
        handlesPendingDeadlines();
        sharesRestartWindow();
        handlesZeroUptime();
        retainsPoseDebtAcrossRepeatedLostFrames();
        clearsPoseDebtOnlyAfterTerminalCommit();
        cancelledRevealStillNeedsRestingCommit();
        liftSettlesOnceWithoutMaterialPulsing();
        lateLayerJoinsLandingAfterSkippedFrames();
        System.out.println("DockUnlockReveal tests passed");
    }

    private static void joinsTransitionEpoch() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        check(reveal.arm(10000), "unlock arms before visibility");
        check(reveal.alpha(10016) == 0f, "queued frame cannot expose resting Dock");
        check(reveal.risePx(3f, 10016) > 0f, "hidden pose is already offset");
        check(!reveal.arm(10020), "duplicate callback cannot restart");
        check(reveal.startIfArmed(10032), "first visible frame joins the transition");
        DockUnlockReveal immediate = new DockUnlockReveal();
        immediate.arm(10000);
        immediate.startIfArmed(10000);
        check(reveal.progress(10032) == immediate.progress(10032),
                "delayed visibility cannot shift the transition epoch");
        check(reveal.alpha(10032) > 0f, "first visible frame catches up to the existing fade");
        check(reveal.alpha(10100) > reveal.alpha(10032), "later frames fade in");
        check(!reveal.startIfArmed(10315), "late visibility cannot restart an active clock");
        check(reveal.alpha(11000) == 1f && reveal.risePx(3f, 11000) == 0f,
                "finished reveal restores opacity and position");
    }

    private static void sharesEpochWithLateLayer() {
        DockUnlockReveal transition = new DockUnlockReveal();
        transition.arm(20000);
        transition.startIfArmed(20000);
        check(transition.alpha(20000) == 0f, "transition begins hidden");
        DockUnlockReveal lateLayer = new DockUnlockReveal();
        lateLayer.arm(20000);
        check(lateLayer.startIfArmed(20315), "new layer may join an in-flight reveal");
        check(lateLayer.progress(20315) == transition.progress(20315),
                "new layer shares transition epoch, not its creation time");
        check(lateLayer.risePx(3f, 20315) == transition.risePx(3f, 20315),
                "late-created layer shares the existing layer's position");

        DockUnlockReveal completedLayer = new DockUnlockReveal();
        completedLayer.arm(20000);
        check(completedLayer.startIfArmed(21000), "recent pending event can still be consumed");
        check(!completedLayer.needsFrame(21000), "late creation must not replay a finished reveal");
        check(completedLayer.alpha(21000) == 1f && completedLayer.risePx(3f, 21000) == 0f,
                "creation after animation end immediately uses the resting pose");
    }

    private static void expiresWithoutVisibleProgress() {
        DockUnlockReveal hidden = new DockUnlockReveal();
        hidden.arm(30000);
        hidden.startIfArmed(30000);
        check(hidden.needsFrame(30000 + DockUnlockReveal.TOTAL_MS - 1),
                "hidden active reveal still has a deadline");
        check(!hidden.needsFrame(30000 + DockUnlockReveal.TOTAL_MS),
                "hidden reveal expires exactly at its duration");
        check(!hidden.isRunning(), "no progress sampling is needed to clear running state");
        check(!hidden.arm(30000 + DockUnlockReveal.TOTAL_MS + 400 - 1),
                "completed hidden reveal still observes duplicate-event guard");
        check(hidden.arm(30000 + DockUnlockReveal.TOTAL_MS + 400),
                "hidden completion cannot block the next real unlock");

        DockUnlockReveal unsampled = new DockUnlockReveal();
        unsampled.arm(40000);
        unsampled.startIfArmed(40000);
        check(unsampled.arm(40000 + DockUnlockReveal.TOTAL_MS + 400), "arm itself expires a previous reveal with no frame callbacks");
        check(unsampled.startIfArmed(40000 + DockUnlockReveal.TOTAL_MS + 400), "next unlock starts after missing every previous frame");
        check(unsampled.alpha(41221) == 0f, "next unlock gets its own transparent first pose");
    }

    private static void handlesPendingDeadlines() {
        check(DockUnlockReveal.acceptsPending(10000, 10500), "new layer inherits recent event");
        check(!DockUnlockReveal.acceptsPending(-1, 10500), "no event is not an unlock");
        check(!DockUnlockReveal.acceptsPending(11000, 10500), "future event rejected");
        check(DockUnlockReveal.acceptsPending(10000, 11500), "pending deadline is inclusive");
        check(!DockUnlockReveal.acceptsPending(10000, 11501), "old event rejected");
        DockUnlockReveal expired = new DockUnlockReveal();
        expired.arm(10000);
        check(!expired.startIfArmed(12000), "visibility after deadline cannot replay stale reveal");
        check(expired.alpha(12000) == 1f, "expired reveal fails open");
        check(!expired.isRunning(), "stale visibility clears the armed animation state");
        DockUnlockReveal neverVisible = new DockUnlockReveal();
        neverVisible.arm(10000);
        check(neverVisible.needsFrame(11500), "armed wait lasts through the pending deadline");
        check(!neverVisible.needsFrame(11501), "armed wait also expires without visibility");
        check(neverVisible.risePx(3f, 11501) == 0f, "expired armed wait restores the resting pose");
        DockUnlockReveal future = new DockUnlockReveal();
        future.arm(10000);
        check(!future.startIfArmed(9999), "visibility timestamp before the event is rejected");
        check(!future.isRunning(), "rejected future event cannot leave a hidden pose armed");
    }

    private static void sharesRestartWindow() {
        check(DockUnlockReveal.acceptsNewEvent(-1, 10000), "first event has no restart guard");
        check(!DockUnlockReveal.acceptsNewEvent(-1, -1), "negative event time is invalid");
        check(!DockUnlockReveal.acceptsNewEvent(10000, 9999), "event cannot precede its predecessor");
        check(!DockUnlockReveal.acceptsNewEvent(10000, 10000), "same-time duplicate is rejected");
        check(!DockUnlockReveal.acceptsNewEvent(10000, 10000 + DockUnlockReveal.TOTAL_MS + 400 - 1),
                "duplicate-event window is exclusive");
        check(DockUnlockReveal.acceptsNewEvent(10000, 10000 + DockUnlockReveal.TOTAL_MS + 400),
                "next event starts at the guard boundary");
        check(DockUnlockReveal.acceptsPending(10000, 10000 + DockUnlockReveal.TOTAL_MS + 400),
                "an old pending event can coexist with an accepted next event");

        DockUnlockReveal existingLayer = new DockUnlockReveal();
        existingLayer.arm(10000);
        existingLayer.startIfArmed(10000);
        long pendingAt = 10000;
        long nextEvent = 10000 + DockUnlockReveal.TOTAL_MS + 400;
        if (DockUnlockReveal.acceptsNewEvent(pendingAt, nextEvent)) pendingAt = nextEvent;
        check(pendingAt == nextEvent, "accepted event replaces the still-recent pending epoch");
        check(existingLayer.arm(nextEvent), "existing layer accepts the same next-event boundary");
        existingLayer.startIfArmed(nextEvent);
        DockUnlockReveal recreatedLayer = new DockUnlockReveal();
        recreatedLayer.arm(pendingAt);
        recreatedLayer.startIfArmed(nextEvent + 32);
        check(existingLayer.progress(nextEvent + 32) == recreatedLayer.progress(nextEvent + 32),
                "existing and recreated layers use the new event instead of different epochs");
        check(existingLayer.risePx(3f, nextEvent + 32) > 0f,
                "second unlock has not inherited the completed first unlock's pose");
    }

    private static void handlesZeroUptime() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        check(reveal.arm(0), "uptime zero is a valid first event");
        check(reveal.startIfArmed(0), "uptime-zero event starts normally");
        check(reveal.alpha(0) == 0f, "uptime-zero event begins transparent");
        check(!reveal.needsFrame(DockUnlockReveal.TOTAL_MS), "uptime-zero event expires at the normal duration");
        check(!reveal.arm(DockUnlockReveal.TOTAL_MS), "uptime-zero history must not be confused with an absent event");
        check(!reveal.arm(DockUnlockReveal.TOTAL_MS + 400 - 1), "uptime-zero event keeps the full duplicate guard");
        check(reveal.arm(DockUnlockReveal.TOTAL_MS + 400), "uptime-zero event permits the next unlock at the same boundary");
    }

    private static void retainsPoseDebtAcrossRepeatedLostFrames() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        check(!reveal.hasPendingPose(), "untouched layer owes no reveal pose");
        reveal.arm(10000);
        reveal.startIfArmed(10000);
        check(reveal.alpha(10700) == 1f && reveal.scale(10700) == 1f,
                "late reveal can already have resting opacity and scale");
        check(Math.abs(reveal.risePx(3f, 10700)) > 0f,
                "resting opacity and scale do not imply a resting position");
        reveal.onPoseCommitted(10700);
        check(reveal.hasPendingPose(), "committing an intermediate rise still owes its terminal pose");

        check(!reveal.needsFrame(10900), "first watchdog may expire the animation clock");
        check(reveal.hasPendingPose(), "first lost terminal frame keeps its pose debt");
        check(!reveal.needsFrame(11000), "second watchdog sees an already expired clock");
        check(reveal.hasPendingPose(), "second lost terminal frame must not discard its pose debt");
        check(reveal.progress(11000) == 1f && reveal.risePx(3f, 11000) == 0f,
                "terminal values remain available after repeated lost frames");
        check(reveal.hasPendingPose(), "sampling terminal values is not a successful submission");
        reveal.onPoseCommitted(11000);
        check(!reveal.hasPendingPose(), "successful terminal submission clears the persistent debt");
    }

    private static void clearsPoseDebtOnlyAfterTerminalCommit() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        reveal.arm(20000);
        check(reveal.hasPendingPose(), "arming immediately creates a pose debt");
        reveal.onPoseCommitted(20000);
        check(reveal.hasPendingPose(), "priming an armed hidden pose is not a terminal submission");
        reveal.startIfArmed(20000);
        reveal.onPoseCommitted(20000 + DockUnlockReveal.TOTAL_MS - 1);
        check(reveal.hasPendingPose(), "submission before the duration boundary cannot clear debt");
        reveal.onPoseCommitted(20000 + DockUnlockReveal.TOTAL_MS);
        check(!reveal.hasPendingPose(), "terminal commit clears debt even without progress sampling");

        check(reveal.arm(20000 + DockUnlockReveal.TOTAL_MS + 400), "a later unlock can start after the previous terminal commit");
        check(reveal.hasPendingPose(), "every accepted unlock creates a fresh pose debt");
        check(!reveal.arm(20000 + DockUnlockReveal.TOTAL_MS + 401), "duplicate callback still cannot restart the fresh unlock");
        check(reveal.hasPendingPose(), "duplicate callback cannot acknowledge the fresh pose debt");
        reveal.startIfArmed(20000 + DockUnlockReveal.TOTAL_MS + 400);
        reveal.onPoseCommitted(20000 + DockUnlockReveal.TOTAL_MS + 401);
        check(reveal.hasPendingPose(), "an early second-unlock commit cannot clear its debt");
        long secondTerminal = 20000 + (DockUnlockReveal.TOTAL_MS + 400) + DockUnlockReveal.TOTAL_MS;
        reveal.progress(secondTerminal);
        check(reveal.hasPendingPose(), "progress expiry alone cannot acknowledge a second terminal pose");
        reveal.onPoseCommitted(secondTerminal);
        check(!reveal.hasPendingPose(), "second terminal commit clears only the current debt");
    }

    private static void cancelledRevealStillNeedsRestingCommit() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        reveal.arm(30000);
        reveal.startIfArmed(30000);
        reveal.onPoseCommitted(30000);
        reveal.cancel();
        check(!reveal.isRunning(), "cancellation stops animation immediately");
        check(reveal.alpha(30010) == 1f && reveal.risePx(3f, 30010) == 0f,
                "cancelled reveal supplies the resting pose");
        check(reveal.hasPendingPose(), "cancellation does not imply the resting pose was submitted");
        reveal.onPoseCommitted(30010);
        check(!reveal.hasPendingPose(), "resting submission acknowledges a cancelled reveal");

        DockUnlockReveal expired = new DockUnlockReveal();
        expired.arm(40000);
        check(!expired.needsFrame(41501), "an unstarted reveal can time out");
        check(expired.hasPendingPose(), "armed timeout retains the need to clear its primed pose");
        expired.onPoseCommitted(41501);
        check(!expired.hasPendingPose(), "resting submission also acknowledges armed timeout");
    }

    private static void liftSettlesOnceWithoutMaterialPulsing() {
        DockUnlockReveal reveal = new DockUnlockReveal();
        reveal.arm(10000);
        reveal.startIfArmed(10000);
        check(reveal.alpha(10000) == 0f, "the first pose stays transparent");
        float previousY = reveal.risePx(1f, 10000);
        float previousAlpha = 0f;
        float minimumY = previousY;
        boolean landing = false;
        for (long elapsed = 1; elapsed <= DockUnlockReveal.TOTAL_MS; elapsed++) {
            long now = 10000 + elapsed;
            float y = reveal.risePx(1f, now);
            float alpha = reveal.alpha(now);
            check(Float.isFinite(y) && y >= -6f && y <= 96f,
                    "lift and overshoot remain bounded near the icon row");
            check(alpha >= previousAlpha && alpha <= 1f,
                    "the material only fades in, never pulses during the landing");
            check(reveal.scale(now) == 1f, "glass is never resampled by reveal scaling");
            if (y > previousY) landing = true;
            if (landing) check(y >= previousY, "only one landing, with no repeated bounces");
            minimumY = Math.min(minimumY, y);
            previousY = y;
            previousAlpha = alpha;
        }
        check(landing && minimumY < -4.5f, "the lift has a visible but restrained overshoot");
        check(previousY == 0f && previousAlpha == 1f, "the deadline ends at the exact resting pose");

        DockUnlockReveal lead = new DockUnlockReveal();
        lead.arm(40000);
        lead.startIfArmed(40000);
        float startPose = lead.risePx(1f, 40000);
        check(startPose == DockUnlockReveal.RISE_DP, "the lead keeps the dock at its start offset");
        check(lead.risePx(1f, 40009) == startPose && lead.alpha(40009) == 0f,
                "the icons' measured head start is held, not skipped");
        check(lead.risePx(1f, 40011) < startPose,
                "motion begins exactly when the launcher's icon fly-in begins");
        check(lead.risePx(1f, 40000 + DockUnlockReveal.TOTAL_MS) == 0f,
                "the lead does not shorten or lengthen the fly-in itself");

        DockUnlockReveal fading = new DockUnlockReveal();
        fading.arm(20000);
        fading.startIfArmed(20000);
        check(fading.alpha(20100) > 0.4f && fading.alpha(20100) < 0.6f,
                "opacity builds visibly during the early lift");
        check(fading.alpha(20190) == 1f, "the glass is opaque before the landing begins");
        check(fading.alpha(20191) == 1f, "fade completion does not produce an opacity step");
    }

    private static void lateLayerJoinsLandingAfterSkippedFrames() {
        DockUnlockReveal smooth = new DockUnlockReveal();
        smooth.arm(30000);
        smooth.startIfArmed(30000);
        for (long elapsed = 0; elapsed < 530; elapsed += 8) {
            smooth.risePx(3f, 30000 + elapsed);
            smooth.alpha(30000 + elapsed);
        }
        DockUnlockReveal late = new DockUnlockReveal();
        late.arm(30000);
        late.startIfArmed(30530);
        check(late.risePx(3f, 30530) == smooth.risePx(3f, 30530),
                "a recreated surface joins the landing even after skipping the whole lift");
        check(late.alpha(30530) == smooth.alpha(30530), "late material keeps the same opacity");
        check(late.risePx(Float.NaN, 30530) == 0f && late.risePx(0f, 30530) == 0f,
                "invalid density cannot put a non-finite position into a surface transaction");
    }
}
