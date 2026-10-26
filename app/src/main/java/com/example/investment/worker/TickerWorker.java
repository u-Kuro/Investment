package com.example.investment.worker;

import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.investment.service.TickerService;
import com.example.investment.util.Logger;

public class TickerWorker extends Worker {
    public TickerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        try {
            Intent serviceIntent = new Intent(context, TickerService.class);
            serviceIntent.setAction(INCREMENTAL_DATA_REFRESH_ACTION);
            context.startForegroundService(serviceIntent);

            return Result.success();
        } catch (Exception e) {
            Logger.error("Error starting the ticker service from the worker", e);
            return Result.retry();
        }
    }
}