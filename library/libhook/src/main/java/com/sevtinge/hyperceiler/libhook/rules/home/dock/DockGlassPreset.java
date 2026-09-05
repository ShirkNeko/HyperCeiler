/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** OS4's 42-float HWUI ABI. See tests/home-dock-window/OS4_GLASS_NOTES.md. */
public final class DockGlassPreset {
    // New value: do not reuse 2, which means legacy custom blur on older systems.
    public static final int MODE = 3;
    public static final int MATERIAL_TYPE = 1;
    public static final int SMALL_BLUR_RADIUS = 110;
    public static final int BIG_BLUR_RADIUS = 110;
    public static final int GLASS_ENHANCE_FLAG = 0x2000;

    private DockGlassPreset() {}

    /** Fresh arrays: native setters and callers must never mutate a shared preset. */
    public static float[] parameters(boolean dark) {
        // miui.systemui.util.MiBackgroundStyle.DEFAULT_GLASS_TOKEN, as used by
        // defaultContentBgMaterialToken / mediaItemToken. No day/night branch in
        // this token. Do not mix in notification presets or hand-tuned overrides.
        return new float[]{
            .67f, .16f, .09f, 0f, .24f, 1.4f, -.02f, .3f, .6f, 1f,
            .03f, 1f, 1f, 1f, .1f, .2f, .3f, 1f, 1f,
            72f, 3.8f, 80f, 800f, 1.2f, 1f, -.4f, .6f, -.8f,
            1.4f, .7f, .8f, 1.15f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
        };
    }

    public static int fallbackColor(boolean dark) {
        return dark ? 0x18505050 : 0x18FFFFFF;
    }
}
