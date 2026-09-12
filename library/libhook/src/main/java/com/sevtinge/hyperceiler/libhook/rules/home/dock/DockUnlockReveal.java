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
 * buffer, so the reveal is approximated here instead: the background rises and fades in over
 * the same measured window (821 ms, {@code _showPresent} -&gt; {@code endAnimation}) starting from
 * the platform's early {@code keyguardGoingAway} transition, not its later visibility callback.
 *
 * <p>This type is deliberately free of framework references so the timing stays testable.
 */
public final class DockUnlockReveal {
    /** Measured on device: UnlockAnimGetxController._showPresent -> endAnimation. */
    public static final long DURATION_MS = 821L;
    /**
     * How much later the launcher's own icon fly-in starts, measured on device.
     *
     * <p>Our transition epoch comes from the platform's {@code keyguardGoingAway}, which
     * precedes the Flutter scene's {@code _showPresent} by 9-10 ms across three measured
     * unlocks. Holding the start pose for that lead puts the background on the same phase
     * as the dock icons instead of half a frame ahead of them. It is a phase correction,
     * not a perceptible delay, and the first pose is fully transparent anyway.
     */
    public static final long ICON_LEAD_MS = 10L;
    /** Wall time from the transition epoch to the resting pose: the lead plus the fly-in. */
    public static final long TOTAL_MS = ICON_LEAD_MS + DURATION_MS;
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
     * <p>The hidden first pose gives the lift room to accelerate without exposing a position jump.
     * The return overshoot below is bounded separately so the landing stays close to the icons.
     */
    public static final float RISE_DP = 96f;
    /**
     * Ease-out-back tension: one roughly 5.5dp overshoot, then a zero-velocity landing.
     * This is an artistic position curve, not a sample of the launcher's icon transform.
     */
    private static final float LIFT_TENSION = 1.25f;
    /** Reach full opacity early with smooth endpoints, independently of the lift's overshoot. */
    private static final long FADE_DURATION_MS = 180L;
    /** Give up waiting for a visible frame, and never leave the background mid-transform. */
    private static final long EXPIRY_MS = 1500L;
    /** By this point a reveal must be over: the arm wait, the animation, and a small margin. */
    public static final long SETTLE_MS = EXPIRY_MS + TOTAL_MS + 300L;
    /** The platform reports "no longer showing" several times per unlock; ignore the repeats. */
    private static final long RESTART_GUARD_MS = TOTAL_MS + 400L;

    private boolean armed;
    private boolean running;
    private boolean pendingPose;
    private long armedAt;
    private long startedAt = -1L;

    /** A late-created surface may join this unlock, but not an old or future event. */
    public static boolean acceptsPending(long eventMillis, long nowMillis) {
        return eventMillis >= 0L && nowMillis >= eventMillis
                && nowMillis - eventMillis <= EXPIRY_MS;
    }

    /** Use one duplicate-event window for existing surfaces and late-created surfaces. */
    public static boolean acceptsNewEvent(long previousMillis, long nowMillis) {
        return nowMillis >= 0L && (previousMillis < 0L
                || (nowMillis >= previousMillis && nowMillis - previousMillis >= RESTART_GUARD_MS));
    }

    /**
     * Record the transition epoch. A visible frame joins this clock; it never starts a new clock.
     *
     * @return true when this call armed the reveal, false when one was already armed, running, or
     *     finished too recently to be a new unlock.
     */
    public boolean arm(long nowMillis) {
        // Hidden layers do not call progress(). Expire their previous animation here too,
        // otherwise a completed but unsampled reveal rejects the next real unlock.
        needsFrame(nowMillis);
        if (armed || running) return false;
        if (!acceptsNewEvent(startedAt, nowMillis)) return false;
        armed = true;
        armedAt = nowMillis;
        pendingPose = true;
        return true;
    }

    /** The dock stayed hidden, so there is nothing to reveal. */
    public void cancel() {
        armed = false;
        running = false;
    }

    /**
     * Consume a pending arm without shifting its transition epoch to the visibility time.
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
        startedAt = armedAt;
        return true;
    }

    /** True while the reveal still owes the caller a transform, including the armed wait. */
    public boolean isRunning() {
        return armed || running;
    }

    /** Clock expiry alone cannot prove that the resting position/opacity reached the surface. */
    public boolean hasPendingPose() {
        return pendingPose;
    }

    /** Acknowledge only after successfully submitting the pose sampled at this timestamp. */
    public void onPoseCommitted(long nowMillis) {
        if (!armed && (!running || nowMillis - startedAt >= TOTAL_MS)) {
            pendingPose = false;
        }
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
        if (running) return nowMillis - startedAt > TOTAL_MS + 200L;
        return true;
    }

    /** Drive the frame loop while there is something left to draw, with a hard deadline. */
    public boolean needsFrame(long nowMillis) {
        if (armed && nowMillis - armedAt > EXPIRY_MS) {
            cancel();
            return false;
        }
        if (running && nowMillis >= startedAt && nowMillis - startedAt >= TOTAL_MS) {
            running = false;
        }
        return isRunning();
    }

    /** One shared absolute clock, so late surfaces and skipped frames join the same phase. */
    private float elapsedFraction(long nowMillis) {
        if (armed) return 0f;
        if (!running) return 1f;
        // The lead holds the start pose while the launcher brings up its own icon scene.
        long elapsed = Math.max(0L, nowMillis - startedAt - ICON_LEAD_MS);
        if (elapsed >= DURATION_MS) {
            running = false;
            return 1f;
        }
        return elapsed / (float) DURATION_MS;
    }

    /** Monotonic eased progress; opacity must never inherit the position curve's overshoot. */
    public float progress(long nowMillis) {
        float t = elapsedFraction(nowMillis);
        float inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }

    public float scale(long nowMillis) {
        return 1f - GROW * (1f - progress(nowMillis));
    }

    public float alpha(long nowMillis) {
        float t = Math.min(1f, elapsedFraction(nowMillis) * DURATION_MS / FADE_DURATION_MS);
        return t * t * (3f - 2f * t);
    }

    /** Positive means "below the resting place", the same sign the caller adds to its own lift. */
    public float risePx(float density, long nowMillis) {
        if (!Float.isFinite(density) || density <= 0f) return 0f;
        float t = elapsedFraction(nowMillis);
        float remaining = 1f - t;
        // Cross the resting position once, overshoot gently, then return with zero velocity.
        // A closed-form curve remains identical at 60/90/120Hz and after a missed frame.
        float lift = remaining * remaining * (1f - (LIFT_TENSION + 1f) * t);
        return RISE_DP * density * lift;
    }
}
