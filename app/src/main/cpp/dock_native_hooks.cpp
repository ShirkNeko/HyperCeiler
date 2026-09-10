/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include "dock_native_layout.h"
#include "dock_native_resolver.h"
#include "dock_native_runtime.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <fstream>
#include <fcntl.h>
#include <limits>
#include <mutex>
#include <optional>
#include <pthread.h>
#include <span>
#include <string>
#include <sys/mman.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <utility>
#include <vector>

bool prepare_dock_motion();
void activate_dock_motion();
void deactivate_dock_motion();
void run_dock_motion();

extern "C" {
#define DEFINE_DOCK_MOTION_BANK(bank) \
    uint32_t dock_params_class_id_##bank = 0; \
    uint32_t dock_double_class_id_##bank = 0; \
    int32_t dock_tagged_header_offset_##bank = 0; \
    uint32_t dock_class_id_shift_##bank = 0; \
    uint32_t dock_class_id_mask_##bank = 0; \
    uint32_t dock_alpha_offset_##bank = 0; \
    uint32_t dock_scale_offset_##bank = 0; \
    uint32_t dock_surface_offset_##bank = 0; \
    uint32_t dock_recents_offset_##bank = 0; \
    uint32_t dock_double_value_offset_##bank = 0; \
    uint32_t dock_false_from_null_##bank = 0; \
    void *dock_motion_scale_original_##bank = nullptr; \
    void *dock_motion_anim_original_##bank = nullptr; \
    void *dock_motion_set_original_##bank = nullptr;
DOCK_MOTION_BANKS(DEFINE_DOCK_MOTION_BANK)
#undef DEFINE_DOCK_MOTION_BANK

// Published by the replacement trampolines in dock_native_motion.cpp. The health
// worker reads them so a silent hook loss is visible in the log instead of looking
// exactly like an idle desktop.
extern std::atomic<uint64_t> dock_motion_entry_hits;
extern std::atomic<uint64_t> dock_motion_publish_hits;
extern std::atomic<uint64_t> dock_motion_active_callbacks;
extern std::atomic<uint32_t> dock_motion_subscribed;
}

namespace {
constexpr char kTag[] = "HyperCeiler.DockNative";
constexpr size_t kTargetCount = 3;
constexpr size_t kPatchBytes = 4 * sizeof(uint32_t);
constexpr unsigned kInventorySettlingScans = 3;
constexpr unsigned kScanCooldownChecks = 4;
using Hook = int (*)(void *, void *, void **);
using Unhook = int (*)(void *);
using PatchWords = std::array<uint32_t, kPatchBytes / sizeof(uint32_t)>;
using TargetAddresses = std::array<uintptr_t, kTargetCount>;
using TargetSources = std::array<dock_motion::CodeSource, kTargetCount>;

Hook hook_function = nullptr;
std::atomic_bool started{false};
std::atomic_bool worker_alive{false};
std::atomic_bool runtime_ready{false};
std::mutex hook_mutex;
std::vector<dock_motion::ExecutableMapping> last_inventory;
unsigned settling_scans = 0;
unsigned scan_cooldown = 0;
bool capacity_reported = false;

struct ResolvedInstance {
    dock_motion::Resolution resolution;
    std::vector<dock_motion::ExecutableMapping> mappings;
    TargetSources sources;
    std::array<PatchWords, kTargetCount> original_words;
};

struct HookSlot {
    uintptr_t address;
    void *replacement;
    void **original;
    dock_motion::CodeSource source;
    PatchWords original_words;
    PatchWords patch_words{};
    bool registered = false;
    bool patch_known = false;
};

struct HookBank {
    size_t index;
    dock_motion::Resolution resolution;
    std::vector<dock_motion::ExecutableMapping> mappings;
    std::array<HookSlot, kTargetCount> slots;
};

std::vector<HookBank> hook_banks;

struct BankSymbols {
    uint32_t *params_class_id;
    uint32_t *double_class_id;
    int32_t *tagged_header_offset;
    uint32_t *class_id_shift;
    uint32_t *class_id_mask;
    uint32_t *alpha_offset;
    uint32_t *scale_offset;
    uint32_t *surface_offset;
    uint32_t *recents_offset;
    uint32_t *double_value_offset;
    uint32_t *false_from_null;
    std::array<void *, kTargetCount> replacements;
    std::array<void **, kTargetCount> originals;
};

#define DOCK_BANK_SYMBOLS(bank) BankSymbols{ \
    &dock_params_class_id_##bank, &dock_double_class_id_##bank, \
    &dock_tagged_header_offset_##bank, &dock_class_id_shift_##bank, \
    &dock_class_id_mask_##bank, &dock_alpha_offset_##bank, \
    &dock_scale_offset_##bank, &dock_surface_offset_##bank, \
    &dock_recents_offset_##bank, &dock_double_value_offset_##bank, \
    &dock_false_from_null_##bank, \
    {reinterpret_cast<void *>(dock_motion_scale_entry_##bank), \
     reinterpret_cast<void *>(dock_motion_anim_entry_##bank), \
     reinterpret_cast<void *>(dock_motion_set_entry_##bank)}, \
    {&dock_motion_scale_original_##bank, &dock_motion_anim_original_##bank, \
     &dock_motion_set_original_##bank}}

const std::array<BankSymbols, 16> kBankSymbols{{
    DOCK_BANK_SYMBOLS(0), DOCK_BANK_SYMBOLS(1), DOCK_BANK_SYMBOLS(2),
    DOCK_BANK_SYMBOLS(3), DOCK_BANK_SYMBOLS(4), DOCK_BANK_SYMBOLS(5),
    DOCK_BANK_SYMBOLS(6), DOCK_BANK_SYMBOLS(7),
    DOCK_BANK_SYMBOLS(8), DOCK_BANK_SYMBOLS(9), DOCK_BANK_SYMBOLS(10),
    DOCK_BANK_SYMBOLS(11), DOCK_BANK_SYMBOLS(12), DOCK_BANK_SYMBOLS(13),
    DOCK_BANK_SYMBOLS(14), DOCK_BANK_SYMBOLS(15),
}};
#undef DOCK_BANK_SYMBOLS

TargetAddresses addresses(const dock_motion::Resolution &resolution) {
    return {resolution.scale, resolution.animate, resolution.set};
}

TargetAddresses addresses(const HookBank &bank) {
    TargetAddresses result{};
    for (size_t i = 0; i < result.size(); ++i) result[i] = bank.slots[i].address;
    return result;
}

TargetSources sources(const HookBank &bank) {
    TargetSources result{};
    for (size_t i = 0; i < result.size(); ++i) result[i] = bank.slots[i].source;
    return result;
}

bool safe_read(uintptr_t address, std::span<std::byte> destination) {
    if (destination.empty() || address > UINTPTR_MAX - destination.size()) return false;
    size_t completed = 0;
    while (completed < destination.size()) {
        iovec local{destination.data() + completed, destination.size() - completed};
        iovec remote{reinterpret_cast<void *>(address + completed), destination.size() - completed};
        const ssize_t count = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            // Some Android process policies reject process_vm_readv even for self.
            // pread remains fault-reporting: never replace this with an unchecked
            // memcpy from a runtime mapping which may disappear concurrently.
            const int memory = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
            if (memory < 0) return false;
            bool success = true;
            while (completed < destination.size()) {
                const uintptr_t position = address + completed;
                if (position > static_cast<uintptr_t>(std::numeric_limits<off_t>::max())) {
                    success = false;
                    break;
                }
                const ssize_t copied = pread(memory, destination.data() + completed,
                    destination.size() - completed, static_cast<off_t>(position));
                if (copied < 0 && errno == EINTR) continue;
                if (copied <= 0) { success = false; break; }
                completed += static_cast<size_t>(copied);
            }
            close(memory);
            return success;
        }
        if (static_cast<size_t>(count) > destination.size() - completed) return false;
        completed += static_cast<size_t>(count);
    }
    return true;
}

bool mapping_subset(const std::vector<dock_motion::ExecutableMapping> &subset,
    const std::vector<dock_motion::ExecutableMapping> &set) {
    return std::ranges::all_of(subset, [&](const auto &mapping) {
        return std::ranges::find(set, mapping) != set.end();
    });
}

// ---------------------------------------------------------------------------
// AOT image discovery
//
// HYOS does not extract its launcher libraries: `libapp.so` lives uncompressed
// and page aligned inside the application APK, so the extracted library path does
// not exist and `/proc/self/maps` reports the APK path for every library that
// shares it. A name test therefore finds nothing at all, and a naive "any
// executable mapping of the APK" test would splice several unrelated libraries
// into one inventory.
//
// Ownership is recovered in three bounded layers:
//   1. a bare `libapp.so` mapping (older packaging, or an extracted image);
//   2. the stored `lib/<abi>/libapp.so` entry of a ZIP container, resolved from
//      the central directory and rebased onto the same page granularity the
//      kernel used, so the executable ranges of one image can be attributed;
//   3. a page-aligned ELF header probe, kept as a fallback for a container that
//      is not a readable ZIP. That layer cannot tell sibling images apart, so it
//      is used only when layers 1 and 2 produced nothing; the resolver's
//      unique-match requirement still fails closed on a mixed inventory.
// ---------------------------------------------------------------------------

/** Discovery layers, recorded per file so each probe runs at most once. */
struct ContainerProbe {
    ContainerProbe(std::string path_value, uint64_t inode_value)
        : path(std::move(path_value)), inode(inode_value) {}

    std::string path;
    uint64_t inode = 0;
    bool zip_done = false;
    bool embedded_done = false;
    std::optional<dock_motion::LibappContainer> zip;
    std::optional<dock_motion::LibappContainer> embedded;
};

constexpr size_t kContainerProbesPerScan = 8;
constexpr size_t kContainerProbeLimit = 2048;
constexpr uint64_t kContainerMinBytes = 64U * 1024U;
constexpr size_t kElfProbeStride = 4096;
constexpr unsigned kElfProbeSlots = 4;
constexpr size_t kElfProbeBytes = 64 + 128 * 56;

std::vector<ContainerProbe> container_probes;

uint64_t host_page_size() {
    const long page = sysconf(_SC_PAGESIZE);
    return page > 0 ? static_cast<uint64_t>(page) : 4096;
}

uint64_t page_down(uint64_t value) {
    const uint64_t page = host_page_size();
    return value - value % page;
}

uint64_t page_up(uint64_t value) {
    const uint64_t page = host_page_size();
    const uint64_t remainder = value % page;
    return remainder == 0 ? value : value + (page - remainder);
}

/** Slot lookup by index: probing appends, so references would not stay valid. */
size_t probe_slot(const std::string &path, uint64_t inode) {
    for (size_t index = 0; index < container_probes.size(); ++index) {
        const auto &probe = container_probes[index];
        if (probe.path == path && probe.inode == inode) return index;
    }
    if (container_probes.size() >= kContainerProbeLimit) return container_probes.size();
    container_probes.push_back(ContainerProbe{path, inode});
    return container_probes.size() - 1;
}

/**
 * Fallback discovery for a container that is not a readable ZIP: find a page
 * aligned ELF header in a readable, non-writable mapping and bound the image from
 * its own segment table.
 */
std::optional<dock_motion::LibappContainer> probe_embedded_image(
    const dock_motion::FileMapping &mapping) {
    const uint64_t length = mapping.end - mapping.begin;
    for (unsigned slot = 0; slot < kElfProbeSlots; ++slot) {
        const uint64_t displacement = slot * kElfProbeStride;
        if (displacement >= length) break;
        const size_t wanted = static_cast<size_t>(
            std::min<uint64_t>(kElfProbeBytes, length - displacement));
        if (wanted < 64) break;
        std::vector<std::byte> header(wanted);
        if (!safe_read(mapping.begin + displacement, std::span(header))) continue;
        const auto image = dock_motion::parse_embedded_image(
            std::span(header), mapping.file_offset + displacement);
        if (!image) continue;
        return dock_motion::LibappContainer{
            mapping.path, image->view_begin, image->view_end};
    }
    return std::nullopt;
}

bool container_is_new(const std::vector<dock_motion::LibappContainer> &result,
    const dock_motion::LibappContainer &candidate) {
    return std::ranges::none_of(result, [&](const auto &known) {
        return known.path == candidate.path && known.view_begin == candidate.view_begin
            && known.view_end == candidate.view_end;
    });
}

void append_container(std::vector<dock_motion::LibappContainer> &result,
    const std::optional<dock_motion::LibappContainer> &candidate) {
    if (candidate && container_is_new(result, *candidate)) result.push_back(*candidate);
}

/** Candidates ordered by mapped bytes so a bounded budget still reaches the AOT image. */
std::vector<std::pair<std::string, uint64_t>> probe_candidates(
    const std::vector<dock_motion::FileMapping> &entries, bool embedded_layer) {
    struct Candidate {
        uint64_t inode = 0;
        uint64_t bytes = 0;
        std::string path;
    };
    std::vector<Candidate> candidates;
    for (const auto &entry : entries) {
        if (entry.inode == 0 || entry.writable) continue;
        const std::string_view path = dock_motion::strip_deleted(entry.path);
        if (path.empty() || path.front() != '/') continue;
        const std::string owned(path);
        const auto found = std::ranges::find_if(candidates, [&](const Candidate &candidate) {
            return candidate.inode == entry.inode || candidate.path == owned;
        });
        const uint64_t bytes = entry.end - entry.begin;
        if (found == candidates.end()) candidates.push_back({entry.inode, bytes, owned});
        else found->bytes += bytes;
    }
    std::ranges::sort(candidates, [](const Candidate &left, const Candidate &right) {
        return left.bytes > right.bytes;
    });
    std::vector<std::pair<std::string, uint64_t>> result;
    size_t budget = kContainerProbesPerScan;
    for (const auto &candidate : candidates) {
        if (candidate.bytes < kContainerMinBytes || budget == 0) break;
        const size_t slot = probe_slot(candidate.path, candidate.inode);
        if (slot >= container_probes.size()) break;
        const bool done = embedded_layer
            ? container_probes[slot].embedded_done : container_probes[slot].zip_done;
        if (done) continue;
        --budget;
        result.emplace_back(candidate.path, candidate.inode);
    }
    return result;
}

/** One-shot report per distinct signature, bounded so a log cannot grow forever. */
bool claim_report(const std::string &signature) {
    constexpr size_t kMaxReports = 96;
    static std::mutex mutex;
    static std::vector<std::string> reported;
    std::lock_guard lock(mutex);
    if (std::ranges::find(reported, signature) != reported.end()) return false;
    if (reported.size() >= kMaxReports) return false;
    reported.push_back(signature);
    return true;
}

/** Log the inventory once per distinct container set so a failure is diagnosable. */
void report_containers(const std::vector<dock_motion::LibappContainer> &containers,
    const char *source, size_t entries) {
    std::string signature = source;
    for (const auto &container : containers) {
        signature += '|' + container.path + ':' + std::to_string(container.view_begin)
            + ':' + std::to_string(container.view_end);
    }
    if (!claim_report(signature)) return;
    for (const auto &container : containers) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "motion container source=%s entries=%zu path=%s view=0x%llx bytes=0x%llx",
            source, entries, container.path.c_str(),
            static_cast<unsigned long long>(container.view_begin),
            static_cast<unsigned long long>(container.view_end - container.view_begin));
    }
    if (containers.empty()) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
            "motion container absent: no AOT image located across %zu file mappings",
            entries);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "motion container ready source=%s count=%zu", source, containers.size());
}

/**
 * Log the container view once per distinct outcome, next to the file mappings that
 * actually back it and the executable ranges selected from them. A container that
 * is discovered but selects nothing looks identical to no container at all
 * from the outside, and that pair of facts is what tells the two apart.
 */
void report_image(const std::vector<dock_motion::LibappContainer> &containers,
    const std::vector<dock_motion::FileMapping> &entries,
    const std::vector<dock_motion::ExecutableMapping> &mappings) {
    std::string signature = "image";
    for (const auto &container : containers) {
        signature += '|' + container.path + ':' + std::to_string(container.view_begin)
            + ':' + std::to_string(container.view_end);
    }
    signature += '#';
    for (const auto &mapping : mappings) {
        signature += std::to_string(mapping.begin) + ':' + std::to_string(mapping.file_offset)
            + ',';
    }
    if (!claim_report(signature)) return;

    constexpr size_t kMaxContainers = 3;
    constexpr size_t kMaxEntriesPerContainer = 8;
    for (size_t index = 0; index < containers.size() && index < kMaxContainers; ++index) {
        const auto &container = containers[index];
        size_t shown = 0;
        for (const auto &entry : entries) {
            if (shown >= kMaxEntriesPerContainer) break;
            if (dock_motion::strip_deleted(entry.path) != container.path) continue;
            ++shown;
            __android_log_print(ANDROID_LOG_INFO, kTag,
                "motion image map path=%s off=0x%llx bytes=0x%llx exec=%d write=%d in_view=%d",
                container.path.c_str(),
                static_cast<unsigned long long>(entry.file_offset),
                static_cast<unsigned long long>(entry.end - entry.begin),
                entry.executable ? 1 : 0, entry.writable ? 1 : 0,
                (entry.file_offset >= container.view_begin
                    && entry.file_offset < container.view_end) ? 1 : 0);
        }
    }
    for (const auto &mapping : mappings) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "motion image owner begin=0x%llx end=0x%llx offset=0x%llx inode=%llu",
            static_cast<unsigned long long>(mapping.begin),
            static_cast<unsigned long long>(mapping.end),
            static_cast<unsigned long long>(mapping.file_offset),
            static_cast<unsigned long long>(mapping.inode));
    }
}

/** Resolve the AOT image's container, recording each layer so it runs at most once. */
bool collect_containers(const std::vector<dock_motion::FileMapping> &entries,
    std::vector<dock_motion::LibappContainer> &containers, const char *&source) {
    // Layer 1: the AOT image is its own file.
    for (const auto &entry : entries) {
        const std::string_view path = dock_motion::strip_deleted(entry.path);
        if (!dock_motion::libapp_path(path)) continue;
        append_container(containers,
            dock_motion::LibappContainer{std::string(path), 0, UINT64_MAX});
    }
    if (!containers.empty()) {
        source = "bare";
        return true;
    }

    // Layer 2: the AOT image is a stored entry of an application container.
    for (const auto &probe : container_probes) {
        if (probe.zip_done) append_container(containers, probe.zip);
    }
    if (containers.empty()) {
        for (const auto &candidate : probe_candidates(entries, false)) {
            const size_t slot = probe_slot(candidate.first, candidate.second);
            if (slot >= container_probes.size()) break;
            auto &probe = container_probes[slot];
            probe.zip_done = true;
            const auto entry = dock_motion::zip_stored_libapp(candidate.first);
            if (!entry) continue;
            probe.zip = dock_motion::LibappContainer{candidate.first,
                page_down(entry->first), page_up(entry->first + entry->second)};
        }
        for (const auto &probe : container_probes) {
            if (probe.zip_done) append_container(containers, probe.zip);
        }
    }
    if (!containers.empty()) {
        source = "zip";
        return true;
    }

    // Layer 3: container readable but not a ZIP; probe embedded ELF headers.
    for (const auto &probe : container_probes) {
        if (probe.embedded_done) append_container(containers, probe.embedded);
    }
    if (containers.empty()) {
        for (const auto &candidate : probe_candidates(entries, true)) {
            const size_t slot = probe_slot(candidate.first, candidate.second);
            if (slot >= container_probes.size()) break;
            auto &probe = container_probes[slot];
            probe.embedded_done = true;
            for (const auto &entry : entries) {
                if (entry.inode != candidate.second || entry.writable) continue;
                if (dock_motion::strip_deleted(entry.path) != candidate.first) continue;
                auto found = probe_embedded_image(entry);
                if (!found) continue;
                found->path = candidate.first;
                probe.embedded = found;
                break;
            }
        }
        for (const auto &probe : container_probes) {
            if (probe.embedded_done) append_container(containers, probe.embedded);
        }
    }
    source = containers.empty() ? "none" : "embedded";
    return !containers.empty();
}

std::optional<std::vector<dock_motion::ExecutableMapping>> current_mappings() {
    std::ifstream maps("/proc/self/maps");
    if (!maps) return {};
    const auto entries = dock_motion::parse_file_mappings(maps);

    std::vector<dock_motion::LibappContainer> containers;
    const char *source = "none";
    collect_containers(entries, containers, source);
    report_containers(containers, source, entries.size());
    auto mappings = dock_motion::executable_libapp_mappings(entries, containers);
    report_image(containers, entries, mappings);
    return mappings;
}

/** A silent resolver looks like a working one; report the first failing stage. */
void report_resolution(const std::vector<dock_motion::ExecutableMapping> &mappings,
    const char *stage) {
    std::string signature = std::string("stage=") + stage;
    uint64_t bytes = 0;
    for (const auto &mapping : mappings) {
        signature += '|' + std::to_string(mapping.begin) + ':' + std::to_string(mapping.file_offset);
        bytes += mapping.end - mapping.begin;
    }
    if (!claim_report(signature)) return;
    __android_log_print(ANDROID_LOG_WARN, kTag,
        "motion resolve stalled stage=%s ranges=%zu bytes=0x%llx", stage, mappings.size(),
        static_cast<unsigned long long>(bytes));
}
bool stable_read(const TargetAddresses &locations, const TargetSources &expected,
    std::array<PatchWords, kTargetCount> &words) {
    const auto before = current_mappings();
    if (!before || dock_motion::mapping_state(*before, locations, expected, kPatchBytes)
            != dock_motion::MappingState::same) return false;
    for (size_t i = 0; i < locations.size(); ++i) {
        if (!safe_read(locations[i], std::as_writable_bytes(std::span(&words[i], 1)))) return false;
    }
    const auto after = current_mappings();
    return after && dock_motion::mapping_state(*after, locations, expected, kPatchBytes)
            == dock_motion::MappingState::same;
}

bool stable_read(const HookSlot &slot, PatchWords &words) {
    const std::array<uintptr_t, 1> location{slot.address};
    const std::array<dock_motion::CodeSource, 1> expected{slot.source};
    const auto before = current_mappings();
    if (!before || dock_motion::mapping_state(*before, location, expected, kPatchBytes)
            != dock_motion::MappingState::same) return false;
    if (!safe_read(slot.address, std::as_writable_bytes(std::span(&words, 1)))) return false;
    const auto after = current_mappings();
    return after && dock_motion::mapping_state(*after, location, expected, kPatchBytes)
            == dock_motion::MappingState::same;
}

struct OwnedRanges {
    std::vector<std::vector<uint32_t>> storage;
    std::vector<dock_motion::CodeRange> ranges;
};

std::optional<OwnedRanges> copy_generation_code(
    const std::vector<dock_motion::ExecutableMapping> &mappings) {
    if (mappings.empty()) return {};
    OwnedRanges owned;
    owned.storage.reserve(mappings.size());
    owned.ranges.reserve(mappings.size());
    for (const auto &mapping : mappings) {
        const size_t length = mapping.end - mapping.begin;
        owned.storage.emplace_back(length / sizeof(uint32_t));
        auto &copy = owned.storage.back();
        if (!safe_read(mapping.begin, std::as_writable_bytes(std::span(copy)))) return {};
        owned.ranges.push_back({mapping.begin, std::span<const uint32_t>(copy)});
    }
    const auto after = current_mappings();
    if (!after || !mapping_subset(mappings, *after)) return {};
    return owned;
}

bool distinct_targets(const TargetAddresses &locations) {
    for (size_t left = 0; left < locations.size(); ++left) {
        if (locations[left] == 0 || locations[left] % alignof(uint32_t) != 0) return false;
        for (size_t right = left + 1; right < locations.size(); ++right) {
            if (locations[left] == locations[right]) return false;
        }
    }
    return true;
}

std::optional<ResolvedInstance> resolve_generation(
    const std::vector<dock_motion::ExecutableMapping> &mappings) {
    try {
        auto owned = copy_generation_code(mappings);
        if (!owned) {
            report_resolution(mappings, "copy");
            return {};
        }
        const auto resolution = dock_motion::resolve(owned->ranges);
        if (!resolution) {
            report_resolution(mappings, "resolve");
            return {};
        }
        const auto locations = addresses(*resolution);
        if (!distinct_targets(locations)) {
            report_resolution(mappings, "distinct");
            return {};
        }
        TargetSources target_sources{};
        if (!dock_motion::sources_for(mappings, locations, kPatchBytes, target_sources)) {
            report_resolution(mappings, "sources");
            return {};
        }
        std::array<PatchWords, kTargetCount> originals{};
        if (!stable_read(locations, target_sources, originals)) {
            report_resolution(mappings, "stable");
            return {};
        }
        for (size_t i = 0; i < locations.size(); ++i) {
            const auto copied = dock_motion::at(owned->ranges, locations[i], originals[i].size());
            if (copied.size() != originals[i].size()
                || !std::equal(copied.begin(), copied.end(), originals[i].begin())) {
                report_resolution(mappings, "words");
                return {};
            }
        }
        return ResolvedInstance{*resolution, mappings, target_sources, originals};
    } catch (...) {
        report_resolution(mappings, "exception");
        return {};
    }
}

void publish_layout(size_t index, const dock_motion::Layout &layout) {
    const auto &symbols = kBankSymbols[index];
    *symbols.params_class_id = layout.params_class_id;
    *symbols.double_class_id = layout.double_class_id;
    *symbols.tagged_header_offset = layout.tagged_header_offset;
    *symbols.class_id_shift = layout.class_id_shift;
    *symbols.class_id_mask = layout.class_id_mask;
    *symbols.alpha_offset = layout.alpha_offset;
    *symbols.scale_offset = layout.scale_offset;
    *symbols.surface_offset = layout.surface_offset;
    *symbols.recents_offset = layout.recents_offset;
    *symbols.double_value_offset = layout.double_value_offset;
    *symbols.false_from_null = layout.false_from_null;
}

bool same_instance(const HookBank &bank, const ResolvedInstance &instance) {
    if (!dock_motion::same_resolution(bank.resolution, instance.resolution)) return false;
    for (size_t i = 0; i < bank.slots.size(); ++i) {
        if (!(bank.slots[i].source == instance.sources[i])) return false;
    }
    return true;
}

HookBank make_bank(size_t index, const ResolvedInstance &instance) {
    const auto &symbols = kBankSymbols[index];
    return {index, instance.resolution, instance.mappings, {{
        {instance.resolution.scale, symbols.replacements[0], symbols.originals[0],
            instance.sources[0], instance.original_words[0]},
        {instance.resolution.animate, symbols.replacements[1], symbols.originals[1],
            instance.sources[1], instance.original_words[1]},
        {instance.resolution.set, symbols.replacements[2], symbols.originals[2],
            instance.sources[2], instance.original_words[2]},
    }}};
}

bool install_slot(HookSlot &slot) {
    if (slot.registered) return true;
    PatchWords before{};
    if (hook_function == nullptr || !stable_read(slot, before)
        || before != slot.original_words) return false;
    if (hook_function(reinterpret_cast<void *>(slot.address), slot.replacement,
            slot.original) != 0) return false;
    slot.registered = true;
    PatchWords after{};
    if (stable_read(slot, after) && after != slot.original_words) {
        slot.patch_words = after;
        slot.patch_known = true;
    }
    return true;
}

/**
 * Re-write the exact patch words the hook library installed, without involving it.
 *
 * This module never unhooks a bank and the library keeps both its record and its
 * trampoline for the life of the process, so restoring the recorded bytes at the target
 * address is equivalent to the original installation. It is the fallback for the case
 * that matters most: the file-backed prologue came back and the library refuses to re-arm
 * an address it still believes it owns. Without it the channel stays dead until restart.
 */
bool restore_patch_words(HookSlot &slot) {
    if (!slot.patch_known) return false;
    const uintptr_t page = slot.address - slot.address % host_page_size();
    const size_t length = static_cast<size_t>(slot.address + kPatchBytes - page);
    void *base = reinterpret_cast<void *>(page);
    if (mprotect(base, length, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    std::memcpy(reinterpret_cast<void *>(slot.address), slot.patch_words.data(), kPatchBytes);
    __builtin___clear_cache(reinterpret_cast<char *>(slot.address),
        reinterpret_cast<char *>(slot.address + kPatchBytes));
    if (mprotect(base, length, PROT_READ | PROT_EXEC) != 0) return false;
    // The live words are the recorded patch again, so the bank verifies clean from here on.
    slot.registered = true;
    return true;
}

/**
 * Install any slot that is not registered yet, and re-arm a registered slot whose live
 * words have returned to the untouched original.
 *
 * The OS4 launcher AOT image is mapped straight out of base.apk (extractNativeLibs=false),
 * so its code pages are file backed and can be re-read underneath a live inline hook, which
 * silently restores the original prologue. The bank then still looks "installed" while
 * nothing fires: no enters, no publishes, no Binder sample, and real-time following stays
 * dead until the desktop restarts. Re-issuing the hook here costs three reads per health
 * tick and restores the channel in place.
 *
 * A slot whose words are neither ours nor the original is a foreign edit; leave it alone.
 */
bool ensure_slots_live(HookBank &bank) {
    // Scene hooks must be available before the scale hook can wake subscribers.
    constexpr std::array<size_t, kTargetCount> order{1, 2, 0};
    for (const size_t index : order) {
        auto &slot = bank.slots[index];
        if (slot.registered) {
            PatchWords observed{};
            if (!stable_read(slot, observed)) return false;
            if (slot.patch_known && observed == slot.patch_words) continue;
            if (observed == slot.original_words) {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "motion hook patch lost bank=%zu slot=%zu; re-arming",
                    bank.index, index);
                slot.registered = false;
            } else if (!slot.patch_known) {
                // A hook that landed after the bank was reported partial: adopt the live
                // words instead of writing the bank off for the rest of the process life.
                slot.patch_words = observed;
                slot.patch_known = true;
                continue;
            } else {
                return false;
            }
        }
        if (!install_slot(slot) && !restore_patch_words(slot)) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                "motion hook re-arm failed bank=%zu slot=%zu; channel stays down",
                bank.index, index);
            return false;
        }
    }
    return true;
}

constexpr uint64_t kPipelineReportNs = 10000000000ULL;

bool monotonic_ns(uint64_t &value) {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
    value = static_cast<uint64_t>(now.tv_sec) * 1000000000ULL + now.tv_nsec;
    return true;
}

/**
 * Low-rate ground truth for the whole native chain: whether the Dart hooks still execute
 * (entry), whether they publish a packed sample (publish), whether a replacement is on the
 * stack right now (callbacks) and whether the health pass believes the bank is live. Both
 * a silent hook loss and a healthy idle desktop produce no other log line, so without this
 * the two are indistinguishable. Must be called with hook_mutex held.
 */
void report_pipeline(bool healthy) {
    static uint64_t next_ns = 0;
    static uint64_t entry = 0;
    static uint64_t publish = 0;
    static uint64_t callbacks = 0;
    uint64_t now = 0;
    if (!monotonic_ns(now)) return;
    if (next_ns == 0) next_ns = now + kPipelineReportNs;
    if (now < next_ns) return;
    next_ns = now + kPipelineReportNs;
    const uint64_t current_entry = dock_motion_entry_hits.load(std::memory_order_relaxed);
    const uint64_t current_publish = dock_motion_publish_hits.load(std::memory_order_relaxed);
    const uint64_t current_callbacks = dock_motion_active_callbacks.load(std::memory_order_relaxed);
    const uint32_t current_subscribed = dock_motion_subscribed.load(std::memory_order_relaxed);
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "motion pipeline subscribed=%u healthy=%d banks=%zu entry=%llu(+%llu) publish=%llu(+%llu) callbacks=%llu(+%llu)",
        current_subscribed, healthy ? 1 : 0, hook_banks.size(),
        static_cast<unsigned long long>(current_entry),
        static_cast<unsigned long long>(current_entry - entry),
        static_cast<unsigned long long>(current_publish),
        static_cast<unsigned long long>(current_publish - publish),
        static_cast<unsigned long long>(current_callbacks),
        static_cast<unsigned long long>(current_callbacks - callbacks));
    entry = current_entry;
    publish = current_publish;
    callbacks = current_callbacks;
}

bool bank_healthy(HookBank &bank) {
    std::array<PatchWords, kTargetCount> observed{};
    if (!stable_read(addresses(bank), sources(bank), observed)) return false;
    for (size_t i = 0; i < bank.slots.size(); ++i) {
        auto &slot = bank.slots[i];
        if (!slot.registered) return false;
        if (!slot.patch_known) {
            if (observed[i] == slot.original_words) return false;
            slot.patch_words = observed[i];
            slot.patch_known = true;
        } else if (observed[i] != slot.patch_words) {
            return false;
        }
    }
    return true;
}

/**
 * Index of a bank whose executable image is no longer mapped, or kBankSymbols.size()
 * when every bank still owns live code. Recycling one is safe because an unmapped
 * generation can never run its replacement again, so republishing that bank's layout
 * symbols cannot change the behaviour of anything still executing.
 */
size_t dead_bank_index() {
    for (size_t index = 0; index < hook_banks.size(); ++index) {
        if (mapping_subset(hook_banks[index].mappings, last_inventory)) continue;
        return index;
    }
    return kBankSymbols.size();
}

bool add_instance(const ResolvedInstance &instance) {
    if (std::ranges::any_of(hook_banks,
            [&](const auto &bank) { return same_instance(bank, instance); })) return false;
    size_t index = hook_banks.size();
    bool recycled = false;
    if (index >= kBankSymbols.size()) {
        // Every bank is immutable while its image is mapped, but a generation whose
        // executable ranges are no longer mapped can never fire again. Reclaim one of
        // those instead of refusing forever: refusing used to freeze the whole chain
        // once 16 generations had been consumed, until the desktop was restarted.
        index = dead_bank_index();
        if (index >= kBankSymbols.size()) {
            if (!capacity_reported) {
                capacity_reported = true;
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "motion runtime bank capacity reached; keeping existing generations");
            }
            return false;
        }
        recycled = true;
    }
    // Publish the layout before installing: the replacement reads these symbols.
    publish_layout(index, instance.resolution.layout);
    HookBank replacement = make_bank(index, instance);
    if (recycled) hook_banks[index] = std::move(replacement);
    else hook_banks.push_back(std::move(replacement));
    auto &bank = hook_banks[index];
    const bool installed = ensure_slots_live(bank) && bank_healthy(bank);
    __android_log_print(installed ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, kTag,
        "motion runtime bank=%zu recycled=%d paramsCID=%u doubleCID=%u install=%s",
        index, recycled ? 1 : 0, instance.resolution.layout.params_class_id,
        instance.resolution.layout.double_class_id, installed ? "complete" : "partial");
    return installed;
}

bool maintain_dock_motion_hooks_impl(bool force) {
    std::lock_guard lock(hook_mutex);
    const auto inventory = current_mappings();
    if (!inventory) {
        report_pipeline(false);
        deactivate_dock_motion();
        return false;
    }
    const bool inventory_changed = *inventory != last_inventory;
    if (inventory_changed) {
        last_inventory = *inventory;
        settling_scans = kInventorySettlingScans;
        scan_cooldown = 0;
    }

    bool healthy = false;
    for (auto &bank : hook_banks) {
        const auto state = dock_motion::mapping_state(*inventory, addresses(bank),
            sources(bank), kPatchBytes);
        if (state != dock_motion::MappingState::same) continue;
        // bank_healthy() is the only per-tick cost while the generation is intact. The
        // repair probe (a per-slot stable read) runs only after that verification fails,
        // so a lost patch is healed without taxing the healthy steady state.
        if (bank_healthy(bank)) healthy = true;
        else if (ensure_slots_live(bank) && bank_healthy(bank)) healthy = true;
    }

    bool scan = force || inventory_changed || !healthy;
    if (!scan && settling_scans != 0) {
        if (scan_cooldown == 0) scan = true;
        else --scan_cooldown;
    }
    if (scan) {
        const auto generations = dock_motion::runtime_generations(*inventory);
        for (const auto &generation : generations) {
            const auto instance = resolve_generation(generation);
            if (instance && add_instance(*instance)) healthy = true;
        }
        if (generations.empty()) {
            // A silent resolver is indistinguishable from a working one on the
            // device, so report each distinct executable inventory once.
            static std::string reported_inventory;
            std::string signature;
            for (const auto &mapping : *inventory) {
                signature += std::to_string(mapping.inode) + ':'
                    + std::to_string(mapping.begin) + ':'
                    + std::to_string(mapping.file_offset) + ';';
            }
            if (signature != reported_inventory) {
                reported_inventory = signature;
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "motion resolver idle: executable=%zu generations=0, no hook bank",
                    inventory->size());
            }
        }
        if (settling_scans != 0) --settling_scans;
        scan_cooldown = healthy ? kScanCooldownChecks : kScanCooldownChecks * 2;
    }

    // A scan can install a bank after the first health pass.
    if (!healthy) {
        for (auto &bank : hook_banks) {
            if (bank_healthy(bank)) {
                healthy = true;
                break;
            }
        }
    }
    report_pipeline(healthy);
    if (healthy) activate_dock_motion();
    else deactivate_dock_motion();
    return healthy;
}

bool maintain_dock_motion_hooks(bool force) noexcept {
    try {
        const bool healthy = maintain_dock_motion_hooks_impl(force);
        runtime_ready.store(healthy, std::memory_order_release);
        return healthy;
    } catch (...) {
        runtime_ready.store(false, std::memory_order_release);
        deactivate_dock_motion();
        __android_log_print(ANDROID_LOG_WARN, kTag,
            "motion health check failed safely due to a native exception");
        return false;
    }
}

void pause_for(long nanoseconds) {
    timespec remaining{nanoseconds / 1000000000L, nanoseconds % 1000000000L};
    while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {}
}

void *health_worker(void *) {
    for (;;) {
        const bool healthy = maintain_dock_motion_hooks(false);
        pause_for(healthy ? 250000000L : 2000000000L);
    }
}

void *motion_worker_impl() {
    if (!prepare_dock_motion()) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion event channel unavailable");
        return nullptr;
    }
    pthread_t health;
    if (pthread_create(&health, nullptr, health_worker, nullptr) == 0) pthread_detach(health);
    else {
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion health worker unavailable");
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "dynamic motion v34 transport starting independently of runtime discovery");
    // run_dock_motion() is a permanent service loop. If it ever returns, the launcher
    // would silently lose real-time motion for the rest of its life, because the only
    // remaining re-arm paths fire on rare one-shot events. Restart the transport
    // instead of letting this worker end and clearing `started`.
    for (;;) {
        run_dock_motion();
        __android_log_print(ANDROID_LOG_WARN, kTag,
            "motion transport returned unexpectedly; restarting in 1s");
        pause_for(1000000000L);
    }
}

void *motion_worker(void *) {
    worker_alive.store(true, std::memory_order_release);
    try {
        void *result = motion_worker_impl();
        worker_alive.store(false, std::memory_order_release);
        started.store(false, std::memory_order_release);
        return result;
    } catch (...) {
        worker_alive.store(false, std::memory_order_release);
        runtime_ready.store(false, std::memory_order_release);
        started.store(false, std::memory_order_release);
        deactivate_dock_motion();
        __android_log_print(ANDROID_LOG_WARN, kTag,
            "motion worker stopped safely due to a native exception");
        return nullptr;
    }
}
} // namespace

bool revalidate_dock_motion_hooks() {
    return maintain_dock_motion_hooks(true);
}

uint32_t dock_native_motion_state() {
    uint32_t state = 0;
    if (started.load(std::memory_order_acquire)) state |= 1U;
    if (worker_alive.load(std::memory_order_acquire)) state |= 2U;
    if (runtime_ready.load(std::memory_order_acquire)) state |= 4U;
    return state;
}

void start_dock_native_motion(Hook hook, Unhook) {
    if (hook == nullptr || started.exchange(true)) return;
    hook_function = hook;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, motion_worker, nullptr) == 0) pthread_detach(thread);
    else {
        started.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion worker unavailable");
    }
}
