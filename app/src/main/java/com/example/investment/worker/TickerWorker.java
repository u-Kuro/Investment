package com.example.investment.worker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.investment.R;
import com.example.investment.service.TickerService;
import com.example.investment.util.Logger;

public class TickerWorker extends Worker {
    public TickerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @NonNull
    @Override
    public Result doWork() {
        Intent serviceIntent = new Intent(getApplicationContext(), TickerService.class);
        getApplicationContext().startService(serviceIntent);
        return Result.success();
    }
} 