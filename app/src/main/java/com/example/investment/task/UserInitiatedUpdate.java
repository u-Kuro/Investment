package com.example.investment.task;

import static com.example.investment.service.TickerService.EXTRA_USER_INITIATED;
import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.Constants.EVENT_THREAD;
import static com.example.investment.util.Constants.TICKER_UIDT_JOB_ID;
import static com.example.investment.util.Constants.UI_THREAD;
import static com.example.investment.util.Constants.TICKER_UIDT_ESTIMATED_DOWNLOAD_BYTES;
import static com.example.investment.util.Constants.TICKER_UIDT_ESTIMATED_UPLOAD_BYTES;
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
import android.widget.Toast;

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
        TickerDataLoader tickerDataLoader = TickerDataLoader.getInstance();
        if (!isFullRefresh && !tickerDataLoader.hasPendingTickers(appContext, System.currentTimeMillis())) {
            WorkerManager.cancelRetryWork(appContext);
            Toast.makeText(appContext, "Tickers are already up to date", Toast.LENGTH_SHORT).show();
            return;
        }

        appContext.stopService(new Intent(appContext, TickerService.class));

        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        long previousRequestId = CURRENT_REQUEST_ID.get();
        if (previousRequestId != 0) expectStop(previousRequestId);
        scheduler.cancel(directJobId(TICKER_UIDT_JOB_ID));
        CURRENT_REQUEST_ID.set(0);

        long requestId = Math.max(System.currentTimeMillis(), previousRequestId + 1);
        JobInfo jobInfo = getJobInfo(isFullRefresh, requestId, appContext);

        try {
            int result = scheduler.schedule(jobInfo);
            if (result == JobScheduler.RESULT_SUCCESS) {
                CURRENT_REQUEST_ID.set(requestId);
                return;
            }
            Logger.error("Failed to schedule the user-initiated ticker update", new IllegalStateException("JobScheduler result: " + result));
        } catch (Exception e) {
            Logger.error("Failed to schedule the user-initiated ticker update", e);
        }

        startServiceFallback(appContext, isFullRefresh);
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
        .setEstimatedNetworkBytes(TICKER_UIDT_ESTIMATED_DOWNLOAD_BYTES, TICKER_UIDT_ESTIMATED_UPLOAD_BYTES)
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
        Intent serviceIntent = new Intent(context, TickerService.class);
        serviceIntent.setAction(isFullRefresh ? FULL_DATA_REFRESH_ACTION : INCREMENTAL_DATA_REFRESH_ACTION);
        serviceIntent.putExtra(EXTRA_USER_INITIATED, true);
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