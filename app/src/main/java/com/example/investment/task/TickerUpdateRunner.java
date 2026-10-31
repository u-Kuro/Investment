package com.example.investment.task;

import static com.example.investment.service.TickerService.BACKUP_ACTION;
import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.BackupUtils.exportToFolder;
import static com.example.investment.util.BackupUtils.getExportFolderUri;
import static com.example.investment.util.BackupUtils.hasNoExportableData;
import static com.example.investment.util.BackupUtils.isBackupOwed;
import static com.example.investment.util.BackupUtils.markBackupOwed;
import static com.example.investment.util.Constants.BACKGROUND_THREAD;
import static com.example.investment.util.Constants.EXTRA_TICKER_REQUEST_ID;
import static com.example.investment.util.NetworkUtils.isOffline;

import android.app.job.JobParameters;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.MainActivity;
import com.example.investment.R;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.receiver.NetworkReceiver;
import com.example.investment.service.TickerJobService;
import com.example.investment.service.TickerService;
import com.example.investment.util.ImportUpdateGuard;
import com.example.investment.util.Logger;
import com.example.investment.util.SafeSingleTaskRunner;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class TickerUpdateRunner {
    private enum RunType {
        SERVICE,
        JOB
    }

    private static final Object RUN_TASK_ID = new Object();
    private static final Object RUN_LOCK = new Object();
    private static @Nullable RunRequest activeRun;

    private static final class RunRequest {
        private final Context context;
        private final Object owner;
        private final RunType type;
        private final String action;
        private final boolean isFullRefresh;
        private final boolean isBackupOnly;
        private final boolean isUserInitiatedJob;
        private final @Nullable JobParameters jobParameters;
        private final AtomicBoolean madeProgress = new AtomicBoolean(false);
        private final AtomicBoolean callbackHandled = new AtomicBoolean(false);
        private volatile boolean cancelRequested;
        private volatile boolean failed;
        private volatile boolean nothingPending;

        private RunRequest(
            @NonNull Context context,
            @NonNull Object owner,
            @NonNull RunType type,
            @NonNull String action,
            boolean isUserInitiatedJob,
            @Nullable JobParameters jobParameters
        ) {
            this.context = context.getApplicationContext();
            this.owner = owner;
            this.type = type;
            this.action = action;
            this.isFullRefresh = FULL_DATA_REFRESH_ACTION.equals(action);
            this.isBackupOnly = BACKUP_ACTION.equals(action);
            this.isUserInitiatedJob = isUserInitiatedJob;
            this.jobParameters = jobParameters;
        }
    }

    public static boolean startForService(
        @NonNull TickerService service,
        @NonNull String action,
        boolean isUserInitiatedJob
    ) {
        RunRequest request = new RunRequest(
            service,
            service,
            RunType.SERVICE,
            action,
            isUserInitiatedJob,
            null
        );

        @Nullable RunRequest replacedRun;
        synchronized (RUN_LOCK) {
            if (!isUserInitiatedJob && activeRun != null && activeRun.isUserInitiatedJob) return false;
            if (!isUserInitiatedJob && activeRun != null && activeRun.owner == service) return false;
            replacedRun = activeRun;
            activeRun = request;
        }

        releaseReplacedHost(replacedRun, request);
        submitRun(request);
        return true;
    }

    public static void startForJob(
        @NonNull TickerJobService service,
        @NonNull JobParameters params,
        @NonNull String action
    ) {
        RunRequest request = new RunRequest(
            service,
            service,
            RunType.JOB,
            action,
            params.isUserInitiatedJob(),
            params
        );

        @Nullable RunRequest replacedRun;
        synchronized (RUN_LOCK) {
            replacedRun = activeRun;
            activeRun = request;
        }

        releaseReplacedHost(replacedRun, request);
        submitRun(request);
    }

    private static void releaseReplacedHost(@Nullable RunRequest replacedRun, @NonNull RunRequest request) {
        if (replacedRun == null) return;
        replacedRun.cancelRequested = true;

        try {
            if (replacedRun.type == RunType.SERVICE) {
                if (replacedRun.owner != request.owner) ((TickerService) replacedRun.owner).onRunnerFinished();
            } else if (replacedRun.jobParameters != null && differentJobParameters(replacedRun.jobParameters, request.jobParameters)) {
                ((TickerJobService) replacedRun.owner).onRunnerFinished(replacedRun.jobParameters);
            }
        } catch (Exception e) {
            Logger.error("Error releasing the host of the replaced ticker run", e);
        }
    }

    private static void submitRun(@NonNull RunRequest request) {
        try {
            BACKGROUND_THREAD.replaceUniqueTask(RUN_TASK_ID, ui -> run(request, ui));
        } catch (Exception e) {
            synchronized (RUN_LOCK) {
                if (activeRun == request) activeRun = null;
            }
            throw e;
        }
    }

    public static boolean isRunning() {
        synchronized (RUN_LOCK) {
            return activeRun != null;
        }
    }

    public static void cancelServiceRun(@NonNull TickerService service) {
        cancelRun(service, null);
    }

    public static void onServiceTimeout(@NonNull TickerService service) {
        @Nullable RunRequest request = cancelRun(service, null);
        WorkerManager.scheduleRetryWork(service);

        if (request != null) {
            UpdateLauncher.continueFrom(service, getContinuedAction(request.action), request.isUserInitiatedJob, UpdateLauncher.Host.JOB);
        }
    }

    public static void cancelJobRun(
        @NonNull TickerJobService service,
        @NonNull JobParameters params
    ) {
        cancelRun(service, params);
    }

    public static @NonNull String getContinuedAction(@NonNull String action) {
        return BACKUP_ACTION.equals(action) ? BACKUP_ACTION : INCREMENTAL_DATA_REFRESH_ACTION;
    }

    private static @Nullable RunRequest cancelRun(
        @NonNull Object owner,
        @Nullable JobParameters params
    ) {
        @Nullable RunRequest request;
        synchronized (RUN_LOCK) {
            request = activeRun;
            if (request == null || request.owner != owner || differentJobParameters(request.jobParameters, params)) return null;
            request.cancelRequested = true;
            activeRun = null;
        }

        BACKGROUND_THREAD.cancelCurrentUniqueTask(RUN_TASK_ID);
        return request;
    }

    private static void run(
        @NonNull RunRequest request,
        @NonNull SafeSingleTaskRunner.UI ui
    ) {
        TickerDataLoader loader = TickerDataLoader.getInstance();
        boolean guardStarted = false;
        try {
            if (isInterrupted(request)) return;

            if (request.isBackupOnly) {
                request.nothingPending = true;
                backup(request, ui);
                return;
            }

            long nowMillis = System.currentTimeMillis();
            if (!request.isFullRefresh && !loader.hasPendingTickers(request.context, nowMillis)) {
                request.nothingPending = true;
                ui.post(() -> {
                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) activity.updateFinished(loader.getLastUpdateTime(request.context));
                });
                if (isBackupOwed(request.context)) backup(request, ui);
                return;
            }

            WorkerManager.scheduleRetryWork(request.context);

            if (!ImportUpdateGuard.tryStartUpdate()) {
                request.failed = true;
                handleError(request, loader.getTickers(request.context), ui);
                return;
            }
            guardStarted = true;

            oweBackup(request);

            String dataRefreshType = request.isFullRefresh
                ? FULL_DATA_REFRESH_ACTION
                : INCREMENTAL_DATA_REFRESH_ACTION;

            loader.updateTickers(
                request.context,
                request.isFullRefresh,
                new TickerUpdateListener() {
                    @Override
                    public void onSymbolsProgress(final int current) {
                        if (isInterrupted(request) || request.callbackHandled.get()) return;

                        if (current > 0) {
                            showNotification(
                                request,
                                String.format(
                                    request.context.getString(R.string.fetching_tickers_found),
                                    dataRefreshType,
                                    current
                                )
                            );
                        } else {
                            showNotification(
                                request,
                                String.format(request.context.getString(R.string.fetching_tickers), dataRefreshType)
                            );
                        }

                        ui.post(() -> {
                            MainActivity activity = MainActivity.getInstanceActivity();
                            if (activity != null) activity.updateSymbolsProgress(current, dataRefreshType);
                        });
                    }

                    @Override
                    public void onTickersProgress(final int current, final int total) {
                        if (isInterrupted(request) || request.callbackHandled.get()) return;

                        if (total > 0) {
                            double progress = ((double) current / total) * 100;
                            if (current < total) {
                                showNotification(
                                    request,
                                    String.format(
                                        request.context.getString(R.string.processing_tickers),
                                        progress,
                                        dataRefreshType
                                    )
                                );
                            } else {
                                showNotification(
                                    request,
                                    request.context.getString(R.string.successful_ticker_progress)
                                );
                            }
                        } else {
                            showNotification(
                                request,
                                String.format(request.context.getString(R.string.fetching_tickers), dataRefreshType)
                            );
                        }

                        ui.post(() -> {
                            MainActivity activity = MainActivity.getInstanceActivity();
                            if (activity != null) activity.updateTickersProgress(current, total, dataRefreshType);
                        });
                    }

                    @Override
                    public void onTickerUpdate(@NonNull List<TickerData> tickers) {
                        if (isInterrupted(request) || request.callbackHandled.get()) return;
                        request.madeProgress.set(true);
                        ui.post(() -> {
                            MainActivity activity = MainActivity.getInstanceActivity();
                            if (activity != null) activity.updateTickerList(tickers);
                        });
                    }

                    @Override
                    public void onFinish(@NonNull List<TickerData> tickers, long updateTime) {
                        if (isInterrupted(request) || !request.callbackHandled.compareAndSet(false, true)) return;

                        try {
                            processRunResult(request, tickers);
                        } finally {
                            ui.post(() -> {
                                MainActivity activity = MainActivity.getInstanceActivity();
                                if (activity != null) activity.updateFinished(updateTime);
                            });
                        }
                    }

                    @Override
                    public void onError(@Nullable List<TickerData> oldTickers) {
                        if (isInterrupted(request) || !request.callbackHandled.compareAndSet(false, true)) return;
                        request.failed = true;
                        processErrorResult(request, oldTickers, ui);
                    }
                }
            );
        } catch (Exception e) {
            if (isInterrupted(request)) {
                Thread.currentThread().interrupt();
            } else {
                Logger.error("Error running ticker update", e);
                request.failed = true;
                if (request.callbackHandled.compareAndSet(false, true)) {
                    processErrorResult(request, loader.getTickers(request.context), ui);
                }
            }
        } finally {
            if (guardStarted) ImportUpdateGuard.finishUpdate();

            boolean interrupted = isInterrupted(request);
            boolean isCurrent;
            synchronized (RUN_LOCK) {
                isCurrent = activeRun == request;
                if (isCurrent && interrupted) activeRun = null;
            }

            if (isCurrent) {
                if (interrupted) {
                    boolean pendingAfterInterrupt = true;
                    try {
                        pendingAfterInterrupt = loader.hasPendingTickers(request.context, System.currentTimeMillis());
                    } catch (Exception e) {
                        Logger.error("Error checking pending tickers after interruption", e);
                    }
                    if (pendingAfterInterrupt) WorkerManager.scheduleRetryWork(request.context);
                    if (request.type == RunType.SERVICE) {
                        ((TickerService) request.owner).onRunnerFinished();
                    } else if (request.jobParameters != null) {
                        ((TickerJobService) request.owner).onRunnerFinished(request.jobParameters);
                    }
                } else {
                    boolean pending = true;
                    try {
                        try {
                            pending = loader.hasPendingTickers(request.context, System.currentTimeMillis());
                        } catch (Exception e) {
                            Logger.error("Error checking pending tickers after update", e);
                        }

                        if (pending) {
                            WorkerManager.scheduleRetryWork(request.context);
                        } else {
                            WorkerManager.cancelRetryWork(request.context);
                        }

                        if (!request.callbackHandled.get() && !request.nothingPending) {
                            request.failed = true;
                            if (request.callbackHandled.compareAndSet(false, true)) {
                                try {
                                    processErrorResult(request, loader.getTickers(request.context), ui);
                                } catch (Exception e) {
                                    Logger.error("Error handling the failed ticker update", e);
                                }
                            }
                        }

                        if (!request.nothingPending) backup(request, ui);
                    } finally {
                        synchronized (RUN_LOCK) {
                            if (activeRun == request) activeRun = null;
                        }

                        if (!isInterrupted(request)) {
                            if (request.type == RunType.SERVICE) {
                                ((TickerService) request.owner).onRunnerFinished();
                            } else if (request.type == RunType.JOB) {
                                TickerJobService jobService = (TickerJobService) request.owner;
                                if (request.jobParameters != null) {
                                    jobService.onRunnerFinished(request.jobParameters);
                                }

                                if (request.failed && pending && request.isUserInitiatedJob) {
                                    long failedAtMillis = System.currentTimeMillis();
                                    BACKGROUND_THREAD.execute(() -> {
                                        if (UpdateLauncher.wasCancelledSince(failedAtMillis)) return;

                                        UpdateLauncher.continueFrom(
                                            request.context,
                                            INCREMENTAL_DATA_REFRESH_ACTION,
                                            true,
                                            UpdateLauncher.Host.SERVICE
                                        );
                                    });
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void oweBackup(@NonNull RunRequest request) {
        try {
            if (getExportFolderUri(request.context) == null || hasNoExportableData(request.context)) return;
            markBackupOwed(request.context);
        } catch (Exception e) {
            Logger.error("Error marking the backup as owed", e);
        }
    }

    private static void backup(@NonNull RunRequest request, @NonNull SafeSingleTaskRunner.UI ui) {
        try {
            @Nullable Uri folderUri = getExportFolderUri(request.context);
            if (folderUri == null || hasNoExportableData(request.context)) return;

            markBackupOwed(request.context);

            showNotification(request, request.context.getString(R.string.backing_up));

            exportToFolder(request.context, folderUri);
            ui.post(() -> {
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) activity.updateBackupFinished(true);
            });
        } catch (Exception e) {
            if (isInterrupted(request)) return;

            Logger.error("Error backing up the data", e);
            ui.post(() -> {
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) activity.updateBackupFinished(false);
            });
        }
    }

    private static void processRunResult(@NonNull RunRequest request, @NonNull List<TickerData> tickers) {
        TickerUpdateProcessor processor = new TickerUpdateProcessor(request.context);
        if (request.madeProgress.get()) {
            processor.processChangedTickers(tickers);
        } else {
            processor.processUnchangedTickers(tickers);
        }
    }

    private static void processErrorResult(
        @NonNull RunRequest request,
        @Nullable List<TickerData> tickers,
        @NonNull SafeSingleTaskRunner.UI ui
    ) {
        try {
            TickerUpdateProcessor processor = new TickerUpdateProcessor(request.context);
            if (request.madeProgress.get() && TickerDataLoader.getInstance().getLastUpdateTime(request.context) > 0) {
                processor.processChangedTickers(tickers != null ? tickers : List.of());
            } else {
                processor.processUnchangedTickers(tickers);
            }

            if (isOffline(request.context)) NetworkReceiver.retryOnNetworkAvailable(request.context);
        } finally {
            ui.post(() -> {
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) activity.updateError();
            });
        }
    }

    private static void handleError(
        @NonNull RunRequest request,
        @Nullable List<TickerData> tickers,
        @NonNull SafeSingleTaskRunner.UI ui
    ) {
        if (!request.callbackHandled.compareAndSet(false, true)) return;
        request.failed = true;
        processErrorResult(request, tickers, ui);
    }

    private static void showNotification(@NonNull RunRequest request, @NonNull String message) {
        try {
            if (request.type == RunType.SERVICE) {
                ((TickerService) request.owner).showUpdateNotification(message);
            } else if (request.type == RunType.JOB && request.jobParameters != null) {
                ((TickerJobService) request.owner).setUpdateNotification(request.jobParameters, message);
            }
        } catch (Exception e) {
            Logger.error("Error showing the ticker update notification", e);
        }
    }

    private static boolean differentJobParameters(
        @Nullable JobParameters first,
        @Nullable JobParameters second
    ) {
        if (first == second) return false;
        if (first == null || second == null || first.getJobId() != second.getJobId()) return true;
        return first.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0) != second.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
    }

    private static boolean isInterrupted(@NonNull RunRequest request) {
        return request.cancelRequested || Thread.currentThread().isInterrupted();
    }
}