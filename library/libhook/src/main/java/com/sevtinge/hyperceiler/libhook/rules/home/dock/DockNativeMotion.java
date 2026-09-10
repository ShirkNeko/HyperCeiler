/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** Pure Binder-sample/scene policy. No Android dependencies and no log-derived animation. */
public final class DockNativeMotion {
    public static final long MAX_AGE_NS = 500_000_000L;
    private long sequence;
    private boolean recents;
    private float progress;
    private Sample lastSample;
    private boolean lastOverviewHint;
    public record Sample(long sequence, long uptimeNanos, int scene, double scale,
                         long entryHits, long publishHits) { }

    public static Sample validate(long sequence, long timestamp, long packed,
                                  long entryHits, long publishHits,
                                  long previousSequence, long nowNanos) {
        int scene = (int) (packed & 3);
        double scale = Double.longBitsToDouble(packed & ~3L);
        if (sequence <= previousSequence || timestamp < 0 || timestamp > nowNanos
                || nowNanos - timestamp > MAX_AGE_NS || scene > 2
                || entryHits < 0 || publishHits < 0 || publishHits > entryHits
                || !Double.isFinite(scale) || scale < 0 || scale > 2) return null;
        return new Sample(sequence, timestamp, scene, scale, entryHits, publishHits);
    }

    /** Keepalive/entry-only packets advance transport replay state, never motion freshness. */
    public static boolean hasPublishedProgress(Sample previous, Sample incoming) {
        if (incoming == null) return false;
        if (previous == null) return incoming.publishHits() > 0;
        if (incoming.entryHits() < previous.entryHits()
                || incoming.publishHits() < previous.publishHits()) return false;
        return incoming.publishHits() > previous.publishHits()
                || incoming.scene() != previous.scene()
                || Double.doubleToRawLongBits(incoming.scale())
                    != Double.doubleToRawLongBits(previous.scale());
    }

    public boolean accept(Sample sample, boolean overviewHint) {
        if (sample == null || sample.sequence() < sequence) return false;
        if (sample.sequence() == sequence) {
            // Native scale can arrive just before the authenticated wallpaper
            // overview command. Replay protection must not prevent that same
            // immutable sample from being reinterpreted once the hint becomes
            // true, or a short first gesture remains at the default position.
            if (lastSample == null || !lastSample.equals(sample)
                    || lastOverviewHint || !overviewHint) return false;
        } else {
            sequence = sample.sequence();
            lastSample = sample;
        }
        lastOverviewHint = overviewHint;
        if (sample.scene() == 1) recents = true;
        else if (overviewHint && sample.scale() < .999999 && sample.scale() >= .90) recents = true;
        else if (sample.scene() == 0
                && (!recents || sample.scale() >= .999999 || sample.scale() < .90)) recents = false;
        // OS4 briefly publishes scene 0 while an already-authenticated recents
        // drag is still in the recents scale band, then resumes scene 1. Preserve that latch
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

    public boolean accept(Sample sample) { return accept(sample, false); }

    public float progress() { return progress; }
    public float offsetY(float density, int baseY) {
        if (!Float.isFinite(density) || density <= 0 || baseY <= 0) return 0;
        return -Math.min(baseY, DockRecentsMotion.LIFT_DP * density * progress);
    }
    public void reset() {
        sequence = 0;
        recents = false;
        progress = 0;
        lastSample = null;
        lastOverviewHint = false;
    }
}
