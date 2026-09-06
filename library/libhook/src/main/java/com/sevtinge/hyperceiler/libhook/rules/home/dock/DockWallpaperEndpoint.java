/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import java.lang.reflect.Method;

/** Exact scoped endpoints only; never observe a display-global wallpaper command. */
public final class DockWallpaperEndpoint {
    public record Endpoint(Method method, boolean sessionScoped) {}

    private DockWallpaperEndpoint() {}

    public static Endpoint resolve(Class<?> controller, Class<?> window, Class<?> session,
                                   Class<?> binder, Class<?> bundle) throws NoSuchMethodException {
        for (String name : new String[]{"sendWindowWallpaperCommandUnchecked", "sendWindowWallpaperCommand"}) {
            for (boolean legacy : new boolean[]{false, true}) {
                Method method = find(controller, name, window, bundle, legacy);
                if (method != null) return new Endpoint(method, false);
            }
        }
        // The device's Android 37 IWindowSession uses IBinder + action + xyz + Bundle,
        // without the old boolean sync argument. Session identity AND window token
        // must be matched to an existing Dock layer before consuming its commands.
        for (boolean legacy : new boolean[]{false, true}) {
            Method method = find(session, "sendWallpaperCommand", binder, bundle, legacy);
            if (method != null) return new Endpoint(method, true);
        }
        throw new NoSuchMethodException("No window- or session-scoped wallpaper endpoint");
    }

    private static Method find(Class<?> owner, String name, Class<?> source, Class<?> bundle,
                               boolean legacy) {
        Class<?>[] parameters = legacy
                ? new Class<?>[]{source, String.class, int.class, int.class, int.class, bundle, boolean.class}
                : new Class<?>[]{source, String.class, int.class, int.class, int.class, bundle};
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            return method.getReturnType() == void.class || method.getReturnType() == bundle ? method : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @SuppressWarnings("ReferenceEquality") // Intentional: session must be the same WMS object instance
    public static boolean ownsWindow(Object expectedSession, Object expectedToken,
                                     Object actualSession, Object actualToken) {
        // Session must be the very same WMS object, not a caller-defined value
        // comparison. Binder tokens retain their platform equality semantics.
        return expectedSession != null && expectedToken != null
                && expectedSession == actualSession && expectedToken.equals(actualToken);
    }
}
