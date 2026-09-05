/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
// Offsets within OUR immutable C++/assembly handoff, never in launcher memory.
#define DOCK_PARAMS_CID 0
#define DOCK_DOUBLE_CID 4
#define DOCK_ALPHA_OFFSET 8
#define DOCK_SCALE_OFFSET 12
#define DOCK_SURFACE_OFFSET 16
#define DOCK_RECENTS_OFFSET 20
#define DOCK_DOUBLE_VALUE_OFFSET 24
#ifndef __ASSEMBLER__
#include "dock_native_resolver.h"
static_assert(offsetof(dock_motion::Layout, params_class_id) == DOCK_PARAMS_CID);
static_assert(offsetof(dock_motion::Layout, double_class_id) == DOCK_DOUBLE_CID);
static_assert(offsetof(dock_motion::Layout, alpha_offset) == DOCK_ALPHA_OFFSET);
static_assert(offsetof(dock_motion::Layout, scale_offset) == DOCK_SCALE_OFFSET);
static_assert(offsetof(dock_motion::Layout, surface_offset) == DOCK_SURFACE_OFFSET);
static_assert(offsetof(dock_motion::Layout, recents_offset) == DOCK_RECENTS_OFFSET);
static_assert(offsetof(dock_motion::Layout, double_value_offset) == DOCK_DOUBLE_VALUE_OFFSET);
extern "C" dock_motion::Layout dock_motion_layout;
#endif
