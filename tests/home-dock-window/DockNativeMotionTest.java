package com.sevtinge.hyperceiler.tests.dock;

/* SPDX-License-Identifier: AGPL-3.0-or-later */
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockNativeMotion;
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockRecentsMotion;

public class DockNativeMotionTest {
    private static DockNativeMotion.Sample sample(long sequence, long time, int scene, double scale, int editState,
                                                   long previousSequence, long now) {
        long packed = (Double.doubleToRawLongBits(scale) & ~3L) | scene;
        return DockNativeMotion.validate(sequence, time, packed, editState, previousSequence, now);
    }
    private static DockNativeMotion.Sample sample(long sequence, long time, int scene, double scale,
                                                   long previousSequence, long now) {
        return sample(sequence, time, scene, scale, 0, previousSequence, now);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void near(float actual, float expected) { check(Math.abs(actual - expected) < 0.001); }
    public static void main(String[] args) {
        long now = 1_000_000_000L;
        check(sample(1, now, 1, .99, 0, now) != null);
        check(sample(1, now, 1, .99, 1, now) == null);
        check(sample(1, now, 1, .99, 0, now - 1) == null);
        check(sample(1, now, 1, .99, 0, now + DockNativeMotion.MAX_AGE_NS + 1) == null);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 2.1}) {
            check(sample(1, now, 1, invalid, 0, now) == null);
        }
        check(sample(1, now, 3, .99, 0, now) == null);
        check(sample(1, now, 1, .99, 9, 0, now) == null);
        check(sample(1, now, 1, .99, -1, 0, now) == null);
        Boolean[] hidden = {null, false, false, true, true, true, true, false, true};
        for (int state = 0; state < hidden.length; state++) {
            DockNativeMotion.Sample edit = sample(state + 1L, now, 0, 1, state, 0, now);
            check(edit != null && edit.editHidden() == hidden[state]);
        }
        DockNativeMotion hinted = new DockNativeMotion();
        hinted.accept(sample(1, now, 0, .98, 0, now), true);
        near(hinted.progress(), .4f); // Verified overview target fills a transient native scene gap.
        hinted.accept(sample(2, now, 0, .97, 0, now), false);
        near(hinted.progress(), .6f); // An existing native recents latch survives a short scene gap.
        hinted.accept(sample(3, now, 0, 1, 0, now), false);
        near(hinted.progress(), 0);
        hinted.accept(sample(4, now, 1, .97, 0, now), false);
        hinted.accept(sample(5, now, 0, .85, 0, now), false);
        near(hinted.progress(), 0); // Folder/app scale cannot inherit an old recents latch.
        DockNativeMotion motion = new DockNativeMotion();
        motion.accept(sample(1, now, 2, .96, 0, now));
        near(motion.progress(), 0); // Folder/home return cannot start a recents lift.
        motion.accept(sample(2, now, 1, .99, 0, now));
        near(motion.progress(), .2f); // Follows drag before wallpaper overview arrives.
        near(motion.offsetY(3.25f, 2000), -13);
        motion.accept(sample(3, now, 1, .95, 0, now));
        near(motion.progress(), 1);
        motion.accept(sample(4, now, 1, .945, 0, now));
        near(motion.progress(), 1.1f); // Preserve measured small native spring overshoot.
        motion.accept(sample(5, now, 1, .91, 0, now));
        near(motion.progress(), 1.2f); // Independent safety limit of 24dp.
        motion.accept(sample(6, now, 2, .98, 0, now));
        near(motion.progress(), .4f);
        check(!motion.accept(sample(5, now, 1, .95, 0, now)));
        near(motion.progress(), .4f);
        motion.accept(sample(7, now, 2, 1, 0, now));
        near(motion.progress(), 0);
        motion.accept(sample(8, now, 2, .96, 0, now));
        near(motion.progress(), 0); // Return completed: no stale latch for another scene.
        motion.accept(sample(9, now, 1, .8, 0, now));
        near(motion.progress(), 1.2f); // Large authenticated drag remains clamped, never snaps home.
        motion.reset();
        motion.accept(sample(9, now, 0, .96, 0, now));
        near(motion.progress(), 0); // Scene 0 cannot start recents by itself.
        motion.accept(sample(10, now, 1, .97, 0, now));
        motion.accept(sample(11, now, 0, .96, 0, now));
        near(motion.progress(), .8f); // Transient scene 0 during a drag preserves the position.
        motion.accept(sample(12, now, 0, 1, 0, now));
        near(motion.progress(), 0);
        motion.reset();
        motion.accept(sample(1, now, 1, .98, 0, now));
        near(motion.progress(), .4f);
        near(motion.offsetY(Float.NaN, 2000), 0);
        near(motion.offsetY(3.25f, 1), -1);
        DockRecentsMotion fallback = new DockRecentsMotion();
        fallback.resumeFrom(.4f, false, 100);
        near(fallback.progress(100), .4f);
        near(fallback.progress(1700), 0);
        System.out.println("DockNativeMotion tests passed");
    }
}
