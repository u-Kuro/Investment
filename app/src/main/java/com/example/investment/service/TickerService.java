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
import com.example.investment.task.UpdateLauncher;
import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;

public class TickerService extends Service {
    public static final String STOP_SERVICE_ACTION = "stop_service";
    public static final String FULL_DATA_REFRESH_ACTION = "Full";
    public static final String INCREMENTAL_DATA_REFRESH_ACTION = "Incremental";
    public static final String BACKUP_ACTION = "Backup";
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

            UpdateLauncher.onServiceStartFailed(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        UpdateLauncher.onServiceStarted();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (STOP_SERVICE_ACTION.equals(action)) {
            UpdateLauncher.cancel(this);
            return START_NOT_STICKY;
        }

        if (
            !FULL_DATA_REFRESH_ACTION.equals(action)
            && !INCREMENTAL_DATA_REFRESH_ACTION.equals(action)
            && !BACKUP_ACTION.equals(action)
        ) {
            return START_NOT_STICKY;
        }

        if (startFailed) return START_NOT_STICKY;

        if (dataRefreshType != null) {
            WorkerManager.scheduleRetryWork(this);
            return START_NOT_STICKY;
        }
        dataRefreshType = action;

        boolean isUserInitiatedJob = intent.getBooleanExtra(EXTRA_USER_INITIATED, false);
        try {
            if (TickerUpdateRunner.startForService(this, action, isUserInitiatedJob)) return START_NOT_STICKY;
            WorkerManager.scheduleRetryWork(this);
        } catch (Exception e) {
            Logger.error("Failed to start the ticker update in the service", e);
            UpdateLauncher.continueFrom(this, action, isUserInitiatedJob, UpdateLauncher.Host.JOB);
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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
        try {
            TickerUpdateRunner.onServiceTimeout(this);
        } catch (Exception e) {
            Logger.error("Error handing over after the ticker service timed out", e);
            WorkerManager.scheduleRetryWork(this);
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onDestroy() {
        try {
            TickerUpdateRunner.cancelServiceRun(this);
        } catch (Exception e) {
            Logger.error("Error cancelling the ticker service run", e);
        }
        super.onDestroy();
    }
}