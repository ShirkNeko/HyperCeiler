/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/**
 * Unlock reveal for our background, timed to the launcher's own "user present" animation.
 *
 * <p>The launcher plays its unlock fly-in entirely inside its Flutter scene. On HyperOS 4 the whole
 * workspace is projected in 3D and every item - the dock row included - travels forward by the same
 * depth step (70.49 at a camera distance of 346.41 on the reference device, read from
 * {@code _UnlockWidgetState.showUserPresentAnimation}). None of that crosses a process boundary:
 * the native motion channel stays at {@code entry=0} for the whole animation, and the launcher's
 * 2D diagnostics ({@code hotSeatScale}/{@code hotSeatTranslateY}/{@code hotSeatAlpha}) are constant
 * identity on this ROM, so they cannot be mirrored either.
 *
 * <p>A child SurfaceControl cannot join a transform that is rasterised inside the launcher's own
 * buffer, so the reveal is reproduced here instead: the background grows, rises and fades in over
 * the same measured window (821 ms, {@code _showPresent} -&gt; {@code endAnimation}) starting from
 * the same moment the platform stops showing the keyguard.
 *
 * <p>This type is deliberately free of framework references so the timing stays testable.
 */
public final class DockUnlockReveal {
    /** Measured on device: UnlockAnimGetxController._showPresent -> endAnimation. */
    public static final long DURATION_MS = 821L;
    /**
     * Start scale offset. Deliberately zero.
     *
     * <p>Scaling the dock was tried and rejected on the device: this layer carries a glass/blur
     * material, and a SurfaceControl matrix resamples that texture, so the material visibly smears
     * and the corners resize. The reveal moves instead, which leaves the glass untouched.
     */
    public static final float GROW = 0f;
    /**
     * Start offset below the resting place, in dp. The dock rises into position.
     *
     * <p>This is the one knob that governs how pronounced the fly-in feels. It is safe to raise it:
     * the layer is posed before it is ever drawn, so the start offset cannot be seen as a jump.
     */
    public static final float RISE_DP = 22f;
    /**
     * The reveal fades in ahead of the geometry. A value above 1 lands full opacity before the
     * rise settles, which keeps the dock from lingering as a half-transparent ghost.
     */
    public static final float ALPHA_RAMP = 2.2f;
    /** Give up waiting for a visible frame, and never leave the background mid-transform. */
    private static final long EXPIRY_MS = 1500L;
    /** By this point a reveal must be over: the arm wait, the animation, and a small margin. */
    public static final long SETTLE_MS = EXPIRY_MS + DURATION_MS + 300L;
    /** The platform reports "no longer showing" several times per unlock; ignore the repeats. */
    private static final long RESTART_GUARD_MS = DURATION_MS + 400L;

    private boolean armed;
    private boolean running;
    private long armedAt;
    private long startedAt;

    /** A late-created surface may join this unlock, but not an old or future event. */
    public static boolean acceptsPending(long eventMillis, long nowMillis) {
        return eventMillis >= 0L && nowMillis >= eventMillis
                && nowMillis - eventMillis <= EXPIRY_MS;
    }

    /**
     * Keyguard has gone: the next frame where the dock is on screen starts the reveal.
     *
     * @return true when this call armed the reveal, false when one was already armed, running, or
     *     finished too recently to be a new unlock.
     */
    public boolean arm(long nowMillis) {
        if (armed || running) return false;
        if (startedAt != 0L && nowMillis - startedAt < RESTART_GUARD_MS) return false;
        armed = true;
        armedAt = nowMillis;
        return true;
    }

    /** The dock stayed hidden, so there is nothing to reveal. */
    public void cancel() {
        armed = false;
        running = false;
    }

    /**
     * Consume a pending arm. The caller only reaches this on a visible dock frame.
     *
     * @return true when this call started the clock, so the caller can report the exact moment the
     *     dock first became animatable.
     */
    public boolean startIfArmed(long nowMillis) {
        if (!armed) return false;
        if (!acceptsPending(armedAt, nowMillis)) {
            cancel();
            return false;
        }
        armed = false;
        running = true;
        startedAt = nowMillis;
        return true;
    }

    /** True while the reveal still owes the caller a transform, including the armed wait. */
    public boolean isRunning() {
        return armed || running;
    }

    /**
     * True once this reveal can no longer be making progress on its own, so the caller should
     * restore the resting transform.
     *
     * <p>The rescue timer is scheduled per arm, and the restart guard allows a new unlock while an
     * older timer is still pending. Checking progress here is what stops a stale timer from cutting
     * a later reveal short mid-flight.
     */
    public boolean isStalled(long nowMillis) {
        if (armed) return nowMillis - armedAt > EXPIRY_MS;
        if (running) return nowMillis - startedAt > DURATION_MS + 200L;
        return true;
    }

    /** Drive the frame loop while there is something left to draw, with a hard deadline. */
    public boolean needsFrame(long nowMillis) {
        if (armed && nowMillis - armedAt > EXPIRY_MS) {
            cancel();
            return false;
        }
        return isRunning();
    }

    /** 0 -&gt; 1 over {@link #DURATION_MS} with an ease-out cubic matching the launcher settle. */
    public float progress(long nowMillis) {
        if (armed) return 0f;
        if (!running) return 1f;
        long elapsed = Math.max(0L, nowMillis - startedAt);
        if (elapsed >= DURATION_MS) {
            running = false;
            return 1f;
        }
        float t = elapsed / (float) DURATION_MS;
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }

    public float scale(long nowMillis) {
        return 1f - GROW * (1f - progress(nowMillis));
    }

    public float alpha(long nowMillis) {
        return Math.min(1f, progress(nowMillis) * ALPHA_RAMP);
    }

    /** Positive means "below the resting place", the same sign the caller adds to its own lift. */
    public float risePx(float density, long nowMillis) {
        if (!Float.isFinite(density) || density <= 0f) return 0f;
        return RISE_DP * density * (1f - progress(nowMillis));
    }
}
