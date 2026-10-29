package com.example.investment.task;

import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.Constants.BACKGROUND_THREAD;
import static com.example.investment.util.Constants.EXTRA_TICKER_REQUEST_ID;
import static com.example.investment.util.NetworkUtils.isOffline;
import static com.example.investment.util.BackupUtils.autoExport;

import android.app.job.JobParameters;
import android.content.Context;
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
        private final boolean isFullRefresh;
        private final boolean isUserInitiated;
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
            boolean isFullRefresh,
            boolean isUserInitiated,
            @Nullable JobParameters jobParameters
        ) {
            this.context = context.getApplicationContext();
            this.owner = owner;
            this.type = type;
            this.isFullRefresh = isFullRefresh;
            this.isUserInitiated = isUserInitiated;
            this.jobParameters = jobParameters;
        }
    }

    public static boolean startForService(
        @NonNull TickerService service,
        boolean isFullRefresh,
        boolean isUserInitiated
    ) {
        RunRequest request = new RunRequest(
            service,
            service,
            RunType.SERVICE,
            isFullRefresh,
            isUserInitiated,
            null
        );

        synchronized (RUN_LOCK) {
            // A background trigger must not interrupt a user-initiated update
            if (!isUserInitiated && activeRun != null && activeRun.isUserInitiated) return false;
            if (!isUserInitiated && activeRun != null && activeRun.owner == service) return false;
            activeRun = request;
        }

        BACKGROUND_THREAD.replaceUniqueTask(RUN_TASK_ID, ui -> run(request, ui));
        return true;
    }

    public static void startForJob(
        @NonNull TickerJobService service,
        @NonNull JobParameters params,
        boolean isFullRefresh
    ) {
        RunRequest request = new RunRequest(
            service,
            service,
            RunType.JOB,
            isFullRefresh,
            true,
            params
        );

        synchronized (RUN_LOCK) {
            activeRun = request;
        }

        BACKGROUND_THREAD.replaceUniqueTask(RUN_TASK_ID, ui -> run(request, ui));
    }

    public static void cancelServiceRun(@NonNull TickerService service) {
        cancelRun(service, null);
    }

    public static void onServiceTimeout(@NonNull TickerService service) {
        cancelRun(service, null);
        WorkerManager.scheduleRetryWork(service);
    }

    public static void cancelJobRun(
        @NonNull TickerJobService service,
        @NonNull JobParameters params
    ) {
        cancelRun(service, params);
    }

    private static void cancelRun(
        @NonNull Object owner,
        @Nullable JobParameters params
    ) {
        @Nullable RunRequest request;
        synchronized (RUN_LOCK) {
            request = activeRun;
            if (request == null || request.owner != owner || !sameJobParameters(request.jobParameters, params)) return;
            request.cancelRequested = true;
            activeRun = null;
        }

        BACKGROUND_THREAD.cancelCurrentUniqueTask(RUN_TASK_ID);
    }

    private static void run(
        @NonNull RunRequest request,
        @NonNull com.example.investment.util.SafeSingleTaskRunner.UI ui
    ) {
        TickerDataLoader loader = TickerDataLoader.getInstance();
        boolean guardStarted = false;
        try {
            if (isInterrupted(request)) return;

            long nowMillis = System.currentTimeMillis();
            if (!request.isFullRefresh && !loader.hasPendingTickers(request.context, nowMillis)) {
                request.nothingPending = true;
                ui.post(() -> {
                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) activity.updateFinished(loader.getLastUpdateTime(request.context));
                });
                return;
            }

            // Arm the retry before a long run can hit the platform's dataSync limit
            WorkerManager.scheduleRetryWork(request.context);

            if (!ImportUpdateGuard.tryStartUpdate()) {
                request.failed = true;
                handleError(request, loader.getTickers(request.context), ui);
                return;
            }
            guardStarted = true;

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
                        processRunResult(request, tickers);

                        ui.post(() -> {
                            MainActivity activity = MainActivity.getInstanceActivity();
                            if (activity != null) activity.updateFinished(updateTime);
                        });

                        try {
                            if (!TickerDataLoader.getInstance().hasPendingTickers(request.context, System.currentTimeMillis())) {
                                autoExport(request.context);
                            }
                        } catch (Exception e) {
                            Logger.error("Error checking pending tickers before export", e);
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
                if (isCurrent) activeRun = null;
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
                            processErrorResult(request, loader.getTickers(request.context), ui);
                        }
                    }

                    if (request.type == RunType.SERVICE) {
                        ((TickerService) request.owner).onRunnerFinished();
                    } else {
                        TickerJobService jobService = (TickerJobService) request.owner;
                        if (request.jobParameters != null)
                            jobService.onRunnerFinished(request.jobParameters);
                        if (request.failed && pending) {
                            BACKGROUND_THREAD.execute(() -> UserInitiatedUpdate.startServiceFallback(request.context, false));
                        }
                    }
                }
            }
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
        @NonNull com.example.investment.util.SafeSingleTaskRunner.UI ui
    ) {
        TickerUpdateProcessor processor = new TickerUpdateProcessor(request.context);
        if (request.madeProgress.get() && TickerDataLoader.getInstance().getLastUpdateTime(request.context) > 0) {
            processor.processChangedTickers(tickers != null ? tickers : List.of());
        } else {
            processor.processUnchangedTickers(tickers);
        }

        if (isOffline(request.context)) NetworkReceiver.retryOnNetworkAvailable(request.context);

        ui.post(() -> {
            MainActivity activity = MainActivity.getInstanceActivity();
            if (activity != null) activity.updateError();
        });
    }

    private static void handleError(
        @NonNull RunRequest request,
        @Nullable List<TickerData> tickers,
        @NonNull com.example.investment.util.SafeSingleTaskRunner.UI ui
    ) {
        if (!request.callbackHandled.compareAndSet(false, true)) return;
        request.failed = true;
        processErrorResult(request, tickers, ui);
    }

    private static void showNotification(@NonNull RunRequest request, @NonNull String message) {
        if (request.type == RunType.SERVICE) {
            ((TickerService) request.owner).showUpdateNotification(message);
        } else if (request.jobParameters != null) {
            ((TickerJobService) request.owner).setUpdateNotification(request.jobParameters, message);
        }
    }

    private static boolean sameJobParameters(
        @Nullable JobParameters first,
        @Nullable JobParameters second
    ) {
        if (first == second) return true;
        if (first == null || second == null || first.getJobId() != second.getJobId()) return false;
        return first.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0)
            == second.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
    }

    private static boolean isInterrupted(@NonNull RunRequest request) {
        return request.cancelRequested || Thread.currentThread().isInterrupted();
    }
}