/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** Pure Binder-sample/scene policy. No Android dependencies and no log-derived animation. */
public final class DockNativeMotion {
    public static final long MAX_AGE_NS = 150_000_000L;
    private long sequence;
    private boolean recents;
    private float progress;
    public record Sample(long sequence, long uptimeNanos, int scene, double scale,
                         int editState) {
        /** null until native EditMode has published its first state. */
        public Boolean editHidden() {
            return switch (editState) {
                case 0 -> null;
                // Encoded enum indices: disabled=1, normal=2, shortcutMenu=7.
                case 1, 2 -> false;
                // quick, multiselect, pinchingIn/out and preview are editing UI.
                case 3, 4, 5, 6, 8 -> true;
                default -> null;
            };
        }
    }

    public static Sample validate(long sequence, long timestamp, long packed, long editState,
                                  long previousSequence, long nowNanos) {
        int scene = (int) (packed & 3);
        double scale = Double.longBitsToDouble(packed & ~3L);
        if (sequence <= previousSequence || timestamp < 0 || timestamp > nowNanos
                || nowNanos - timestamp > MAX_AGE_NS || scene > 2
                || !Double.isFinite(scale) || scale < 0 || scale > 2
                || editState < 0 || editState > 8) return null;
        return new Sample(sequence, timestamp, scene, scale, (int) editState);
    }

    public boolean accept(Sample sample) {
        if (sample == null || sample.sequence() <= sequence) return false;
        sequence = sample.sequence();
        if (sample.scene() == 1) recents = true;
        else if (sample.scene() == 0 && (!recents || sample.scale() >= .999999)) recents = false;
        // OS4 briefly publishes scene 0 while an already-authenticated recents
        // drag is still below scale 1, then resumes scene 1. Preserve that latch
        // instead of snapping to the default position. Scene 0 can never start
        // a lift by itself, and scale 1 still terminates it.
        // A folder/app returning to scale 1 cannot start a recents animation.
        progress = recents ? (float) Math.max(0, Math.min(1.2, (1 - sample.scale()) / 0.05)) : 0;
        if (sample.scene() == 2 && Math.abs(sample.scale() - 1) < 0.000001) {
            progress = 0;
            recents = false;
        }
        return true;
    }

    public float progress() { return progress; }
    public float offsetY(float density, int baseY) {
        if (!Float.isFinite(density) || density <= 0 || baseY <= 0) return 0;
        return -Math.min(baseY, DockRecentsMotion.LIFT_DP * density * progress);
    }
    public void reset() { sequence = 0; recents = false; progress = 0; }
}
