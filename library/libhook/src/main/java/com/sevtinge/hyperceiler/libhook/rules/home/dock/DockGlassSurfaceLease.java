/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** One renderer generation. All operations run on the same serial worker. */
public final class DockGlassSurfaceLease implements AutoCloseable {
    public interface Operations {
        void attach(Object parent);
        void detach();
        void release();
    }

    private final Operations operations;
    private boolean attached;
    private boolean retired;
    private boolean released;

    public DockGlassSurfaceLease(Operations operations) {
        this.operations = operations;
    }

    public boolean isAttached() {
        return attached && !retired;
    }

    public void attach(Object parent) {
        if (retired || attached) return;
        operations.attach(parent);
        attached = true;
    }

    @Override
    public void close() {
        if (released) return;
        // Retire BEFORE detaching: even a failed cleanup must reject late attaches.
        retired = true;
        // Also detach after a partially failed attach. Do not release the last
        // handle on failure; close() can retry without orphaning a visible root.
        operations.detach();
        operations.release();
        attached = false;
        released = true;
    }
}
