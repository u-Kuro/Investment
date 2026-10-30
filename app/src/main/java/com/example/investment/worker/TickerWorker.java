package com.example.investment.worker;

import static com.example.investment.util.Constants.EXTRA_IS_RETRY_WORK;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.investment.model.ticker.TickerData;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.task.UserInitiatedUpdate;
import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;

import java.util.List;

public class TickerWorker extends Worker {
    public TickerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        TickerDataLoader loader = TickerDataLoader.getInstance();
        boolean isRetryWork = getInputData().getBoolean(EXTRA_IS_RETRY_WORK, false);

        try {
            WorkerManager.scheduleOwedBackupWork(context);
        } catch (Exception e) {
            Logger.error("Error scheduling the owed backup", e);
        }

        try {
            if (!loader.hasPendingTickers(context, System.currentTimeMillis())) {
                if (!isRetryWork) WorkerManager.cancelRetryWork(context);
                return Result.success();
            }

            List<TickerData> tickers = loader.getTickers(context);
            UserInitiatedUpdate.startIfIdle(context, tickers == null || tickers.isEmpty());
            return Result.success();
        } catch (Exception e) {
            Logger.error("Error starting the ticker update from the worker", e);
            WorkerManager.scheduleRetryWork(context);
            return Result.success();
        }
    }
}