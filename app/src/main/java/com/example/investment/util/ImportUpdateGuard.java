package com.example.investment.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ImportUpdateGuard {
    private static final Object guardLock = new Object();
    private static int runningUpdates = 0;
    private static @Nullable Object currentImportToken = null;

    public static boolean isUpdating() {
        synchronized (guardLock) {
            return runningUpdates > 0;
        }
    }

    public static boolean isImporting() {
        synchronized (guardLock) {
            return currentImportToken != null;
        }
    }

    // Import
    public static @Nullable Object tryStartImport() {
        synchronized (guardLock) {
            if (runningUpdates > 0) return null;
            currentImportToken = new Object();
            return currentImportToken;
        }
    }

    public static boolean isNotCurrentImport(@NonNull Object importToken) {
        synchronized (guardLock) {
            return currentImportToken != importToken;
        }
    }

    public static void finishImport(@NonNull Object importToken) {
        synchronized (guardLock) {
            if (currentImportToken == importToken) currentImportToken = null;
        }
    }

    // Update
    public static boolean tryStartUpdate() {
        synchronized (guardLock) {
            if (currentImportToken != null) return false;

            runningUpdates++;
            return true;
        }
    }

    public static void finishUpdate() {
        synchronized (guardLock) {
            if (runningUpdates > 0) runningUpdates--;
        }
    }
}
