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

/**
 * No provider IPC, waiting or HWUI work on the WMS thread/under the global WM lock.
 *
 * <p>This class runs inside system_server, so an exception that leaves any posted task or
 * framework callback reaches the process uncaught handler and becomes
 * "FATAL EXCEPTION IN SYSTEM PROCESS". Every asynchronous entry point therefore goes through
 * [guard], and recoverable failures are turned into a bounded retry instead of an escape.
 */
internal class DockGlassClient(private val processGuard: DockGlassProcessGuard, private val changed: () -> Unit) {
    class Ticket(val key: String, val context: Context,
        val bounds: DockWindowPolicy.Bounds, val dark: Boolean) {
        val id: String = UUID.randomUUID().toString()

        /**
         * Generation counter, single-flight recovery latch, retry budget, readiness and
         * refresh epochs. All bookkeeping lives here so the transitions are unit-testable
         * without an Android runtime.
         */
        val gate = DockGlassRecoveryGate()

        @Volatile var lease: DockGlassSurfaceLease? = null
        @Volatile var ready = false
        @Volatile var dead = false

        /** Retired by [DockGlassClient.release]; also permanently blocks later retries. */
        val cancelled: Boolean get() = gate.isCancelled()

        // Only touched by the serial IPC worker.
        var parcel: SurfaceControlViewHost.SurfacePackage? = null
        var lifetime: IBinder? = null
        var death: IBinder.DeathRecipient? = null
        var client: ContentProviderClient? = null
        var refreshes = 0

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
            catchingRecoverable({
                val client = context.contentResolver.acquireUnstableContentProviderClient(uri)
                    ?: return
                client.use {
                    it.call("dock_glass_record", null, Bundle().apply {
                        putStringArray("events", events.toTypedArray())
                    }) ?: error("No journal response")
                }
                events.clear()
            })
            // If boot-time provider acquisition fails, retain the bounded queue for the next event.
        }

        fun finish() {
            worker().removeCallbacks(diagnosticFlush)
            flushDiagnostics()
        }
    }

    fun bindDiagnostics(context: Context) { if (!closed) journal.bind(context) }
    fun record(event: String) { if (!closed) journal.record(event) }

    /**
     * Run one asynchronous DockGlass task behind a hard exception boundary.
     *
     * <p>Only [Exception] is caught: `OutOfMemoryError`, `StackOverflowError` and `ThreadDeath`
     * keep their normal, fatal behaviour rather than being silently swallowed. The boundary
     * exists so a defect in DockGlass degrades glass instead of killing system_server.
     */
    private fun guard(task: String, body: () -> Unit) {
        try {
            body()
        } catch (error: Exception) {
            val reason = "${error.javaClass.simpleName}: ${error.message?.take(160)}"
            try {
                record("glass task failed task=$task reason=$reason")
            } catch (ignored: Exception) {
                // The diagnostic path must never mask the original failure.
            }
            try {
                XposedLog.w("DockGlass", "system", "DockGlass $task failed: $reason")
            } catch (ignored: Exception) {
            }
        }
    }

    fun create(context: Context, key: String, bounds: DockWindowPolicy.Bounds, dark: Boolean): Ticket {
        val ticket = Ticket(key, context, bounds, dark)
        bindDiagnostics(context)
        worker.post { guard("create") { attemptCreate(ticket) } }
        return ticket
    }

    private fun attemptCreate(ticket: Ticket) {
        if (!ticket.gate.beginAttempt(closed)) return
        ticket.dead = false
        record("glass create id=${ticket.id} attempt=${ticket.gate.getAttempts()}")
        try {
            check(dispose(ticket)) { "Previous glass surface could not be detached" }
            if (!processGuard.acquire(ticket.context, ticket)) {
                // The package is mid-replacement: nothing is broken and nothing may be
                // disposed. Park the generation and retry on the dependency cadence.
                deferForDependency(ticket)
                return
            }
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
            val generation = ticket.gate.getAttempts()
            val readinessEpoch = ticket.gate.openReadinessEpoch()
            val death = IBinder.DeathRecipient {
                worker.post {
                    guard("renderer death") {
                        if (!ticket.gate.isAttemptCurrent(generation)) {
                            if (ticket.gate.noteStaleCallback()) {
                                record("glass stale death callback id=${ticket.id} " +
                                    "generation=$generation current=${ticket.gate.getAttempts()}")
                            }
                            return@guard
                        }
                        recover(ticket, "renderer died")
                    }
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
            checkBackground(ticket, 0, generation, readinessEpoch)
        } catch (error: Exception) {
            // Covers the framework exception types this path used to catch individually
            // (IllegalStateException, RemoteException, SecurityException) plus any reflection
            // failure from the guard/framework calls. VM fatals are intentionally not caught.
            recover(ticket, "create ${error.javaClass.simpleName}: ${error.message?.take(160)}")
        }
    }

    private fun checkBackground(ticket: Ticket, attempt: Int, generation: Int, readinessEpoch: Int) {
        worker.postDelayed({
            guard("readiness") {
                if (ticket.cancelled || ticket.dead || closed) return@guard
                if (!ticket.gate.isReadinessCurrent(generation, readinessEpoch)) {
                    if (ticket.gate.noteStaleCallback()) {
                        record("glass stale readiness id=${ticket.id} generation=$generation " +
                            "current=${ticket.gate.getAttempts()}")
                    }
                    return@guard
                }
                try {
                    val status = ticket.request("dock_glass_status")
                    status.getString("error")?.let { error(it) }
                    val wasReady = ticket.ready
                    ticket.ready = ticket.lease?.isAttached == true && status.getBoolean("backgroundReady")
                    if (wasReady != ticket.ready) changed()
                    if (ticket.ready) {
                        ticket.gate.markReady()
                        record("glass ready id=${ticket.id} attempt=${ticket.gate.getAttempts()} check=${attempt + 1}")
                    } else if (attempt + 1 < DockGlassRetryPolicy.BACKGROUND_CHECKS) {
                        checkBackground(ticket, attempt + 1, generation, readinessEpoch)
                    } else {
                        recover(ticket, "background texture timeout after ${attempt + 1} checks")
                    }
                } catch (error: Exception) {
                    recover(ticket, "status ${error.javaClass.simpleName}: ${error.message?.take(160)}")
                }
            }
        }, 500)
    }

    fun attach(ticket: Ticket, parent: Any) {
        val lease = ticket.lease ?: return
        // Never queue remote reparent operations in WMS's deferred sync transaction:
        // it could commit AFTER worker cleanup and resurrect a retired surface.
        worker.post {
            guard("attach") {
                val stale = closed || ticket.cancelled || ticket.dead || ticket.lease !== lease
                if (stale) {
                    if (ticket.gate.noteStaleCallback()) {
                        record("glass stale attach ignored id=${ticket.id} closed=$closed " +
                            "cancelled=${ticket.cancelled} dead=${ticket.dead}")
                    }
                    return@guard
                }
                catchingRecoverable({ lease.attach(parent) }) {
                    recover(ticket, "surface attachment failed: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    /** Verify HyperCeiler's own texture after its parent becomes visible again. */
    fun resume(ticket: Ticket, allowFallback: Boolean = true) {
        // Visibility and wallpaper-home callbacks can describe the same return.
        val requestEpoch = ticket.gate.requestRefresh(SystemClock.uptimeMillis())
        if (requestEpoch == DockGlassRecoveryGate.REFRESH_DEDUPLICATED) return
        // The parent's show is part of WMS's sync transaction. Probe after that
        // transaction commits; a healthy producer is never toggled or recreated.
        worker.postDelayed({
            guard("resume probe") {
                if (closed || ticket.cancelled || ticket.dead || ticket.lease == null) return@guard
                if (!ticket.gate.isRefreshCurrent(requestEpoch)) {
                    if (ticket.gate.noteStaleCallback()) {
                        record("glass stale resume probe id=${ticket.id} epoch=$requestEpoch")
                    }
                    return@guard
                }
                // Initial creation already owns its readiness loop. A resume probe is
                // meaningful only after this generation has displayed native glass.
                if (!ticket.gate.isPreviouslyReady()) return@guard
                catchingRecoverable({
                    val response = ticket.request("dock_glass_probe")
                    response.getString("error")?.let { error(it) }
                    val healthy = ticket.lease?.isAttached == true
                        && response.getBoolean("backgroundReady")
                    if (healthy) {
                        val wasReady = ticket.ready
                        ticket.ready = true
                        ticket.gate.markReady()
                        if (!wasReady) changed()
                    } else if (!allowFallback) {
                        // Settle window: the wallpaper swap keeps the producer busy, so an unhealthy
                        // probe here is expected and transient. Keep the current material on screen
                        // instead of flashing the compositor fallback and restarting the producer;
                        // the caller re-probes once the window closes.
                        record("glass probe unhealthy during settle; keeping current material")
                    } else {
                        // Put compositor fallback behind the Dock before restarting the
                        // private producer. This prevents a transparent/white flash.
                        val wasReady = ticket.ready
                        ticket.ready = false
                        if (wasReady) changed()
                        worker.postDelayed(
                            { guard("refresh") { hardRefresh(ticket, requestEpoch) } },
                            if (wasReady) 80 else 0)
                    }
                }) {
                    recover(ticket, "resume probe ${it.javaClass.simpleName}: ${it.message?.take(160)}")
                }
            }
        }, 50)
    }

    private fun hardRefresh(ticket: Ticket, requestEpoch: Int) {
        if (closed || ticket.cancelled || ticket.dead || ticket.lease == null) return
        if (!ticket.gate.isRefreshCurrent(requestEpoch)) {
            if (ticket.gate.noteStaleCallback()) {
                record("glass stale refresh ignored id=${ticket.id} epoch=$requestEpoch")
            }
            return
        }
        catchingRecoverable({
            val response = ticket.request("dock_glass_refresh")
            response.getString("error")?.let { error(it) }
            ticket.refreshes++
            val generation = ticket.gate.getAttempts()
            val readinessEpoch = ticket.gate.openReadinessEpoch()
            if (ticket.refreshes <= 8) {
                record("glass stale producer restarted id=${ticket.id} count=${ticket.refreshes}")
            }
            checkBackground(ticket, 0, generation, readinessEpoch)
        }) {
            recover(ticket, "refresh ${it.javaClass.simpleName}: ${it.message?.take(160)}")
        }
    }

    /** Cancel delayed refresh/readiness work while the launcher parent is hidden. */
    fun pauseRefresh(ticket: Ticket) {
        ticket.gate.pauseRefresh()
        worker.post { guard("pause refresh") { ticket.gate.invalidateReadiness() } }
    }

    private fun recover(ticket: Ticket, reason: String) {
        val wasReady = ticket.ready
        val outcome = ticket.gate.requestRecovery(closed, wasReady)
        if (outcome == DockGlassRecoveryGate.Outcome.IGNORED) return
        ticket.dead = true
        ticket.ready = false
        dispose(ticket)
        record("glass failed id=${ticket.id} attempt=${ticket.gate.getAttempts()} " +
            "failures=${ticket.gate.getConsecutiveFailures()} " +
            "deduplicated=${ticket.gate.getDeduplicatedRecoveries()} " +
            "retryMs=${ticket.gate.getRetryDelayMs()} reason=$reason")
        changed()
        scheduleRetry(ticket, outcome, ticket.gate.getRetryDelayMs())
    }

    /**
     * The HyperCeiler package itself is momentarily unresolvable, typically because it is
     * being replaced by an in-place upgrade.
     *
     * <p>Nothing is disposed and no compatibility budget is consumed: this is an external,
     * self-healing outage. The retry cadence is capped, so a long outage stays bounded.
     */
    private fun deferForDependency(ticket: Ticket) {
        val outcome = ticket.gate.requestDependencyDefer(closed)
        if (outcome == DockGlassRecoveryGate.Outcome.IGNORED) return
        ticket.dead = true
        if (ticket.ready) {
            ticket.ready = false
            changed()
        }
        if (ticket.gate.shouldReportDependencyDefer()) {
            record("glass dependency unavailable id=${ticket.id} " +
                "attempt=${ticket.gate.getAttempts()} retryMs=${ticket.gate.getRetryDelayMs()}")
        }
        scheduleRetry(ticket, outcome, ticket.gate.getRetryDelayMs())
    }

    private fun scheduleRetry(ticket: Ticket, outcome: DockGlassRecoveryGate.Outcome, delayMs: Long) {
        when (outcome) {
            DockGlassRecoveryGate.Outcome.RETRY_NOW,
            DockGlassRecoveryGate.Outcome.RETRY_LATER ->
                worker.postDelayed({ guard("retry") { attemptCreate(ticket) } }, delayMs)
            DockGlassRecoveryGate.Outcome.EXHAUSTED ->
                XposedLog.w("DockGlass", "system", "Glass recovery budget exhausted; retaining fallback")
            DockGlassRecoveryGate.Outcome.IGNORED -> Unit
        }
    }

    fun release(ticket: Ticket) {
        // Retire exactly once: a repeated release must not queue a second teardown, and it
        // must also cancel every retry/recovery already scheduled for this ticket.
        if (!ticket.gate.markRetired()) {
            record("glass release ignored id=${ticket.id} reason=already retired")
            return
        }
        record("glass cancelled id=${ticket.id}")
        worker.post { guard("release") { dispose(ticket) } }
    }

    fun close() {
        if (closed) return
        // Marking the client closing first stops every later task from touching resources,
        // including retries already posted by recover().
        closed = true
        if (workerDelegate.isInitialized()) worker.post {
            guard("closing") {
                journal.record("glass client closing")
                journal.finish()
            }
            worker.looper.quitSafely()
        }
    }

    private fun dispose(ticket: Ticket): Boolean {
        ticket.ready = false
        ticket.death?.let { catchingRecoverable({ ticket.lifetime?.unlinkToDeath(it, 0) }) }
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
            if (ticket.client != null) catchingRecoverable({ ticket.request("dock_glass_release") })
        } finally {
            catchingRecoverable({ ticket.client?.close() })
            ticket.client = null
            processGuard.release(ticket)
        }
        return true
    }

}
