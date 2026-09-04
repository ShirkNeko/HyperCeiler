/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace dock_probe {
struct Profile {
    const char *version;
    uintptr_t scale_offset;
    std::array<uint8_t, 16> build_id;
    std::array<uint32_t, 20> instructions;
};
// Entire callbacks, including both Rx scale writes and relative call targets.
inline constexpr Profile kProfiles[] = {
{"6179", 0xe2a1d0, {
    0x4f, 0x1b, 0xda, 0xed, 0xba, 0x4e, 0xd6, 0x0a,
    0xde, 0x7d, 0x3e, 0xbc, 0x0f, 0xbb, 0xdc, 0x89
}, {
    0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e3, 0xaa0203e0,
    0xf81f83a1, 0xf81f03a2, 0xb8443061, 0x8b1c8021, 0xaa0003e2,
    0x97f22967, 0xf85f83a0, 0xb8447001, 0x8b1c8021, 0xf85f03a2,
    0x97f22962, 0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0
}},
{"6236", 0xe25740, {
    0x4f, 0x1b, 0xda, 0xed, 0x2d, 0xd4, 0x03, 0x23,
    0xde, 0x7d, 0x3e, 0xbc, 0xec, 0x64, 0xd4, 0xb3
}, {
    0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e3, 0xaa0203e0,
    0xf81f83a1, 0xf81f03a2, 0xb8443061, 0x8b1c8021, 0xaa0003e2,
    0x97e9216f, 0xf85f83a0, 0xb8447001, 0x8b1c8021, 0xf85f03a2,
    0x97e9216a, 0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0
}}
};
inline const Profile *profile_for_build(const void *data, size_t length) {
    if (data == nullptr) return nullptr;
    for (const auto &profile : kProfiles) {
        if (length == profile.build_id.size()
            && std::memcmp(data, profile.build_id.data(), length) == 0) return &profile;
    }
    return nullptr;
}
inline bool code_matches(const Profile &profile, const void *data, size_t length) {
    return data != nullptr && length == sizeof(profile.instructions)
        && std::memcmp(data, profile.instructions.data(), length) == 0;
}
} // namespace dock_probe
