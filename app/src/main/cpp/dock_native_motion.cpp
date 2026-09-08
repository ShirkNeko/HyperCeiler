/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include <android/log.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <time.h>

extern "C" {
// Low two bits carry scene: 0=unrelated, 1=recents, 2=home return.
// Removing two mantissa bits loses < 1e-15, well below a physical pixel.
alignas(8) std::atomic<uint64_t> dock_motion_value{0x3ff0000000000000ULL};
// 0=unknown; otherwise EditState enum index + 1 (indices 0..7).
alignas(4) std::atomic<uint32_t> dock_edit_state{0};
alignas(4) std::atomic<uint32_t> dock_motion_subscribed{0};
int dock_motion_event = -1;
extern const uint64_t dock_motion_one = 1;
void *dock_motion_scale_original = nullptr;
void *dock_motion_anim_original = nullptr;
void *dock_motion_set_original = nullptr;
void *dock_edit_original = nullptr;
}
static_assert(std::atomic<uint64_t>::is_always_lock_free && sizeof(std::atomic<uint64_t>) == 8);
static_assert(std::atomic<uint32_t>::is_always_lock_free && sizeof(std::atomic<uint32_t>) == 4);

namespace {
constexpr char kTag[] = "HyperCeiler.DockNative";
constexpr transaction_code_t kMotionTransaction = 0x00484346;
constexpr int32_t kMotionAck = 0x48434B32;
constexpr char kWindowDescriptor[] = "android.view.IWindowManager";
std::atomic<uint64_t> motion_sequence{0};

void retry_delay() {
    timespec delay{0, 500000000};
    while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
}

bool clock_ns(clockid_t clock, uint64_t &value) {
    timespec now{};
    if (clock_gettime(clock, &now) != 0) return false;
    value = static_cast<uint64_t>(now.tv_sec) * 1000000000ULL + now.tv_nsec;
    return true;
}

struct Sample {
    uint64_t sequence;
    uint64_t uptime_ns;
    uint64_t value;
    uint64_t edit_state;
};

bool current_sample(Sample &sample) {
    uint64_t now = 0;
    if (!clock_ns(CLOCK_MONOTONIC, now)) return false;
    sample.sequence = motion_sequence.fetch_add(1, std::memory_order_relaxed) + 1;
    sample.uptime_ns = now;
    sample.value = dock_motion_value.load(std::memory_order_acquire);
    sample.edit_state = dock_edit_state.load(std::memory_order_acquire);
    return true;
}

void *binder_on_create(void *) {
    return nullptr;
}

void binder_on_destroy(void *) {}

binder_status_t binder_on_transact(AIBinder *, transaction_code_t,
    const AParcel *, AParcel *) {
    return STATUS_UNKNOWN_TRANSACTION;
}

const AIBinder_Class *window_manager_class() {
    static AIBinder_Class *clazz = AIBinder_Class_define(kWindowDescriptor,
        binder_on_create, binder_on_destroy, binder_on_transact);
    return clazz;
}

class WindowBinderTransport {
public:
    ~WindowBinderTransport() {
        if (window_ != nullptr) AIBinder_decStrong(window_);
    }

    bool connect() {
        // Service-manager lookup is a platform extension omitted from the app
        // NDK headers, but exported by the same libbinder_ndk already used by
        // the OS4 launcher. Resolve the symbol, never a library/address offset.
        using GetService = AIBinder *(*)(const char *instance);
        const auto get_service = reinterpret_cast<GetService>(
            dlsym(RTLD_DEFAULT, "AServiceManager_getService"));
        if (get_service == nullptr) return false;
        window_ = get_service("window");
        if (window_ == nullptr) return false;

        const AIBinder_Class *clazz = AIBinder_getClass(window_);
        if (clazz != nullptr) {
            const char *descriptor = AIBinder_Class_getDescriptor(clazz);
            return descriptor != nullptr && std::strcmp(descriptor, kWindowDescriptor) == 0;
        }
        clazz = window_manager_class();
        return clazz != nullptr && AIBinder_associateClass(window_, clazz);
    }

    bool send(const Sample &sample) {
        AParcel *input = nullptr;
        if (AIBinder_prepareTransaction(window_, &input) != STATUS_OK || input == nullptr) {
            return false;
        }
        if (AParcel_writeInt64(input, static_cast<int64_t>(sample.sequence)) != STATUS_OK
            || AParcel_writeInt64(input, static_cast<int64_t>(sample.uptime_ns)) != STATUS_OK
            || AParcel_writeInt64(input, static_cast<int64_t>(sample.value)) != STATUS_OK
            || AParcel_writeInt64(input, static_cast<int64_t>(sample.edit_state)) != STATUS_OK) {
            AParcel_delete(input);
            return false;
        }
        AParcel *output = nullptr;
        const binder_status_t status = AIBinder_transact(window_, kMotionTransaction,
            &input, &output, 0);
        int32_t acknowledgment = 0;
        const bool acknowledged = status == STATUS_OK && output != nullptr
            && AParcel_readInt32(output, &acknowledgment) == STATUS_OK
            && acknowledgment == kMotionAck;
        if (output != nullptr) AParcel_delete(output);
        return acknowledged;
    }

private:
    AIBinder *window_ = nullptr;
};
} // namespace

bool prepare_dock_motion() {
    dock_motion_event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (dock_motion_event < 0) return false;
    // Hooks are installed only after this succeeds. Keep notifications enabled while
    // Binder reconnects so a gesture racing recovery is coalesced in the eventfd.
    dock_motion_subscribed.store(1, std::memory_order_release);
    return true;
}

void run_dock_motion() {
    const int event = dock_motion_event;
    if (event < 0) return;
    constexpr int kPollMs = 1000;
    constexpr uint64_t kKeepAliveNs = 5000000000ULL;
    constexpr uint64_t kSuspendGapNs = 5000000000ULL;
    unsigned reconnects = 0;
    bool unavailable_reported = false;
    for (;;) {
        WindowBinderTransport transport;
        Sample sample{};
        if (!transport.connect() || !current_sample(sample) || !transport.send(sample)) {
            if (!unavailable_reported) {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "motion Binder transport unavailable; retrying in background");
                unavailable_reported = true;
            }
            retry_delay();
            continue;
        }

        unavailable_reported = false;
        __android_log_print(ANDROID_LOG_INFO, kTag,
        "motion v25 ready: native scale/edit with suspend-aware Binder recovery reconnect=%u",
            reconnects);

        bool disconnected = false;
        uint64_t last_value = sample.value;
        uint64_t last_edit_state = sample.edit_state;
        uint64_t last_sent_ns = sample.uptime_ns;
        uint64_t last_boot_ns = 0;
        uint64_t last_mono_ns = 0;
        if (!clock_ns(CLOCK_BOOTTIME, last_boot_ns)
            || !clock_ns(CLOCK_MONOTONIC, last_mono_ns)) break;
        bool resumed = false;
        while (!disconnected) {
            pollfd descriptor{event, POLLIN, 0};
            int result;
            do {
                result = poll(&descriptor, 1, kPollMs);
            } while (result < 0 && errno == EINTR);
            if (result < 0) {
                disconnected = true;
                continue;
            }
            uint64_t boot_ns = 0;
            uint64_t mono_ns = 0;
            if (!clock_ns(CLOCK_BOOTTIME, boot_ns)
                || !clock_ns(CLOCK_MONOTONIC, mono_ns)) {
                disconnected = true;
                continue;
            }
            // Both clocks advance while this worker is merely descheduled or frozen.
            // Only CLOCK_BOOTTIME advances through device suspend, so compare their
            // deltas instead of treating any long scheduling gap as a screen resume.
            const uint64_t boot_delta = boot_ns >= last_boot_ns
                ? boot_ns - last_boot_ns : UINT64_MAX;
            const uint64_t mono_delta = mono_ns >= last_mono_ns
                ? mono_ns - last_mono_ns : UINT64_MAX;
            if (boot_delta == UINT64_MAX || mono_delta == UINT64_MAX
                || (boot_delta > mono_delta && boot_delta - mono_delta > kSuspendGapNs)) {
                resumed = true;
                disconnected = true;
                continue;
            }
            last_boot_ns = boot_ns;
            last_mono_ns = mono_ns;
            if (descriptor.revents & POLLIN) {
                eventfd_t count = 0;
                if (eventfd_read(event, &count) != 0) {
                    disconnected = true;
                    continue;
                }
            }
            if (!current_sample(sample)) {
                disconnected = true;
                continue;
            }
            const bool changed = sample.value != last_value || sample.edit_state != last_edit_state;
            const bool keep_alive = !changed
                && (sample.uptime_ns - last_sent_ns) >= kKeepAliveNs;
            if (changed || keep_alive) {
                if (!transport.send(sample)) {
                    disconnected = true;
                    continue;
                }
                last_value = sample.value;
                last_edit_state = sample.edit_state;
                last_sent_ns = sample.uptime_ns;
            }
        }

        if (resumed) {
            __android_log_print(ANDROID_LOG_INFO, kTag,
                "device resume detected; rebuilding motion Binder transport");
        }
        if (reconnects < 3) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                "motion Binder transport disconnected; reconnecting");
        }
        ++reconnects;
        if (!resumed) retry_delay();
    }
}
