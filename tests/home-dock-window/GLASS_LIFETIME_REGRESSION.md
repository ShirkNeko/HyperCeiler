# OS4 glass lifetime regression (2026-09-06)

## Observed failure

While the user reported missing blur in the minus-one screen, Control Center
and notifications, SurfaceFlinger retained 12 HyperCeiler SurfaceControlViewHost
roots and 11 Dock glass buffer layers under one Dock effect parent. Only one
renderer host was active. Most old roots had no remaining local handle.
The compositor logged `PassBlur.deqBuf failed`; affected system processes also
reported missing background snapshots. Global window blur remained enabled.

The old client released SurfacePackage references without detaching their
server-side roots. Renderer death/retry therefore accumulated attached layers.
WMS also used its deferred sync transaction to reparent the remote root; an
independent worker detach alone would leave a late-commit resurrection race.

## Fix

- One lease per renderer generation owns attachment/retirement state.
- The serial IPC worker performs both attachment and explicit null-parent
  detachment. WMS only transforms/shows/hides the owned Dock parent.
- Retirement rejects late attaches, including after partial attachment failure.
- Detachment precedes package release. A failed detach retains the handle and
  blocks replacement creation until bounded recovery can complete cleanup.
- Readiness requires attachment as well as a vendor background texture.

The host-JDK test covers twelve generations, attach-once, late requests,
partial attachment failure, cleanup retry, cancellation and repeated release.
All seven Dock host regression suites passed. Release APK signature verified.

## Device checks

The first installation loaded hook diagnostic version 9 through hot reload.
All 12 old roots / 11 old buffers disappeared. A fresh renderer reported
`backgroundReady=true`, and the user confirmed that blur was normal again.
Repeated renderer recovery did not accumulate old roots after this change.
Neither the phone nor launcher/SystemUI was restarted; only HyperCeiler-owned
surfaces were removed. One explicit HyperCeiler-only force-stop was used to
exercise renderer death, without clearing data.

Further testing exposed an existing, separate lifecycle issue: OS4 SmartPower
froze HyperCeiler while the windowless renderer was in use. Binder calls then
returned `BR_FROZEN_REPLY`; ActivityManager recorded `unstable content provider`
exits. Several such exits preceded the deliberate force-stop and also existed
in the pre-fix history. The retry budget could become exhausted.

Hook diagnostic version 10 additionally binds a private, direct-boot-aware
HyperCeiler service for each glass generation, using the normal Android service
dependency rather than a stable provider dependency or a system-wide freezer
exemption. Disposal unbinds the service. The service is not exported and is not
a started/foreground service. No global policy or battery setting is changed.

The version 10 APK built successfully, passed APK-v2 verification, and was
installed with data preserved. Hot reload was confirmed. Final on-device
service/texture verification requires returning to the desktop; no host is
created while the launcher is invisible. This final check must not be inferred
from the user's earlier version 9 visual confirmation.

## Follow-up verification

1. With glass visible, confirm one bound DockGlassRenderService, one host root,
   one Dock glass buffer, and a nonzero background texture timestamp.
2. Leave the desktop visible past the previously observed freezing interval,
   visit Control Center/notifications/minus-one, then return. Verify stable PID
   and no new `BR_FROZEN_REPLY` or renderer-death retries.
3. Disable glass / change material / remove Dock: confirm the service binding
   and remote root disappear. Repeat creation and renderer recovery; counts
   must return to one active generation rather than growing.
4. Boot recovery and long-duration stability remain separate device checks;
   no phone reboot was authorized or performed for this repair.
