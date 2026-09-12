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
     * The five fly-in looks a user can pick in the Dock settings. They differ in which axis
     * carries the motion and how the easing reads, so no two feel like variations of one idea:
     * <ul>
     *   <li>{@link #DAYBREAK} — a 96dp rise from below with one restrained settle.</li>
     *   <li>{@link #DEPTH_FLIP} — the same rise plus a real perspective flip from depth.</li>
     *   <li>{@link #GALE} — no vertical travel; the glass slides in from the side and fades.</li>
     *   <li>{@link #ORBIT_SWEEP} — a fast arc: lateral offset leads, the rise follows, and a
     *       single underdamped spring snaps everything onto the resting pose.</li>
     *   <li>{@link #RIPPLE} — the glass arrives small and gathers into place, scale plus fade
     *       only, with no sway and no overshoot.</li>
     * </ul>
     */
    public enum Style {
        DAYBREAK, DEPTH_FLIP, GALE, ORBIT_SWEEP, RIPPLE;

        public static Style of(String name) {
            if (name != null) {
                for (Style style : values()) {
                    if (style.name().equalsIgnoreCase(name)) return style;
                }
            }
            return DAYBREAK;
        }
    }

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
    private volatile Style style = Style.DAYBREAK;

    /** The look chosen in the Dock settings; read on every pose sample. */
    public void setStyle(Style newStyle) {
        this.style = newStyle == null ? Style.DAYBREAK : newStyle;
    }

    public Style getStyle() {
        return style;
    }

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
        if (style == Style.GALE || style == Style.RIPPLE) return 0f;
        if (style == Style.ORBIT_SWEEP) {
            return ORBIT_RISE_DP * density * (1f - spring(t, ORBIT_OMEGA_Y));
        }
        float remaining = 1f - t;
        // Cross the resting position once, overshoot gently, then return with zero velocity.
        // A closed-form curve remains identical at 60/90/120Hz and after a missed frame.
        float lift = remaining * remaining * (1f - (LIFT_TENSION + 1f) * t);
        return RISE_DP * density * lift;
    }

    /**
     * Horizontal offset; negative means "still to the left of rest".
     *
     * <p>Two very different looks share this axis. {@link Style#GALE} is Material's shared axis
     * X: content enters from the side on a decelerating curve. {@link Style#ORBIT_SWEEP} uses the
     * same axis as the leading half of an arc, which is why its spring settles earlier than the
     * vertical one.
     */
    public float slidePx(float widthPx, long nowMillis) {
        if (!Float.isFinite(widthPx) || widthPx <= 0f) return 0f;
        float t = elapsedFraction(nowMillis);
        if (style == Style.GALE) {
            return -GALE_SLIDE_FRACTION * widthPx * (1f - emphasizedDecelerate(t));
        }
        if (style == Style.ORBIT_SWEEP) {
            return -ORBIT_SLIDE_FRACTION * widthPx * (1f - spring(t, ORBIT_OMEGA_X));
        }
        return 0f;
    }

    /**
     * Unit spring response of a mass released with velocity, used by the orbital sweep.
     *
     * <p>Closed form of the standard underdamped solution for {@code x(0)=0, x'(0)=v} with
     * damping ratio {@code z} and natural frequency {@code w}, so every frame is derived from the
     * absolute clock and a missed frame cannot change the result. It is a real spring rather than
     * an ease-out-back: the single overshoot decays by the same physics that governs the settle.
     */
    private static float spring(float t, float w) {
        // Normalised so the response is exactly 1 at the end of the window. A raw spring still
        // holds a fraction of a percent of offset there, and the caller's contract is that the
        // final pose is the resting pose, not something imperceptibly close to it.
        return rawSpring(t, w) / rawSpring(1f, w);
    }

    private static float rawSpring(float t, float w) {
        float wd = w * (float) Math.sqrt(1f - ORBIT_ZETA * ORBIT_ZETA);
        float decay = (float) Math.exp(-ORBIT_ZETA * w * t);
        // The coefficient carries (damping - entry velocity): with the opposite sign the solution
        // would start by moving away from the target, which shows up as a visible wind-up.
        float coefficient = (ORBIT_ZETA * w - ORBIT_ENTRY_VELOCITY) / wd;
        return 1f - decay * ((float) Math.cos(wd * t) + coefficient * (float) Math.sin(wd * t));
    }

    /** Material 3 "emphasized decelerate": starts at peak velocity and comes to rest. */
    private static float emphasizedDecelerate(float t) {
        return cubicBezier(EMPHASIZED_DECELERATE, t);
    }

    /**
     * Evaluate a CSS/Material cubic-bezier(x1, y1, x2, y2) easing.
     *
     * <p>Frames sample this by progress, so the curve has to be solvable for x rather than
     * parametric in t: a short bisection is enough for a value that is then rounded to a
     * device pixel, and it keeps the class free of framework types.
     */
    private static float cubicBezier(float[] points, float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        float low = 0f;
        float high = 1f;
        for (int i = 0; i < 24; i++) {
            float mid = (low + high) * 0.5f;
            if (bezierAxis(points[0], points[2], mid) < x) low = mid;
            else high = mid;
        }
        return bezierAxis(points[1], points[3], (low + high) * 0.5f);
    }

    private static float bezierAxis(float control1, float control2, float t) {
        float inverse = 1f - t;
        return 3f * inverse * inverse * t * control1 + 3f * inverse * t * t * control2 + t * t * t;
    }

    /**
     * Start pose of the 3D fly-in ("depth-flip"): the dock arrives from depth — laid back
     * 58°, yawed 14°, rolled 3.5°, scaled to 62% — and settles flat. The projection happens
     * inside our own glass view, where a real camera with a finite distance is available.
     * The SurfaceControl that carries the surface only supports an affine matrix, so a
     * perspective there is impossible.
     */
    public static final float POSE_ROT_X_DEG = 58f;
    public static final float POSE_ROT_Y_DEG = -14f;
    public static final float POSE_ROT_Z_DEG = 3.5f;
    public static final float POSE_SCALE = 0.62f;
    /** Perspective strength: camera distance as a multiple of the layer height. */
    public static final float CAMERA_HEIGHTS = 2.4f;
    /** How far the flip overshoots past flat, as a fraction of the start angle (~5%). */
    private static final float FLIP_OVERSHOOT = 0.9f;
    /**
     * Lateral travel of the "shared axis X" look, as a fraction of the layer width.
     *
     * <p>Material's shared axis X slides incoming content in from the side while it fades in.
     * Its nominal 30dp offset is specified for full-screen content, where 30dp reads as a
     * short directional nudge; a dock panel is a small element, so the same perceived weight
     * needs the distance expressed relative to the element itself.
     */
    private static final float GALE_SLIDE_FRACTION = 0.34f;
    /**
     * Start scale of the "gather" look. Deliberately below 1: a child surface can never draw
     * larger than its own buffer, so a start scale above 1 would be clipped at the layer bounds
     * and therefore invisible. Arriving small is also what Material's fade-through does (92%);
     * the value here is exaggerated because a settle the user cannot see is not a style.
     */
    private static final float SETTLE_SCALE = 0.84f;
    /** Material 3 "emphasized decelerate": begins at peak velocity, ends at rest. */
    private static final float[] EMPHASIZED_DECELERATE = {0.05f, 0.7f, 0.1f, 1f};
    /**
     * "Orbital sweep" start pose: the panel sweeps in on a shallow arc, so it starts below the
     * resting line and off to one side, slightly turned and further away.
     *
     * <p>Sized to the element rather than copied from an icon: a dock panel is far wider than an
     * icon, so the same 12-24dp lateral offset would vanish. The lateral term is a fraction of
     * the width (about 24dp on the reference dock) while the rise stays in dp, which keeps the
     * arc readable without making the panel travel across the screen.
     */
    private static final float ORBIT_RISE_DP = 44f;
    private static final float ORBIT_SLIDE_FRACTION = 0.08f;
    private static final float ORBIT_ROT_DEG = -3.2f;
    private static final float ORBIT_SCALE = 0.86f;
    /** Damping ratio: underdamped enough for one clear overshoot, clean rather than wobbly. */
    private static final float ORBIT_ZETA = 0.62f;
    /** The lateral axis settles first, so the path bends instead of running straight. */
    private static final float ORBIT_OMEGA_X = 12f;
    /** The rise finishes a little later, which is what turns two curves into an arc. */
    private static final float ORBIT_OMEGA_Y = 8.8f;
    /** Entry velocity of the spring: the panel arrives already moving, then snaps onto rest. */
    private static final float ORBIT_ENTRY_VELOCITY = 3.4f;

    /** Immutable 3D pose sampled at one instant. {@code active=false} means resting. */
    public static final class Pose3D {
        public final float rotationX;
        public final float rotationY;
        public final float rotationZ;
        public final float scale;
        public final boolean active;

        Pose3D(float rotationX, float rotationY, float rotationZ, float scale, boolean active) {
            this.rotationX = rotationX;
            this.rotationY = rotationY;
            this.rotationZ = rotationZ;
            this.scale = scale;
            this.active = active;
        }

        public static Pose3D identity() {
            return new Pose3D(0f, 0f, 0f, 1f, false);
        }
    }

    /**
     * Sample the 3D fly-in pose at one instant.
     *
     * <p>Shared by both processes: system_server owns the epoch, and the glass view that
     * actually performs the projection lives in the module's own process, where it
     * re-derives the same phase from {@code SystemClock.uptimeMillis}. Only an absolute
     * clock crosses the boundary, so no per-frame IPC is needed and the two sides cannot
     * drift. Only {@link Style#DEPTH_FLIP}, {@link Style#GALE} and {@link Style#RIPPLE}
     * carry a view transform; the position-only looks report identity.
     */
    public static Pose3D pose3D(Style style, long startedAtMillis, long nowMillis) {
        long elapsed = nowMillis - startedAtMillis - ICON_LEAD_MS;
        if (elapsed <= 0L) {
            return startPose(style);
        }
        if (elapsed >= DURATION_MS) return Pose3D.identity();
        float t = elapsed / (float) DURATION_MS;
        float inverse = 1f - t;
        float quint = 1f - inverse * inverse * inverse * inverse * inverse;
        float remain = 1f - quint;
        switch (style) {
            case DEPTH_FLIP: {
                // The main flip uses an ease-out-back with a small overshoot past flat (one
                // restrained bounce reads as confidence, not wobble); yaw, roll and scale
                // ride a plain ease-out quint so only one axis shows the overshoot.
                float c1 = FLIP_OVERSHOOT;
                float c3 = c1 + 1f;
                float back = 1f + c3 * (t - 1f) * (t - 1f) * (t - 1f) + c1 * (t - 1f) * (t - 1f);
                return new Pose3D(POSE_ROT_X_DEG * (1f - back),
                        POSE_ROT_Y_DEG * remain,
                        POSE_ROT_Z_DEG * remain,
                        POSE_SCALE + (1f - POSE_SCALE) * quint,
                        true);
            }
            case RIPPLE:
                // Material's shared axis Z scales incoming content on a decelerating curve. The
                // start scale is below 1 because a child surface cannot draw outside its own
                // buffer: anything above 1 is clipped and simply invisible. No sway - the scale
                // and the fade are the whole gesture, which is what keeps it calm.
                return new Pose3D(0f, 0f, 0f,
                        SETTLE_SCALE + (1f - SETTLE_SCALE) * emphasizedDecelerate(t), true);
            case ORBIT_SWEEP: {
                // Same spring as the trajectory, so scale and rotation land with the arc instead
                // of drifting in after it. The scale rides the vertical axis and therefore shows
                // the one overshoot; the rotation uses the lateral axis that settles first.
                float lift = spring(t, ORBIT_OMEGA_Y);
                float lateral = spring(t, ORBIT_OMEGA_X);
                return new Pose3D(0f, 0f, ORBIT_ROT_DEG * (1f - lateral),
                        ORBIT_SCALE + (1f - ORBIT_SCALE) * lift, true);
            }
            default:
                // Daybreak is position-only, and so is Gale: its motion is the horizontal slide
                // served by slidePx(), not a rotation.
                return Pose3D.identity();
        }
    }

    private static Pose3D startPose(Style style) {
        switch (style) {
            case DEPTH_FLIP:
                return new Pose3D(POSE_ROT_X_DEG, POSE_ROT_Y_DEG, POSE_ROT_Z_DEG, POSE_SCALE, true);
            case RIPPLE:
                return new Pose3D(0f, 0f, 0f, SETTLE_SCALE, true);
            case ORBIT_SWEEP:
                return new Pose3D(0f, 0f, ORBIT_ROT_DEG, ORBIT_SCALE, true);
            default:
                return Pose3D.identity();
        }
    }
}
