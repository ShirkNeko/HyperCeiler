/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "../../app/src/main/cpp/dock_native_probe_profile.h"
#include "../../app/src/main/cpp/dock_native_motion_profile.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char **argv) {
    using namespace dock_probe;
    for (const auto &profile : kProfiles) {
        assert(profile_for_build(profile.build_id.data(), profile.build_id.size()) == &profile);
        assert(profile_for_build(nullptr, profile.build_id.size()) == nullptr);
        assert(profile_for_build(profile.build_id.data(), profile.build_id.size() - 1) == nullptr);
        auto wrong_build = profile.build_id;
        wrong_build[0] ^= 1;
        assert(profile_for_build(wrong_build.data(), wrong_build.size()) == nullptr);
        assert(code_matches(profile, profile.instructions.data(), sizeof(profile.instructions)));
        assert(!code_matches(profile, nullptr, sizeof(profile.instructions)));
        assert(!code_matches(profile, profile.instructions.data(), sizeof(profile.instructions) - 4));
        for (size_t i = 0; i < profile.instructions.size(); ++i) {
            auto changed = profile.instructions;
            changed[i] ^= 1;
            assert(!code_matches(profile, changed.data(), sizeof(changed)));
        }
        for (const auto &other : kProfiles) {
            if (&other != &profile) {
                assert(!code_matches(profile, other.instructions.data(), sizeof(other.instructions)));
            }
        }
    }
    for (int argument = 1; argument < argc; ++argument) {
        // Both pinned ELFs have matching file/virtual addresses for the callback.
        // Validate profiles against the original artifacts, not only themselves.
        std::ifstream library(argv[argument], std::ios::binary);
        assert(library.is_open());
        std::array<uint8_t, 16> build{};
        library.seekg(0x1d8);
        library.read(reinterpret_cast<char *>(build.data()), build.size());
        assert(library);
        const auto *profile = profile_for_build(build.data(), build.size());
        assert(profile != nullptr);
        std::array<uint32_t, 20> code{};
        library.seekg(profile->scale_offset);
        library.read(reinterpret_cast<char *>(code.data()), sizeof(code));
        assert(library && code_matches(*profile, code.data(), sizeof(code)));
        if (std::strcmp(profile->version, "6236") == 0) {
            for (const auto &region : dock_motion::kRegions) {
                std::vector<uint8_t> body(region.size);
                library.seekg(region.offset);
                library.read(reinterpret_cast<char *>(body.data()), body.size());
                assert(library && dock_motion::matches(region, body.data(), body.size()));
                assert(!dock_motion::matches(region, nullptr, body.size()));
                assert(!dock_motion::matches(region, body.data(), body.size() - 1));
                for (size_t i = 0; i < body.size(); ++i) {
                    body[i] ^= 1;
                    assert(!dock_motion::matches(region, body.data(), body.size()));
                    body[i] ^= 1;
                }
            }
        }
        std::cout << "Verified launcher profile " << profile->version << '\n';
    }
    std::cout << "DockNativeProbeProfile tests passed\n";
}
