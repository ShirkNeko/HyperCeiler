/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.addMiBackgroundBlendColor
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.clearAllBlur
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.clearMiBackgroundBlendColor
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setBackgroundBlurScaleRatio
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setBlurRoundRect
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setMiBackgroundBlurMode
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setMiBackgroundBlurRadius
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setMiBackgroundBlendColors
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setMiViewBlurMode
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtilsKt.setPassWindowBlurEnabled
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethod
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHooks
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.util.function.Consumer

object DockCustomNew : BaseHook() {
    private const val HYPER_OS_4_LAUNCHER = "com.miui.home.launcher.Launcher"

    override fun useDexKit() = !isMoreHyperOSVersion(4f)

    override fun initDexKit(): Boolean {
        showAnimationLambda
        return true
    }

    private val launcherClass by lazy {
        loadClassOrNull("com.miui.home.launcher.BaseLauncher")
            ?: loadClass("com.miui.home.launcher.Launcher")
    }

    private val animationCompatComplexClass by lazy {
        loadClass("com.miui.home.launcher.compat.UserPresentAnimationCompatComplex")
    }

    private val folderBlurUtilsClass by lazy {
        loadClassOrNull("com.miui.home.common.utils.MiuixMaterialBlurUtilities")
    }


    private val showAnimationLambda by lazy {
        requiredMember("ShowAnimationLambda") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(
                        "com.miui.home.launcher.compat.UserPresentAnimationCompat",
                        StringMatchType.StartsWith
                    )
                    addInvoke {
                        name = "conversionValueFrom3DTo2D"
                    }
                    addInvoke {
                        name = "setTranslationZ"
                    }
                }
            }.singleOrNull()
        } as Method?
    }

    private var isSupportHyperMaterialBlur = false
    private var hyperOS4DockBackground: View? = null

    @Suppress("UNCHECKED_CAST")
    override fun init() {
        if (isMoreHyperOSVersion(4f)) {
            initHyperOS4()
            return
        }

        val dockBgStyle = PrefsBridge.getStringAsInt("home_dock_add_blur", 0)
        var dockBlurView: View? = null

        launcherClass.findMethod {
            name("setupViews")
        }.createAfterHook {
            val isAllApp = PrefsBridge.getBoolean("home_dock_bg_all_app")
            val dockBgColor = PrefsBridge.getInt("home_dock_bg_color", 0)
            val dockRadius = dp2px(PrefsBridge.getInt("home_dock_bg_radius", 30))
            val dockHeight = dp2px(PrefsBridge.getInt("home_dock_bg_height", 80))
            val dockMargin = dp2px(PrefsBridge.getInt("home_dock_bg_margin_horizontal", 30) - 6)
            val dockBottomMargin = dp2px(PrefsBridge.getInt("home_dock_bg_margin_bottom", 30) - 92)

            isSupportHyperMaterialBlur = if (isMoreHyperOSVersion(3f)) {
                folderBlurUtilsClass?.callStaticMethod("isSupportHyperMaterialBlur") as? Boolean ?: false
            } else {
                false
            }

            val hotSeats = it.thisObject.getObjectFieldAs<FrameLayout>("mHotSeats")
            dockBlurView = View(hotSeats.context).apply {
                if (dockBgStyle == 0) {
                    setBackgroundColor(dockBgColor)
                } else if (dockBgStyle == 1) {
                    doOnAttach {
                        addBlur()
                    }

                    doOnDetach {
                        clearAllBlur()
                    }
                }

                setBlurRoundRect(dockRadius)
            }

            hotSeats.addView(
                dockBlurView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dockHeight).apply {
                    gravity = if (isAllApp) {
                        Gravity.TOP
                    } else {
                        Gravity.BOTTOM
                    }
                    setMargins(
                        dockMargin,
                        0,
                        dockMargin,
                        dockBottomMargin
                    )
                }
            )
        }

        if (dockBgStyle == 1) {
            launcherClass.findMethod {
                name("onDarkModeChanged")
            }.createAfterHook {

                isSupportHyperMaterialBlur = if (isMoreHyperOSVersion(3f)) {
                    folderBlurUtilsClass?.callStaticMethod("isSupportHyperMaterialBlur") as? Boolean ?: false
                } else {
                    false
                }

                dockBlurView?.addBlur()
            }
        }

        // 添加动画
        animationCompatComplexClass.findMethod {
            name("operateAllPresentAnimationRelatedViews")
        }.createAfterHook {
            dockBlurView?.run {
                val consumer = it.args[0] as Consumer<View>
                consumer.accept(this)
            }
        }

        showAnimationLambda?.createAfterHook {
            val view = it.args[2] as View
            if (view == dockBlurView) {
                view.translationZ = 0F
            }
        } ?: XposedLog.d(TAG, lpparam.packageName, $$"can't find lambda$showUserPresentAnimation")
    }

    /**
     * HyperOS 4's launcher no longer contains dex bytecode. The launcher content is rendered by
     * libapp.so/libapp_launcher.so and its Dock is presented in a separate native overlay window.
     * Put the custom background in the Launcher activity window so that it stays below that Dock
     * overlay while remaining above the wallpaper.
     */
    private fun initHyperOS4() {
        registerHotReloadCleanup {
            hyperOS4DockBackground?.let { view ->
                view.post {
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            }
            hyperOS4DockBackground = null
        }

        Activity::class.java.findAllMethods {
            filter {
                (name == "onCreate" && parameterCount == 1 && parameterTypes[0] == Bundle::class.java) ||
                    (name == "onPostResume" && parameterCount == 0) ||
                    (name == "onConfigurationChanged" &&
                        parameterCount == 1 && parameterTypes[0] == Configuration::class.java)
            }
        }.createAfterHooks { param ->
            val activity = param.thisObject as Activity
            if (activity.componentName.className != HYPER_OS_4_LAUNCHER) return@createAfterHooks

            activity.window.decorView.post {
                addHyperOS4DockBackground(activity)
            }
        }
    }

    private fun addHyperOS4DockBackground(activity: Activity) {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        val oldView = decorView.findViewWithTag<View>(TAG)
        if (oldView != null) {
            (oldView.parent as? ViewGroup)?.removeView(oldView)
        }

        val dockBgStyle = PrefsBridge.getStringAsInt("home_dock_add_blur", 0)
        val dockBgColor = PrefsBridge.getInt("home_dock_bg_color", 0)
        val dockRadius = dp2px(PrefsBridge.getInt("home_dock_bg_radius", 30))
        val dockHeight = dp2px(PrefsBridge.getInt("home_dock_bg_height", 150))
        val dockMargin = dp2px(PrefsBridge.getInt("home_dock_bg_margin_horizontal", 25))
        val dockBottomMargin = dp2px(PrefsBridge.getInt("home_dock_bg_margin_bottom", 15))

        // HyperOS 4 exposes its real glass renderer only inside the Flutter engine. For an
        // Android overlay, combine the compositor pass blur with the same material blend modes
        // and a specular gradient. Unsupported devices fall back to a translucent surface.
        isSupportHyperMaterialBlur = true

        val dockBackground = View(activity).apply {
            tag = TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            when (dockBgStyle) {
                0 -> setBackgroundColor(dockBgColor)
                1 -> {
                    doOnAttach { addBlur() }
                    doOnDetach { clearAllBlur() }
                }
            }
            setBlurRoundRect(dockRadius)
        }
        hyperOS4DockBackground = dockBackground

        decorView.addView(
            dockBackground,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dockHeight).apply {
                gravity = Gravity.BOTTOM
                setMargins(dockMargin, 0, dockMargin, dockBottomMargin)
            }
        )
    }

    private fun View.addBlur() {
        val isDarkMode = isDarkDockMode()

        if (isMoreHyperOSVersion(4f)) {
            addHyperOS4SoftGlass(isDarkMode)
            return
        }

        clearMiBackgroundBlendColor()
        setMiViewBlurMode(1)

        if (isSupportHyperMaterialBlur) {
            val list: ArrayList<Point> = ArrayList()

            if (isDarkMode) {
                list.add(Point(1719105399, 19))
                list.add(Point(863270004, 15))
                list.add(Point(855638016, 3))
            } else {
                list.add(Point(-428575628, 15))
                list.add(Point(-1722658222, 18))
                list.add(Point(869388753, 3))
            }

            setMiBackgroundBlendColors(list)
        } else {
            if (isDarkMode) {
                addMiBackgroundBlendColor(0xB3767676.toInt(), 100)
                addMiBackgroundBlendColor(0xFF149400.toInt(), 106)
            } else {
                addMiBackgroundBlendColor(0x66B4B4B4, 100)
                addMiBackgroundBlendColor(0xFF2EF200.toInt(), 106)
            }
        }
    }

    private fun View.addHyperOS4SoftGlass(isDarkMode: Boolean) {
        runCatching {
            clearAllBlur()
            setPassWindowBlurEnabled(true)
            setMiBackgroundBlurMode(1)
            setMiBackgroundBlurRadius(120)
            // Some OS4 builds omit the scale-ratio extension while retaining pass blur.
            runCatching { setBackgroundBlurScaleRatio(0.82f) }
            setMiViewBlurMode(3)

            val materialColors = ArrayList<Point>()
            if (isDarkMode) {
                materialColors.add(Point(0x66767676, 19))
                materialColors.add(Point(0x33141414, 15))
                materialColors.add(Point(0x14000000, 3))
            } else {
                materialColors.add(Point(0x66FFFFFF, 19))
                materialColors.add(Point(0x26E9E9E9, 15))
                materialColors.add(Point(0x0FFFFFFF, 3))
            }
            setMiBackgroundBlendColors(materialColors)
            background = createSoftGlassHighlight(isDarkMode)
        }.onFailure {
            XposedLog.w(TAG, lpparam.packageName, "Soft glass is unavailable; using translucent fallback", it)
            runCatching { clearAllBlur() }
            background = createSoftGlassHighlight(isDarkMode)
        }
    }

    private fun createSoftGlassHighlight(isDarkMode: Boolean): GradientDrawable {
        val colors = if (isDarkMode) {
            intArrayOf(0x367A7A7A, 0x22505050, 0x18202020)
        } else {
            intArrayOf(0x66FFFFFF, 0x36FFFFFF, 0x1AFFFFFF)
        }
        val strokeColor = if (isDarkMode) 0x33FFFFFF else 0x80FFFFFF.toInt()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dp2px(PrefsBridge.getInt("home_dock_bg_radius", 30)).toFloat()
            setStroke(dp2px(1), strokeColor)
        }
    }

    private fun View.isDarkDockMode(): Boolean {
        return when (PrefsBridge.getStringAsInt("home_other_home_mode", 0)) {
            1 -> false
            2 -> true
            else -> AppsTool.isDarkMode(context)
        }
    }
}
