/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "../../app/src/main/cpp/dock_native_maps.h"
#include <cassert>
#include <iostream>
#include <sstream>

int main() {
    std::istringstream maps(
        "71000000-71001000 r-xp 00001000 08:01 42 /data/app/example/lib/arm64/libapp.so\n"
        "71001000-71002000 rw-p 00002000 08:01 42 /data/app/example/lib/arm64/libapp.so\n"
        "72000000-72001000 r-xp 00000000 00:00 0 [anon:executable]\n"
        "73000000-73001000 r-xp 00001000 08:01 43 /data/app/example/lib/arm64/other.so\n"
        "74000000-74001000 r-xp 00001000 08:01 44 /data/app/example/lib/arm64/libapp.so (deleted)\n");
    const auto ranges = dock_motion::mapped_code_ranges(maps);
    assert(ranges.size() == 1 && ranges[0].address == 0x71000000);
    assert(ranges[0].words.size() == 1024);
    std::istringstream relocated("81004000-81005000 r-xp 00001000 08:01 42 /x/libapp.so\n");
    assert(dock_motion::mapped_code_ranges(relocated)[0].address == 0x81004000);
    std::istringstream invalid("1-40001000 r-xp 0 08:01 42 /x/libapp.so\n");
    assert(dock_motion::mapped_code_ranges(invalid).empty());
    std::cout << "Dynamic mapping discovery tests passed\n";
}
