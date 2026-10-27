package com.example.investment.service;

import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;

import android.app.Notification;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.R;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.task.TickerUpdateRunner;
import com.example.investment.task.UserInitiatedUpdate;
import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;
import com.example.investment.util.NotificationUtils;
import com.example.investment.util.Constants;
import static com.example.investment.util.Constants.EXTRA_TICKER_FULL_REFRESH;
import static com.example.investment.util.Constants.EXTRA_TICKER_REQUEST_ID;

import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("SpecifyJobSchedulerIdRange")
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class TickerJobService extends JobService {
    private static final class JobRunState {
        private final JobParameters params;
        private boolean completed;
        private boolean stopped;

        private JobRunState(@NonNull JobParameters params) {
            this.params = params;
        }
    }

    private final ConcurrentHashMap<Long, JobRunState> jobRuns = new ConcurrentHashMap<>();

    @Override
    public boolean onStartJob(@NonNull JobParameters params) {
        long requestId = params.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
        UserInitiatedUpdate.registerRequestId(requestId);
        jobRuns.put(requestId, new JobRunState(params));

        setUpdateNotification(params, getString(R.string.ticker_update_notification_title));

        boolean isFullRefresh = params.getExtras().getBoolean(EXTRA_TICKER_FULL_REFRESH, false);
        TickerUpdateRunner.startForJob(this, params, isFullRefresh);
        return true;
    }

    @Override
    public boolean onStopJob(@NonNull JobParameters params) {
        long requestId = params.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
        JobRunState state = jobRuns.remove(requestId);
        if (state != null) {
            synchronized (state) {
                state.stopped = true;
                state.completed = true;
            }
        }

        boolean expectedStop = UserInitiatedUpdate.consumeExpectedStop(requestId);
        TickerUpdateRunner.cancelJobRun(this, params);
        if (!expectedStop) {
            WorkerManager.scheduleRetryWork(this);
            Constants.EVENT_THREAD.execute(() -> {
                try {
                    if (TickerDataLoader.getInstance().hasPendingTickers(this, System.currentTimeMillis())) {
                        UserInitiatedUpdate.startServiceFallback(this, false);
                    } else {
                        WorkerManager.cancelRetryWork(this);
                    }
                } catch (Exception e) {
                    Logger.error("Error checking pending tickers after the UIDT job stopped", e);
                    WorkerManager.scheduleRetryWork(this);
                }
            });
        }
        return false;
    }

    public void setUpdateNotification(@NonNull JobParameters params, @NonNull String message) {
        try {
            Notification notification = NotificationUtils.createLowPriorityServiceNotification(this, message);
            setNotification(
                params,
                NOTIFICATION_ID_SERVICE,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE
            );
        } catch (Exception e) {
            Logger.error("Failed to update the ticker job notification", e);
        }
    }

    public void onRunnerFinished(@NonNull JobParameters params) {
        long requestId = params.getExtras().getLong(EXTRA_TICKER_REQUEST_ID, 0);
        JobRunState state = jobRuns.get(requestId);
        if (state == null) return;

        synchronized (state) {
            if (state.stopped || state.completed) return;
            state.completed = true;
            jobRuns.remove(requestId, state);
            jobFinished(state.params, false);
        }
    }
}