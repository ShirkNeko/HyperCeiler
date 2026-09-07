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
constexpr transaction_code_t kMotionTransaction = 0x00484344;
constexpr char kWindowDescriptor[] = "android.view.IWindowManager";
std::atomic<uint64_t> motion_sequence{0};

void retry_delay() {
    timespec delay{0, 500000000};
    while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {}
}

struct Sample {
    uint64_t sequence;
    uint64_t uptime_ns;
    uint64_t value;
    uint64_t edit_state;
};

bool current_sample(Sample &sample) {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
    sample.sequence = motion_sequence.fetch_add(1, std::memory_order_relaxed) + 1;
    sample.uptime_ns = static_cast<uint64_t>(now.tv_sec) * 1000000000ULL + now.tv_nsec;
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
        if (output != nullptr) AParcel_delete(output);
        return status == STATUS_OK;
    }

private:
    AIBinder *window_ = nullptr;
};
} // namespace

bool prepare_dock_motion() {
    dock_motion_event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    return dock_motion_event >= 0;
}

void run_dock_motion() {
    const int event = dock_motion_event;
    if (event < 0) return;
    unsigned reconnects = 0;
    bool unavailable_reported = false;
    for (;;) {
        WindowBinderTransport transport;
        Sample sample{};
        if (!transport.connect() || !current_sample(sample) || !transport.send(sample)) {
            dock_motion_subscribed.store(0, std::memory_order_release);
            if (!unavailable_reported) {
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "motion Binder transport unavailable; retrying in background");
                unavailable_reported = true;
            }
            retry_delay();
            continue;
        }

        unavailable_reported = false;
        dock_motion_subscribed.store(1, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "motion v19 ready: native scale/edit over authenticated IWindowManager Binder reconnect=%u",
            reconnects);

        bool disconnected = false;
        while (!disconnected) {
            pollfd descriptor{event, POLLIN, 0};
            int result;
            do {
                result = poll(&descriptor, 1, -1);
            } while (result < 0 && errno == EINTR);
            if (result <= 0 || !(descriptor.revents & POLLIN)) {
                disconnected = true;
                continue;
            }
            eventfd_t count = 0;
            disconnected = eventfd_read(event, &count) != 0
                || !current_sample(sample) || !transport.send(sample);
        }

        dock_motion_subscribed.store(0, std::memory_order_release);
        if (reconnects < 3) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                "motion Binder transport disconnected; reconnecting");
        }
        ++reconnects;
        retry_delay();
    }
}
