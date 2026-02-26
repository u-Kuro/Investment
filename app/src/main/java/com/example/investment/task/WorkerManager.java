package com.example.investment.task;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;

import com.example.investment.worker.TickerWorker;

import java.util.concurrent.TimeUnit;

public class WorkerManager {
    private static final String TICKER_WORK_TAG = "ticker_work";
    public static void scheduleTickerWork(Context context) {
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            TickerWorker.class,
            1, TimeUnit.DAYS
        )
        .addTag(TICKER_WORK_TAG)
        .setConstraints(new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build())
        .build();

        WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            TICKER_WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        );
    }
}