# OS4 Dock v20: dynamic motion/edit resolution and reconnect

The production observer no longer has a launcher Build ID/address table, fixed
class IDs, or fixed launcher payload offsets. The old probe and profile-only
tests were removed (recoverable from Git history).

## Resolution and safety boundary

1. Find `libapp.so` with `dl_iterate_phdr`, accepting only bounded, readable and
   executable PT_LOAD ranges. HYOS can map AOT code outside the linker's image
   list, so a bounded fallback reads only this process's explicit executable
   `/libapp.so` mappings from `/proc/self/maps`. It never scans writable,
   anonymous, heap, or another process's memory.
2. Match reviewed ARM64 instruction shapes after masking branch displacements,
   pool/field operands and materialized immediates. These are compiler shapes,
   not offsets from a library base. Unknown shapes remain unsupported.
3. Require unique scene, setter, parameter-string, factory and EditMode callback
   matches. The scale
   callback has two shape matches in the known artifacts: disambiguate through
   its two BL targets and the setter's receiver-field accesses.
4. Decode class allocation tags and field accesses, then independently check
   constructor stores against parameter/setter reads. Require aligned fields
   inside the decoded allocation size. Invalid or ambiguous input fails closed.
5. Publish the derived layout once, before installing callbacks. Assembly uses
   that immutable layout and does not retain or modify Dart heap pointers.

The remaining ARM64/Dart constants describe the calling convention, tagged
header, class-tag encoding and bool singleton ABI, not a launcher build's memory
addresses. Layout-handoff macros describe HyperCeiler's own C++ struct and are
checked using `offsetof` assertions. A different Dart ABI/compiler shape needs
review, not speculative memory reads. Structure fingerprints are locators,
not cryptographic authenticity checks.

Failure retains the existing wallpaper-command animation fallback. A scene-0 sample
cannot initiate motion by itself, but a verified window-scoped overview target can
bridge a transient native scene gap while native scale remains in the recents band.
An expiry frame returns from the last native position if samples stop after overview
exit. Edit visibility uses the dynamically resolved EditMode state callback; the old
wallpaper-scale/time heuristic has been removed.

LSPosed initializes the module's native entry in `/system_ext/bin/hyos_spawner`
(currently named `usap64`) before it forks MiuiHome. Version 17 hooks the spawner's
dynamic `setprogname` symbol; the inherited hook starts the worker only after a
child identifies itself exactly as `com.miui.home`. The loader callback and
property-read detection remain bounded fallback signals. HYOS maps its AOT
application outside the ordinary linker callback path, so the worker still waits
for and resolves only that process's executable `libapp.so` ranges.

Samples travel from a detached MiuiHome transport worker to system_server as custom
transactions on the launcher's existing `IWindowManager` Binder. Synchronous Binder
identity is required because this OS4 kernel reports PID 0 for one-way calls; the
launcher render callback only publishes to eventfd and never waits on Binder. WMS accepts the fixed-size payload
only from the UID/PID bound to the exact launcher window Session, validates sequence,
monotonic timestamp, age, scene and scale, then applies the latest value on the SF
frame clock. The previous system_server-to-launcher Unix socket was blocked by
SELinux and has been removed.

If a new launcher PID publishes before its WindowState is prepared, WMS retains the
sample without applying it and promotes it only after the exact UID/PID is independently
bound. This closes the launcher-restart race without trusting package data from the
payload.

The detached sender recreates its `IWindowManager` handle and retries every 500ms
after a transaction failure. This covers the temporary endpoint loss caused by a
module install/hot reload without polling while connected or touching the launcher
render thread. Only the first unavailable interval and first three disconnects are
logged; each successful connection immediately publishes the latest sample before
waiting on eventfd again.

## Verification (2026-09-08)

- Artifact resolver passed against both launcher 6179 and 6236, discovering
  their different function locations and parameter class IDs (1773 and 1777).
- Tests relocate executable ranges, mutate the parameter CID and all four
  relevant field offsets, reject inconsistent accessors, duplicate matches,
  missing callbacks and empty input.
- Eight Java policy tests pass, including packets, replay, fallback continuity,
  background-mode migration, glass presets and endpoint ownership.
- The production assembly was cross-compiled and executed on the connected
  phone using HyperCeiler-only synthetic fixtures. Registers x0-x15, NZCV,
  Dart stack, unchanged fixtures, scene guards, eventfd notifications and
  alternate class IDs/field layouts passed. Temporary phone files were removed.
- Launcher 6236 resolved the v20 native hooks at runtime. Device diagnostics then
  confirmed exact EditState transitions (`state=4` hidden, `state=2` shown) and
  per-frame native-vsync motion. Java diagnostic v21 removes the superseded wallpaper
  edit inference, adds cold-return refresh/expiry safeguards, and versions the private
  Binder transaction once so callbacks left by pre-v21 hot reloads cannot consume the
  current stream. Cleaned-up v21 callbacks explicitly yield to their successor.

Host artifact test (pass paths to extracted ELF files, not APKs):

```sh
clang++ -std=c++20 -O2 -Wall -Wextra -Werror \
  tests/home-dock-window/DockNativeResolverTest.cpp -o /tmp/HyperCeilerResolverTest
/tmp/HyperCeilerResolverTest /path/to/6179/libapp.so /path/to/6236/libapp.so
```

## Codacy PR 1686

The public report for commit `8eb09d035` contained 81 newly added issues.
Changes address the obsolete sleep API, untyped eventfd read, runtime-selected
reflection inventory, Java field/package declarations, test packages, deeply
nested socket reads, large window callbacks, labeled returns, duplicated strings,
glass-host NPath complexity and interrupted-future handling. Window lifecycle,
lock ordering, cleanup and fail-closed fallback remain intact.

Local Detekt 1.23.8 recheck no longer reports the listed long methods, labeled
returns, duplicate strings, complex conditions, excessive function counts or
native-client generic catch/nesting in the changed Kotlin paths. All-rules mode
also reports rules not enabled in this Codacy report; this is not a claim that
the entire repository or remote quality gate is clean.

Deliberate boundaries retained for review:

- The loader-owned `NativeApiEntries.unhook_func` slot must remain ABI-compatible
  even though these permanent process-lifetime hooks do not unhook.
- WMS Session comparison requires object identity; replacing it with an arbitrary
  value equality implementation weakens the scope guard.
- Renderer IPC/hidden-vendor API recovery catches unexpected vendor failures off
  the WMS thread. Narrowing this to a guessed list must not let an OEM failure
  terminate the system process, so the broad recovery boundary is retained.
- Access to own-process vendor ViewRoot instance fields is necessary for texture
  readiness and the own-window blur filter. Its scoped PMD suppression documents
  that requirement; public APIs are used for ordinary inventory types.

The requested push target is `os4`. PR 1686 uses `os4-branch`, so pushing only
`os4` does not refresh that PR's Codacy report.
