package com.example.investment;

import android.app.Application;

import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Thread.UncaughtExceptionHandler systemHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Logger.error(throwable.getMessage(), throwable);

            // IMPORTANT: Android apps usually need to be killed after a Throwable/Error,
            // - otherwise they stay in a "zombie" broken state.
            if (systemHandler != null) {
                systemHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });

        // Ensure scheduled work every start of the app
        try {
            WorkerManager.scheduleTickerWork(this);
        } catch (Exception e) {
            Logger.error("Error scheduling the ticker work", e);
        }
    }
}