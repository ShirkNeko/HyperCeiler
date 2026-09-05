/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.app.os4.home;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.home.HomePortraitReverse;
import com.sevtinge.hyperceiler.libhook.rules.home.gesture.HomeGestureOS4;
import com.sevtinge.hyperceiler.libhook.rules.home.gesture.ShakeDevice;
import com.sevtinge.hyperceiler.libhook.rules.home.os4.HomeSettingsOS4;
import com.sevtinge.hyperceiler.libhook.rules.home.os4.NativeHomeHooks;

@HookBase(targetPackage = "com.miui.home", deviceType = 1, minOSVersion = 4.0F)
public class HomePad extends BaseLoad {
    @Override
    public void onPackageLoaded() {
        NativeHomeHooks.INSTANCE.init();
        initHook(HomeSettingsOS4.INSTANCE, true);
        boolean gesturesEnabled = PrefsBridge.getBoolean("home_gesture_enable");
        boolean hasTouchGesture = PrefsBridge.getInt("home_gesture_double_tap_action", 0) > 0
            || PrefsBridge.getInt("home_gesture_up_swipe_action", 0) > 0
            || PrefsBridge.getInt("home_gesture_down_swipe_action", 0) > 0
            || PrefsBridge.getInt("home_gesture_up_swipe2_action", 0) > 0
            || PrefsBridge.getInt("home_gesture_down_swipe2_action", 0) > 0;

        // OS4 dock background is installed by SystemFrameworkB (HomeDockWindow).
        initHook(HomeGestureOS4.INSTANCE, gesturesEnabled && hasTouchGesture);
        initHook(new ShakeDevice(), gesturesEnabled
            && PrefsBridge.getInt("home_gesture_shake_action", 0) > 0);
        initHook(new HomePortraitReverse(), PrefsBridge.getBoolean("home_other_portrait_reverse"));
    }
}
