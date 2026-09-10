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
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicLong

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
        const val STALE_FRAME_NS = 50_000_000L
        const val NATIVE_BIND_SWEEP_MS = 1_000L
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
        var nativeUid: Int = -1, var nativePid: Int = -1,
        var nativeApplied: Boolean = false, var overview: Boolean = false,
        var nativeScene: Int = -1,
        var overviewGeneration: Long = 0, var nativeOverviewGeneration: Long = 0,
        var lastOverviewValidationNs: Long = 0,
        var lastVisible: Boolean? = null,
        var lastGlassReady: Boolean? = null,
        var motionSession: Any? = null, var motionClient: IBinder? = null,
        var motionSamples: Int = 0, var motionEndPending: Boolean = false,
        var baseY: Int = 0, var density: Float = 0f, var motionTime: Long = 0,
        var nativeSampleDeadlineNs: Long = 0,
        var x: Float = Float.NaN, var y: Float = Float.NaN) {
        /** Drop every cached native identity/sample after the launcher Session changed. */
        fun resetNativeMotion() {
            nativeMotion.reset()
            nativeApplied = false
            nativeScene = -1
            nativeSampleDeadlineNs = 0
            motionSamples = 0
            motionEndPending = false
            nativeUid = -1
            nativePid = -1
        }
    }
    private val layers = IdentityHashMap<Any, Layer>()
    private val observed = HashSet<String>()
    @Volatile private var stopped = false
    @Volatile private var settings = Settings.read()
    @Volatile private var service: Any? = null
    private var blurAvailable = true
    private var commandSamples = 0
    private val processGuard = DockGlassProcessGuard()
    private val glassClient = DockGlassClient(processGuard) { requestTraversal() }
    private val nativeMotionEndpoint = DockNativeMotionEndpoint(
        {
            if (directMotionAvailable) scheduleAnimationFrame(true) else {
                requestTraversal()
                scheduleDirectMotionRecovery()
            }
        },
        {
            // Native's idle packet does not refresh motion state. It only proves transport
            // liveness and exercises our dedicated frame receiver before the next gesture.
            if (directMotionAvailable) scheduleAnimationFrame() else scheduleDirectMotionRecovery()
        },
        glassClient::record)
    private val nativeMotionReply = ThreadLocal<Int>()
    private data class FrameClock(val choreographer: Choreographer, val owned: Boolean)
    private data class ScheduledFrame(val epoch: Long, val clock: FrameClock,
        val callback: Choreographer.FrameCallback)
    private val frameEpoch = AtomicLong(0)
    private val scheduledFrameEpoch = AtomicLong(0)
    private val scheduledFrameStartedNs = AtomicLong(0)
    private val urgentFrameRecoveryScheduled = AtomicBoolean(false)
    private val directRecoveryScheduled = AtomicBoolean(false)
    private val nativeBindSweepScheduled = AtomicBoolean(false)
    @Volatile private var animationAvailable = true
    @Volatile private var directMotionAvailable = true
    // Owned and used only on WMS's handler thread, never the host window transaction.
    private var animationFrameClock: FrameClock? = null
    private var motionTransaction: Any? = null
    private var scheduledFrame: ScheduledFrame? = null
    private var directRecoveryDelay = 100L

    override fun init() {
        glassClient.record("hook init diagnosticVersion=32 enabled=${settings.enabled} mode=${settings.mode}")
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
                cancelScheduledFrame()
                runCatching { motionTransaction?.callMethod("close") }
                motionTransaction = null
                retireFrameClock()
                directRecoveryScheduled.set(false)
                nativeBindSweepScheduled.set(false)
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
            installNativeMotionTransaction()
            installWallpaper()
        }

        private fun installNativeMotionTransaction() {
            runCatching {
                val stub = loadClass("android.view.IWindowManager\$Stub")
                val transact = stub.getDeclaredMethod("onTransact", Integer.TYPE, Parcel::class.java,
                    Parcel::class.java, Integer.TYPE).apply { isAccessible = true }
                transact.createBeforeHook { param ->
                        // A hot reload cannot physically remove callbacks already registered in
                        // system_server. Never short-circuit here: restore the Parcel so every live
                        // endpoint can observe the same frame, including the newest hook.
                        if (stopped) return@createBeforeHook
                        val code = param.args[0] as Int
                        if (code != DockNativeMotionEndpoint.TRANSACTION_CODE) return@createBeforeHook
                        val data = param.args[1] as Parcel
                        val position = data.dataPosition()
                        nativeMotionReply.remove()
                        runCatching {
                            nativeMotionReply.set(
                                nativeMotionEndpoint.receive(code, data, param.args[3] as Int))
                        }.onFailure {
                            if (observed.add("native-motion-transaction-error")) {
                                glassClient.record("native motion transaction rejected=${it.javaClass.simpleName}")
                            }
                        }.also {
                            data.setDataPosition(position)
                        }
                    }
                transact.createAfterHook { param ->
                    if (stopped || param.args[0] as Int != DockNativeMotionEndpoint.TRANSACTION_CODE) {
                        return@createAfterHook
                    }
                    // The original Stub sees an unknown private code. Confirm it only after all
                    // before callbacks have had a chance to consume the restored input Parcel.
                    val acknowledgment = nativeMotionReply.get() ?: DockNativeMotionEndpoint.ACK
                    nativeMotionReply.remove()
                    val reply = param.args[2] as? Parcel ?: return@createAfterHook
                    reply.setDataPosition(0)
                    reply.writeInt(acknowledgment)
                    param.result = true
                }
                glassClient.record("native motion IWindowManager endpoint ready")
            }.onFailure {
                glassClient.record("native motion IWindowManager endpoint unavailable=${it.javaClass.simpleName}")
            }
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
                scheduleNativeBindSweep()
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
                    val action = param.args[1] as? String
                    if (action != DockRecentsMotion.WALLPAPER_ACTION) return
                    if (extras == null) return
                    @Suppress("DEPRECATION")
                    val scale = (extras.get("scale_to") as? Number)?.toDouble() ?: return
                    val sceneAction = extras.getString("action")
                    val command = param.args[1] as String
                    val isSetTo = sceneAction == "setTo"
                    val overview = DockRecentsMotion.overviewTarget(command, sceneAction, scale) ?: return
                    if (DockRecentsMotion.homeTarget(command, sceneAction, scale)) {
                        scheduleVisibleGlassRefresh(window)
                    }
                    updateOverview(window, overview, isSetTo, scale)
                }
            }
        }

        private fun scheduleVisibleGlassRefresh(window: Any) {
            val wm = service ?: return
            wm.getObjectFieldAs<Handler>(WM_HANDLER).postDelayed({
                runCatching {
                    synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                        synchronized(layers) {
                            val layer = layers[window] ?: return@postDelayed
                            if (stopped) return@postDelayed
                            val nativeLatest = nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
                            if (layer.overview || nativeLatest != null) return@postDelayed
                            if (!layer.nativeApplied && window.callMethod("isVisible") == true) {
                                layer.glass?.let { glassClient.resume(it) }
                            }
                        }
                    }
                }.onFailure { reportMotionError(it) }
            }, 120)
        }

        private fun recordCommand(action: Any?, extras: Bundle?) {
            if (commandSamples++ >= 100) return
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
            val nativeLatest = nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
            val validationNow = System.nanoTime()
            if (overview && validationNow - layer.lastOverviewValidationNs > 80_000_000L) {
                layer.lastOverviewValidationNs = validationNow
                layer.overviewGeneration++
                val alreadyObserved = nativeMotionEndpoint.sawOverviewSince(
                    layer.nativeUid, layer.nativePid, validationNow - 100_000_000L)
                scheduleNativeHookValidation(window, layer, layer.overviewGeneration,
                    layer.nativeUid, layer.nativePid, validationNow, alreadyObserved)
            }
            if (nativeLatest != null) {
                if (targetChanged || (immediate && wasRunning)
                    || layer.nativeScene == 1
                    || (layer.overview && layer.nativeScene == 0)) {
                    scheduleAnimationFrame(true)
                }
                return
            }
            if (!targetChanged && !(immediate && wasRunning)) {
                glassClient.record("motion overview command without target change overview=$overview immediate=$immediate scale=$scale")
                if (overview) scheduleAnimationFrame() else requestTraversal()
            } else {
                layer.motionSamples = 0
                layer.motionEndPending = true
                glassClient.record("motion target overview=$overview immediate=$immediate scale=$scale liftDp=${DockRecentsMotion.LIFT_DP} curve=sceneSpring")
                if (directMotionAvailable) scheduleAnimationFrame() else requestTraversal()
            }
        }

        private fun scheduleNativeHookValidation(window: Any, expectedLayer: Layer,
            generation: Long, expectedUid: Int, expectedPid: Int,
            since: Long, alreadyObserved: Boolean) {
            val wm = service ?: return
            wm.getObjectFieldAs<Handler>(WM_HANDLER).postDelayed({
                runCatching {
                    synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                        synchronized(layers) {
                            val layer = layers[window] ?: return@postDelayed
                            if (stopped || layer !== expectedLayer
                                || layer.overviewGeneration != generation
                                || layer.nativeUid != expectedUid || layer.nativePid != expectedPid) {
                                return@postDelayed
                            }
                            if (alreadyObserved || nativeMotionEndpoint.sawOverviewSince(
                                    expectedUid, expectedPid, since)) {
                                layer.nativeOverviewGeneration = generation
                                return@postDelayed
                            }
                            nativeMotionEndpoint.requestHookRevalidation(
                                expectedUid, expectedPid)
                            glassClient.record(
                                "native motion missing after overview target; requesting semantic revalidation")
                        }
                    }
                }.onFailure { reportMotionError(it) }
            }, 250)
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
            val windowVisible = window.callMethod("isVisible") == true
            val visible = windowVisible
            bindNativeMotion(window, layer)
            if (!visible) {
                layer.motion.finish()
                layer.nativeMotion.reset()
                layer.nativeApplied = false
                layer.nativeScene = -1
                layer.nativeSampleDeadlineNs = 0
            } else if (!animationAvailable && !layer.nativeApplied) {
                layer.motion.finish()
            }
            val glass = updateGlass(layer, config, bounds, dark, visible)
            if (visible && layer.lastVisible == false && glass != null) glassClient.resume(glass)
            if (!visible && layer.lastVisible == true && glass != null) glassClient.pauseRefresh(glass)
            layer.lastVisible = visible
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
                // Resolve the two identities independently. They used to share one
                // runCatching, so a failure while reading mClient.asBinder() also threw
                // away a perfectly good mSession, leaving this window with nativeUid/Pid
                // = -1 forever: no native sample, no real-time follow, and only a desktop
                // restart (which builds a new WindowState and layer) could recover.
                runCatching { motionLayer.motionSession = window.getObjectFieldAs<Any>("mSession") }
                    .onFailure {
                        motionLayer.motionSession = null
                        glassClient.record("motion window identity unavailable=${it.javaClass.simpleName}")
                    }
                if (motionLayer.motionSession != null) {
                    runCatching {
                        motionLayer.motionClient = window.getObjectFieldAs<Any>("mClient")
                            .callMethod("asBinder") as IBinder
                        glassClient.record("motion window identity bound")
                    }.onFailure {
                        // The client binder is only an optional Session fallback; the native
                        // binding needs mSession's uid/pid alone. Never discard the session.
                        motionLayer.motionClient = null
                        glassClient.record(
                            "motion window identity bound session-only=${it.javaClass.simpleName}")
                    }
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

    private fun updateMotionFrame(frameTimeNanos: Long, frameClock: FrameClock) {
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
                            layer.nativeMotion.reset()
                            layer.nativeApplied = false
                            layer.nativeScene = -1
                            layer.nativeSampleDeadlineNs = 0
                            continue
                        }
                        val now = DockRecentsMotion.frameTimeMillis(frameTimeNanos, layer.motionTime)
                        layer.motionTime = now
                        val y = layer.baseY + motionOffset(layer, now)
                        if (layer.nativeSampleDeadlineNs > System.nanoTime()
                            || (!layer.nativeApplied && layer.motion.isRunning(now))) needsFrame = true
                        if (layer.x.isFinite() && layer.baseY > 0 && y != layer.y) updates.add(layer to y)
                    }
                    if (updates.isNotEmpty()) {
                        val transaction = motionTransaction ?: loadClass(TRANSACTION)
                            .getConstructor().newInstance().also { motionTransaction = it }
                        for ((layer, y) in updates) transaction.callMethod(SET_POSITION, layer.effect, layer.x, y)
                        transaction.callMethod("setAnimationTransaction")
                        transaction.callMethod("setFrameTimelineVsync",
                            frameClock.choreographer.callMethod("getVsyncId") as Long)
                        transaction.callMethod("apply")
                        // Publish cached positions only after a successful submission.
                        for ((layer, y) in updates) {
                            layer.y = y
                            recordMotion(layer, y, layer.motionTime, true, "vsync")
                        }
                    }
                    directRecoveryDelay = 100L
                    if (needsFrame) scheduleAnimationFrame()
                }
            }
        }.onFailure {
            // Wallpaper/display replacement and suspend can invalidate a cached frame clock or
            // transaction temporarily. Keep the static background, but rebuild the direct path;
            // permanently disabling it makes all later gestures lose real-time following.
            directMotionAvailable = false
            runCatching { motionTransaction?.callMethod("close") }
            motionTransaction = null
            retireFrameClock(frameClock)
            glassClient.record("motion direct frame unavailable=${it.javaClass.simpleName}; rebuilding frame channel")
            requestTraversal()
            scheduleDirectMotionRecovery()
        }
    }

    private fun bindNativeMotion(window: Any, layer: Layer) {
        // Bind the Binder identity eagerly so early samples are never lost.
        // The native transport starts sending as soon as hooks are installed,
        // which may precede the first prepareSurfaces where the window is visible.
        // Re-read the window's Session on every update: HYOS builds a fresh Session for
        // each launcher process, and a cached one would pin this layer to a dead uid/pid,
        // so every sample is authenticated away until the desktop is restarted.
        val current = runCatching { window.getObjectFieldAs<Any>("mSession") }.getOrNull()
        if (current != null && current !== layer.motionSession) {
            layer.motionSession = current
            layer.resetNativeMotion()
        }
        val session = layer.motionSession ?: return
        runCatching {
            val uid = session.getObjectFieldAs<Int>("mUid")
            val pid = session.getObjectFieldAs<Int>("mPid")
            if (uid < 10000 || pid <= 0) return@runCatching
            if (uid != layer.nativeUid || pid != layer.nativePid) {
                layer.nativeMotion.reset()
                layer.nativeApplied = false
                layer.nativeScene = -1
                layer.nativeSampleDeadlineNs = 0
                layer.nativeUid = uid
                layer.nativePid = pid
                glassClient.record("native motion Binder identity uid=$uid pid=$pid")
            }
            // Re-assert on every traversal, not only when the uid/pid changes. The receiver
            // retains (and refuses to apply) every sample until this exact identity is bound,
            // and a module hot reload installs a fresh receiver whose identity store starts
            // empty. A layer that already holds the right uid/pid would then never bind it, so
            // real-time following looks dead until the window is recreated - leave and re-enter
            // the launcher - even though the native side keeps publishing samples.
            // bindIdentity also promotes an already-retained sample, so a frame that arrived
            // during the race is recovered in this same traversal instead of being dropped.
            nativeMotionEndpoint.bindIdentity(uid, pid)
        }.onFailure {
            if (observed.add("native-motion-identity")) {
                glassClient.record("native motion identity unavailable=${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Safety net for the receiver identity.
     *
     * [bindNativeMotion] re-asserts the exact uid/pid on every launcher traversal, but a
     * traversal is not guaranteed after the process is replaced: WMS can place the surface
     * once and then leave the window alone while the device idles. Because the receiver
     * retains - and refuses to apply - every sample until that identity is bound, a stale
     * identity would silently kill real-time following until the window happened to be
     * recreated, which is exactly the "leave and re-enter the launcher fixes it" symptom.
     * One field read per layer per second is cheap insurance against that.
     */
    private fun scheduleNativeBindSweep() {
        val wm = service ?: return
        if (stopped || !nativeBindSweepScheduled.compareAndSet(false, true)) return
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        handler.postDelayed({
            nativeBindSweepScheduled.set(false)
            if (stopped) return@postDelayed
            var keepGoing = false
            runCatching {
                // Preserve the WM -> layer lock order used by the wallpaper command path.
                synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                    synchronized(layers) {
                        if (stopped) return@postDelayed
                        keepGoing = layers.isNotEmpty()
                        layers.entries.toList().forEach { (window, layer) ->
                            bindNativeMotion(window, layer)
                        }
                    }
                }
            }.onFailure {
                if (observed.add("native-bind-sweep")) {
                    glassClient.record("native motion bind sweep failed=${it.javaClass.simpleName}")
                }
            }
            if (keepGoing) scheduleNativeBindSweep()
        }, NATIVE_BIND_SWEEP_MS)
    }

    // Called only under the layer lock. The authenticated Binder receiver publishes an
    // immutable latest sample; intermediate queued values never become a second animation.
    private fun motionOffset(layer: Layer, now: Long): Float {
        val sample = nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
        if (sample != null) {
            layer.nativeMotion.accept(sample, layer.overview)
            layer.nativeApplied = true
            layer.nativeSampleDeadlineNs = sample.uptimeNanos() + DockNativeMotion.MAX_AGE_NS
            if (sample.scene() == 1) {
                layer.nativeOverviewGeneration = layer.overviewGeneration
            }
            if (sample.scene() != layer.nativeScene) {
                layer.nativeScene = sample.scene()
                layer.motionSamples = 0
                layer.motionEndPending = true
                glassClient.record("native motion scene=${sample.scene()} scale=${sample.scale()}")
            }
            return layer.nativeMotion.offsetY(layer.density, layer.baseY)
        }
        if (layer.nativeApplied) {
            // A held recents gesture legitimately produces no changing scale samples.
            // Once the verified overview target has cleared, resume the local return
            // curve from the exact native position instead of freezing or snapping.
            if (layer.overview) return layer.nativeMotion.offsetY(layer.density, layer.baseY)
            layer.motion.resumeFrom(layer.nativeMotion.progress(), false, now)
            layer.nativeApplied = false
            layer.nativeMotion.reset()
            layer.nativeScene = -1
            layer.nativeSampleDeadlineNs = 0
            layer.motionSamples = 0
            layer.motionEndPending = true
            scheduleAnimationFrame()
            glassClient.record("native motion idle after overview exit; resuming return")
        }
        return layer.motion.offsetY(layer.density, layer.baseY, now)
    }

    private fun createFrameClock(handler: Handler): FrameClock {
        // OS4 exposes a factory for a non-ThreadLocal Choreographer. Owning the receiver lets us
        // dispose and truly recreate a channel whose mFrameScheduled stayed latched over suspend;
        // releasing either system ThreadLocal instance would break unrelated WMS callbacks.
        val dedicatedAttempt = runCatching {
            Choreographer::class.java.getDeclaredMethod(
                "getInstanceForSurfaceControl", Long::class.javaPrimitiveType, Looper::class.java)
                .apply { isAccessible = true }
                .invoke(null, 0L, handler.looper) as Choreographer
        }
        val dedicated = dedicatedAttempt.getOrNull()
        dedicatedAttempt.exceptionOrNull()?.let {
            if (observed.add("native-motion-dedicated-clock")) {
                glassClient.record("motion dedicated frame clock unavailable=${it.javaClass.simpleName}")
            }
        }
        val clock = if (dedicated != null) {
            FrameClock(dedicated, true)
        } else {
            val shared = runCatching {
                Choreographer::class.java.getDeclaredMethod("getSfInstance")
                    .apply { isAccessible = true }
                    .invoke(null) as Choreographer
            }.getOrElse { Choreographer.getInstance() }
            FrameClock(shared, false)
        }
        animationFrameClock = clock
        glassClient.record("motion frame clock ready direct=$directMotionAvailable owned=${clock.owned}")
        return clock
    }

    private fun retireFrameClock(expected: FrameClock? = animationFrameClock) {
        if (expected == null) return
        if (animationFrameClock === expected) animationFrameClock = null
        if (expected.owned) runCatching {
            Choreographer::class.java.getDeclaredMethod("invalidate")
                .apply { isAccessible = true }
                .invoke(expected.choreographer)
        }
    }

    private fun hasPendingMotionFrame(): Boolean {
        val now = SystemClock.uptimeMillis()
        return synchronized(layers) {
            layers.values.any { layer ->
                nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid) != null
                    || (layer.nativeApplied && !layer.overview)
                    || (!layer.nativeApplied && layer.motion.isRunning(now))
            }
        }
    }

    private fun scheduleAnimationFrame(urgent: Boolean = false) {
        val wm = service ?: return
        if (stopped || !directMotionAvailable) return
        val epoch = frameEpoch.incrementAndGet()
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        if (!scheduledFrameEpoch.compareAndSet(0, epoch)) {
            val pendingEpoch = scheduledFrameEpoch.get()
            val pendingSince = scheduledFrameStartedNs.get()
            if (urgent && pendingEpoch != 0L && pendingSince != 0L
                && requestedAt - pendingSince >= STALE_FRAME_NS
                && urgentFrameRecoveryScheduled.compareAndSet(false, true)) {
                handler.post {
                    try {
                        if (scheduledFrameEpoch.get() == pendingEpoch) {
                            glassClient.record(
                                "motion progress replaced stale frame epoch=$pendingEpoch")
                            recoverStalledFrame(pendingEpoch)
                        }
                    } finally {
                        urgentFrameRecoveryScheduled.set(false)
                    }
                }
            }
            return
        }
        scheduledFrameStartedNs.set(requestedAt)
        handler.post {
            if (scheduledFrameEpoch.get() != epoch) return@post
            if (stopped || !directMotionAvailable) clearScheduledFrame(epoch)
            else runCatching {
                val clock = animationFrameClock ?: createFrameClock(handler)
                val callback = Choreographer.FrameCallback { frameTimeNanos ->
                    if (!clearScheduledFrame(epoch)) return@FrameCallback
                    if (!stopped) {
                        if (directMotionAvailable) updateMotionFrame(frameTimeNanos, clock)
                        else requestTraversal()
                    }
                }
                scheduledFrame = ScheduledFrame(epoch, clock, callback)
                clock.choreographer.postFrameCallback(callback)
                // Handler time stops in deep sleep, so this runs shortly after resume even when
                // the pre-suspend Choreographer callback was silently discarded. The epoch makes
                // a late old callback harmless after a replacement frame has been posted.
                handler.postDelayed({ recoverStalledFrame(epoch) }, 100)
            }.onFailure {
                // Never let an optional animation callback throw on a system handler thread.
                animationAvailable = false
                clearScheduledFrame(epoch)
                glassClient.record("motion scheduling failed=${it.javaClass.simpleName}: ${it.message?.take(160)}")
                XposedLog.w(TAG, LOG_TAG, "Dock animation scheduling unavailable; rebuilding frame channel", it)
                requestTraversal()
                directMotionAvailable = false
                retireFrameClock()
                scheduleDirectMotionRecovery()
            }
        }
    }

    private fun clearScheduledFrame(epoch: Long): Boolean {
        if (!scheduledFrameEpoch.compareAndSet(epoch, 0)) return false
        scheduledFrameStartedNs.set(0)
        if (scheduledFrame?.epoch == epoch) scheduledFrame = null
        return true
    }

    private fun cancelScheduledFrame() {
        val scheduled = scheduledFrame
        if (scheduled != null) {
            runCatching { scheduled.clock.choreographer.removeFrameCallback(scheduled.callback) }
            scheduledFrame = null
        }
        scheduledFrameEpoch.set(0)
        scheduledFrameStartedNs.set(0)
    }

    private fun recoverStalledFrame(epoch: Long) {
        if (scheduledFrameEpoch.get() != epoch) return
        val scheduled = scheduledFrame
        if (scheduled?.epoch == epoch) {
            runCatching { scheduled.clock.choreographer.removeFrameCallback(scheduled.callback) }
        }
        if (!clearScheduledFrame(epoch)) return
        retireFrameClock(scheduled?.clock ?: animationFrameClock)
        glassClient.record("motion frame callback stalled; rebuilding frame clock epoch=$epoch")
        // Screen-off legitimately has no vsync. Retry immediately only while a real motion
        // sample/local curve is live; an idle keepalive must not start a 10 Hz recovery loop.
        if (hasPendingMotionFrame()) scheduleAnimationFrame()
    }

    private fun scheduleDirectMotionRecovery() {
        val wm = service ?: return
        if (stopped || !directRecoveryScheduled.compareAndSet(false, true)) return
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        val delay = directRecoveryDelay
        directRecoveryDelay = (directRecoveryDelay * 2).coerceAtMost(5_000L)
        handler.postDelayed({
            directRecoveryScheduled.set(false)
            if (stopped) return@postDelayed
            cancelScheduledFrame()
            retireFrameClock()
            animationAvailable = true
            directMotionAvailable = true
            glassClient.record("motion direct frame retry after lifecycle interruption delayMs=$delay")
            scheduleAnimationFrame()
        }, delay)
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
