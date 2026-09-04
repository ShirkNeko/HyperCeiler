/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.provider;

import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Process;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.util.Locale;
import java.util.TreeSet;

/** Read-only API inventory for Dock glass compatibility; never captures screen or app data. */
final class DockGlassDiagnostics {
    private DockGlassDiagnostics() {}

    static MatrixCursor query() {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid() && uid != 2000 && uid != 0) {
            throw new SecurityException("Dock diagnostics are restricted to this app and ADB");
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"api"});
        try {
            Class<?> root = Class.forName("android.view.ViewRootImpl");
            for (String capability : new String[]{"getSupportedBionicMaterial", "getSupportedMiBlur"}) {
                cursor.addRow(new Object[]{capability + "=" + HiddenApiBypass.invoke(root, null, capability)});
            }
        } catch (Throwable error) {
            cursor.addRow(new Object[]{"Capability query: " + error.getClass().getSimpleName()});
        }
        String[] names = {
            "android.view.SurfaceControl$Transaction", "android.view.SurfaceControl$Builder",
            "android.view.View", "android.graphics.RenderEffect", "android.graphics.RenderNode",
            "com.android.internal.graphics.drawable.BackgroundBlurDrawable"
        };
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name);
                TreeSet<String> matches = new TreeSet<>();
                for (Executable method : HiddenApiBypass.getDeclaredMethods(type)) {
                    String lower = method.getName().toLowerCase(Locale.ROOT);
                    if (lower.contains("glass") || lower.contains("blur") || lower.contains("material")
                            || lower.contains("backdrop") || lower.contains("stroke")
                            || lower.contains("refract") || lower.contains("blend")
                            || lower.contains("shader") || lower.contains("effect")) {
                        matches.add(method.toString());
                    }
                }
                if (matches.isEmpty()) matches.add(name + ": no matching API");
                for (String match : matches) cursor.addRow(new Object[]{match});
            } catch (Throwable error) {
                cursor.addRow(new Object[]{name + ": " + error.getClass().getSimpleName()});
            }
        }
        return cursor;
    }
}
