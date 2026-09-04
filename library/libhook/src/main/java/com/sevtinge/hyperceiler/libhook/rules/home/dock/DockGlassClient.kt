/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceControlViewHost
import com.sevtinge.hyperceiler.common.log.XposedLog
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import java.util.UUID

/** No provider IPC, waiting or HWUI work on the WMS thread/under the global WM lock. */
internal class DockGlassClient(private val changed: () -> Unit) {
    class Ticket(val key: String, val context: Context,
        val bounds: DockWindowPolicy.Bounds, val dark: Boolean) {
        val id: String = UUID.randomUUID().toString()
        @Volatile var surface: Any? = null
        @Volatile var ready = false
        @Volatile var dead = false
        @Volatile var cancelled = false
        // Only touched by the serial IPC worker.
        var parcel: SurfaceControlViewHost.SurfacePackage? = null
        var lifetime: IBinder? = null
        var death: IBinder.DeathRecipient? = null
        var attempts = 0
        var recoveryPending = false
    }

    private val owner = Binder()
    private val workerDelegate = lazy {
        Handler(HandlerThread("HyperCeiler-DockGlass-IPC").apply { start() }.looper)
    }
    private val worker by workerDelegate
    private val uri = Uri.parse("content://com.sevtinge.hyperceiler.provider.sharedprefs")
    @Volatile private var closed = false
    @Volatile private var diagnosticContext: Context? = null
    private val events = ArrayDeque<String>() // IPC worker only; no frame-by-frame history.

    fun bindDiagnostics(context: Context) {
        if (diagnosticContext != null || closed) return
        diagnosticContext = context
        worker.post { flushDiagnostics() }
    }

    fun record(event: String) {
        if (closed) return
        val message = "wall=${System.currentTimeMillis()} up=${SystemClock.uptimeMillis()} $event".take(512)
        // Deliberately independent of the release build's error-only Xposed log filter.
        Log.i("HyperCeiler.DockGlass", message)
        worker.post {
            if (events.size >= 96) events.removeFirst()
            events.addLast(message)
            flushDiagnostics()
        }
    }

    private fun flushDiagnostics() {
        val context = diagnosticContext ?: return
        if (events.isEmpty()) return
        runCatching {
            val client = context.contentResolver.acquireUnstableContentProviderClient(uri)
                ?: return
            client.use {
                it.call("dock_glass_record", null, Bundle().apply {
                    putStringArray("events", events.toTypedArray())
                }) ?: error("No journal response")
            }
            events.clear()
        }
        // If boot-time provider acquisition fails, retain the bounded queue for the next event.
    }

    fun create(context: Context, key: String, bounds: DockWindowPolicy.Bounds, dark: Boolean): Ticket {
        val ticket = Ticket(key, context, bounds, dark)
        bindDiagnostics(context)
        worker.post { attemptCreate(ticket) }
        return ticket
    }

    private fun attemptCreate(ticket: Ticket) {
        if (ticket.cancelled || closed) return
        ticket.recoveryPending = false
        ticket.attempts++
        ticket.dead = false
        record("glass create id=${ticket.id} attempt=${ticket.attempts}")
        try {
            val bounds = ticket.bounds
            val args = Bundle().apply {
                putInt("width", bounds.width()); putInt("height", bounds.height())
                putFloat("radius", bounds.radius()); putBoolean("dark", ticket.dark)
                putBinder("owner", owner)
            }
            val response = request(ticket, "dock_glass_create", args)
            response.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
            ticket.parcel = response.getParcelable("surface", SurfaceControlViewHost.SurfacePackage::class.java)
                ?: error(response.getString("error") ?: "Renderer returned no surface")
            ticket.lifetime = response.getBinder("lifetime") ?: error("Renderer returned no lifetime token")
            if (ticket.cancelled || closed) { dispose(ticket); return }
            val generation = ticket.attempts
            val death = IBinder.DeathRecipient {
                worker.post {
                    if (generation == ticket.attempts) recover(ticket, "renderer died")
                }
            }
            ticket.death = death
            ticket.lifetime!!.linkToDeath(death, 0)
            ticket.surface = ticket.parcel!!.callMethod("getSurfaceControl")
                ?: error("Renderer returned no SurfaceControl")
            changed()
            // Parent first, then wait for the vendor background texture, not just a drawn buffer.
            checkBackground(ticket, 0, generation)
        } catch (error: Throwable) {
            recover(ticket, "create ${error.javaClass.simpleName}: ${error.message?.take(160)}")
        }
    }

    private fun checkBackground(ticket: Ticket, attempt: Int, generation: Int) {
        worker.postDelayed({
            if (ticket.cancelled || ticket.dead || closed || generation != ticket.attempts) return@postDelayed
            try {
                val status = request(ticket, "dock_glass_status")
                status.getString("error")?.let { error(it) }
                ticket.ready = status.getBoolean("backgroundReady")
                if (ticket.ready) {
                    record("glass ready id=${ticket.id} attempt=${ticket.attempts} check=${attempt + 1}")
                    changed()
                } else if (attempt + 1 < DockGlassRetryPolicy.BACKGROUND_CHECKS) {
                    checkBackground(ticket, attempt + 1, generation)
                } else {
                    recover(ticket, "background texture timeout after ${attempt + 1} checks")
                }
            } catch (error: Throwable) {
                recover(ticket, "status ${error.javaClass.simpleName}: ${error.message?.take(160)}")
            }
        }, 500)
    }

    fun surfaceFailed(ticket: Ticket) {
        ticket.dead = true
        ticket.ready = false
        worker.post { recover(ticket, "surface attachment failed") }
    }

    private fun recover(ticket: Ticket, reason: String) {
        if (ticket.cancelled || closed || ticket.recoveryPending) return
        ticket.recoveryPending = true
        ticket.dead = true
        ticket.ready = false
        dispose(ticket)
        val delay = DockGlassRetryPolicy.delayAfterFailure(ticket.attempts)
        record("glass failed id=${ticket.id} attempt=${ticket.attempts} reason=$reason retryMs=$delay")
        changed()
        if (delay >= 0) worker.postDelayed({ attemptCreate(ticket) }, delay)
        else XposedLog.w("DockGlass", "system", "Glass recovery budget exhausted; retaining fallback")
    }

    fun release(ticket: Ticket) {
        ticket.cancelled = true
        record("glass cancelled id=${ticket.id}")
        worker.post { dispose(ticket) }
    }

    fun close() {
        closed = true
        if (workerDelegate.isInitialized()) worker.post { worker.looper.quitSafely() }
    }

    private fun dispose(ticket: Ticket) {
        ticket.ready = false
        ticket.surface = null
        ticket.death?.let { runCatching { ticket.lifetime?.unlinkToDeath(it, 0) } }
        ticket.death = null
        runCatching { ticket.parcel?.release() }
        ticket.parcel = null
        runCatching { request(ticket, "dock_glass_release") }
    }

    private fun request(ticket: Ticket, method: String, args: Bundle? = null): Bundle {
        // An unstable client avoids making system_server depend on the app process surviving.
        val client = ticket.context.contentResolver.acquireUnstableContentProviderClient(uri)
            ?: error("HyperCeiler provider unavailable")
        client.use { return it.call(method, ticket.id, args) ?: Bundle.EMPTY }
    }
}
