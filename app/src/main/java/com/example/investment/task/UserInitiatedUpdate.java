package com.example.investment.task;

import static com.example.investment.service.TickerService.EXTRA_USER_INITIATED;
import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.Constants.EVENT_THREAD;
import static com.example.investment.util.Constants.TICKER_UIDT_JOB_ID;
import static com.example.investment.util.Constants.UI_THREAD;
import static com.example.investment.util.Constants.EXTRA_TICKER_FULL_REFRESH;
import static com.example.investment.util.Constants.EXTRA_TICKER_REQUEST_ID;
import static com.example.investment.util.Constants.directJobId;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.MainActivity;
import com.example.investment.service.TickerJobService;
import com.example.investment.service.TickerService;
import com.example.investment.util.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class UserInitiatedUpdate {
    private static final AtomicLong CURRENT_REQUEST_ID = new AtomicLong();
    private static final Set<Long> EXPECTED_STOP_REQUEST_IDS = new HashSet<>();

    public static void start(@NonNull Context context, boolean isFullRefresh) {
        Context appContext = context.getApplicationContext();

        appContext.stopService(new Intent(appContext, TickerService.class));

        try {
            JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
            long previousRequestId = CURRENT_REQUEST_ID.get();
            if (previousRequestId != 0) expectStop(previousRequestId);
            scheduler.cancel(directJobId(TICKER_UIDT_JOB_ID));
            CURRENT_REQUEST_ID.set(0);

            if (scheduleJob(appContext, scheduler, isFullRefresh, previousRequestId)) return;
        } catch (Exception e) {
            Logger.error("Failed to start the user-initiated ticker update", e);
        }

        startServiceFallback(appContext, isFullRefresh, true);
    }

    public static void startIfIdle(@NonNull Context context, boolean isFullRefresh) {
        Context appContext = context.getApplicationContext();
        if (TickerUpdateRunner.isRunning()) return;

        try {
            JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
            if (scheduler.getPendingJob(directJobId(TICKER_UIDT_JOB_ID)) != null) return;

            if (scheduleJob(appContext, scheduler, isFullRefresh, CURRENT_REQUEST_ID.get())) return;
        } catch (Exception e) {
            Logger.error("Failed to start the user-initiated ticker update", e);
        }

        startServiceFallback(appContext, isFullRefresh, false);
    }

    private static boolean scheduleJob(
        @NonNull Context appContext,
        @NonNull JobScheduler scheduler,
        boolean isFullRefresh,
        long previousRequestId
    ) {
        long requestId = Math.max(System.currentTimeMillis(), previousRequestId + 1);

        try {
            int result = scheduler.schedule(getJobInfo(isFullRefresh, requestId, appContext));
            if (result == JobScheduler.RESULT_SUCCESS) {
                CURRENT_REQUEST_ID.set(requestId);
                return true;
            }
            Logger.debug("The user-initiated ticker update was not scheduled, JobScheduler result: " + result);
        } catch (Exception e) {
            Logger.error("Failed to schedule the user-initiated ticker update", e);
        }
        return false;
    }

    private static JobInfo getJobInfo(boolean isFullRefresh, long requestId, Context appContext) {
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_TICKER_FULL_REFRESH, isFullRefresh);
        extras.putLong(EXTRA_TICKER_REQUEST_ID, requestId);

        return new JobInfo.Builder(
            directJobId(TICKER_UIDT_JOB_ID),
            new ComponentName(appContext, TickerJobService.class)
        )
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setUserInitiated(true)
        .setExtras(extras)
        .build();
    }

    public static void cancel(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        long requestId = CURRENT_REQUEST_ID.getAndSet(0);
        if (requestId != 0) expectStop(requestId);

        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        JobInfo pendingJob = scheduler.getPendingJob(directJobId(TICKER_UIDT_JOB_ID));
        if (pendingJob != null) {
            long pendingRequestId = pendingJob.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
            if (pendingRequestId != 0) expectStop(pendingRequestId);
        }
        scheduler.cancel(directJobId(TICKER_UIDT_JOB_ID));
        appContext.stopService(new Intent(appContext, TickerService.class));
        EVENT_THREAD.execute(() -> {
            TickerDataLoader.getInstance().clearRefreshPoint(appContext);
            if (!TickerDataLoader.getInstance().hasPendingTickers(appContext, System.currentTimeMillis())) {
                WorkerManager.cancelRetryWork(appContext);
            }
        });

        UI_THREAD.post(() -> {
            MainActivity activity = MainActivity.getInstanceActivity();
            if (activity != null) activity.updateCancel();
        });
    }

    public static void startServiceFallback(@NonNull Context context, boolean isFullRefresh) {
        startServiceFallback(context, isFullRefresh, true);
    }

    public static void startServiceFallback(@NonNull Context context, boolean isFullRefresh, boolean isUserInitiated) {
        Intent serviceIntent = new Intent(context, TickerService.class);
        serviceIntent.setAction(isFullRefresh ? FULL_DATA_REFRESH_ACTION : INCREMENTAL_DATA_REFRESH_ACTION);
        serviceIntent.putExtra(EXTRA_USER_INITIATED, isUserInitiated);
        try {
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            Logger.error("Failed to start the ticker service fallback", e);
            WorkerManager.scheduleRetryWork(context);
        }
    }

    public static void registerRequestId(long requestId) {
        if (requestId != 0) CURRENT_REQUEST_ID.set(requestId);
    }

    public static void releaseRequestId(long requestId) {
        if (requestId != 0) CURRENT_REQUEST_ID.compareAndSet(requestId, 0);
    }

    public static boolean consumeExpectedStop(long requestId) {
        if (requestId == 0) return false;
        synchronized (EXPECTED_STOP_REQUEST_IDS) {
            return EXPECTED_STOP_REQUEST_IDS.remove(requestId);
        }
    }

    private static void expectStop(long requestId) {
        synchronized (EXPECTED_STOP_REQUEST_IDS) {
            EXPECTED_STOP_REQUEST_IDS.add(requestId);
        }
    }
}