package com.sevtinge.hyperceiler.tests.dock;

/* SPDX-License-Identifier: AGPL-3.0-or-later */
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockNativeMotion;
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockRecentsMotion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DockNativeMotionTest {
    private static byte[] packet(long sequence, long time, int scene, double scale) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x48434437).putInt(1).putLong(sequence).putLong(time)
            .putLong((Double.doubleToRawLongBits(scale) & ~3L) | scene).array();
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void near(float actual, float expected) { check(Math.abs(actual - expected) < 0.001); }
    public static void main(String[] args) {
        long now = 1_000_000_000L;
        check(DockNativeMotion.decode(null, 0, now) == null);
        check(DockNativeMotion.decode(new byte[31], 0, now) == null);
        byte[] data = packet(1, now, 1, .99);
        check(DockNativeMotion.decode(data, 0, now) != null);
        check(DockNativeMotion.decode(data, 1, now) == null);
        check(DockNativeMotion.decode(data, 0, now - 1) == null);
        check(DockNativeMotion.decode(data, 0, now + DockNativeMotion.MAX_AGE_NS + 1) == null);
        data[0] ^= 1;
        check(DockNativeMotion.decode(data, 0, now) == null);
        data = packet(1, now, 1, .99);
        data[4] = 2;
        check(DockNativeMotion.decode(data, 0, now) == null);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 2.1}) {
            check(DockNativeMotion.decode(packet(1, now, 1, invalid), 0, now) == null);
        }
        check(DockNativeMotion.decode(packet(1, now, 3, .99), 0, now) == null);
        DockNativeMotion motion = new DockNativeMotion();
        motion.accept(DockNativeMotion.decode(packet(1, now, 2, .96), 0, now));
        near(motion.progress(), 0); // Folder/home return cannot start a recents lift.
        motion.accept(DockNativeMotion.decode(packet(2, now, 1, .99), 0, now));
        near(motion.progress(), .2f); // Follows drag before wallpaper overview arrives.
        near(motion.offsetY(3.25f, 2000), -13);
        motion.accept(DockNativeMotion.decode(packet(3, now, 1, .95), 0, now));
        near(motion.progress(), 1);
        motion.accept(DockNativeMotion.decode(packet(4, now, 1, .945), 0, now));
        near(motion.progress(), 1.1f); // Preserve measured small native spring overshoot.
        motion.accept(DockNativeMotion.decode(packet(5, now, 1, .91), 0, now));
        near(motion.progress(), 1.2f); // Independent safety limit of 24dp.
        motion.accept(DockNativeMotion.decode(packet(6, now, 2, .98), 0, now));
        near(motion.progress(), .4f);
        check(!motion.accept(DockNativeMotion.decode(packet(5, now, 1, .95), 0, now)));
        near(motion.progress(), .4f);
        motion.accept(DockNativeMotion.decode(packet(7, now, 2, 1), 0, now));
        near(motion.progress(), 0);
        motion.accept(DockNativeMotion.decode(packet(8, now, 2, .96), 0, now));
        near(motion.progress(), 0); // Return completed: no stale latch for another scene.
        motion.accept(DockNativeMotion.decode(packet(9, now, 1, .8), 0, now));
        near(motion.progress(), 0); // Legitimate unrelated zoom cannot become a 24dp lift.
        motion.reset();
        motion.accept(DockNativeMotion.decode(packet(9, now, 1, .97), 0, now));
        motion.accept(DockNativeMotion.decode(packet(10, now, 0, .96), 0, now));
        near(motion.progress(), 0);
        motion.reset();
        motion.accept(DockNativeMotion.decode(packet(1, now, 1, .98), 0, now));
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
