/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.provider;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Small private module-only history. Authorization is enforced by DockGlassHost. */
final class DockDiagnosticJournal {
    private static final int MAX_EVENTS = 96;
    private static final int MAX_LENGTH = 512;

    private DockDiagnosticJournal() {}

    static synchronized Bundle access(Context context, Bundle input) {
        SharedPreferences prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("dock_diagnostic_history", Context.MODE_PRIVATE);
        String previous = prefs.getString("events", "");
        if (input != null) {
            String[] events = input.getStringArray("events");
            if (events == null || events.length > MAX_EVENTS) {
                throw new IllegalArgumentException("Invalid Dock diagnostic batch");
            }
            StringBuilder history = new StringBuilder(previous);
            for (String event : events) {
                if (event == null || event.length() > MAX_LENGTH) {
                    throw new IllegalArgumentException("Invalid Dock diagnostic event");
                }
                history.append(event.replace('\n', ' ').replace('\r', ' ')).append('\n');
            }
            String[] lines = history.toString().split("\n");
            StringBuilder bounded = new StringBuilder();
            for (int i = Math.max(0, lines.length - MAX_EVENTS); i < lines.length; i++) {
                bounded.append(lines[i]).append('\n');
            }
            previous = bounded.toString();
            prefs.edit().putString("events", previous).apply();
            // Acknowledge a write without copying the full history over Binder again.
            return Bundle.EMPTY;
        }
        Bundle result = new Bundle();
        result.putString("events", previous);
        result.putInt("diagnosticVersion", 1);
        return result;
    }
}
