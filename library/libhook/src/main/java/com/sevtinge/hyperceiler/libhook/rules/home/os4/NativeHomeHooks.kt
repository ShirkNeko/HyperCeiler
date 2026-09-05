/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sevtinge.hyperceiler.libhook.rules.home.os4

import androidx.annotation.Keep
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge

/** Configures native property hooks, with a library-load fallback for legacy loaders. */
@Keep
object NativeHomeHooks {
    private external fun nativeConfigure(
        highDeviceLevel: Boolean,
        disablePrestart: Boolean,
        softGlass: Boolean
    )

    /** Apply current launcher preferences to the process-local native hooks. */
    fun init() {
        val configure = {
            nativeConfigure(
                PrefsBridge.getBoolean("home_other_high_models"),
                PrefsBridge.getBoolean("home_other_disable_prestart"),
                PrefsBridge.getBoolean("home_dock_bg_custom_enable") &&
                    PrefsBridge.getStringAsInt("home_dock_add_blur", 0) == 1
            )
        }
        runCatching(configure).recoverCatching {
            // Legacy loaders do not process native_init.list, so keep an explicit fallback.
            System.loadLibrary("hyperceiler_home")
            configure()
        }.onFailure {
            XposedLog.e("NativeHomeHooks", "com.miui.home", "Unable to initialize native hooks", it)
        }
    }
}
