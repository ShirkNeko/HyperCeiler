/* SPDX-License-Identifier: AGPL-3.0-or-later */
#include <android/log.h>
#include <atomic>
#include <bit>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

extern "C" {
// Low two bits carry scene: 0=unrelated, 1=recents, 2=home return.
// Removing two mantissa bits loses < 1e-15, well below a physical pixel.
alignas(8) std::atomic<uint64_t> dock_motion_value{0x3ff0000000000000ULL};
alignas(4) std::atomic<uint32_t> dock_motion_subscribed{0};
int dock_motion_event = -1;
extern const uint64_t dock_motion_one = 1;
void *dock_motion_scale_original = nullptr;
void *dock_motion_anim_original = nullptr;
void *dock_motion_set_original = nullptr;
}
static_assert(std::atomic<uint64_t>::is_always_lock_free && sizeof(std::atomic<uint64_t>) == 8);
static_assert(std::atomic<uint32_t>::is_always_lock_free && sizeof(std::atomic<uint32_t>) == 4);

namespace {
constexpr char kTag[] = "HyperCeiler.DockNative";
struct Packet {
    uint32_t magic = 0x48434437;
    uint32_t version = 1;
    uint64_t sequence;
    uint64_t uptime_ns;
    uint64_t value;
};
static_assert(sizeof(Packet) == 32 && std::endian::native == std::endian::little);

bool send_sample(int client, uint64_t &sequence) {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
    Packet packet{0x48434437, 1, ++sequence,
        static_cast<uint64_t>(now.tv_sec) * 1000000000ULL + now.tv_nsec,
        dock_motion_value.load(std::memory_order_acquire)};
    // Never block either worker or render thread on WMS. A short write closes
    // the connection; the receiver never decodes a partially spliced packet.
    return send(client, &packet, sizeof(packet), MSG_DONTWAIT | MSG_NOSIGNAL) == sizeof(packet);
}
} // namespace

bool prepare_dock_motion() {
    dock_motion_event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    return dock_motion_event >= 0;
}

void run_dock_motion() {
    const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (server < 0) return;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    const int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
        "hyperceiler.dock.motion.%d", getpid());
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(address.sun_path) - 1
        || bind(server, reinterpret_cast<sockaddr *>(&address),
            offsetof(sockaddr_un, sun_path) + 1 + length) != 0 || listen(server, 2) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "motion socket unavailable errno=%d", errno);
        close(server);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "motion v8 ready: dynamically resolved; event-driven native scale");
    int client = -1;
    uint64_t sequence = 0;
    unsigned connections = 0;
    auto disconnect = [&] {
        dock_motion_subscribed.store(0, std::memory_order_release);
        if (client >= 0) close(client);
        client = -1;
    };
    for (;;) {
        pollfd descriptors[] = {{server, POLLIN, 0}, {dock_motion_event, POLLIN, 0},
            {client, POLLIN, 0}};
        if (poll(descriptors, 3, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        // No input protocol: unexpected input, EOF or a dead peer closes the stream.
        if (client >= 0 && descriptors[2].revents != 0) disconnect();
        if (descriptors[0].revents & POLLIN) {
            int incoming = accept4(server, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (incoming >= 0) {
                ucred credentials{};
                socklen_t size = sizeof(credentials);
                if (getsockopt(incoming, SOL_SOCKET, SO_PEERCRED, &credentials, &size) != 0
                    || size != sizeof(credentials) || credentials.uid != 1000 || client >= 0) {
                    close(incoming);
                } else {
                    client = incoming;
                    const int buffer_bytes = 2048;
                    setsockopt(client, SOL_SOCKET, SO_SNDBUF, &buffer_bytes, sizeof(buffer_bytes));
                    dock_motion_subscribed.store(1, std::memory_order_release);
                    if (!send_sample(client, sequence)) disconnect();
                    else if (connections++ < 12) __android_log_print(ANDROID_LOG_INFO, kTag,
                        "motion subscriber connected pid=%d", credentials.pid);
                }
            }
        }
        if (descriptors[1].revents & POLLIN) {
            eventfd_t count = 0;
            if (eventfd_read(dock_motion_event, &count) == 0 && client >= 0
                && !send_sample(client, sequence)) disconnect();
        }
    }
    disconnect();
    close(server);
    // The event FD remains allocated until process exit: a callback may have
    // already read it. Never recycle its number into an unrelated descriptor.
}
