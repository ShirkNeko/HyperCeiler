/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include "dock_native_probe_profile.h"

namespace dock_motion {
struct Region { uintptr_t offset; size_t size; uint64_t fingerprint; };
// 6236 only: complete animTo/setTo bodies, the toString field mapping and
// the aPb allocation stub (CID 1777). Build ID + scale body are checked first.
inline constexpr Region kRegions[] = {
    {0xdeecd4, 0x660, 0x9644ab953a171133ULL},
    {0xe24488, 0x334, 0x51c3e694d970896cULL},
    {0x155b334, 0x30, 0xd0d2f50023fe50a3ULL},
    {0x1900a30, 0xc, 0x3ce975bb4116c09cULL}
};
inline bool matches(const Region &region, const void *data, size_t size) {
    if (!data || size != region.size) return false;
    uint64_t hash = 0xcbf29ce484222325ULL;
    const auto *bytes = static_cast<const uint8_t *>(data);
    for (size_t i = 0; i < size; ++i) hash = (hash ^ bytes[i]) * 0x100000001b3ULL;
    return hash == region.fingerprint;
}
} // namespace dock_motion
