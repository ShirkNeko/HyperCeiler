/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "dock_native_probe_profile.h"

#include <android/log.h>
#include <atomic>
#include <bit>
#include <cmath>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <string_view>
#include <unistd.h>

#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
#include "dock_native_motion_profile.h"
bool prepare_dock_motion();
void run_dock_motion();
extern "C" {
void dock_motion_scale_entry();
void dock_motion_anim_entry();
void dock_motion_set_entry();
extern void *dock_motion_anim_original;
extern void *dock_motion_set_original;
}
#endif

extern "C" {
alignas(8) std::atomic<uint64_t> dock_probe_scale_bits{0};
alignas(8) std::atomic<uint64_t> dock_probe_samples{0};
alignas(4) std::atomic<uint32_t> dock_probe_active{0};
void *dock_probe_original = nullptr;
void dock_probe_scale_entry();
}
static_assert(std::atomic<uint64_t>::is_always_lock_free && sizeof(std::atomic<uint64_t>) == 8);
static_assert(std::atomic<uint32_t>::is_always_lock_free && sizeof(std::atomic<uint32_t>) == 4);

namespace {
constexpr char kTag[] = "HyperCeiler.DockNative";
using Hook = int (*)(void *, void *, void **);
Hook hook_function = nullptr;
std::atomic_bool started{false};

bool mapped(const dl_phdr_info &info, uintptr_t offset, size_t length, uint32_t flags) {
    for (ElfW(Half) i = 0; i < info.dlpi_phnum; ++i) {
        const auto &ph = info.dlpi_phdr[i];
        if (ph.p_type == PT_LOAD && (ph.p_flags & flags) == flags && offset >= ph.p_vaddr
            && offset - ph.p_vaddr <= ph.p_memsz && length <= ph.p_memsz - (offset - ph.p_vaddr)) return true;
    }
    return false;
}

const dock_probe::Profile *find_profile(const dl_phdr_info &info) {
    for (ElfW(Half) i = 0; i < info.dlpi_phnum; ++i) {
        const auto &ph = info.dlpi_phdr[i];
        if (ph.p_type != PT_NOTE || !mapped(info, ph.p_vaddr, ph.p_memsz, PF_R)) continue;
        const auto *cursor = reinterpret_cast<const uint8_t *>(info.dlpi_addr + ph.p_vaddr);
        size_t remaining = ph.p_memsz;
        while (remaining >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note{};
            std::memcpy(&note, cursor, sizeof(note));
            cursor += sizeof(note);
            remaining -= sizeof(note);
            // Bound padding arithmetic and reject unexpected oversized records.
            if (note.n_namesz > 128 || note.n_descsz > 128) break;
            const size_t name_size = (note.n_namesz + 3u) & ~size_t(3);
            const size_t desc_size = (note.n_descsz + 3u) & ~size_t(3);
            if (name_size > remaining || desc_size > remaining - name_size) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                && std::memcmp(cursor, "GNU", 4) == 0) {
                return dock_probe::profile_for_build(cursor + name_size, note.n_descsz);
            }
            cursor += name_size + desc_size;
            remaining -= name_size + desc_size;
        }
    }
    return nullptr;
}

struct Candidate {
    void *target = nullptr;
    const dock_probe::Profile *profile = nullptr;
    const char *rejection = nullptr;
    uintptr_t base = 0;
    bool motion = false;
};

int inspect_library(dl_phdr_info *info, size_t, void *opaque) {
    if (info->dlpi_name == nullptr) return 0;
    const std::string_view path(info->dlpi_name);
    if (!(path == "libapp.so" || path.ends_with("/libapp.so"))) return 0;
    auto &candidate = *static_cast<Candidate *>(opaque);
    candidate.profile = find_profile(*info);
    if (candidate.profile == nullptr) {
        candidate.rejection = "unknown Build ID";
        return 1;
    }
    const auto &profile = *candidate.profile;
    if (!mapped(*info, profile.scale_offset, sizeof(profile.instructions), PF_R | PF_X)) {
        candidate.rejection = "callback outside executable mapping";
        return 1;
    }
    auto *target = reinterpret_cast<void *>(info->dlpi_addr + profile.scale_offset);
    if (!dock_probe::code_matches(profile, target, sizeof(profile.instructions))) {
        candidate.rejection = "callback instructions changed";
        return 1;
    }
    candidate.target = target;
#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
    if (std::strcmp(profile.version, "6236") == 0) {
        candidate.motion = true;
        candidate.base = info->dlpi_addr;
        for (const auto &region : dock_motion::kRegions) {
            if (!mapped(*info, region.offset, region.size, PF_R | PF_X)
                || !dock_motion::matches(region,
                    reinterpret_cast<void *>(info->dlpi_addr + region.offset), region.size)) {
                candidate.motion = false;
                break;
            }
        }
    }
#endif
    return 1;
}

void *probe_worker(void *) {
    Candidate candidate;
    for (int attempt = 0; attempt < 30; ++attempt) {
        dl_iterate_phdr(inspect_library, &candidate);
        if (candidate.target != nullptr || candidate.rejection != nullptr) break;
        usleep(500000);
    }
    if (candidate.target == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "probe not installed: %s",
            candidate.rejection != nullptr ? candidate.rejection : "libapp not visible within 15s");
        return nullptr;
    }
#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
    if (candidate.motion) {
        if (!prepare_dock_motion()
            || hook_function(reinterpret_cast<void *>(candidate.base + dock_motion::kRegions[0].offset),
                reinterpret_cast<void *>(dock_motion_anim_entry), &dock_motion_anim_original) != 0
            || hook_function(reinterpret_cast<void *>(candidate.base + dock_motion::kRegions[1].offset),
                reinterpret_cast<void *>(dock_motion_set_entry), &dock_motion_set_original) != 0
            || hook_function(candidate.target, reinterpret_cast<void *>(dock_motion_scale_entry),
                &dock_probe_original) != 0) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "motion hooks unavailable; no subscription enabled");
            return nullptr;
        }
        run_dock_motion();
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "motion not installed: unverified scene profile");
#endif
#ifndef HYPERCEILER_DOCK_NATIVE_PROBE
    return nullptr;
#else
    // Install outside the dynamic linker's enumeration callback. The hook API publishes
    // its relocated trampoline before making the replacement callable.
    if (hook_function(candidate.target, reinterpret_cast<void *>(dock_probe_scale_entry),
            &dock_probe_original) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "probe hook installation failed");
        return nullptr;
    }
    dock_probe_active.store(1, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "probe v2 ready: launcher=%s; observing for 180s; no Dock changes", candidate.profile->version);
    uint64_t previous = 0;
    unsigned reported = 0;
    for (int interval = 0; interval < 360; ++interval) {
        usleep(500000);
        const auto count = dock_probe_samples.load(std::memory_order_acquire);
        if (count == previous) continue;
        const double scale = std::bit_cast<double>(dock_probe_scale_bits.load(std::memory_order_acquire));
        if (reported++ < 60) {
            if (std::isfinite(scale) && scale >= 0 && scale <= 2) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "scale callback samples=%llu delta=%llu latest=%.7f",
                    static_cast<unsigned long long>(count), static_cast<unsigned long long>(count - previous), scale);
            } else __android_log_print(ANDROID_LOG_WARN, kTag, "scale callback value rejected");
        }
        previous = count;
    }
    dock_probe_active.store(0, std::memory_order_release);
    // Do not race a live Dart stack by removing its trampoline. The disabled observer
    // retains no pointers/IPC and delegates immediately until the process exits.
    __android_log_print(ANDROID_LOG_INFO, kTag, "probe observation ended samples=%llu",
        static_cast<unsigned long long>(dock_probe_samples.load(std::memory_order_acquire)));
    return nullptr;
#endif
}
} // namespace

void start_dock_native_probe(Hook hook) {
    if (hook == nullptr || started.exchange(true)) return;
    hook_function = hook;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, probe_worker, nullptr) == 0) pthread_detach(thread);
    else __android_log_print(ANDROID_LOG_WARN, kTag, "probe worker unavailable");
}
