/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.tests.dock;

import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockGlassSurfaceLease;

public final class DockGlassSurfaceLeaseTest {
    private static final class Fake implements DockGlassSurfaceLease.Operations {
        boolean parented;
        boolean failAttach;
        boolean failDetach;
        int attaches;
        int releases;

        @Override public void attach(Object parent) {
            check(releases == 0, "cannot attach a released handle");
            parented = true;
            attaches++;
            if (failAttach) throw new IllegalStateException("partial attach");
        }
        @Override public void detach() {
            if (failDetach) throw new IllegalStateException("detach failed");
            parented = false;
        }
        @Override public void release() {
            check(!parented, "must detach before releasing the last handle");
            releases++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected simulated failure");
    }

    public static void main(String[] args) {
        Object dock = new Object();
        for (int generation = 0; generation < 12; generation++) {
            Fake remote = new Fake();
            DockGlassSurfaceLease lease = new DockGlassSurfaceLease(remote);
            lease.attach(dock);
            lease.attach(dock);
            check(lease.isAttached() && remote.attaches == 1, "attach once per generation");
            lease.close();
            lease.attach(dock); // Late queued WMS request after renderer death.
            lease.close();
            check(!lease.isAttached() && !remote.parented, "no orphan after renderer death");
            check(remote.attaches == 1 && remote.releases == 1, "no resurrection or double release");
        }

        Fake failed = new Fake();
        failed.failAttach = true;
        DockGlassSurfaceLease partial = new DockGlassSurfaceLease(failed);
        expectFailure(() -> partial.attach(dock));
        failed.failDetach = true;
        expectFailure(partial::close);
        partial.attach(dock);
        check(failed.releases == 0 && failed.attaches == 1, "retain failed cleanup handle; reject late attach");
        failed.failDetach = false;
        partial.close();
        check(!failed.parented && failed.releases == 1, "retry retires a partially attached root");

        Fake cancelled = new Fake();
        DockGlassSurfaceLease unparented = new DockGlassSurfaceLease(cancelled);
        unparented.close();
        unparented.attach(dock);
        check(cancelled.attaches == 0 && cancelled.releases == 1, "cancel before first attach");
        System.out.println("DockGlassSurfaceLease tests passed");
    }
}
