import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockRecentsMotion;

public class DockRecentsMotionTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.0001f, message + ": " + actual);
    }

    public static void main(String[] args) {
        String command = DockRecentsMotion.WALLPAPER_ACTION;
        check(Boolean.TRUE.equals(DockRecentsMotion.overviewTarget(command, "startAnim", 1.06f)), "float recents endpoint");
        for (double scale : new double[]{1.0, 1.14, 1.18}) {
            check(Boolean.FALSE.equals(DockRecentsMotion.overviewTarget(command, "startAnim", scale)), "exit scene " + scale);
        }
        check(DockRecentsMotion.overviewTarget("other", "startAnim", 1.06) == null, "unrelated command");
        check(DockRecentsMotion.overviewTarget(command, null, 1.06) == null, "missing action");
        check(DockRecentsMotion.overviewTarget(command, "cancelAnim", 1.06) == null, "unknown semantics ignored");
        for (double scale : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 1.03, 1.0602, 2}) {
            check(DockRecentsMotion.overviewTarget(command, "startAnim", scale) == null, "invalid/unknown scale");
        }
        var motion = new DockRecentsMotion();
        close(motion.offsetY(3, 1905, 1000), 0, "initial position");
        check(!motion.isRunning(1000), "no idle frame loop");
        check(motion.setOverview(true, 1000), "enter");
        close(motion.progress(1000), 0, "no initial jump");
        close(motion.progress(1125), .5f, "midpoint");
        close(motion.offsetY(3, 1905, 1125), -30, "density scaled lift");
        check(!motion.setOverview(true, 1125), "duplicate signal does not restart");
        close(motion.progress(1250), 1, "endpoint");
        close(motion.offsetY(3, 1905, 1250), -60, "20dp total lift");
        check(!motion.isRunning(1250), "frames stop at completion");
        check(motion.setOverview(false, 1300), "exit");
        close(motion.progress(1300), 1, "no exit jump");
        close(motion.progress(1550), 0, "restored exact base position");
        motion.setOverview(true, 1600);
        float beforeCancel = motion.progress(1675);
        motion.setOverview(false, 1675);
        close(motion.progress(1675), beforeCancel, "cancel is position continuous");
        float beforeReenter = motion.progress(1725);
        motion.setOverview(true, 1725);
        close(motion.progress(1725), beforeReenter, "rapid reentry is position continuous");
        for (long now = 1725; now <= 2100; now++) {
            check(motion.progress(now) >= 0 && motion.progress(now) <= 1, "no overshoot");
        }
        close(motion.offsetY(3, 10, 2100), -10, "clamped above screen top");
        close(motion.offsetY(Float.NaN, 1905, 2100), 0, "invalid density");
        close(motion.offsetY(3, -1, 2100), 0, "invalid bounds");
        motion.setOverview(false, 2200);
        motion.finish();
        close(motion.progress(2200), 0, "hidden host snaps to latest target");
        check(!motion.isRunning(2200), "hidden host schedules no frames");
        System.out.println("DockRecentsMotion tests passed");
    }
}
