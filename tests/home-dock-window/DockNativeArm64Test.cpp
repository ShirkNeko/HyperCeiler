/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include <atomic>
#include <bit>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <sys/eventfd.h>
#include <unistd.h>
#include "../../app/src/main/cpp/dock_native_layout.h"

extern "C" {
void dock_motion_scale_entry();
void dock_motion_anim_entry();
void dock_motion_set_entry();
void dock_test_trampoline();
void dock_test_invoke(void (*)(), void *, uint64_t *);
alignas(8) std::atomic<uint64_t> dock_motion_value{0x3ff0000000000000ULL};
alignas(4) std::atomic<uint32_t> dock_motion_subscribed{0};
int dock_motion_event = -1;
extern const uint64_t dock_motion_one = 1;
void *dock_motion_scale_original = reinterpret_cast<void *>(dock_test_trampoline);
dock_motion::Layout dock_motion_layout{};
void *dock_motion_anim_original = reinterpret_cast<void *>(dock_test_trampoline);
void *dock_motion_set_original = reinterpret_cast<void *>(dock_test_trampoline);
}

struct alignas(16) Fixture {
    uint64_t header = uint64_t(1777) << 12;
    double alpha = 1;
    double scale_x = .95;
    double scale_y = .95;
    double damping = 1;
    double response = .5;
    uint32_t surface = 0x10000031;
    uint32_t recents = 0x10000021;
    uint32_t reason = 0;
    uint32_t anim = 0x10000021;
};
static_assert(sizeof(Fixture) == 64 && offsetof(Fixture, recents) == 0x34);

void invoke(void (*entry)(), void *tagged) {
    uint64_t registers[18]{};
    dock_test_invoke(entry, tagged, registers);
    for (int i = 0; i < 15; ++i) {
        const uint64_t expected = i == 2 ? reinterpret_cast<uint64_t>(tagged) : 100 + i;
        assert(registers[i] == expected);
    }
    assert(registers[15] == registers[17]);
    assert(registers[16] == 0xa0000000);
}
uint32_t scene() { return dock_motion_value.load() & 3; }
double scale() { return std::bit_cast<double>(dock_motion_value.load() & ~3ULL); }
template<class T> void invoke_fixture(void (*entry)(), T &fixture) {
    invoke(entry, reinterpret_cast<char *>(&fixture) + 1);
}
int main() {
    dock_motion_event = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    assert(dock_motion_event >= 0);
    dock_motion_layout = {1777, 62, offsetof(Fixture, alpha) - 1, offsetof(Fixture, scale_x) - 1,
        offsetof(Fixture, surface) - 1, offsetof(Fixture, recents) - 1, 7};
    for (uint32_t subscribed : {0u, 1u}) {
        dock_motion_subscribed.store(subscribed);
        Fixture value;
        const Fixture original = value;
        invoke_fixture(dock_motion_anim_entry, value);
        assert(scene() == 1);
        assert(std::memcmp(&original, &value, sizeof(value)) == 0);
        invoke_fixture(dock_motion_set_entry, value);
        assert(scene() == 1 && std::abs(scale() - .95) < 1e-12);
        struct alignas(16) { uint64_t header; double value; } boxed{62ULL << 12, .945};
        invoke_fixture(dock_motion_scale_entry, boxed);
        assert(boxed.header == 62ULL << 12);
        assert(scene() == 1 && std::abs(scale() - .945) < 1e-12);
        invoke_fixture(dock_motion_scale_entry, boxed); // unchanged fast path
        value.scale_x = 1;
        invoke_fixture(dock_motion_anim_entry, value);
        assert(scene() == 2); // Also clear recents on home's default-true flag.
        value.recents = 0x10000031;
        invoke_fixture(dock_motion_set_entry, value);
        assert(scene() == 2 && scale() == 1);
        value.scale_x = .8;
        value.recents = 0x10000021;
        invoke_fixture(dock_motion_anim_entry, value);
        assert(scene() == 0);
        value.scale_x = .95;
        value.alpha = 0;
        invoke_fixture(dock_motion_anim_entry, value);
        assert(scene() == 0);
        value.alpha = 1;
        value.surface = 0x10000021;
        invoke_fixture(dock_motion_anim_entry, value);
        assert(scene() == 0);
        value.header = 45ULL << 12;
        invoke_fixture(dock_motion_set_entry, value);
        assert(scene() == 0);
        invoke(dock_motion_set_entry, reinterpret_cast<void *>(2)); // Smi must not be dereferenced
        invoke(dock_motion_scale_entry, reinterpret_cast<void *>(2));
        eventfd_t notifications = 0;
        const auto received = eventfd_read(dock_motion_event, &notifications);
        if (subscribed) assert(received == 0 && notifications > 0);
        else assert(received == -1);
    }
    // A different CID and relocated payload must work without changing assembly.
    struct alignas(16) Moved {
        uint64_t header = 2011ULL << 12;
        uint64_t padding[2]{};
        double scale = .96;
        double alpha = 1;
        uint32_t recents = 0x10000021;
        uint32_t surface = 0x10000031;
    } moved;
    dock_motion_layout = {2011, 77, offsetof(Moved, alpha) - 1, offsetof(Moved, scale) - 1,
        offsetof(Moved, surface) - 1, offsetof(Moved, recents) - 1, 23};
    invoke_fixture(dock_motion_set_entry, moved);
    assert(moved.header == 2011ULL << 12);
    assert(scene() == 1 && std::abs(scale() - .96) < 1e-12);
    struct alignas(16) MovedDouble { uint64_t header = 77ULL << 12; uint64_t padding[2]{}; double value = .97; } boxed;
    invoke_fixture(dock_motion_scale_entry, boxed);
    assert(boxed.header == 77ULL << 12);
    assert(scene() == 1 && std::abs(scale() - .97) < 1e-12);
    close(dock_motion_event);
    puts("Dock ARM64 tests passed: registers, NZCV, Dart stack, scene guards, scalar writes, nonblocking notification");
}
