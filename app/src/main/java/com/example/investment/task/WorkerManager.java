package com.example.investment.task;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import static com.example.investment.util.Constants.EXTRA_IS_RETRY_WORK;
import static com.example.investment.util.Constants.TICKER_RETRY_DELAY_HOURS;
import static com.example.investment.util.Constants.TICKER_RETRY_WORK_NAME;
import static com.example.investment.util.Constants.TICKER_RETRY_WORK_TAG;

import com.example.investment.util.Logger;
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

    public static void scheduleRetryWork(@NonNull Context context) {
        try {
            OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(TickerWorker.class)
            .addTag(TICKER_RETRY_WORK_TAG)
            .setInputData(new Data.Builder().putBoolean(EXTRA_IS_RETRY_WORK, true).build())
            .setInitialDelay(TICKER_RETRY_DELAY_HOURS, TimeUnit.HOURS)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build();

            WorkManager.getInstance(context.getApplicationContext())
            .enqueueUniqueWork(TICKER_RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest);
        } catch (Exception e) {
            Logger.error("Failed to schedule the ticker retry work", e);
        }
    }

    public static void cancelRetryWork(@NonNull Context context) {
        try {
            WorkManager.getInstance(context.getApplicationContext())
            .cancelUniqueWork(TICKER_RETRY_WORK_NAME);
        } catch (Exception e) {
            Logger.error("Failed to cancel the ticker retry work", e);
        }
    }
}