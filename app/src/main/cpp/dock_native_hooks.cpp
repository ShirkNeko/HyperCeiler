/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "dock_native_layout.h"
#include "dock_native_maps.h"
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <elf.h>
#include <exception>
#include <fstream>
#include <link.h>
#include <pthread.h>
#include <string_view>
#include <time.h>

bool prepare_dock_motion();
void run_dock_motion();
extern "C" {
// Published before hook installation and never mutated once callbacks can run.
dock_motion::Layout dock_motion_layout{};
void dock_motion_scale_entry();
void dock_motion_anim_entry();
void dock_motion_set_entry();
void dock_edit_entry();
extern void *dock_motion_scale_original;
extern void *dock_motion_anim_original;
extern void *dock_motion_set_original;
extern void *dock_edit_original;
}

namespace {
constexpr char kTag[] = "HyperCeiler.DockNative";
using Hook = int (*)(void *, void *, void **);
Hook hook_function = nullptr;
std::atomic_bool started{false};

struct Candidate {
    bool found = false;
    std::optional<dock_motion::Resolution> resolution;
};

std::vector<dock_motion::CodeRange> executable_ranges(const dl_phdr_info &info) {
    std::vector<dock_motion::CodeRange> ranges;
    size_t total = 0;
    constexpr size_t max_code_bytes = 256 * 1024 * 1024;
    for (ElfW(Half) i = 0; i < info.dlpi_phnum; ++i) {
        const auto &ph = info.dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || (ph.p_flags & (PF_R | PF_X)) != (PF_R | PF_X)) continue;
        if (ph.p_memsz > max_code_bytes - total || ph.p_vaddr > UINTPTR_MAX - info.dlpi_addr) return {};
        const auto address = info.dlpi_addr + ph.p_vaddr;
        if (address % alignof(uint32_t) != 0 || ph.p_memsz > UINTPTR_MAX - address) return {};
        total += ph.p_memsz;
        ranges.push_back({address, {reinterpret_cast<const uint32_t *>(address), ph.p_memsz / 4}});
    }
    return ranges;
}

int inspect_library(dl_phdr_info *info, size_t, void *opaque) {
    if (info->dlpi_name == nullptr) return 0;
    const std::string_view path(info->dlpi_name);
    if (!(path == "libapp.so" || path.ends_with("/libapp.so"))) return 0;
    auto &candidate = *static_cast<Candidate *>(opaque);
    candidate.found = true;
    // No exceptions may unwind through the linker's C callback.
    try {
        candidate.resolution = dock_motion::resolve(executable_ranges(*info));
    } catch (const std::exception &) {
        candidate.resolution.reset();
    }
    return 1;
}

void pause_startup() {
    timespec remaining{0, 500000000};
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {}
}

void *motion_worker(void *) {
    Candidate candidate;
    for (int attempt = 0; attempt < 30; ++attempt) {
        dl_iterate_phdr(inspect_library, &candidate);
        if (!candidate.resolution) {
            std::ifstream maps("/proc/self/maps");
            try {
                const auto ranges = dock_motion::mapped_code_ranges(maps);
                if (!ranges.empty()) {
                    candidate.found = true;
                    candidate.resolution = dock_motion::resolve(ranges);
                }
            } catch (const std::exception &) {
                candidate.resolution.reset();
            }
        }
        if (candidate.resolution) break;
        pause_startup();
    }
    if (!candidate.resolution) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "native motion unavailable: %s; keeping scene fallback",
            candidate.found ? "unrecognized or ambiguous instruction structure" : "libapp not visible within 15s");
        return nullptr;
    }
    const auto &resolved = *candidate.resolution;
    dock_motion_layout = resolved.layout;
    // Install outside dl_iterate_phdr. The hook API publishes each trampoline
    // before its replacement becomes callable. No subscribers until ALL succeed.
    if (!prepare_dock_motion()
        || hook_function(reinterpret_cast<void *>(resolved.animate),
            reinterpret_cast<void *>(dock_motion_anim_entry), &dock_motion_anim_original) != 0
        || hook_function(reinterpret_cast<void *>(resolved.set),
            reinterpret_cast<void *>(dock_motion_set_entry), &dock_motion_set_original) != 0
        || hook_function(reinterpret_cast<void *>(resolved.scale),
            reinterpret_cast<void *>(dock_motion_scale_entry), &dock_motion_scale_original) != 0
        || hook_function(reinterpret_cast<void *>(resolved.edit),
            reinterpret_cast<void *>(dock_edit_entry), &dock_edit_original) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion hooks unavailable; no subscription enabled");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "dynamic motion v20 resolved: paramsCID=%u doubleCID=%u edit=dynamic",
        resolved.layout.params_class_id, resolved.layout.double_class_id);
    run_dock_motion();
    return nullptr;
}
} // namespace

void start_dock_native_motion(Hook hook) {
    if (hook == nullptr || started.exchange(true)) return;
    hook_function = hook;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, motion_worker, nullptr) == 0) pthread_detach(thread);
    else __android_log_print(ANDROID_LOG_WARN, kTag, "motion worker unavailable");
}
