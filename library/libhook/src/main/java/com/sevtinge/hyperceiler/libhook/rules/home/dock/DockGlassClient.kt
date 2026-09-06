/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.Context
import android.content.ContentProviderClient
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import com.sevtinge.hyperceiler.common.log.XposedLog
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import java.util.UUID

/** No provider IPC, waiting or HWUI work on the WMS thread/under the global WM lock. */
internal class DockGlassClient(private val processGuard: DockGlassProcessGuard, private val changed: () -> Unit) {
    class Ticket(val key: String, val context: Context,
        val bounds: DockWindowPolicy.Bounds, val dark: Boolean) {
        val id: String = UUID.randomUUID().toString()
        @Volatile var lease: DockGlassSurfaceLease? = null
        @Volatile var ready = false
        @Volatile var dead = false
        @Volatile var cancelled = false
        // Only touched by the serial IPC worker.
        var parcel: SurfaceControlViewHost.SurfacePackage? = null
        var lifetime: IBinder? = null
        var death: IBinder.DeathRecipient? = null
        var attempts = 0
        var recoveryPending = false
        var client: ContentProviderClient? = null

        fun request(method: String, args: Bundle? = null): Bundle {
            // Keep an UNSTABLE reference for the lifetime of the active windowless host,
            // instead of acquiring/releasing its process around every readiness check.
            // Renderer death still never makes system_server a stable provider dependent.
            val activeClient = client ?: context.contentResolver.acquireUnstableContentProviderClient(uri)
                ?.also { client = it } ?: error("HyperCeiler provider unavailable")
            // All callers recover/dispose on failure. Let dispose try releasing an
            // existing live host before closing the reference, even after a failed call.
            return activeClient.call(method, id, args) ?: Bundle.EMPTY
        }
    }

    private val owner = Binder()
    private val workerDelegate = lazy {
        Handler(HandlerThread("HyperCeiler-DockGlass-IPC").apply { start() }.looper)
    }
    private val worker by workerDelegate
    private companion object {
        val uri: Uri = Uri.parse("content://com.sevtinge.hyperceiler.provider.sharedprefs")
    }
    @Volatile private var closed = false
    private val journal = Journal { worker }

    private class Journal(private val worker: () -> Handler) {
        @Volatile private var diagnosticContext: Context? = null
        private val events = ArrayDeque<String>() // IPC worker only; no frame-by-frame history.
        private var diagnosticFlushScheduled = false
        private val diagnosticFlush = Runnable {
            diagnosticFlushScheduled = false
            flushDiagnostics()
        }

        fun bind(context: Context) {
            if (diagnosticContext != null) return
            diagnosticContext = context
            worker().post { flushDiagnostics() }
        }

        fun record(event: String) {
            val message = "wall=${System.currentTimeMillis()} up=${SystemClock.uptimeMillis()} $event".take(512)
            // Deliberately independent of the release build's error-only Xposed log filter.
            Log.i("HyperCeiler.DockGlass", message)
            worker().post {
                if (events.size >= 96) events.removeFirst()
                events.addLast(message)
                if (!diagnosticFlushScheduled) {
                    diagnosticFlushScheduled = true
                    worker().postDelayed(diagnosticFlush, 250)
                }
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

        fun finish() {
            worker().removeCallbacks(diagnosticFlush)
            flushDiagnostics()
        }
    }

    fun bindDiagnostics(context: Context) { if (!closed) journal.bind(context) }
    fun record(event: String) { if (!closed) journal.record(event) }

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
            check(dispose(ticket)) { "Previous glass surface could not be detached" }
            processGuard.acquire(ticket.context, ticket)
            val bounds = ticket.bounds
            val args = Bundle().apply {
                putInt("width", bounds.width()); putInt("height", bounds.height())
                putFloat("radius", bounds.radius()); putBoolean("dark", ticket.dark)
                putBinder("owner", owner)
            }
            val response = ticket.request("dock_glass_create", args)
            processGuard.setPid(ticket, response.getInt("rendererPid", -1))
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
            val parcel = ticket.parcel!!
            val surface = parcel.callMethod("getSurfaceControl") as? SurfaceControl
                ?: error("Renderer returned no SurfaceControl")
            ticket.lease = DockGlassSurfaceLease(object : DockGlassSurfaceLease.Operations {
                override fun attach(parent: Any) {
                    SurfaceControl.Transaction().use { transaction ->
                        transaction.reparent(surface, parent as SurfaceControl)
                        transaction.setLayer(surface, 2)
                        transaction.setPosition(surface, 0f, 0f)
                        transaction.callMethod("setWindowCrop", surface, bounds.width(), bounds.height())
                        transaction.callMethod("show", surface)
                        transaction.apply()
                    }
                }

                override fun detach() {
                    // release() only drops our handle; an attached server-side root
                    // otherwise survives renderer death underneath the Dock parent.
                    if (surface.isValid) SurfaceControl.Transaction().use {
                        it.reparent(surface, null).apply()
                    }
                }

                override fun release() { parcel.release() }
            })
            changed()
            // Parent first, then wait for the vendor background texture, not just a drawn buffer.
            checkBackground(ticket, 0, generation)
        } catch (error: Exception) {
            recover(ticket, "create ${error.javaClass.simpleName}: ${error.message?.take(160)}")
        }
    }

    private fun checkBackground(ticket: Ticket, attempt: Int, generation: Int) {
        worker.postDelayed(fun() {
            if (ticket.cancelled || ticket.dead || closed) return
            if (generation != ticket.attempts) return
            try {
                val status = ticket.request("dock_glass_status")
                status.getString("error")?.let { error(it) }
                ticket.ready = ticket.lease?.isAttached == true && status.getBoolean("backgroundReady")
                if (ticket.ready) {
                    record("glass ready id=${ticket.id} attempt=${ticket.attempts} check=${attempt + 1}")
                    changed()
                } else if (attempt + 1 < DockGlassRetryPolicy.BACKGROUND_CHECKS) {
                    checkBackground(ticket, attempt + 1, generation)
                } else {
                    recover(ticket, "background texture timeout after ${attempt + 1} checks")
                }
            } catch (error: Exception) {
                recover(ticket, "status ${error.javaClass.simpleName}: ${error.message?.take(160)}")
            }
        }, 500)
    }

    fun attach(ticket: Ticket, parent: Any) {
        val lease = ticket.lease ?: return
        // Never queue remote reparent operations in WMS's deferred sync transaction:
        // it could commit AFTER worker cleanup and resurrect a retired surface.
        worker.post {
            val shouldSkip = closed || ticket.cancelled || ticket.dead || ticket.lease !== lease
            if (shouldSkip) return@post
            runCatching { lease.attach(parent) }.onFailure {
                recover(ticket, "surface attachment failed: ${it.javaClass.simpleName}")
            }
        }
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
        if (workerDelegate.isInitialized()) worker.post {
            journal.finish()
            worker.looper.quitSafely()
        }
    }

    private fun dispose(ticket: Ticket): Boolean {
        ticket.ready = false
        ticket.death?.let { runCatching { ticket.lifetime?.unlinkToDeath(it, 0) } }
        ticket.death = null
        ticket.lifetime = null
        try {
            val lease = ticket.lease
            if (lease != null) lease.close() else ticket.parcel?.release()
        } catch (error: RuntimeException) {
            // Keep the last handle and do not create another host until cleanup
            // succeeds. Recovery's bounded backoff retries this same retirement.
            record("glass detach failed id=${ticket.id}: ${error.javaClass.simpleName}")
            return false
        }
        ticket.lease = null
        ticket.parcel = null
        // Do not reacquire/start a renderer merely to release a failed acquisition.
        try {
            if (ticket.client != null) runCatching { ticket.request("dock_glass_release") }
        } finally {
            runCatching { ticket.client?.close() }
            ticket.client = null
            processGuard.release(ticket)
        }
        return true
    }

}
