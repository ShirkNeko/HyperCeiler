/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.provider;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassPreset;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Own-process HWUI only. Never execute vendor RenderThread code in system_server. */
final class DockGlassHost {
    private static final String TAG = "HyperCeiler.DockGlass";
    private final Handler main = new Handler(Looper.getMainLooper());
    // Accessed on the app main thread only. Two slots allow resize handover.
    private final HashMap<String, Entry> entries = new HashMap<>();
    private final IBinder lifetime = new Binder();
    private int dockCreates;
    private String lastDockStatus = "no Dock request in this app process";
    private String lastDockRelease = "none";

    private static final class Entry {
        final SurfaceControlViewHost host;
        final View view;
        final View backdrop;
        final IBinder owner;
        final IBinder.DeathRecipient death;
        SurfaceControlViewHost.SurfacePackage parcel;
        Entry(SurfaceControlViewHost host, View view, View backdrop, IBinder owner, IBinder.DeathRecipient death) {
            this.host = host; this.view = view; this.backdrop = backdrop;
            this.owner = owner; this.death = death;
        }
    }

    Bundle call(Context context, String method, String id, Bundle args) {
        checkCaller(method);
        return switch (method) {
            case "dock_glass_history" -> DockDiagnosticJournal.access(context, null);
            case "dock_glass_record" -> DockDiagnosticJournal.access(context, args);
            case "dock_glass_self_test" -> selfTest(context);
            default -> callHost(context, method, id, args);
        };
    }

    private static void checkCaller(String method) {
        int uid = Binder.getCallingUid();
        boolean selfTest = "dock_glass_self_test".equals(method);
        boolean diagnostics = "dock_glass_diagnostics".equals(method);
        boolean history = "dock_glass_history".equals(method);
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()
                && !((selfTest || diagnostics || history) && uid == 2000)) {
            throw new SecurityException("Only the system Dock hook may manage glass hosts");
        }
    }

    private Bundle callHost(Context context, String method, String id, Bundle args) {
        if (!"dock_glass_diagnostics".equals(method) && (id == null || id.length() > 100)) {
            throw new IllegalArgumentException("Invalid host id");
        }
        CompletableFuture<Bundle> result = new CompletableFuture<>();
        main.post(() -> dispatch(context, method, id, args, result));
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return cancelRequest(id, result, error);
        } catch (ExecutionException | TimeoutException error) {
            return cancelRequest(id, result, error);
        }
    }

    private void dispatch(Context context, String method, String id, Bundle args, CompletableFuture<Bundle> result) {
        if (result.isDone()) return;
        try {
            switch (method) {
                case "dock_glass_diagnostics" -> result.complete(diagnostics());
                case "dock_glass_create" -> create(context, id, args, result);
                case "dock_glass_status" -> result.complete(status(id));
                case "dock_glass_release" -> { release(id); result.complete(Bundle.EMPTY); }
                default -> throw new IllegalArgumentException("Unknown glass operation");
            }
        } catch (Throwable error) {
            release(id);
            record(id, "failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.w(TAG, "Glass host unavailable; retain compositor fallback", error);
            result.complete(failure(error));
        }
    }

    private Bundle cancelRequest(String id, CompletableFuture<Bundle> result, Exception error) {
        result.cancel(false);
        main.post(() -> release(id));
        return failure(error);
    }

    private static Bundle failure(Throwable error) {
        Bundle failure = new Bundle();
        Throwable cause = error.getCause() == null ? error : error.getCause();
        failure.putString("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        return failure;
    }

    private void create(Context context, String id, Bundle args, CompletableFuture<Bundle> result)
            throws Exception {
        if (args == null) throw new IllegalArgumentException("Missing host configuration");
        if (!id.startsWith("self-test-")) { dockCreates++; record(id, "creating"); }
        int width = args.getInt("width");
        int height = args.getInt("height");
        float radius = args.getFloat("radius");
        IBinder owner = args.getBinder("owner");
        validateHost(width, height, radius, owner);
        requireGlassSupport();
        release(id);
        if (entries.size() >= 2) throw new IllegalStateException("Dock host limit reached");
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Default display unavailable");
        Context displayContext = context.createDisplayContext(display);
        View view = new View(displayContext);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusable(false);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(radius);
        view.setBackground(shape);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, width, height, radius);
            }
        });
        view.setClipToOutline(true);
        // The source container and glass element have different HWUI roles. A
        // single View with both container mode 1 and element mode 1 can render
        // its container blur instead of the child's refractive material.
        FrameLayout backdrop = new FrameLayout(displayContext);
        backdrop.setBackgroundColor(Color.TRANSPARENT);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        backdrop.addView(view, new FrameLayout.LayoutParams(width, height));
        SurfaceControlViewHost host = new SurfaceControlViewHost(displayContext, display, new Binder());
        IBinder.DeathRecipient death = () -> main.post(() -> release(id));
        Entry entry = new Entry(host, view, backdrop, owner, death);
        entries.put(id, entry);
        owner.linkToDeath(death, 0);
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        layout.setTitle("HyperCeiler Dock glass");
        HiddenApiBypass.invoke(SurfaceControlViewHost.class, host, "setView", backdrop, layout);
        // setView schedules attachment; configure only after the View has a ViewRootImpl.
        view.post(() -> configure(id, entry, args.getBoolean("dark"), result));
    }

    private static void validateHost(int width, int height, float radius, IBinder owner) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid host dimensions");
        }
        if (!Float.isFinite(radius) || radius < 0 || radius > Math.min(width, height) / 2f) {
            throw new IllegalArgumentException("Invalid host radius");
        }
        if (owner == null || !owner.isBinderAlive()) throw new IllegalArgumentException("Invalid host owner");
    }

    private static void requireGlassSupport() throws Exception {
        Class<?> rootClass = Class.forName("android.view.ViewRootImpl");
        if (!Boolean.TRUE.equals(HiddenApiBypass.invoke(rootClass, null, "getSupportedBionicMaterial"))
                || !Boolean.TRUE.equals(HiddenApiBypass.invoke(rootClass, null, "getSupportedMiBlur"))) {
            throw new UnsupportedOperationException("Native glass or cross-window blur is unsupported");
        }
    }

    private void configure(String id, Entry entry, boolean dark, CompletableFuture<Bundle> result) {
        View view = entry.view;
        View backdrop = entry.backdrop;
        if (result.isDone() || !entries.containsKey(id)) return;
        try {
            if (!view.isAttachedToWindow() || !view.isHardwareAccelerated()) {
                throw new UnsupportedOperationException("No hardware-accelerated ViewRoot");
            }
            if (!enableOwnBackground(backdrop)) {
                throw new UnsupportedOperationException("Cross-window background was rejected");
            }
            invoke(backdrop, "setMiBackgroundBlurMode", 1);
            invoke(backdrop, "setMiBackgroundBlurRadius", 120);
            invoke(backdrop, "setMiViewBlurMode", 0);
            invoke(view, "setMiBackgroundBlurMode", 0);
            invoke(view, "setMiViewBlurMode", 1);
            invoke(backdrop, "setMiGlassBlurRadius", DockGlassPreset.SMALL_BLUR_RADIUS,
                    DockGlassPreset.BIG_BLUR_RADIUS);
            // CCMaterialToken clears its solid background and enables native glass
            // clipping enhancement before BionicsStyle applies the material token.
            try {
                invoke(view, "setMiBackgroundBlurEnhanceFlag", DockGlassPreset.GLASS_ENHANCE_FLAG,
                        DockGlassPreset.GLASS_ENHANCE_FLAG);
                view.setClipToOutline(false);
            } catch (Exception unsupported) {
                // Older vendor APIs retain the ordinary rounded-outline clipping.
                Log.i(TAG, "Glass clip enhancement unavailable; rounded outline retained");
            }
            invoke(view, "setMiViewMaterialType", DockGlassPreset.MATERIAL_TYPE);
            invoke(view, "setMiGlass", (Object) DockGlassPreset.parameters(dark));
            view.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> commitFrame(id, entry, result)));
            view.invalidate();
        } catch (Throwable error) {
            release(id);
            record(id, "configuration failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            result.completeExceptionally(error);
            Log.w(TAG, "Native glass configuration failed", error);
        }
    }

    private void commitFrame(String id, Entry entry, CompletableFuture<Bundle> result) {
        if (result.isDone() || !entries.containsKey(id)) return;
        SurfaceControlViewHost.SurfacePackage surface = entry.host.getSurfacePackage();
        if (surface == null) {
            result.completeExceptionally(new IllegalStateException("No surface package"));
            release(id);
            return;
        }
        Bundle response = new Bundle();
        response.putParcelable("surface", surface);
        response.putBinder("lifetime", lifetime);
        if (!result.complete(response)) { surface.release(); release(id); return; }
        entry.parcel = surface;
        // ContentProvider serializes the package after call() returns; do not release it here.
        Log.i(TAG, "Native glass frame committed for Dock host " + id);
        record(id, "frame committed; awaiting background texture; pipeline=container+glassChild");
    }

    private Bundle status(String id) throws Exception {
        Bundle result = new Bundle();
        Entry entry = entries.get(id);
        boolean ready = false;
        long timestamp = 0;
        if (entry != null && entry.view.isAttachedToWindow()) {
            Object root = invoke(entry.view, "getViewRootImpl");
            Field field = ownField(root.getClass(), "mSurTex");
            if (field != null) {
                Object texture = field.get(root);
                // Only a timestamp, never copy, retain or expose background pixels.
                if (texture instanceof SurfaceTexture) timestamp = ((SurfaceTexture) texture).getTimestamp();
                ready = timestamp > 0;
            }
        }
        result.putBoolean("backgroundReady", ready);
        result.putLong("textureTimestamp", timestamp);
        record(id, "backgroundReady=" + ready + ", textureTimestamp=" + timestamp);
        return result;
    }

    private Bundle diagnostics() throws Exception {
        Bundle result = new Bundle();
        result.putInt("pid", Process.myPid());
        result.putInt("dockCreateRequests", dockCreates);
        result.putInt("activeHosts", entries.size());
        result.putString("lastDockRelease", lastDockRelease);
        for (String id : entries.keySet()) {
            if (!id.startsWith("self-test-")) result.putBundle("host_" + id, status(id));
        }
        result.putString("lastDockStatus", lastDockStatus);
        result.putString("pipeline", "container+glassChild");
        return result;
    }

    private void record(String id, String event) {
        if (id != null && !id.startsWith("self-test-")) lastDockStatus = id + ": " + event;
    }

    /** Fixed, unparented test surface: no screenshot, overlay, host window or caller-supplied target. */
    private Bundle selfTest(Context context) {
        String id = "self-test-" + java.util.UUID.randomUUID();
        Bundle config = new Bundle();
        config.putInt("width", 320); config.putInt("height", 120);
        config.putFloat("radius", 30); config.putBinder("owner", new Binder());
        CompletableFuture<Bundle> result = new CompletableFuture<>();
        main.post(() -> {
            if (result.isDone()) return;
            try { create(context, id, config, result); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        Bundle response = new Bundle();
        try {
            Bundle rendered = result.get(5, TimeUnit.SECONDS);
            response.putBoolean("frameCommitted", rendered.containsKey("surface"));
            response.putString("note", "Unparented HWUI smoke test only; desktop background not verified");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            response.putAll(failure(error));
        } catch (ExecutionException | TimeoutException error) {
            result.cancel(false);
            response.putAll(failure(error));
        } finally {
            main.post(() -> release(id));
        }
        return response;
    }

    private static Object invoke(View view, String name, Object... args) throws Exception {
        return HiddenApiBypass.invoke(View.class, view, name, args);
    }

    private static boolean enableOwnBackground(View view) throws Exception {
        if (Boolean.TRUE.equals(invoke(view, "setPassWindowBlurEnabled", true))) return true;
        // A false return can also mean the requested state is already set.
        Field enabledField = ownField(View.class, "mNeedPassWindowBlur");
        if (enabledField != null && enabledField.getBoolean(view)) return true;
        Object root = invoke(view, "getViewRootImpl");
        if (root == null) return false;
        return allowOwnBackground(view, root);
    }

    private static boolean allowOwnBackground(View view, Object root) throws Exception {
        Field field = ownField(root.getClass(), "mPassWindowBlurFilterData");
        if (field != null) {
            Object original = field.get(root);
            if (!(original instanceof String)) return false;
            String ownPackage = view.getContext().getPackageName();
            if (!"com.sevtinge.hyperceiler".equals(ownPackage)) return false;
            // Instance field of OUR windowless ViewRoot only. Never alter static flags,
            // global settings, system properties, or another window's package/filter.
            field.set(root, original + "," + ownPackage);
            boolean enabled = Boolean.TRUE.equals(invoke(view, "setPassWindowBlurEnabled", true));
            if (!enabled) field.set(root, original);
            return enabled;
        }
        return false;
    }

    // Vendor-only instance fields on HyperCeiler's own View/ViewRoot. Public API
    // has no texture-readiness/filter accessor; do not apply this to host windows.
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Field ownField(Class<?> type, String name) {
        for (Field field : HiddenApiBypass.getInstanceFields(type)) {
            if (field.getName().equals(name)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private void release(String id) {
        Entry entry = entries.remove(id);
        if (entry == null) return;
        if (!id.startsWith("self-test-")) lastDockRelease = id;
        try { entry.owner.unlinkToDeath(entry.death, 0); } catch (RuntimeException ignored) {}
        try { invoke(entry.backdrop, "setPassWindowBlurEnabled", false); }
        catch (Throwable ignored) { /* Releasing the root also clears its texture. */ }
        entry.host.release();
        if (entry.parcel != null) entry.parcel.release();
    }
}
