# OS4 native motion v8: dynamic resolution

The production observer no longer has a launcher Build ID/address table, fixed
class IDs, or fixed launcher payload offsets. The old probe and profile-only
tests were removed (recoverable from Git history).

## Resolution and safety boundary

1. Find `libapp.so` with `dl_iterate_phdr`, accepting only bounded, readable and
   executable PT_LOAD ranges. Do not inspect other apps, writable heaps or data.
2. Match reviewed ARM64 instruction shapes after masking branch displacements,
   pool/field operands and materialized immediates. These are compiler shapes,
   not offsets from a library base. Unknown shapes remain unsupported.
3. Require unique scene, setter, parameter-string and factory matches. The scale
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

Failure retains the existing wallpaper-command animation fallback. Native IPC,
scene filtering, latest-sample policy and the glass rendering preset are unchanged.

## Verification (2026-09-05)

- Artifact resolver passed against both launcher 6179 and 6236, discovering
  their different function locations and parameter class IDs (1773 and 1777).
- Tests relocate executable ranges, mutate the parameter CID and all four
  relevant field offsets, reject inconsistent accessors, duplicate matches,
  missing callbacks and empty input.
- Six Java policy tests pass, including packets, replay, fallback continuity,
  background-mode migration, glass presets and endpoint ownership.
- The production assembly was cross-compiled and executed on the connected
  phone using HyperCeiler-only synthetic fixtures. Registers x0-x15, NZCV,
  Dart stack, unchanged fixtures, scene guards, eventfd notifications and
  alternate class IDs/field layouts passed. Temporary phone files were removed.
- This is NOT a live launcher synchronization/visual test. No launcher restart,
  phone reboot or other app's data mutation was performed for this verification.

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
