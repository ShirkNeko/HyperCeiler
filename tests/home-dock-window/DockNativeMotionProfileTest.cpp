/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "../../app/src/main/cpp/dock_native_motion_profile.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char **argv) {
    using namespace dock_motion;
    assert(build_matches(kBuildId.data(), kBuildId.size()));
    assert(!build_matches(nullptr, kBuildId.size()));
    assert(!build_matches(kBuildId.data(), kBuildId.size() - 1));
    auto wrong_build = kBuildId;
    wrong_build[0] ^= 1;
    assert(!build_matches(wrong_build.data(), wrong_build.size()));
    // 6179 was a diagnostic-only profile, never a supported motion profile.
    constexpr std::array<uint8_t, 16> old_build = {
        0x4f, 0x1b, 0xda, 0xed, 0xba, 0x4e, 0xd6, 0x0a,
        0xde, 0x7d, 0x3e, 0xbc, 0x0f, 0xbb, 0xdc, 0x89
    };
    assert(!build_matches(old_build.data(), old_build.size()));
    assert(code_matches(kScaleInstructions.data(), sizeof(kScaleInstructions)));
    assert(!code_matches(nullptr, sizeof(kScaleInstructions)));
    assert(!code_matches(kScaleInstructions.data(), sizeof(kScaleInstructions) - 4));
    for (size_t i = 0; i < kScaleInstructions.size(); ++i) {
        auto changed = kScaleInstructions;
        changed[i] ^= 1;
        assert(!code_matches(changed.data(), sizeof(changed)));
    }
    for (int argument = 1; argument < argc; ++argument) {
        std::ifstream library(argv[argument], std::ios::binary);
        assert(library.is_open());
        std::array<uint8_t, 16> build{};
        library.seekg(0x1d8);
        library.read(reinterpret_cast<char *>(build.data()), build.size());
        assert(library);
        if (build == old_build) {
            assert(!build_matches(build.data(), build.size()));
            std::cout << "Verified 6179 rejects native motion (scene fallback retained)\n";
            continue;
        }
        assert(build_matches(build.data(), build.size()));
        // Reviewed 6236 PT_LOAD file offsets equal the relevant virtual addresses.
        std::array<uint32_t, 20> code{};
        library.seekg(kScaleOffset);
        library.read(reinterpret_cast<char *>(code.data()), sizeof(code));
        assert(library && code_matches(code.data(), sizeof(code)));
        for (const auto &region : kRegions) {
            std::vector<uint8_t> body(region.size);
            library.seekg(region.offset);
            library.read(reinterpret_cast<char *>(body.data()), body.size());
            assert(library && matches(region, body.data(), body.size()));
            assert(!matches(region, nullptr, body.size()));
            assert(!matches(region, body.data(), body.size() - 1));
            for (size_t i = 0; i < body.size(); ++i) {
                body[i] ^= 1;
                assert(!matches(region, body.data(), body.size()));
                body[i] ^= 1;
            }
        }
        std::cout << "Verified launcher motion profile 6236\n";
    }
    std::cout << "DockNativeMotionProfile tests passed\n";
}
