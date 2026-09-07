/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Authenticated custom transaction carried by MiuiHome's existing IWindowManager Binder. */
public final class DockNativeMotionEndpoint {
    public static final int TRANSACTION_CODE = 0x00484344;
    private static final String DESCRIPTOR = "android.view.IWindowManager";

    private record Identity(int uid, int pid) { }
    private record State(Identity identity, DockNativeMotion.Sample sample) { }
    private record Pending(Identity identity, DockNativeMotion.Sample sample) { }
    private final AtomicReference<State> state = new AtomicReference<>(new State(null, null));
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicInteger reported = new AtomicInteger();
    private final Runnable changed;
    private final Consumer<String> diagnostic;

    public DockNativeMotionEndpoint(Runnable changed, Consumer<String> diagnostic) {
        this.changed = changed;
        this.diagnostic = diagnostic;
    }

    public void bindIdentity(int uid, int pid) {
        Identity replacement = new Identity(uid, pid);
        Pending early = pending.getAndSet(null);
        state.updateAndGet(current -> {
            if (replacement.equals(current.identity())) return current;
            DockNativeMotion.Sample sample = early != null && replacement.equals(early.identity())
                ? early.sample() : null;
            return new State(replacement, sample);
        });
        if (early != null && replacement.equals(early.identity())) notifyChanged();
    }

    /** Must be called only from IWindowManager.Stub.onTransact while Binder identity is intact. */
    public boolean receive(int code, Parcel data, int flags) {
        if (code != TRANSACTION_CODE) return false;
        data.enforceInterface("android.view.IWindowManager");
        State current = state.get();
        Identity expected = current.identity();
        int callerUid = Binder.getCallingUid();
        int callerPid = Binder.getCallingPid();
        int available = data.dataAvail();
        report(1, "native motion Binder transport observed uid=" + callerUid
            + " pid=" + callerPid + " flags=" + flags + " bytes=" + available);
        // Binder does not preserve the caller PID for asynchronous transactions
        // on this OS4 build. The launcher sends from a detached transport worker,
        // so requiring a synchronous call keeps exact PID authentication without
        // ever blocking its render callback.
        if ((flags & IBinder.FLAG_ONEWAY) != 0) {
            report(2, "native motion Binder rejected: one-way call has no trusted PID");
            return true;
        }
        if (expected != null && (callerUid != expected.uid() || callerPid != expected.pid())) {
            report(8, "native motion Binder rejected: caller identity mismatch");
            return true;
        }
        if (available != Long.BYTES * 4) {
            report(16, "native motion Binder rejected: payload bytes=" + available);
            return true;
        }
        DockNativeMotion.Sample sample = DockNativeMotion.validate(
            data.readLong(), data.readLong(), data.readLong(), data.readLong(),
            current.sample() == null ? 0 : current.sample().sequence(), System.nanoTime());
        if (sample != null && expected == null) {
            pending.set(new Pending(new Identity(callerUid, callerPid), sample));
            report(4, "native motion Binder retained sample until launcher identity binds");
            return true;
        }
        if (sample != null && state.compareAndSet(current, new State(expected, sample))) {
            report(32, "native motion Binder sample accepted");
            notifyChanged();
        }
        if (sample == null) report(64, "native motion Binder rejected: invalid or stale sample");
        return true;
    }

    private void report(int bit, String message) {
        int current;
        do {
            current = reported.get();
            if ((current & bit) != 0) return;
        } while (!reported.compareAndSet(current, current | bit));
        try {
            diagnostic.accept(message);
        } catch (RuntimeException ignored) {
            // Diagnostics are optional and must not affect Binder handling.
        }
    }

    private void notifyChanged() {
        try {
            changed.run();
        } catch (RuntimeException ignored) {
            // Motion/edit visibility is optional; never unwind into WMS.
        }
    }

    public DockNativeMotion.Sample latest(int uid, int pid) {
        State current = state.get();
        Identity expected = current.identity();
        DockNativeMotion.Sample sample = current.sample();
        long now = System.nanoTime();
        return expected != null && expected.uid() == uid && expected.pid() == pid
                && sample != null && sample.uptimeNanos() <= now
                && now - sample.uptimeNanos() <= DockNativeMotion.MAX_AGE_NS ? sample : null;
    }
}
