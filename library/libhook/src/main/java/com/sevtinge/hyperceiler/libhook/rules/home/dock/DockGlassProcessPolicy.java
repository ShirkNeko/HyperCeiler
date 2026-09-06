/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.function.IntUnaryOperator;

/** In-memory leases only. UID ownership is verified by the Android caller. */
public final class DockGlassProcessPolicy {
    private record Owner(int uid, int pid) {}
    private final IdentityHashMap<Object, Owner> owners = new IdentityHashMap<>();

    public synchronized void acquire(Object token, int uid) {
        if (uid < 10000) throw new IllegalArgumentException("An application UID is required");
        owners.put(token, new Owner(uid, -1));
    }

    public synchronized void setPid(Object token, int pid) {
        Owner owner = owners.get(token);
        if (owner != null && pid > 0) owners.put(token, new Owner(owner.uid, pid));
    }

    public synchronized void release(Object token) { owners.remove(token); }
    public synchronized void clear() { owners.clear(); }

    public synchronized int[] filter(int[] requested, boolean pids, IntUnaryOperator uidForPid) {
        if (owners.isEmpty()) return requested;
        int[] result = new int[requested.length];
        int size = 0;
        for (int value : requested) {
            boolean protectedOwner = false;
            for (Owner owner : owners.values()) {
                if (value == (pids ? owner.pid : owner.uid) && value > 0
                        && (!pids || uidForPid.applyAsInt(value) == owner.uid)) {
                    protectedOwner = true;
                    break;
                }
            }
            if (!protectedOwner) result[size++] = value;
        }
        return size == requested.length ? requested : Arrays.copyOf(result, size);
    }
}
