/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.Context
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

/** Protect only verified HyperCeiler render leases, never another application's UID. */
internal class DockGlassProcessGuard {
    private val policy = DockGlassProcessPolicy()
    private var serviceClass: Class<*>? = null
    @Volatile private var closed = false

    fun install() {
        val clazz = loadClass("com.miui.server.greeze.GreezeManagerService")
        val uidMethod = clazz.getDeclaredMethod("freezeUids", IntArray::class.java,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            Boolean::class.javaPrimitiveType)
        val pidMethod = clazz.getDeclaredMethod("freezePids", IntArray::class.java,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        val pidUid = android.os.Process::class.java.getDeclaredMethod("getUidForPid", Int::class.javaPrimitiveType)
        for ((method, pids) in listOf(uidMethod to false, pidMethod to true)) {
            method.isAccessible = true
            method.createBeforeHook { param ->
                val original = param.args[0] as? IntArray
                if (!closed && original != null) {
                    val filtered = policy.filter(original, pids) { pid ->
                        // Check only candidate renderer PIDs, avoiding a PID-reuse exemption.
                        runCatching { pidUid.invoke(null, pid) as Int }.getOrDefault(-1)
                    }
                    if (filtered !== original) {
                        if (filtered.isEmpty()) param.result = ArrayList<Int>()
                        else param.args[0] = filtered
                    }
                }
            }
        }
        serviceClass = clazz
    }

    // Called only on the IPC worker; no PackageManager or thaw calls under WM locks.
    fun acquire(context: Context, token: Any) {
        check(!closed) { "Renderer guard closed" }
        val clazz = checkNotNull(serviceClass) { "OS4 renderer lifecycle API unavailable" }
        val pkg = "com.sevtinge.hyperceiler"
        val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
        val packages = context.packageManager.getPackagesForUid(uid)
        check(packages?.size == 1 && packages[0] == pkg) { "Renderer UID ownership is ambiguous" }
        policy.acquire(token, uid)
        try {
            val service = clazz.getDeclaredMethod("getService").invoke(null)
                ?: error("OS4 freezer service unavailable")
            val frozen = clazz.getDeclaredMethod("isUidFrozen", Int::class.javaPrimitiveType)
                .invoke(service, uid) == true
            if (frozen) {
                val modules = loadClass("com.miui.server.greeze.GreezeServiceUtils")
                val allModules = modules.getField("GREEZER_MODULE_ALL").getInt(null)
                check(clazz.getDeclaredMethod("thawUid", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java)
                    .invoke(service, uid, allModules, "HyperCeiler Dock renderer Start") == true) {
                    "Renderer could not be thawed"
                }
            }
        } catch (error: ReflectiveOperationException) {
            policy.release(token)
            throw error
        }
    }

    fun setPid(token: Any, pid: Int) { policy.setPid(token, pid) }
    fun release(token: Any) { policy.release(token) }
    fun close() { closed = true; policy.clear() }
}
