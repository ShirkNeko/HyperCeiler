/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One reader per exact visible launcher window. No socket I/O on a WMS thread. */
internal class DockNativeMotionClient(
    val uid: Int, val pid: Int,
    private val changed: () -> Unit,
    private val diagnostic: (String) -> Unit
) : AutoCloseable {
    @Volatile var latest: DockNativeMotion.Sample? = null
        private set
    @Volatile var connected = false
        private set
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: LocalSocket? = null
    private val reader = Thread({ readLoop() }, "HyperCeiler-DockMotion").apply { isDaemon = true }

    fun start() { reader.start() }

    private fun readLoop() {
        // Bounded startup/reconnection attempts; no periodic task when connected/idle.
        var failures = 0
        while (!closed.get() && failures < 8) {
            val current = LocalSocket()
            socket = current
            try {
                if (closed.get()) break
                current.connect(LocalSocketAddress("hyperceiler.dock.motion.$pid", LocalSocketAddress.Namespace.ABSTRACT))
                val peer = current.peerCredentials
                check(peer.uid == uid && peer.pid == pid) { "Wrong launcher socket owner" }
                var sequence = 0L
                val packet = ByteArray(DockNativeMotion.PACKET_SIZE)
                val input = DataInputStream(current.inputStream)
                while (!closed.get()) {
                    input.readFully(packet)
                    val sample = DockNativeMotion.decode(packet, sequence, System.nanoTime())
                        ?: error("Invalid/stale native motion packet")
                    sequence = sample.sequence()
                    latest = sample
                    if (!connected) {
                        connected = true
                        diagnostic("native motion connected uid=$uid pid=$pid")
                    }
                    changed()
                }
            } catch (error: Exception) {
                if (!closed.get() && (failures == 0 || failures == 7)) {
                    diagnostic("native motion unavailable attempt=${failures + 1} error=${error.javaClass.simpleName}: ${error.message?.take(100)}")
                }
            } finally {
                connected = false
                latest = null
                runCatching { current.close() }
                if (socket === current) socket = null
                if (!closed.get()) changed()
            }
            failures++
            if (!closed.get()) {
                try { Thread.sleep(minOf(5000L, 500L shl minOf(failures - 1, 4))) }
                catch (_: InterruptedException) { break }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        latest = null
        reader.interrupt()
        // close/shutdown can enter the kernel: perform them outside the global WM lock.
        val current = socket
        if (current != null) Thread({ runCatching { current.close() } }, "HyperCeiler-DockMotion-Close")
            .apply { isDaemon = true; start() }
    }
}
