/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace dock_motion {
inline constexpr uintptr_t kScaleOffset = 0xe25740;
inline constexpr std::array<uint8_t, 16> kBuildId = {
    0x4f, 0x1b, 0xda, 0xed, 0x2d, 0xd4, 0x03, 0x23,
    0xde, 0x7d, 0x3e, 0xbc, 0xec, 0x64, 0xd4, 0xb3
};
// Complete 6236 scale callback, including both Rx writes and relative call targets.
inline constexpr std::array<uint32_t, 20> kScaleInstructions = {
    0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e3, 0xaa0203e0,
    0xf81f83a1, 0xf81f03a2, 0xb8443061, 0x8b1c8021, 0xaa0003e2,
    0x97e9216f, 0xf85f83a0, 0xb8447001, 0x8b1c8021, 0xf85f03a2,
    0x97e9216a, 0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0
};
inline bool build_matches(const void *data, size_t length) {
    return data != nullptr && length == kBuildId.size()
        && std::memcmp(data, kBuildId.data(), length) == 0;
}
inline bool code_matches(const void *data, size_t length) {
    return data != nullptr && length == sizeof(kScaleInstructions)
        && std::memcmp(data, kScaleInstructions.data(), length) == 0;
}

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
