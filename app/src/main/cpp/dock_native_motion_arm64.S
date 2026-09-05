/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Dart compressed-pointer ARM64 ABI, not AAPCS: x15 stack, x16/x17 scratch.
// Tagged header (-1), CID bits and bool singleton offsets are Dart ABI constants.
// Launcher addresses, class IDs and payload field offsets are resolved at runtime.
// No heap writes, retained pointers, C calls, FP changes or runtime safepoints.
// Notification uses only a raw nonblocking write(2) to our permanent eventfd.
#include "dock_native_layout.h"
.text
.p2align 4
.macro layout reg, field
    adrp x\reg, dock_motion_layout
    add x\reg, x\reg, :lo12:dock_motion_layout
    ldr w\reg, [x\reg, #\field]
.endm
.macro save_state
    stp x9, x10, [x15, #-16]!
    mrs x9, nzcv
    stp x9, x11, [x15, #-16]!
.endm
.macro restore_state
    ldp x9, x11, [x15], #16
    msr nzcv, x9
    ldp x9, x10, [x15], #16
.endm
.macro notify
    adrp x17, dock_motion_subscribed
    add x17, x17, :lo12:dock_motion_subscribed
    ldar w16, [x17]
    cbz w16, 99f
    // arm64 Linux syscall ABI: x8=number, x0..x2=write arguments, x0=result.
    stp x0, x1, [x15, #-16]!
    stp x2, x8, [x15, #-16]!
    adrp x17, dock_motion_event
    ldr w0, [x17, :lo12:dock_motion_event]
    adrp x1, dock_motion_one
    add x1, x1, :lo12:dock_motion_one
    mov x2, #8
    mov x8, #64
    svc #0
    ldp x2, x8, [x15], #16
    ldp x0, x1, [x15], #16
99:
.endm

.global dock_motion_scale_entry
.hidden dock_motion_scale_entry
.type dock_motion_scale_entry, %function
dock_motion_scale_entry:
    save_state
    tbz x2, #0, .Lscale_done
    ldur w16, [x2, #-1]
    ubfx w16, w16, #12, #20
    layout 9, DOCK_DOUBLE_CID
    cmp w16, w9
    b.ne .Lscale_done
    layout 16, DOCK_DOUBLE_VALUE_OFFSET
    ldr x10, [x2, x16]
    and x10, x10, #-4
    adrp x17, dock_motion_value
    add x17, x17, :lo12:dock_motion_value
.Lscale_retry:
    ldaxr x11, [x17]
    and x10, x10, #-4
    and x16, x11, #3
    orr x10, x10, x16
    cmp x10, x11
    b.eq .Lscale_done
    stlxr w16, x10, [x17]
    cbnz w16, .Lscale_retry
    notify
.Lscale_done:
    clrex
    restore_state
    adrp x16, dock_motion_scale_original
    ldr x16, [x16, :lo12:dock_motion_scale_original]
    br x16
.size dock_motion_scale_entry, .-dock_motion_scale_entry

.macro scene_entry name, original, immediate
.global \name
.hidden \name
.type \name, %function
\name:
    save_state
    // ShortcutMenuLayerParams: CID/size/layout corroborated by runtime code.
    mov x10, #0
    tbz x2, #0, 90f
    ldur w16, [x2, #-1]
    ubfx w16, w16, #12, #20
    layout 9, DOCK_PARAMS_CID
    cmp w16, w9
    b.ne 90f
    // Only opaque icon-layer scenes qualify; folder/app fades do not.
    mov x11, #0x3ff0000000000000
    layout 16, DOCK_ALPHA_OFFSET
    ldr x16, [x2, x16]
    cmp x16, x11
    b.ne 90f
    layout 16, DOCK_SURFACE_OFFSET
    ldr w16, [x2, x16]
    add x17, x22, #0x30
    cmp w16, w17
    b.ne 90f
    layout 16, DOCK_RECENTS_OFFSET
    ldr w16, [x2, x16]
    add x17, x22, #0x20
    cmp w16, w17
    b.ne 80f
    // Recents targets are at/near .95; exclude folder/app zooms even if their
    // params inherit the default shouldTryDisableByRecents=true flag.
    layout 16, DOCK_SCALE_OFFSET
    ldr x16, [x2, x16]
    cmp x16, x11
    b.eq 80f
    b.hi 90f
    mov x17, #0xae14
    movk x17, #0xe147, lsl #16
    movk x17, #0x147a, lsl #32
    movk x17, #0x3fee, lsl #48
    cmp x16, x17
    b.lo 90f
    mov x10, #1
    b 90f
80:
    // Home return is permitted only by the receiver's existing recents latch.
    layout 16, DOCK_SCALE_OFFSET
    ldr x16, [x2, x16]
    cmp x16, x11
    b.ne 90f
    mov x10, #2
90:
    adrp x17, dock_motion_value
    add x17, x17, :lo12:dock_motion_value
.if !\immediate
92:
    ldaxr x11, [x17]
    and x11, x11, #-4
    and x10, x10, #3
    orr x10, x10, x11
    stlxr w16, x10, [x17]
    cbnz w16, 92b
.else
    ldar x11, [x17]
    // setTo bypasses the animator callback: publish its actual immediate scale.
    // Invalid params must never be dereferenced, including at this point.
    tbz x2, #0, 91f
    ldur w16, [x2, #-1]
    ubfx w16, w16, #12, #20
    layout 9, DOCK_PARAMS_CID
    cmp w16, w9
    b.ne 91f
    layout 16, DOCK_SCALE_OFFSET
    ldr x11, [x2, x16]
91:
    and x11, x11, #-4
    orr x10, x10, x11
    stlr x10, [x17]
.endif
    notify
    restore_state
    adrp x16, \original
    ldr x16, [x16, :lo12:\original]
    br x16
.size \name, .-\name
.endm
scene_entry dock_motion_anim_entry, dock_motion_anim_original, 0
scene_entry dock_motion_set_entry, dock_motion_set_original, 1
.section .note.GNU-stack,"",%progbits
