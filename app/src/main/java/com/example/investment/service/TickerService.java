package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;
import static com.example.investment.util.Constants.UI_THREAD;
import static com.example.investment.util.NotificationUtils.createLowPriorityServiceNotification;
import static com.example.investment.util.NotificationUtils.showLowPriorityServiceNotification;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.R;
import com.example.investment.task.TickerUpdateRunner;
import com.example.investment.task.UserInitiatedUpdate;
import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;

public class TickerService extends Service {
    public static final String STOP_SERVICE_ACTION = "stop_service";
    public static final String FULL_DATA_REFRESH_ACTION = "Full";
    public static final String INCREMENTAL_DATA_REFRESH_ACTION = "Incremental";
    public static final String EXTRA_USER_INITIATED = "user_initiated";

    private @Nullable String dataRefreshType = null;
    private boolean startFailed;

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onCreate() {
        super.onCreate();

        try {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                createLowPriorityServiceNotification(this, getString(R.string.ticker_service_notification_title)),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } catch (Exception e) {
            startFailed = true;
            Logger.error("Failed to start ticker service", e);
            WorkerManager.scheduleRetryWork(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (startFailed || intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (STOP_SERVICE_ACTION.equals(action)) {
            UserInitiatedUpdate.cancel(this);
            return START_NOT_STICKY;
        }

        if (!FULL_DATA_REFRESH_ACTION.equals(action) && !INCREMENTAL_DATA_REFRESH_ACTION.equals(action)) {
            return START_NOT_STICKY;
        }

        if (dataRefreshType != null) {
            WorkerManager.scheduleRetryWork(this);
            return START_NOT_STICKY;
        }
        dataRefreshType = action;

        boolean isFullRefresh = FULL_DATA_REFRESH_ACTION.equals(action);
        boolean isUserInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, false);
        if (!TickerUpdateRunner.startForService(this, isFullRefresh, isUserInitiated)) {
            WorkerManager.scheduleRetryWork(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void showUpdateNotification(@NonNull String message) {
        showLowPriorityServiceNotification(this, message);
    }

    public void onRunnerFinished() {
        UI_THREAD.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Override
    public void onTimeout(int startId, int fgsType) {
        TickerUpdateRunner.onServiceTimeout(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onDestroy() {
        TickerUpdateRunner.cancelServiceRun(this);
        super.onDestroy();
    }
}