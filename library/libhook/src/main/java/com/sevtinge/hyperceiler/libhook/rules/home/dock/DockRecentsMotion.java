/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** Bounded, interruptible motion for our background, not a transform of the launcher. */
public final class DockRecentsMotion {
    public static final String WALLPAPER_ACTION = "miui.wallpaper.animation";
    // A deliberately small initial lift. The native icon transform is not exported by HYOS.
    public static final float LIFT_DP = 20f;
    public static final long DURATION_MS = 250;
    private float from;
    private float target;
    private long startedAt;

    /** Only recognize the scene endpoints found in this OS4 launcher, never arbitrary zoom. */
    public static Boolean overviewTarget(String command, String action, double scale) {
        if (!WALLPAPER_ACTION.equals(command) || !"startAnim".equals(action)
                || !Double.isFinite(scale)) return null;
        if (Math.abs(scale - 1.06) < 0.0001) return true;
        if (Math.abs(scale - 1.0) < 0.0001 || Math.abs(scale - 1.14) < 0.0001
                || Math.abs(scale - 1.18) < 0.0001) return false;
        return null;
    }

    public boolean setOverview(boolean overview, long now) {
        float next = overview ? 1f : 0f;
        if (target == next) return false; // Repeated commands must not restart the animation.
        from = progress(now);
        target = next;
        startedAt = now;
        return true;
    }

    public float progress(long now) {
        if (from == target) return target;
        float t = Math.max(0f, Math.min(1f, (now - startedAt) / (float) DURATION_MS));
        // Smoothstep: bounded, no overshoot, and continuous position when a gesture reverses.
        float eased = t * t * (3f - 2f * t);
        return from + (target - from) * eased;
    }

    public boolean isRunning(long now) {
        return from != target && now - startedAt < DURATION_MS;
    }

    public float offsetY(float density, int baseY, long now) {
        if (!Float.isFinite(density) || density <= 0 || baseY <= 0) return 0;
        return -Math.min(LIFT_DP * density, baseY) * progress(now);
    }

    /** Hidden windows need no animation frames; retain the latest scene endpoint. */
    public void finish() {
        from = target;
    }
}
