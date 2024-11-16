package com.example.investment.util;

import android.content.Context;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import com.example.investment.worker.TickerWorker;
import java.util.concurrent.TimeUnit;

public class WorkManagerUtil {
    private static final String TICKER_WORK_TAG = "ticker_work";
    
    public static void scheduleTickerWork(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest tickerWork = new PeriodicWorkRequest.Builder(
                TickerWorker.class, 
                1, 
                TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag(TICKER_WORK_TAG)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    TICKER_WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    tickerWork);
    }
} 