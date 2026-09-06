/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/system_properties.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string_view>

#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
void start_dock_native_motion(int (*hook)(void *, void *, void **));
#endif

namespace {

constexpr char kLogTag[] = "HyperCeilerHomeNative";

using HookFunction = int (*)(void *function, void *replacement, void **backup);
using UnhookFunction = int (*)(void *function);
using NativeOnModuleLoaded = void (*)(const char *name, void *handle);

struct NativeApiEntries {
    // Loader-owned ABI: unused optional entries still occupy their original slots.
    uint32_t version;
    HookFunction hook_func;
    UnhookFunction unhook_func;
};

HookFunction g_hook_function = nullptr;
std::atomic_bool g_property_hook_installed = false;
std::atomic_bool g_high_device_level = false;
std::atomic_bool g_disable_prestart = false;
std::atomic_bool g_soft_glass = false;

using SystemPropertyGet = int (*)(const char *name, char *value);
SystemPropertyGet g_original_system_property_get = nullptr;

bool is_launcher_process() {
    char name[128]{};
    FILE *file = std::fopen("/proc/self/cmdline", "r");
    if (file == nullptr) return false;
    const size_t size = std::fread(name, 1, sizeof(name) - 1, file);
    std::fclose(file);
    return size > 0 && std::string_view(name) == "com.miui.home";
}

bool is_prestart_property(std::string_view name) {
    return name == "persist.sys.usap_pool_enabled" ||
        name == "persist.sys.dynamic_usap_enabled" ||
        name == "persist.sys.prestart.proc" ||
        name == "persist.sys.prestart.feedback.enable" ||
        name == "persist.sys.launch_response_optimization.enable";
}

int copy_property_value(char *destination, std::string_view value) {
    if (destination == nullptr) return 0;
    const size_t length = value.copy(destination, PROP_VALUE_MAX - 1);
    destination[length] = '\0';
    return static_cast<int>(length);
}

int hooked_system_property_get(const char *name, char *value) {
    if (name != nullptr) {
        const std::string_view property(name);
        if (g_disable_prestart.load(std::memory_order_relaxed) &&
            is_prestart_property(property)) {
            return copy_property_value(value, "false");
        }
        if (g_soft_glass.load(std::memory_order_relaxed) &&
            property == "persist.sys.background_blur_supported") {
            return copy_property_value(value, "true");
        }
        if (g_high_device_level.load(std::memory_order_relaxed)) {
            if (property == "ro.config.low_ram.threshold_gb") {
                return copy_property_value(value, "false");
            }
            if (property == "ro.miui.backdrop_sampling_enabled") {
                return copy_property_value(value, "true");
            }
            if (property == "ro.config.device_level_for_animation" ||
                property == "persist.sys.computilityV2.devicelevel") {
                return copy_property_value(value, "2");
            }
        }
    }
    return g_original_system_property_get != nullptr
        ? g_original_system_property_get(name, value) : 0;
}

void install_property_hook() {
    if (!is_launcher_process() || g_hook_function == nullptr ||
        g_property_hook_installed.exchange(true, std::memory_order_acq_rel)) {
        return;
    }

    void *target = dlsym(RTLD_DEFAULT, "__system_property_get");
    if (target == nullptr ||
        g_hook_function(target, reinterpret_cast<void *>(hooked_system_property_get),
            reinterpret_cast<void **>(&g_original_system_property_get)) != 0) {
        g_property_hook_installed.store(false, std::memory_order_release);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
            "failed to hook __system_property_get");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
        "HyperOS 4 launcher property hooks installed");
}

void on_library_loaded(const char *name, void *) {
    if (name == nullptr) return;
    const std::string_view library_name(name);
    if (library_name.ends_with("/libapp_launcher.so") ||
        library_name == "libapp_launcher.so") {
        install_property_hook();
#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
        if (is_launcher_process()) start_dock_native_motion(g_hook_function);
#endif
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_sevtinge_hyperceiler_libhook_rules_home_os4_NativeHomeHooks_nativeConfigure(
    JNIEnv *, jobject, jboolean high_device_level, jboolean disable_prestart,
    jboolean soft_glass) {
    g_high_device_level.store(high_device_level == JNI_TRUE, std::memory_order_relaxed);
    g_disable_prestart.store(disable_prestart == JNI_TRUE, std::memory_order_relaxed);
    g_soft_glass.store(soft_glass == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeApiEntries *entries) {
    if (entries == nullptr || entries->hook_func == nullptr) return nullptr;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "native v11 hook API version=%u launcher=%d",
        entries->version, is_launcher_process());
    g_hook_function = entries->hook_func;
    // Install before libapp_launcher/libapp run their static initialization and cache the
    // properties. The load callback remains as a retry path for unusual linker ordering.
    install_property_hook();
#ifdef HYPERCEILER_DOCK_NATIVE_MOTION
    if (is_launcher_process()) start_dock_native_motion(g_hook_function);
#endif
    return on_library_loaded;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}
