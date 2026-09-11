package com.sevtinge.hyperceiler.tests.dock;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockUnlockReveal;

public final class DockUnlockRevealTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        DockUnlockReveal reveal = new DockUnlockReveal();
        check(reveal.arm(10000), "unlock arms before visibility");
        check(reveal.alpha(10016) == 0f, "queued frame cannot expose resting Dock");
        check(reveal.risePx(3f, 10016) > 0f, "hidden pose is already offset");
        check(!reveal.arm(10020), "duplicate callback cannot restart");
        check(reveal.startIfArmed(10032), "first visible frame starts");
        check(reveal.alpha(10032) == 0f, "first frame is transparent");
        check(reveal.alpha(10100) > 0f, "later frames fade in");
        check(reveal.alpha(11000) == 1f && reveal.risePx(3f, 11000) == 0f,
                "finished reveal restores opacity and position");
        check(DockUnlockReveal.acceptsPending(10000, 10500), "new layer inherits recent event");
        check(!DockUnlockReveal.acceptsPending(-1, 10500), "no event is not an unlock");
        check(!DockUnlockReveal.acceptsPending(11000, 10500), "future event rejected");
        check(!DockUnlockReveal.acceptsPending(10000, 11501), "old event rejected");
        DockUnlockReveal expired = new DockUnlockReveal();
        expired.arm(10000);
        check(!expired.startIfArmed(12000), "visibility after deadline cannot replay stale reveal");
        check(expired.alpha(12000) == 1f, "expired reveal fails open");
        DockUnlockReveal transition = new DockUnlockReveal();
        transition.arm(20000);
        transition.startIfArmed(20000);
        check(transition.alpha(20000) == 0f, "transition begins hidden");
        check(!transition.startIfArmed(20315), "late visibility must not restart clock");
        check(transition.progress(20315) > 0f, "late visibility joins current icon phase");
        DockUnlockReveal lateLayer = new DockUnlockReveal();
        lateLayer.arm(20000);
        lateLayer.startIfArmed(20000);
        check(lateLayer.progress(20315) == transition.progress(20315),
                "new layer shares transition epoch, not its creation time");
        System.out.println("DockUnlockReveal tests passed");
    }
}
