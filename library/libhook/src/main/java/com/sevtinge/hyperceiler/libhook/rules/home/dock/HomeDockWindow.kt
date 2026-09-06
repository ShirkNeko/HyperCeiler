/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.SharedPreferences
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HYOS launcher has no ART Activity: its Java module entry and JNI preference setter do not run.
 * WMS still owns its windows. Attach an effect layer below the launcher buffer, above wallpaper,
 * using the existing system scope and remote preferences. No injected input window is necessary.
 */
class HomeDockWindow : BaseHook() {
    private companion object {
        const val LOG_TAG = "system"
        const val WM_HANDLER = "mH"
        const val WM_LOCK = "mGlobalLock"
        const val IS_VALID = "isValid"
        const val SET_POSITION = "setPosition"
        const val SET_LAYER = "setLayer"
        const val SET_CROP = "setWindowCrop"
        const val SET_RADIUS = "setCornerRadius"
        const val TRANSACTION = "android.view.SurfaceControl\$Transaction"
    }
    private object Surfaces {
        fun buildLayer(name: String, parent: Any, color: Boolean): Any {
            val builder = loadClass("android.view.SurfaceControl\$Builder").getConstructor().newInstance()
            builder.callMethod("setName", name)
            builder.callMethod("setParent", parent)
            builder.callMethod("setHidden", true)
            builder.callMethod(if (color) "setColorLayer" else "setEffectLayer")
            return builder.callMethod("build")!!
        }

        fun destroySurface(surface: Any) {
            val transaction = loadClass(TRANSACTION).getConstructor().newInstance()
            try {
                if (surface.callMethod(IS_VALID) == true) {
                    transaction.callMethod("remove", surface)
                    transaction.callMethod("apply")
                }
            } finally {
                try { transaction.callMethod("close") } finally { surface.callMethod("release") }
            }
        }

    }

    private data class Settings(
        val enabled: Boolean, val mode: Int, val color: Int, val height: Int,
        val margin: Int, val bottom: Int, val radius: Int, val nightMode: Int
    ) {
        companion object {
            fun read() = Settings(
                PrefsBridge.getBoolean("home_dock_bg_custom_enable"),
                DockWindowPolicy.normalizeBackgroundMode(PrefsBridge.getStringAsInt("home_dock_add_blur", 1)),
                PrefsBridge.getInt("home_dock_bg_color", 0),
                PrefsBridge.getInt("home_dock_bg_height", 150),
                PrefsBridge.getInt("home_dock_bg_margin_horizontal", 25),
                PrefsBridge.getInt("home_dock_bg_margin_bottom", 15),
                PrefsBridge.getInt("home_dock_bg_radius", 30),
                PrefsBridge.getStringAsInt("home_other_home_mode", 0)
            )
        }
        val blur get() = mode == 1 || mode == DockGlassPreset.MODE
        val glass get() = mode == DockGlassPreset.MODE
    }

    private data class Appearance(val config: Settings, val bounds: DockWindowPolicy.Bounds,
        val dark: Boolean, val visible: Boolean, val glass: DockGlassClient.Ticket?) {
        val surface = glass?.lease
        val ready = glass?.ready == true && !glass.dead && surface != null
        val key = "$config/$bounds/$dark/$visible/$surface/$ready/${glass?.dead}"
    }

    private data class Layer(val parent: Any, val effect: Any, val tint: Any,
        var appearance: String = "", var glass: DockGlassClient.Ticket? = null,
        val motion: DockRecentsMotion = DockRecentsMotion(),
        val nativeMotion: DockNativeMotion = DockNativeMotion(),
        var nativeClient: DockNativeMotionClient? = null,
        var nativeApplied: Boolean = false, var overview: Boolean = false,
        var nativeScene: Int = -1,
        var lastGlassReady: Boolean? = null,
        var motionSession: Any? = null, var motionClient: IBinder? = null,
        var motionSamples: Int = 0, var motionEndPending: Boolean = false,
        var baseY: Int = 0, var density: Float = 0f, var motionTime: Long = 0,
        var x: Float = Float.NaN, var y: Float = Float.NaN)
    private val layers = IdentityHashMap<Any, Layer>()
    private val observed = HashSet<String>()
    @Volatile private var stopped = false
    @Volatile private var settings = Settings.read()
    @Volatile private var service: Any? = null
    private var blurAvailable = true
    private var commandSamples = 0
    private val processGuard = DockGlassProcessGuard()
    private val glassClient = DockGlassClient(processGuard) { requestTraversal() }
    private val frameScheduled = AtomicBoolean(false)
    @Volatile private var animationAvailable = true
    @Volatile private var directMotionAvailable = true
    @Volatile private var animationChoreographer: Choreographer? = null
    // Owned and used only on WMS's handler thread, never the host window transaction.
    private var motionTransaction: Any? = null
    private val animationFrame = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled.set(false)
        if (!stopped) {
            if (directMotionAvailable) updateMotionFrame(frameTimeNanos) else requestTraversal()
        }
    }

    override fun init() {
        glassClient.record("hook init diagnosticVersion=11 enabled=${settings.enabled} mode=${settings.mode}")
        runCatching { processGuard.install() }
            .onFailure { glassClient.record("renderer guard unavailable=${it.javaClass.simpleName}") }
        val prefs = PrefsBridge.getSharedPreferences()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.contains("home_dock_") || key.endsWith("home_other_home_mode")) {
                runCatching { settings = Settings.read(); requestTraversal() }
                    .onFailure { failClosed(it) }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registerHotReloadCleanup {
            stopped = true
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            synchronized(layers) { layers.keys.toList().forEach { removeLayer(it) } }
            glassClient.close()
            processGuard.close()
            service?.getObjectFieldAs<Handler>(WM_HANDLER)?.post {
                runCatching { animationChoreographer?.removeFrameCallback(animationFrame) }
                runCatching { motionTransaction?.callMethod("close") }
                motionTransaction = null
                animationChoreographer = null
                frameScheduled.set(false)
            }
        }
        WindowHooks(loadClass("com.android.server.wm.WindowState")).install()
        XposedLog.i(TAG, LOG_TAG, "WMS dock hook ready: enabled=${settings.enabled}, blur=${settings.blur}")
    }

    /** Keeps optional scene-hook failures separate from the background's lifecycle. */
    private inner class WindowHooks(private val windowClass: Class<*>) {
        private val attrsField = windowClass.getDeclaredField("mAttrs").apply { isAccessible = true }

        fun install() {
            windowClass.getDeclaredMethod("prepareSurfaces").apply { isAccessible = true }
                .createAfterHook { param -> runCatching { prepareWindow(param.thisObject) }.onFailure { failClosed(it) } }
            windowClass.getDeclaredMethod("removeImmediately").apply { isAccessible = true }
                .createBeforeHook { param -> synchronized(layers) { removeLayer(param.thisObject) } }
            installWallpaper()
        }

        private fun prepareWindow(window: Any) {
            if (stopped) return
            val attrs = attrsField.get(window) as WindowManager.LayoutParams
            if (attrs.packageName != "com.miui.home") return
            val title = attrs.title.toString()
            synchronized(layers) {
                if (stopped) return
                if (settings.enabled && observed.size < 12 && observed.add("${attrs.type}:$title")) {
                    XposedLog.i(TAG, LOG_TAG, "Launcher window: type=${attrs.type}, title=${title.take(160)}")
                }
                if (!isLauncher(window, attrs)) return
                service = window.getObjectFieldAs<Any>("mWmService")
                if (settings.enabled) glassClient.bindDiagnostics(service!!.getObjectFieldAs<Context>("mContext"))
                updateLayer(window)
            }
        }

        private fun isLauncher(window: Any, attrs: WindowManager.LayoutParams): Boolean =
            DockWindowPolicy.isLauncherWindow(attrs.packageName, attrs.title.toString(), attrs.type,
                window.callMethod("getDisplayId") as Int)

        private fun installWallpaper() {
            // Read only the exact launcher's scoped command; never alter wallpaper or gesture handling.
            runCatching {
                val endpoint = DockWallpaperEndpoint.resolve(loadClass("com.android.server.wm.WallpaperController"),
                    windowClass, loadClass("com.android.server.wm.Session"), IBinder::class.java, Bundle::class.java)
                val command = endpoint.method().apply { isAccessible = true }
                command.createAfterHook { param ->
                    runCatching { wallpaperCommand(endpoint, param) }.onFailure { reportMotionError(it) }
                }
                XposedLog.i(TAG, LOG_TAG, "Dock recents motion observer ready")
                glassClient.record("motion observer ready endpoint=${command.toGenericString()}")
            }.onFailure {
                glassClient.record("motion observer unsupported=${it.javaClass.simpleName}: ${it.message?.take(160)}")
                XposedLog.w(TAG, LOG_TAG, "Dock recents motion unsupported; keeping background", it)
            }
        }

        private fun wallpaperCommand(endpoint: DockWallpaperEndpoint.Endpoint, param: HookParam) {
            if (stopped || !settings.enabled) return
            val wm = service ?: return
            // Session callbacks may run after WMS releases its lock. Preserve WM -> layer lock order.
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    if (stopped || !settings.enabled) return
                    val window = if (endpoint.sessionScoped()) {
                        layers.entries.firstOrNull { (_, layer) ->
                            DockWallpaperEndpoint.ownsWindow(layer.motionSession, layer.motionClient,
                                param.thisObject, param.args[0])
                        }?.key ?: return
                    } else param.args[0] ?: return
                    val attrs = attrsField.get(window) as WindowManager.LayoutParams
                    if (!isLauncher(window, attrs)) return
                    val extras = param.args[5] as? Bundle
                    recordCommand(param.args[1], extras)
                    if (param.args[1] != DockRecentsMotion.WALLPAPER_ACTION || extras == null) return
                    @Suppress("DEPRECATION")
                    val scale = (extras.get("scale_to") as? Number)?.toDouble() ?: return
                    val overview = DockRecentsMotion.overviewTarget(param.args[1] as String,
                        extras.getString("action"), scale) ?: return
                    updateOverview(window, overview, extras.getString("action") == "setTo", scale)
                }
            }
        }

        private fun recordCommand(action: Any?, extras: Bundle?) {
            if (commandSamples++ >= 12) return
            @Suppress("DEPRECATION")
            val value = extras?.get("scale_to")
            glassClient.record("launcher wallpaper command=${(action as? String)?.take(80)} " +
                "action=${extras?.getString("action")?.take(40)} " +
                "scale=${(value as? Number)?.toDouble()} type=${value?.javaClass?.simpleName}")
        }

        private fun updateOverview(window: Any, overview: Boolean, immediate: Boolean, scale: Double) {
            // A controller command can precede the first prepareSurfaces.
            if (layers[window] == null) {
                updateLayer(window)
                requestTraversal() // Commit the new surface's parent/crop/visibility.
            }
            val layer = layers[window] ?: return
            layer.overview = overview
            val now = SystemClock.uptimeMillis()
            layer.motionTime = now
            val wasRunning = layer.motion.isRunning(now)
            val targetChanged = layer.motion.setOverview(overview, now)
            if (immediate) layer.motion.finish()
            if (layer.nativeClient?.connected == true) return
            if (targetChanged || (immediate && wasRunning)) {
                layer.motionSamples = 0
                layer.motionEndPending = true
                glassClient.record("motion target overview=$overview immediate=$immediate scale=$scale liftDp=${DockRecentsMotion.LIFT_DP} curve=sceneSpring")
                if (directMotionAvailable) scheduleAnimationFrame() else requestTraversal()
            }
        }

        private fun reportMotionError(error: Throwable) {
            synchronized(layers) {
                if (observed.add("recents-motion-error")) {
                    glassClient.record("motion observer error=${error.javaClass.simpleName}: ${error.message?.take(160)}")
                    XposedLog.w(TAG, LOG_TAG, "Dock recents signal unavailable; keeping background", error)
                }
            }
        }
    }

    private inner class LayerUpdate {
        fun update(window: Any) {
            val config = settings
            if (!config.enabled) { removeLayer(window); return }
            val parent = window.callMethod("getSurfaceControl") ?: return
            if (parent.callMethod(IS_VALID) != true) { removeLayer(window); return }
            val frame = window.callMethod("getFrame") as Rect
            val configuration = window.callMethod("getConfiguration") as Configuration
            val bounds = DockWindowPolicy.layout(frame.width(), frame.height(), configuration.densityDpi / 160f,
                config.height, config.margin, config.bottom, config.radius)
            if (bounds == null) { removeLayer(window); return }
            val layer = obtainLayer(window, parent, frame, bounds, config)
            val dark = when (config.nightMode) {
                1 -> false
                2 -> true
                else -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val visible = window.callMethod("isVisible") == true
            bindNativeMotion(layer, visible)
            if (!visible || !animationAvailable) layer.motion.finish()
            val glass = updateGlass(layer, config, bounds, dark, visible)
            val appearance = Appearance(config, bounds, dark, visible, glass)
            val now = SystemClock.uptimeMillis()
            val x = bounds.x().toFloat()
            val density = configuration.densityDpi / 160f
            val geometryChanged = layer.x != x || layer.baseY != bounds.y() || layer.density != density
            layer.baseY = bounds.y()
            layer.density = density
            layer.motionTime = now
            val offset = motionOffset(layer, now)
            val running = !layer.nativeApplied && layer.motion.isRunning(now)
            // In direct mode, do not queue old animation positions in a later WMS traversal.
            // WMS still initializes/repositions our layer when the actual layout changes.
            val movingDirectly = directMotionAvailable && visible && running
            val keepDirectPosition = movingDirectly && !geometryChanged
            val y = if (keepDirectPosition) layer.y
                else bounds.y() + offset
            if (visible && running) scheduleAnimationFrame()
            val moved = layer.x != x || layer.y != y
            if (!moved && layer.appearance == appearance.key) return
            val transaction = window.callMethod("getSyncTransaction")!!
            if (moved) {
                // Move the common parent: glass, tint and fallback blur stay aligned. The size/key
                // remains unchanged, so a frame of motion never recreates the glass host or texture.
                transaction.callMethod(SET_POSITION, layer.effect, x, y)
                layer.x = x
                layer.y = y
                recordMotion(layer, y, now, visible, "layout")
            }
            if (layer.appearance == appearance.key) return
            applyAppearance(transaction, layer, appearance)
            layer.appearance = appearance.key
        }

        private fun updateGlass(layer: Layer, config: Settings, bounds: DockWindowPolicy.Bounds,
            dark: Boolean, visible: Boolean): DockGlassClient.Ticket? {
            val glassKey = "${bounds.width()}/${bounds.height()}/${bounds.radius()}/$dark"
            if (!config.glass || layer.glass?.key?.let { it != glassKey } == true) {
                layer.glass?.let { glassClient.release(it) }
                layer.glass = null
            }
            if (config.glass && visible && layer.glass == null) {
                val context = service!!.getObjectFieldAs<Context>("mContext")
                layer.glass = glassClient.create(context, glassKey, bounds, dark)
            }
            return layer.glass
        }

        private fun obtainLayer(window: Any, parent: Any, frame: Rect, bounds: DockWindowPolicy.Bounds, config: Settings): Layer {
            var layer = layers[window]
            if (layer != null && (layer.parent !== parent || layer.effect.callMethod(IS_VALID) != true)) {
                removeLayer(window)
                layer = null
            }
            if (layer == null) {
                val effect = Surfaces.buildLayer("HyperCeiler Dock blur", parent, false)
                var tint: Any? = null
                try { tint = Surfaces.buildLayer("HyperCeiler Dock tint", effect, true) }
                finally { if (tint == null) Surfaces.destroySurface(effect) }
                layer = Layer(parent, effect, tint)
                layers[window] = layer
                val motionLayer = layer
                runCatching {
                    motionLayer.motionSession = window.getObjectFieldAs<Any>("mSession")
                    motionLayer.motionClient = window.getObjectFieldAs<Any>("mClient").callMethod("asBinder") as IBinder
                    glassClient.record("motion window identity bound")
                }.onFailure {
                    // An optional Session fallback must never disable the glass background.
                    motionLayer.motionSession = null
                    motionLayer.motionClient = null
                    glassClient.record("motion window identity unavailable=${it.javaClass.simpleName}")
                }
                XposedLog.i(TAG, LOG_TAG, "Dock surface created: frame=$frame, bounds=$bounds, blur=${config.blur}")
                if (!config.blur && Color.alpha(config.color) == 0) {
                    XposedLog.w(TAG, LOG_TAG, "Dock color is transparent; select a visible color or enable blur")
                }
            }
            return layer
        }

        private fun applyAppearance(transaction: Any, layer: Layer, appearance: Appearance) {
            val (config, bounds, dark, visible, glass) = appearance
            val glassSurface = appearance.surface
            val glassReady = appearance.ready
            transaction.callMethod(SET_LAYER, layer.effect, -1)
            transaction.callMethod(SET_CROP, layer.effect, bounds.width(), bounds.height())
            transaction.callMethod(SET_RADIUS, layer.effect, bounds.radius())
            transaction.callMethod(SET_LAYER, layer.tint, 1)
            transaction.callMethod(SET_CROP, layer.tint, bounds.width(), bounds.height())
            transaction.callMethod(SET_RADIUS, layer.tint, bounds.radius())
            if (glassSurface != null && glass?.dead == false) {
                // Remote root attachment and retirement share one serial worker.
                // WMS still controls the owned parent's visibility and motion.
                glassClient.attach(glass, layer.effect)
            }
            if (blurAvailable) {
                runCatching { transaction.callMethod("setBackgroundBlurRadius", layer.effect,
                    if (config.blur && !(glassReady && glass?.dead == false)) 120 else 0) }
                    .onFailure {
                        blurAvailable = false
                        XposedLog.w(TAG, LOG_TAG, "Compositor blur unavailable; retaining color fallback", it)
                    }
            }
            val color = when {
                config.glass -> DockGlassPreset.fallbackColor(dark)
                config.blur -> if (dark) 0x66505050 else 0x66FFFFFF
                else -> config.color
            }
            transaction.callMethod("setColor", layer.tint,
                floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f))
            transaction.callMethod("setAlpha", layer.tint,
                if (glassReady && glass?.dead == false) 0f else Color.alpha(color) / 255f)
            transaction.callMethod("show", layer.tint)
            transaction.callMethod(if (visible) "show" else "hide", layer.effect)
            val appliedGlass = glassReady && glass?.dead == false
            if (config.glass && layer.lastGlassReady != appliedGlass) {
                glassClient.record("glass applied native=$appliedGlass fallbackBlur=${if (appliedGlass) 0 else 120} visible=$visible")
                layer.lastGlassReady = appliedGlass
            }
            // Use WMS's transaction, so visibility/position changes commit with the parent window.
        }
    }

    private val layerUpdate = LayerUpdate()
    private fun updateLayer(window: Any) { layerUpdate.update(window) }

    private fun removeLayer(window: Any) {
        val layer = layers.remove(window) ?: return
        layer.nativeClient?.close()
        layer.nativeClient = null
        layer.glass?.let { glassClient.release(it) }
        // Only release surfaces created by this hook. Never release the host's parent handle.
        runCatching { Surfaces.destroySurface(layer.tint) }
        runCatching { Surfaces.destroySurface(layer.effect) }
    }

    private fun requestTraversal() {
        val wm = service ?: return
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        handler.post {
            if (!stopped) runCatching {
                synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                    wm.getObjectFieldAs<Any>("mWindowPlacerLocked").callMethod("requestTraversal")
                }
            }.onFailure { failClosed(it) }
        }
    }

    private fun recordMotion(layer: Layer, y: Float, now: Long, visible: Boolean, source: String) {
        val running = if (layer.nativeApplied) {
            val progress = layer.nativeMotion.progress()
            progress > 0.001f && kotlin.math.abs(progress - 1f) > 0.001f
        } else layer.motion.isRunning(now)
        if (layer.motionEndPending && (layer.motionSamples < 6 || !running)) {
            glassClient.record("motion position offsetY=${y - layer.baseY} running=$running visible=$visible source=${if (layer.nativeApplied) "native-$source" else source}")
            layer.motionSamples++
            if (!running) layer.motionEndPending = false
        }
    }

    private fun updateMotionFrame(frameTimeNanos: Long) {
        val wm = service ?: return
        runCatching {
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    if (stopped || !settings.enabled) return
                    var needsFrame = false
                    val updates = ArrayList<Pair<Layer, Float>>()
                    for ((window, layer) in layers) {
                        if (window.callMethod("isVisible") != true || layer.effect.callMethod(IS_VALID) != true) {
                            layer.motion.finish()
                            continue
                        }
                        val now = DockRecentsMotion.frameTimeMillis(frameTimeNanos, layer.motionTime)
                        layer.motionTime = now
                        val y = layer.baseY + motionOffset(layer, now)
                        if (!layer.nativeApplied && layer.motion.isRunning(now)) needsFrame = true
                        if (layer.x.isFinite() && layer.baseY > 0 && y != layer.y) updates.add(layer to y)
                    }
                    if (updates.isNotEmpty()) {
                        val transaction = motionTransaction ?: loadClass(TRANSACTION)
                            .getConstructor().newInstance().also { motionTransaction = it }
                        for ((layer, y) in updates) transaction.callMethod(SET_POSITION, layer.effect, layer.x, y)
                        transaction.callMethod("setAnimationTransaction")
                        transaction.callMethod("setFrameTimelineVsync", animationChoreographer!!.callMethod("getVsyncId") as Long)
                        transaction.callMethod("apply")
                        // Publish cached positions only after a successful submission.
                        for ((layer, y) in updates) {
                            layer.y = y
                            recordMotion(layer, y, layer.motionTime, true, "vsync")
                        }
                    }
                    if (needsFrame) scheduleAnimationFrame()
                }
            }
        }.onFailure {
            // Optional direct scheduling must not disable the background or touch host surfaces.
            directMotionAvailable = false
            runCatching { motionTransaction?.callMethod("close") }
            motionTransaction = null
            glassClient.record("motion direct frame unavailable=${it.javaClass.simpleName}; using traversal fallback")
            requestTraversal()
        }
    }

    private fun bindNativeMotion(layer: Layer, visible: Boolean) {
        if (!visible) {
            layer.nativeClient?.close()
            layer.nativeClient = null
            layer.nativeMotion.reset()
            layer.nativeApplied = false
            layer.nativeScene = -1
            return
        }
        if (layer.nativeClient != null) return
        val session = layer.motionSession ?: return
        runCatching {
            val uid = session.getObjectFieldAs<Int>("mUid")
            val pid = session.getObjectFieldAs<Int>("mPid")
            if (uid < 10000 || pid <= 0) return
            layer.nativeClient = DockNativeMotionClient(uid, pid, {
                if (directMotionAvailable) scheduleAnimationFrame() else requestTraversal()
            }, glassClient::record).also { it.start() }
        }.onFailure {
            if (observed.add("native-motion-identity")) {
                glassClient.record("native motion identity unavailable=${it.javaClass.simpleName}")
            }
        }
    }

    // Called only under the layer lock. The socket worker publishes an immutable
    // latest sample; intermediate queued values never become a second animation.
    private fun motionOffset(layer: Layer, now: Long): Float {
        val client = layer.nativeClient
        val sample = client?.latest
        if (client?.connected == true && sample != null) {
            layer.nativeMotion.accept(sample)
            layer.nativeApplied = true
            if (sample.scene() != layer.nativeScene) {
                layer.nativeScene = sample.scene()
                layer.motionSamples = 0
                layer.motionEndPending = true
                glassClient.record("native motion scene=${sample.scene()} scale=${sample.scale()}")
            }
            return layer.nativeMotion.offsetY(layer.density, layer.baseY)
        }
        if (layer.nativeApplied) {
            layer.motion.resumeFrom(layer.nativeMotion.progress(), layer.overview, now)
            layer.nativeApplied = false
            layer.nativeMotion.reset()
            layer.nativeScene = -1
            layer.motionSamples = 0
            layer.motionEndPending = true
            glassClient.record("native motion disconnected; resuming scene fallback")
        }
        return layer.motion.offsetY(layer.density, layer.baseY, now)
    }

    private fun scheduleAnimationFrame() {
        val wm = service ?: return
        if (stopped || !frameScheduled.compareAndSet(false, true)) return
        wm.getObjectFieldAs<Handler>(WM_HANDLER).post {
            if (stopped) frameScheduled.set(false)
            else runCatching {
                val choreographer = animationChoreographer ?: run {
                    // Match compositor scheduling, instead of adding another app-frame/traversal hop.
                    runCatching { Choreographer::class.java.getDeclaredMethod("getSfInstance").invoke(null) as Choreographer }
                        .getOrElse { Choreographer.getInstance() }
                }.also {
                    animationChoreographer = it
                    glassClient.record("motion frame clock ready direct=$directMotionAvailable")
                }
                choreographer.postFrameCallback(animationFrame)
            }.onFailure {
                // Never let an optional animation callback throw on a system handler thread.
                animationAvailable = false
                frameScheduled.set(false)
                glassClient.record("motion scheduling failed=${it.javaClass.simpleName}: ${it.message?.take(160)}")
                XposedLog.w(TAG, LOG_TAG, "Dock animation scheduling unavailable; using scene endpoints", it)
                requestTraversal()
            }
        }
    }

    private fun failClosed(error: Throwable) {
        synchronized(layers) {
            if (stopped) return
            stopped = true
            glassClient.record("hook disabled error=${error.javaClass.simpleName}: ${error.message?.take(160)}")
            layers.keys.toList().forEach { removeLayer(it) }
            glassClient.close()
            processGuard.close()
            XposedLog.e(TAG, LOG_TAG, "WMS dock disabled after an error; system windows left unchanged", error)
        }
    }
}
