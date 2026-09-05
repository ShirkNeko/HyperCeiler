/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>
#include <span>
#include <vector>

namespace dock_motion {
// Dart's ARM64 compressed-pointer ABI is a compiler contract, not a launcher
// address/layout profile. Launcher class IDs and field offsets are decoded below.
inline constexpr uint32_t kClassIdShift = 12;
inline constexpr uint32_t kClassIdMask = 0xfffff;

struct CodeRange {
    uintptr_t address;
    std::span<const uint32_t> words;
    bool contains(uintptr_t location, size_t count = 1) const {
        if (location < address || (location - address) % sizeof(uint32_t) != 0) return false;
        const auto index = (location - address) / sizeof(uint32_t);
        return index <= words.size() && count <= words.size() - index;
    }
};

struct Layout {
    uint32_t params_class_id;
    uint32_t double_class_id;
    uint32_t alpha_offset;
    uint32_t scale_offset;
    uint32_t surface_offset;
    uint32_t recents_offset;
    uint32_t double_value_offset;
};
struct Resolution {
    uintptr_t scale;
    uintptr_t animate;
    uintptr_t set;
    Layout layout;
};

// Strip relocatable operands, not opcodes/register flow. These signatures
// describe reviewed compiler shapes; they contain no virtual/file/pool address,
// Build ID, class ID or launcher field offset. Unknown shapes fail closed.
inline uint32_t normalize(uint32_t word) {
    if ((word & 0x7c000000) == 0x14000000) return word & 0xfc000000;
    if ((word & 0xff000010) == 0x54000000) return word & ~0x00ffffe0;
    if ((word & 0x7e000000) == 0x34000000) return word & ~0x00ffffe0;
    if ((word & 0x7e000000) == 0x36000000) return word & ~0x0007ffe0;
    if ((word & 0x1f000000) == 0x11000000) return word & ~0x003ffc00;
    if ((word & 0x3b000000) == 0x39000000) return word & ~0x003ffc00;
    if ((word & 0x3b200c00) == 0x38000000) return word & ~0x001ff000;
    if ((word & 0x1f800000) == 0x12800000) return word & ~0x001fffe0;
    return word;
}
struct Shape {
    size_t words;
    uint64_t fingerprint;
    std::array<uint32_t, 4> prefix;
};
inline constexpr Shape kScaleShape{20, 0x37571acc0a8a293cULL,
    {0xa9bf79fd, 0xaa0f03fd, 0xd10001ef, 0xaa0103e3}};
inline constexpr Shape kAnimateShape{408, 0x995cef99b545a9e3ULL,
    {0xa9bf79fd, 0xaa0f03fd, 0xd10001ef, 0xf80003a1}};
inline constexpr Shape kSetShape{205, 0x3d59fd81e6b4ff29ULL,
    {0xa9bf79fd, 0xaa0f03fd, 0xd10001ef, 0xf80003a1}};
inline constexpr Shape kParamsShape{199, 0xd711731a2821d813ULL,
    {0xa9bf79fd, 0xaa0f03fd, 0xd10001ef, 0xaa1603e1}};
inline constexpr Shape kFactoryShape{21, 0x91e4c216ebc79c09ULL,
    {0x94000000, 0xf800001f, 0x91400371, 0xfd400220}};

struct Match {
    uintptr_t address;
    std::span<const uint32_t> words;
};
inline bool matches(std::span<const uint32_t> words, const Shape &shape) {
    if (words.size() < shape.words) return false;
    for (size_t i = 0; i < shape.prefix.size(); ++i) {
        if (normalize(words[i]) != shape.prefix[i]) return false;
    }
    uint64_t hash = 0xcbf29ce484222325ULL;
    for (const auto word : words.first(shape.words)) hash = (hash ^ normalize(word)) * 0x100000001b3ULL;
    return hash == shape.fingerprint;
}
inline std::vector<Match> find(std::span<const CodeRange> ranges, const Shape &shape) {
    std::vector<Match> result;
    for (const auto &range : ranges) {
        if (range.words.size() < shape.words) continue;
        for (size_t i = 0; i <= range.words.size() - shape.words; ++i) {
            if (normalize(range.words[i]) != shape.prefix[0]) continue;
            const auto words = range.words.subspan(i, shape.words);
            if (matches(words, shape)) {
                result.push_back({range.address + i * sizeof(uint32_t), words});
                if (result.size() > 8) return {}; // Bounded, ambiguous input is unsupported.
            }
        }
    }
    return result;
}
inline std::span<const uint32_t> at(std::span<const CodeRange> ranges, uintptr_t address, size_t count) {
    for (const auto &range : ranges) {
        if (range.contains(address, count)) return range.words.subspan((address - range.address) / 4, count);
    }
    return {};
}
inline std::optional<uintptr_t> call_target(const Match &match, size_t index) {
    if (index >= match.words.size()) return {};
    const auto word = match.words[index];
    if ((word & 0xfc000000) != 0x94000000) return {};
    int64_t displacement = word & 0x03ffffff;
    if ((displacement & 0x02000000) != 0) displacement -= 0x04000000;
    displacement *= 4;
    const auto pc = match.address + index * sizeof(uint32_t);
    if (displacement < 0 && pc < static_cast<uintptr_t>(-displacement)) return {};
    if (displacement > 0 && pc > UINTPTR_MAX - static_cast<uintptr_t>(displacement)) return {};
    return displacement < 0 ? pc - static_cast<uintptr_t>(-displacement) : pc + static_cast<uintptr_t>(displacement);
}
inline int field_offset(uint32_t word) {
    int offset = static_cast<int>((word >> 12) & 0x1ff);
    return offset >= 0x100 ? offset - 0x200 : offset;
}
inline std::optional<uint32_t> allocation_tag(std::span<const uint32_t> words, uint32_t reg) {
    if (words.size() < 2 || (words[0] & 0xffe0001f) != (0xd2800000 | reg)
        || (words[1] & 0xffe0001f) != (0xf2a00000 | reg)) return {};
    return ((words[0] >> 5) & 0xffff) | (((words[1] >> 5) & 0xffff) << 16);
}
inline bool scalar_field(int offset, uint32_t size, unsigned width) {
    return offset >= 7 && (offset + 1) % width == 0
        && static_cast<uint32_t>(offset) < size && width <= size - static_cast<uint32_t>(offset) - 1;
}

inline std::optional<Resolution> resolve(std::span<const CodeRange> ranges) {
    const auto anim = find(ranges, kAnimateShape);
    const auto immediate = find(ranges, kSetShape);
    const auto params = find(ranges, kParamsShape);
    const auto factory = find(ranges, kFactoryShape);
    if (anim.size() != 1 || immediate.size() != 1 || params.size() != 1 || factory.size() != 1) return {};
    const auto allocation = call_target(factory[0], 0);
    if (!allocation) return {};
    const auto stub = at(ranges, *allocation, 3);
    const auto params_tag = allocation_tag(stub, 2);
    if (!params_tag || stub.size() != 3 || (stub[2] & 0xfc000000) != 0x14000000) return {};
    const auto double_tag = allocation_tag(params[0].words.subspan(18, 2), 1);
    if (!double_tag) return {};
    const auto params_size = ((*params_tag >> 8) & 0xf) * 16;
    const auto double_size = ((*double_tag >> 8) & 0xf) * 16;
    // Operand locations are roles in the matched instruction sequence, not
    // object offsets. Read the offsets from the runtime LDUR/STUR instructions.
    const int alpha = field_offset(params[0].words[11]);
    const int scale = field_offset(params[0].words[35]);
    const int surface = field_offset(params[0].words[131]);
    const int recents = field_offset(params[0].words[137]);
    const int double_value = field_offset(params[0].words[21]);
    if (!scalar_field(alpha, params_size, 8) || !scalar_field(scale, params_size, 8)
        || !scalar_field(surface, params_size, 4) || !scalar_field(recents, params_size, 4)
        || !scalar_field(double_value, double_size, 8)) return {};
    if (alpha == scale || surface == recents || surface <= scale + 7 || recents <= scale + 7) return {};
    // Independently corroborate constructor stores and setTo parameter reads.
    const auto &f = factory[0].words;
    const auto &s = immediate[0].words;
    if (field_offset(f[1]) != alpha || field_offset(f[4]) != scale
        || field_offset(f[12]) != surface || field_offset(f[14]) != recents
        || field_offset(s[51]) != alpha || field_offset(s[69]) != scale
        || field_offset(s[61]) != double_value) return {};
    const auto setter = call_target(immediate[0], 62);
    if (!setter || at(ranges, *setter, 1).empty()) return {};
    std::optional<Match> scale_match;
    for (const auto &candidate : find(ranges, kScaleShape)) {
        if (call_target(candidate, 10) != setter || call_target(candidate, 15) != setter) continue;
        if (field_offset(candidate.words[7]) != field_offset(s[64])
            || field_offset(candidate.words[12]) != field_offset(s[82])) continue;
        if (scale_match) return {};
        scale_match = candidate;
    }
    if (!scale_match) return {};
    const auto params_id = (*params_tag >> kClassIdShift) & kClassIdMask;
    const auto double_id = (*double_tag >> kClassIdShift) & kClassIdMask;
    if (params_id == 0 || double_id == 0 || params_id == double_id) return {};
    return Resolution{scale_match->address, anim[0].address, immediate[0].address,
        {params_id, double_id, static_cast<uint32_t>(alpha), static_cast<uint32_t>(scale),
        static_cast<uint32_t>(surface), static_cast<uint32_t>(recents), static_cast<uint32_t>(double_value)}};
}
} // namespace dock_motion
