/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "dock_native_motion_profile.h"

#include <android/log.h>
#include <atomic>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <string_view>
#include <unistd.h>

bool prepare_dock_motion();
void run_dock_motion();
extern "C" {
void dock_motion_scale_entry();
void dock_motion_anim_entry();
void dock_motion_set_entry();
extern void *dock_motion_anim_original;
extern void *dock_motion_set_original;
void *dock_motion_scale_original = nullptr;
}

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

bool matches_build(const dl_phdr_info &info) {
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
                return dock_motion::build_matches(cursor + name_size, note.n_descsz);
            }
            cursor += name_size + desc_size;
            remaining -= name_size + desc_size;
        }
    }
    return false;
}

struct Candidate {
    void *target = nullptr;
    const char *rejection = nullptr;
    uintptr_t base = 0;
};

int inspect_library(dl_phdr_info *info, size_t, void *opaque) {
    if (info->dlpi_name == nullptr) return 0;
    const std::string_view path(info->dlpi_name);
    if (!(path == "libapp.so" || path.ends_with("/libapp.so"))) return 0;
    auto &candidate = *static_cast<Candidate *>(opaque);
    if (!matches_build(*info)) {
        candidate.rejection = "unknown Build ID";
        return 1;
    }
    if (!mapped(*info, dock_motion::kScaleOffset, sizeof(dock_motion::kScaleInstructions), PF_R | PF_X)) {
        candidate.rejection = "callback outside executable mapping";
        return 1;
    }
    auto *target = reinterpret_cast<void *>(info->dlpi_addr + dock_motion::kScaleOffset);
    if (!dock_motion::code_matches(target, sizeof(dock_motion::kScaleInstructions))) {
        candidate.rejection = "callback instructions changed";
        return 1;
    }
    for (const auto &region : dock_motion::kRegions) {
        if (!mapped(*info, region.offset, region.size, PF_R | PF_X)
            || !dock_motion::matches(region,
                reinterpret_cast<void *>(info->dlpi_addr + region.offset), region.size)) {
            candidate.rejection = "scene instructions changed";
            return 1;
        }
    }
    candidate.target = target;
    candidate.base = info->dlpi_addr;
    return 1;
}

void *motion_worker(void *) {
    Candidate candidate;
    for (int attempt = 0; attempt < 30; ++attempt) {
        dl_iterate_phdr(inspect_library, &candidate);
        if (candidate.target != nullptr || candidate.rejection != nullptr) break;
        usleep(500000);
    }
    if (candidate.target == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "motion not installed: %s",
            candidate.rejection != nullptr ? candidate.rejection : "libapp not visible within 15s");
        return nullptr;
    }
    // Install only after all guards pass, outside the dynamic linker callback.
    if (!prepare_dock_motion()
        || hook_function(reinterpret_cast<void *>(candidate.base + dock_motion::kRegions[0].offset),
            reinterpret_cast<void *>(dock_motion_anim_entry), &dock_motion_anim_original) != 0
        || hook_function(reinterpret_cast<void *>(candidate.base + dock_motion::kRegions[1].offset),
            reinterpret_cast<void *>(dock_motion_set_entry), &dock_motion_set_original) != 0
        || hook_function(candidate.target, reinterpret_cast<void *>(dock_motion_scale_entry),
            &dock_motion_scale_original) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion hooks unavailable; no subscription enabled");
        return nullptr;
    }
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
