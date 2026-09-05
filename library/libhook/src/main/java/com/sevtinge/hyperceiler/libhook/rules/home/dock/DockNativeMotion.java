/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Pure packet/scene policy. No Android dependencies and no log-derived animation. */
public final class DockNativeMotion {
    public static final int PACKET_SIZE = 32;
    public static final long MAX_AGE_NS = 150_000_000L;
    private long sequence;
    private boolean recents;
    private float progress;
    public record Sample(long sequence, long uptimeNanos, int scene, double scale) { }

    public static Sample decode(byte[] bytes, long previousSequence, long nowNanos) {
        if (bytes == null || bytes.length != PACKET_SIZE) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 0x48434437 || buffer.getInt() != 1) return null;
        long sequence = buffer.getLong();
        long timestamp = buffer.getLong();
        long packed = buffer.getLong();
        int scene = (int) (packed & 3);
        double scale = Double.longBitsToDouble(packed & ~3L);
        if (sequence <= previousSequence || timestamp < 0 || timestamp > nowNanos
                || nowNanos - timestamp > MAX_AGE_NS || scene > 2
                || !Double.isFinite(scale) || scale < 0 || scale > 2) return null;
        return new Sample(sequence, timestamp, scene, scale);
    }

    public boolean accept(Sample sample) {
        if (sample == null || sample.sequence() <= sequence) return false;
        sequence = sample.sequence();
        if (sample.scene() == 1) recents = true;
        else if (sample.scene() == 0) recents = false;
        // Other scenes may legitimately use much smaller scales. Do not tear
        // down their transport, but never turn them into a recents lift.
        if (sample.scale() < .90 || sample.scale() > 1.10) recents = false;
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
