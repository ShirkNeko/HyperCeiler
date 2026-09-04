/*
 * This file is part of HyperCeiler.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sevtinge.hyperceiler.libhook.rules.home.os4

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook

/** Stable Android-side settings used by HyperOS 4's Flutter launcher. */
object HomeSettingsOS4 : BaseHook() {
    private const val LAUNCHER_ACTIVITY = "com.miui.home.launcher.Launcher"
    private const val RECENTS_MEMORY_INFO = "miui_recents_show_mem_info"

    override fun init() {
        hookHomeMode()
        hookMemoryInfoSetting()
    }

    private fun hookHomeMode() {
        val homeMode = PrefsBridge.getStringAsInt("home_other_home_mode", 0)
        if (homeMode !in 1..2) return

        Activity::class.java.findMethod {
            name("onCreate")
            parameterTypes(Bundle::class.java)
        }.createBeforeHook { param ->
            val activity = param.thisObject as Activity
            if (activity.componentName.className != LAUNCHER_ACTIVITY) return@createBeforeHook

            val nightMode = if (homeMode == 2) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            runCatching {
                activity.applyOverrideConfiguration(Configuration().apply {
                    uiMode = nightMode
                })
            }.onFailure {
                XposedLog.e(TAG, lpparam.packageName, "Unable to override launcher night mode", it)
            }
        }
    }

    private fun hookMemoryInfoSetting() {
        if (!PrefsBridge.getBoolean("home_recent_show_memory_info")) return

        val intHook = object : IMethodHook {
            override fun before(param: HookParam) {
                if (param.args.getOrNull(1) == RECENTS_MEMORY_INFO) param.result = 1
            }
        }
        val stringHook = object : IMethodHook {
            override fun before(param: HookParam) {
                if (param.args.getOrNull(1) == RECENTS_MEMORY_INFO) param.result = "1"
            }
        }
        hookAllMethods(Settings.System::class.java, "getInt", intHook)
        hookAllMethods(Settings.System::class.java, "getIntForUser", intHook)
        hookAllMethods(Settings.System::class.java, "getString", stringHook)
        hookAllMethods(Settings.System::class.java, "getStringForUser", stringHook)
    }
}
