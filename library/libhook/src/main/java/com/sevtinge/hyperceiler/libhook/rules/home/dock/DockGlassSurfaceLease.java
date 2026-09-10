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
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

/** One renderer generation. All operations run on the same serial worker. */
public final class DockGlassSurfaceLease implements AutoCloseable {
    private final Operations operations;
    private boolean attached;
    private boolean retired;
    private boolean released;

    public interface Operations {
        void attach(Object parent);
        void detach();
        void release();
    }

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
