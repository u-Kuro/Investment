package com.example.investment.task;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;

import com.example.investment.worker.TickerWorker;

import java.util.concurrent.TimeUnit;

public class WorkerManager {
    private static final String TICKER_WORK_TAG = "ticker_work";
    private static final long RETRY_BACKOFF_MINUTES = 15;

    public static void scheduleTickerWork(@NonNull Context context) {
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            TickerWorker.class,
            1, TimeUnit.DAYS
        )
        .addTag(TICKER_WORK_TAG)
        .setConstraints(new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
        .build();

        WorkManager.getInstance(context.getApplicationContext())
        .enqueueUniquePeriodicWork(
            TICKER_WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        );
    }
}