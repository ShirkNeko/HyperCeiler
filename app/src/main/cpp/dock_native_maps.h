/* SPDX-License-Identifier: AGPL-3.0-or-later */
#pragma once
#include "dock_native_resolver.h"
#include <cstdio>
#include <istream>
#include <string>

namespace dock_motion {
// HYOS may map AOT code outside the linker's library list. Accept only explicit
// libapp.so RX mappings in THIS process, never heap/anonymous/other-app memory.
inline std::vector<CodeRange> mapped_code_ranges(std::istream &maps) {
    std::vector<CodeRange> result;
    std::string line;
    size_t total = 0;
    constexpr size_t max_bytes = 256 * 1024 * 1024;
    while (std::getline(maps, line)) {
        unsigned long long begin = 0, end = 0, offset = 0, inode = 0;
        char permissions[5]{};
        int path_start = 0;
        if (std::sscanf(line.c_str(), "%llx-%llx %4s %llx %*s %llu %n",
                &begin, &end, permissions, &offset, &inode, &path_start) != 5
            || path_start <= 0) continue;
        const std::string path = line.substr(static_cast<size_t>(path_start));
        if (!path.ends_with("/libapp.so") || permissions[0] != 'r'
            || permissions[1] != '-' || permissions[2] != 'x' || inode == 0) continue;
        if (end <= begin || end > UINTPTR_MAX || begin % alignof(uint32_t) != 0
            || (end - begin) % sizeof(uint32_t) != 0 || end - begin > max_bytes - total) return {};
        total += static_cast<size_t>(end - begin);
        result.push_back({static_cast<uintptr_t>(begin),
            {reinterpret_cast<const uint32_t *>(static_cast<uintptr_t>(begin)),
                static_cast<size_t>(end - begin) / sizeof(uint32_t)}});
    }
    return result;
}
} // namespace dock_motion
