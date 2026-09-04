/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Pinned Dart AOT ABI: x15 = Dart SP; x16/x17 = assembler temporaries.
// No C/C++ calls, heap writes, retained Dart pointers, FP operations or safepoints.
.text
.p2align 4
.global dock_probe_scale_entry
.hidden dock_probe_scale_entry
.type dock_probe_scale_entry, %function
dock_probe_scale_entry:
    stp x9, x10, [x15, #-16]!
    mrs x9, nzcv
    stp x9, x11, [x15, #-16]!
    adrp x17, dock_probe_active
    add x17, x17, :lo12:dock_probe_active
    ldar w16, [x17]
    cbz w16, .Ldone
    // x2 is the typed callback's boxed Double, never a compressed argument.
    tbz x2, #0, .Ldone
    ldur w16, [x2, #-1]
    ubfx w16, w16, #12, #20
    cmp w16, #62
    b.ne .Ldone
    ldur x10, [x2, #7]
    adrp x17, dock_probe_scale_bits
    add x17, x17, :lo12:dock_probe_scale_bits
    stlr x10, [x17]
    adrp x17, dock_probe_samples
    add x17, x17, :lo12:dock_probe_samples
.Lcount:
    ldxr x10, [x17]
    add x10, x10, #1
    stlxr w16, x10, [x17]
    cbnz w16, .Lcount
.Ldone:
    ldp x9, x11, [x15], #16
    msr nzcv, x9
    ldp x9, x10, [x15], #16
    adrp x16, dock_probe_original
    ldr x16, [x16, :lo12:dock_probe_original]
    br x16
.size dock_probe_scale_entry, .-dock_probe_scale_entry
.section .note.GNU-stack,"",%progbits
