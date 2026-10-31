package com.example.investment.task;

import static com.example.investment.service.TickerService.BACKUP_ACTION;
import static com.example.investment.service.TickerService.EXTRA_USER_INITIATED;
import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.Constants.EVENT_THREAD;
import static com.example.investment.util.Constants.EXTRA_TICKER_ACTION;
import static com.example.investment.util.Constants.EXTRA_TICKER_REQUEST_ID;
import static com.example.investment.util.Constants.TICKER_JOB_ID;
import static com.example.investment.util.Constants.TICKER_JOB_PENDING_TIMEOUT_MILLIS;
import static com.example.investment.util.Constants.UI_THREAD;
import static com.example.investment.util.Constants.directJobId;
import static com.example.investment.util.NetworkUtils.isOffline;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.MainActivity;
import com.example.investment.service.TickerJobService;
import com.example.investment.service.TickerService;
import com.example.investment.util.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class UpdateLauncher {
    public enum Host { UIDT, SERVICE, JOB }

    private static final AtomicLong LAST_REQUEST_ID = new AtomicLong();
    private static final AtomicLong CURRENT_REQUEST_ID = new AtomicLong();
    private static final Set<Long> EXPECTED_STOP_REQUEST_IDS = new HashSet<>();
    private static final Object LAUNCH_LOCK = new Object();
    private static volatile long lastCancelMillis;

    private record ServiceStart(@NonNull String action, boolean isUserInitiatedJob) {}
    private static volatile @Nullable ServiceStart pendingServiceStart;

    public static @NonNull String getAction(boolean isFullRefresh) {
        return isFullRefresh ? FULL_DATA_REFRESH_ACTION : INCREMENTAL_DATA_REFRESH_ACTION;
    }

    public static void start(@NonNull Context context, @NonNull String action) {
        Context appContext = context.getApplicationContext();

        synchronized (LAUNCH_LOCK) {
            try {
                appContext.stopService(new Intent(appContext, TickerService.class));
            } catch (Exception e) {
                Logger.error("Failed to stop the previous ticker service", e);
            }

            try {
                JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
                long previousRequestId = CURRENT_REQUEST_ID.getAndSet(0);
                if (previousRequestId != 0) expectStop(previousRequestId);
                scheduler.cancel(directJobId(TICKER_JOB_ID));
            } catch (Exception e) {
                Logger.error("Failed to stop the previous ticker job", e);
            }

            launch(appContext, action, true, Host.UIDT);
        }
    }

    public static void startIfIdle(@NonNull Context context, @NonNull String action) {
        Context appContext = context.getApplicationContext();

        synchronized (LAUNCH_LOCK) {
            if (TickerUpdateRunner.isRunning()) return;

            Host firstHost = Host.UIDT;
            try {
                JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
                @Nullable JobInfo pendingJob = scheduler.getPendingJob(directJobId(TICKER_JOB_ID));
                if (pendingJob != null) {
                    long pendingRequestId = pendingJob.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
                    boolean isWaitingForNetwork = pendingJob.getRequiredNetwork() != null && isOffline(appContext);
                    if (isWaitingForNetwork || System.currentTimeMillis() - pendingRequestId < TICKER_JOB_PENDING_TIMEOUT_MILLIS) return;

                    if (pendingRequestId != 0) expectStop(pendingRequestId);
                    CURRENT_REQUEST_ID.compareAndSet(pendingRequestId, 0);
                    scheduler.cancel(directJobId(TICKER_JOB_ID));
                    WorkerManager.scheduleRetryWork(appContext);
                    firstHost = Host.SERVICE;
                }
            } catch (Exception e) {
                Logger.error("Failed to check the pending ticker job", e);
            }

            launch(appContext, action, false, firstHost);
        }
    }

    public static void continueFrom(
        @NonNull Context context,
        @NonNull String action,
        boolean isUserInitiatedJob,
        @NonNull Host firstHost
    ) {
        launch(context.getApplicationContext(), action, isUserInitiatedJob, firstHost);
    }

    private static void launch(
        @NonNull Context appContext,
        @NonNull String action,
        boolean isUserInitiatedJob,
        @NonNull Host firstHost
    ) {
        synchronized (LAUNCH_LOCK) {
            for (Host host : Host.values()) {
                if (host.compareTo(firstHost) < 0) continue;

                try {
                    if (startOn(appContext, host, action, isUserInitiatedJob)) {
                        WorkerManager.scheduleRetryWork(appContext);
                        return;
                    }
                } catch (Exception e) {
                    Logger.error("Failed to start the ticker update on " + host, e);
                }
            }

            WorkerManager.scheduleRetryWork(appContext);

            UI_THREAD.post(() -> {
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) activity.updateError();
            });
        }
    }

    private static boolean startOn(
        @NonNull Context appContext,
        @NonNull Host host,
        @NonNull String action,
        boolean isUserInitiatedJob
    ) {
        return switch (host) {
            case UIDT -> scheduleJob(appContext, action, true);
            case SERVICE -> startService(appContext, action, isUserInitiatedJob);
            case JOB -> scheduleJob(appContext, action, false);
        };
    }

    private static boolean startService(@NonNull Context appContext, @NonNull String action, boolean isUserInitiatedJob) {
        Intent serviceIntent = new Intent(appContext, TickerService.class);
        serviceIntent.setAction(action);
        serviceIntent.putExtra(EXTRA_USER_INITIATED, isUserInitiatedJob);

        pendingServiceStart = new ServiceStart(action, isUserInitiatedJob);
        try {
            appContext.startForegroundService(serviceIntent);
        } catch (Exception e) {
            pendingServiceStart = null;
            Logger.error("Failed to start the ticker update on " + Host.SERVICE, e);
            return false;
        }
        return true;
    }

    public static void onServiceStarted() {
        pendingServiceStart = null;
    }

    public static void onServiceStartFailed(@NonNull Context context) {
        @Nullable ServiceStart serviceStart = pendingServiceStart;
        pendingServiceStart = null;
        if (serviceStart == null) return;

        continueFrom(context, serviceStart.action(), serviceStart.isUserInitiatedJob(), Host.JOB);
    }

    private static boolean scheduleJob(@NonNull Context appContext, @NonNull String action, boolean isUserInitiatedJob) {
        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        long requestId = LAST_REQUEST_ID.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));

        int result = scheduler.schedule(getJobInfo(action, requestId, isUserInitiatedJob, appContext));
        if (result == JobScheduler.RESULT_SUCCESS) {
            CURRENT_REQUEST_ID.set(requestId);
            return true;
        }

        return false;
    }

    private static JobInfo getJobInfo(
        @NonNull String action,
        long requestId,
        boolean isUserInitiatedJob,
        @NonNull Context appContext
    ) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_TICKER_ACTION, action);
        extras.putLong(EXTRA_TICKER_REQUEST_ID, requestId);

        boolean needsNetwork = isUserInitiatedJob || !BACKUP_ACTION.equals(action);
        JobInfo.Builder builder = new JobInfo.Builder(
            directJobId(TICKER_JOB_ID),
            new ComponentName(appContext, TickerJobService.class)
        )
        .setRequiredNetworkType(needsNetwork ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_NONE)
        .setExtras(extras);

        if (isUserInitiatedJob) builder.setUserInitiated(true);
        return builder.build();
    }

    public static void cancel(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        lastCancelMillis = System.currentTimeMillis();

        try {
            long requestId = CURRENT_REQUEST_ID.getAndSet(0);
            if (requestId != 0) expectStop(requestId);

            JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
            JobInfo pendingJob = scheduler.getPendingJob(directJobId(TICKER_JOB_ID));
            if (pendingJob != null) {
                long pendingRequestId = pendingJob.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
                if (pendingRequestId != 0) expectStop(pendingRequestId);
            }
            scheduler.cancel(directJobId(TICKER_JOB_ID));
        } catch (Exception e) {
            Logger.error("Failed to cancel the ticker job", e);
        }

        try {
            appContext.stopService(new Intent(appContext, TickerService.class));
        } catch (Exception e) {
            Logger.error("Failed to stop the ticker service", e);
        }

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

    public static boolean wasCancelledSince(long sinceMillis) {
        return lastCancelMillis >= sinceMillis;
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
            if (EXPECTED_STOP_REQUEST_IDS.size() > 1024) EXPECTED_STOP_REQUEST_IDS.remove(Collections.min(EXPECTED_STOP_REQUEST_IDS));
        }
    }
}