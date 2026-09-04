# HyperOS 4 Dock window regression checks

The September 4 device log shows `Loaded HyperOS Runtime native module` for
HyperCeiler, but no launcher Java hook entry. An `Activity.onCreate` hook cannot
create the Dock view in this dex-free launcher. `HomeDockWindow` instead runs in
the existing `system` scope and owns two child SurfaceControl layers under the
exact launcher window: a background blur effect and a separate translucent tint.
No host surface, input window or system property is changed by this hook.

OS4 now offers only system material (saved value 1, localized as 高级材质)
and soft-light glass (saved value 3). The removed solid value 0, legacy custom
value 2 and invalid values resolve to system material in both the settings UI
and system hook. Opening settings persists that migration; the hook also
handles it without requiring a settings visit. Older OS versions keep their
existing options. The solid color picker is hidden only on OS4.

## Host test (JDK 25)

From the repository root:

```sh
dock_test_dir=$(mktemp -d)
javac -d "$dock_test_dir" \
  library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/dock/DockWindowPolicy.java \
  library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/dock/DockGlassPreset.java \
  library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/dock/DockRecentsMotion.java \
  library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/dock/DockGlassRetryPolicy.java \
  tests/home-dock-window/DockWindowPolicyTest.java \
  tests/home-dock-window/DockGlassPresetTest.java \
  tests/home-dock-window/DockRecentsMotionTest.java \
  tests/home-dock-window/DockGlassRetryPolicyTest.java
java -cp "$dock_test_dir" DockWindowPolicyTest
java -cp "$dock_test_dir" DockGlassPresetTest
java -cp "$dock_test_dir" DockRecentsMotionTest
java -cp "$dock_test_dir" DockGlassRetryPolicyTest
```

## Required device verification (not covered by host tests)

1. Install the APK, enable the System Framework scope, and reboot the device.
2. Enable Dock background; select system material. Confirm `HomeDockWindow` logs contain
   `WMS dock hook ready`, a matching launcher window, and `Dock surface created`.
3. Confirm background appears behind icons, with no touch interception. Verify
   height, margin, radius, and dark-mode changes.
4. Open apps, recents, folders and the drawer; lock/unlock and rotate. Check no
   background leaks into other apps and the launcher buffer does not obscure it.
5. Disable the feature, restart the launcher, and hot-reload the module. Verify
   no old `HyperCeiler Dock` SurfaceFlinger layers remain.

Unknown window titles are logged (at most twelve launcher-only titles) and are
not guessed. If no title matches, attach the new logs. If layers are created but
remain invisible, inspect the launcher SurfaceFlinger tree on-device before
changing Z order; an opaque Flutter buffer may require a different attachment.

## Native glass (separate style value 3)

`DockGlassHost` creates an input-transparent, windowless HWUI View in the
HyperCeiler process. `DockGlassClient` performs provider IPC on its own worker
with an unstable provider client, never under the WM lock. The system hook
parents only the returned HyperCeiler surface, underneath the launcher buffer.
The original compositor blur is retained until the host commits a frame **and**
reports a nonzero vendor background-texture timestamp. Unsupported APIs,
rejected pass-window background, missing textures, and renderer death fall back
to compositor blur. Each ticket now allows six creation attempts, with delayed
retries of 2, 4, 8, 16 and 30 seconds. Each attempt checks the vendor texture up
to twenty times at 500ms intervals. Successful readiness stops polling; exhausted
recovery retains the fallback. Disabling/removal cancels the ticket, stale
generation callbacks cannot affect a newer attempt, and hot reload stops the
worker. Retries never run on the WMS thread or at frame rate.

`DockGlassRetryPolicyTest` verifies the backoff/readiness limits on the host JDK.
Actual boot-time recovery and cancellation still require device verification.

The diagnostic build records a bounded history (96 metadata events) in
HyperCeiler's private device-protected storage, independently of release logging
preferences. It includes hook endpoint resolution, glass attempt/failure/readiness,
up to twelve launcher wallpaper command samples, and limited motion-position
samples. No pixels, icons, user input or other application data are collected.
Provider history reads allow only app/system/shell; journal writes only app/system.
All IPC and diagnostic storage access happen away from WMS callbacks.

```sh
adb shell content call \
  --uri content://com.sevtinge.hyperceiler.provider.sharedprefs \
  --method dock_glass_history
```

The system hook must load the new code before it can produce these events;
installation alone does not guarantee that. Check for the versioned hook-init
event rather than assuming a reload occurred. Rebooting requires separate
permission. An empty history before reload does not prove observer failure.

Before testing the real desktop, ADB can run a fixed **unparented** host smoke
test through HyperCeiler (no screenshot or third-party app data):

```sh
adb shell content call \
  --uri content://com.sevtinge.hyperceiler.provider.sharedprefs \
  --method dock_glass_self_test
```

This does not prove that wallpaper delivery or final glass appearance works.
It releases its own host after the call. Other host-management methods accept
only system UID or the app UID, not arbitrary apps or ADB shell.

Additional device checks: glass day/night appearance and backdrop motion;
disable/re-enable; switch between system material and glass; rapid dimension changes;
HyperCeiler process death while glass is active; lock/unlock; launcher/window
removal; module hot reload. Verify touch still reaches icons and no host survives
removal. Device reboot/launcher restart and inspecting data outside HyperCeiler
require separate permission under this task's phone restrictions.

## Recents background lift

The OS4 native launcher animates its icons inside Flutter, independently of the
WindowState surface. The Dock observes the launcher's existing, window-scoped
`miui.wallpaper.animation` command without changing its arguments or result.
The APK's `WallpaperSceneAnimator` distinguishes recent (`scale_to=1.06`), home
(`1.0`), app (`1.14`) and home-hide (`1.18`); its animation command uses
`action=startAnim`. Other commands/scales and non-launcher windows are ignored.
Local APK evidence: the decoded Dart object pool has the recent-scene log at
`pp+0x393b8`; ARM64 code at `0x1a74184` writes `scale_to`, at `0x1a74194`
writes `action=startAnim`, and at `0x1a7420c` sends `miui.wallpaper.animation`.
The current AOSP Session forwards the authorized source WindowState to
`sendWindowWallpaperCommandUnchecked`; the vendor endpoint is resolved by its
complete parameter types at runtime, not by a hard-coded method index.
The observer selects the WindowState overload of WallpaperController's
`sendWindowWallpaperCommandUnchecked`, or the older checked endpoint, never
the unscoped overload. If neither exists the static background remains usable.

The initial motion is a bounded 20dp lift over 250ms, reversed on a home/app
scene. This is a conservative approximation, **not** the native icon transform
or an exact gesture-progress match. Repeated targets do not restart it; a
reversal starts from the current position. Only the HyperCeiler effect parent's
position changes, in the WMS sync transaction. Glass host size, preset, key,
texture and child positions stay unchanged. A Choreographer callback requests
traversal while the visible background is moving; there is no idle polling.
Hidden layers finish at their latest endpoint. Removal/disabling discards the
layer's motion and hot reload cancels the pending callback.

After reloading the system hook (reboot requires user permission), verify:

1. From home, swipe up into recents: icons and background both lift; returning
   home restores the original bottom margin, without glass flashing/recreation.
2. Cancel halfway, rapidly enter/exit, and open an app from recents: no jumping,
   accumulated offset or background leaking over another application.
3. Try button-navigation recents and recents entered from an app. Confirm the
   native launcher emits the same recognized scene command in those paths.
4. Repeat for system material and glass styles, rotation, screen lock/unlock, and
   toggling the feature. Validate magnitude/timing visually on the device;
   host tests alone do not prove that this build receives the runtime signal.

September 4 validation: all three host tests above passed, `git diff --check`
passed, the final `:app:assembleRelease --offline` build succeeded, and the
APK passed v2 signature verification. `adb install -r` returned `Success` on
the user's connected device, preserving module settings. No reboot or launcher
restart was performed. Reloading the system hook and observing actual scene
events/visual motion remain pending user permission and device verification.

## September 4 diagnostic build verification

The retry-policy and existing three host tests passed. The final release build
passed assembly and APK v2 signature verification. `adb install -r` succeeded;
the installed APK hash matches the local artifact:
`21493f3b98292a6321277b094520bffed73057241acfa318fa1b916e636c18cd`.
No reboot, launcher restart or other-app data operation was performed.

New-hook events appeared after installation without an agent-requested reboot:

```text
hook init diagnosticVersion=1 enabled=true mode=3
motion observer unsupported=IllegalStateException: No window-scoped wallpaper command endpoint
glass create ... attempt=1
glass ready ... attempt=1 check=1
glass applied native=true fallbackBlur=0 visible=true
```

This confirms the current glass host and compositor fallback transition, not a
successful cold-boot retry. It also directly confirms that the animation
observer was not installed: neither exact WindowState endpoint signature was
found. The vendor method signature still needs inspection before changing the
observer. The user explicitly deferred reboot; cold-boot testing remains pending.
