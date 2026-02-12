package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.example.investment.util.Constants.CACHE_FILE_LEADING_TICKERS;
import static com.example.investment.util.Constants.CHANNEL_ID_ASSET_IMPROVING;
import static com.example.investment.util.Constants.CHANNEL_ID_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_REMOVAL;
import static com.example.investment.util.Constants.CHANNEL_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.CHANNEL_NAME_ASSET_IMPROVING;
import static com.example.investment.util.Constants.CHANNEL_NAME_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_REMOVAL;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.NOTIFICATION_ID_ASSET_IMPROVING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_ASSET_RISK;
import static com.example.investment.util.Constants.NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_REMOVAL;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING;
import static com.example.investment.util.Constants.CHANNEL_NAME_PROFIT_TAKING;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.SAVED_LEADING_TICKERS_COUNT;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;
import static com.example.investment.util.NotificationUtils.createLowPriorityServiceNotification;
import static com.example.investment.util.NotificationUtils.showGroupSummary;
import static com.example.investment.util.NotificationUtils.showHighPriorityNotification;
import static com.example.investment.util.NotificationUtils.showLowPriorityServiceNotification;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.MainActivity;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class TickerService extends Service {
    public static final String STOP_SERVICE_ACTION = "stop_service";
    public static final String FULL_DATA_REFRESH_ACTION = "Full";
    public static final String INCREMENTAL_DATA_REFRESH_ACTION = "Incremental";

    private TickerDataLoader dataLoader;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private Future<?> future;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onCreate() {
        // Log Errors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

        super.onCreate();
        dataLoader = TickerDataLoader.getInstance();

        try {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                createLowPriorityServiceNotification(this, "Ticker service running"),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } catch (Exception e) {
            Logger.error("Failed to Start Service", e);
            stopForeground(true);
            stopSelf();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (STOP_SERVICE_ACTION.equals(action)) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            } else if (FULL_DATA_REFRESH_ACTION.equals(action)) {
                updateTickers(FULL_DATA_REFRESH_ACTION);
            } else if (INCREMENTAL_DATA_REFRESH_ACTION.equals(action)) {
                updateTickers(INCREMENTAL_DATA_REFRESH_ACTION);
            } else {
                // Default to partial refresh
                updateTickers(INCREMENTAL_DATA_REFRESH_ACTION);
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executorService.shutdownNow();
        if (future != null) {
            future.cancel(true);
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateTickers(@NonNull final String dataRefreshType) {
        if (future != null) return; // Update is already running
        future = executorService.submit(() -> dataLoader.loadTickers(
            getApplicationContext(),
            dataRefreshType.equals(FULL_DATA_REFRESH_ACTION),
            new TickerUpdateListener() {
                private final AtomicBoolean isFinished = new AtomicBoolean(false);
                @Override
                public void onProgress(int current, int total) {
                    if (isFinished.get()) return;

                    if (total > 0) {
                        double progress = ((double) current / total) * 100;
                        if (current < total) {
                            showLowPriorityServiceNotification(
                                TickerService.this,
                                String.format(
                                    Locale.US,
                                    "%.2f%% Processing Tickers (%s)",
                                    progress,
                                    dataRefreshType
                                )
                            );
                        } else {
                            showLowPriorityServiceNotification(
                                TickerService.this,
                                getString(R.string.successful_ticker_progress)
                            );
                        }
                    } else {
                        showLowPriorityServiceNotification(
                            TickerService.this,
                            String.format(
                                Locale.US,
                                getString(R.string.fetching_tickers),
                                dataRefreshType
                            )
                        );
                    }
                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateProgress(current, total, dataRefreshType);
                    }
                }

                @Override
                public void onTickerUpdate(@NonNull List<TickerInfo> tickers) {
                    if (isFinished.get()) return;

                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateTickerList(tickers);
                    }
                }

                @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                @Override
                public void onFinish(@NonNull List<TickerInfo> tickers, long updateTime) {
                    isFinished.set(true);

                    if (!tickers.isEmpty()) {
                        checkAndNotifyTickerChanges(tickers);
                    }

                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateFinished(updateTime);
                    }

                    TickerService.this.stopForeground(true);
                    TickerService.this.stopSelf();
                }

                @Override
                public void onError() {
                    isFinished.set(true);

                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateError();
                    }

                    TickerService.this.stopForeground(true);
                    TickerService.this.stopSelf();
                }
            }
        ));
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void checkAndNotifyTickerChanges(List<TickerInfo> tickers) {
        // Get saved leading tickers
        List<TickerInfo> savedLeadingTickers = getSavedLeadingTickers();

        // Notify about deleted leading tickers
        notifyDeletedLeadingTickers(tickers, savedLeadingTickers);

        // Get new leading tickers
        long filterMarketCapThreshold = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        List<TickerInfo> validNewLeadingTickers = tickers.stream()
            .filter(t ->
                // Market Cap is Above Limit (Default of $10B)
                t.getMarketCap() >= filterMarketCapThreshold
                // Asset is Not a Risk
                && !t.getPriceState().equals(PRICE_BELOW)
                // Data is not stale
                && !t.isDataStale()
            )
            .toList();
        List<TickerInfo> newLeadingTickers = validNewLeadingTickers.subList(0, Math.min(validNewLeadingTickers.size(), SAVED_LEADING_TICKERS_COUNT));

        // Notify about updated leading tickers
        notifyUpdatedLeadingTickers(newLeadingTickers, savedLeadingTickers);

        // Save New Leading Tickers
        saveLeadingTickers(newLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyNewLeadingTicker(TickerInfo newLeadingTicker) {
        showHighPriorityNotification(
                this,
                CHANNEL_ID_LEADING,
                CHANNEL_NAME_LEADING,
                CHANNEL_NAME_LEADING,
                String.format(
                    Locale.US,
                    "%s: %.2f%% Per Day",
                    newLeadingTicker.getName(true),
                    newLeadingTicker.getPercentageIncreasePerDay()
                ),
                newLeadingTicker.getSymbol(),
                NOTIFICATION_ID_LEADING
        );
        showGroupSummary(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyDeletedLeadingTicker(TickerInfo deletedTicker) {
        showHighPriorityNotification(
            this,
            CHANNEL_ID_LEADING_ASSET_REMOVAL,
            CHANNEL_NAME_LEADING_ASSET_REMOVAL,
            "Investment Asset Removed",
            String.format(
                "\"%s\" has been removed. Check your investments.",
                deletedTicker.getName(true)
            ),
            deletedTicker.getSymbol(),
            NOTIFICATION_ID_LEADING_ASSET_REMOVAL
        );
        showGroupSummary(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyLeadingTickerChanges(TickerInfo leadingTicker, TickerInfo savedLeadingTicker) {
        String currentState = leadingTicker.getPriceState();
        String previousState = savedLeadingTicker.getPriceState();

        if (!previousState.equals(currentState)) {
            switch (currentState) {
                case PRICE_BETWEEN:
                    if (PRICE_ABOVE.equals(previousState)) {
                        showHighPriorityNotification(
                            this,
                            CHANNEL_ID_PROFIT_TAKING,
                            CHANNEL_NAME_PROFIT_TAKING,
                            CHANNEL_NAME_PROFIT_TAKING,
                            String.format(
                                "You can now take some profits off your \"%s\" investment",
                                leadingTicker.getName(true)
                            ),
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_PROFIT_TAKING
                        );
                    } else if (PRICE_BELOW.equals(previousState)) {
                        showHighPriorityNotification(
                            this,
                            CHANNEL_ID_ASSET_IMPROVING,
                            CHANNEL_NAME_ASSET_IMPROVING,
                            CHANNEL_NAME_ASSET_IMPROVING,
                            String.format(
                                "\"%s\" trend is slowly recovering.",
                                leadingTicker.getName(true)
                            ),
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_ASSET_IMPROVING
                        );
                    }
                    break;

                case PRICE_ABOVE:
                    showHighPriorityNotification(
                        this,
                        CHANNEL_ID_INVESTMENT_OPPORTUNITY,
                        CHANNEL_NAME_INVESTMENT_OPPORTUNITY,
                        CHANNEL_NAME_INVESTMENT_OPPORTUNITY,
                        String.format(
                            "Invest/Hold your assets into \"%s\".",
                            leadingTicker.getName(true)
                        ),
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_INVESTMENT_OPPORTUNITY
                    );
                    break;

                case PRICE_BELOW:
                    showHighPriorityNotification(
                        this,
                        CHANNEL_ID_ASSET_RISK,
                        CHANNEL_NAME_ASSET_RISK,
                        CHANNEL_NAME_ASSET_RISK,
                        String.format(
                            "Risk Warning: \"%s\" moved below its usual price.",
                            leadingTicker.getName(true)
                        ),
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_ASSET_RISK
                    );
                    break;
            }
            showGroupSummary(this);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveLeadingTickers(List<TickerInfo> leadingTickers) {
        try {
            saveObject(
                getApplicationContext(),
                CACHE_FILE_LEADING_TICKERS,
                new ArrayList<>(leadingTickers)
            );
        } catch (IOException e) {
            Logger.error("Error saving leading tickers", e);
        }
    }

    private List<TickerInfo> getSavedLeadingTickers() {
        try {
            //noinspection unchecked
            return (List<TickerInfo>) loadObject(getApplicationContext(), CACHE_FILE_LEADING_TICKERS);
        } catch (Exception e) {
            Logger.error("Error loading leading tickers", e);
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyDeletedLeadingTickers(@NonNull List<TickerInfo> tickers, List<TickerInfo> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) return;

        Set<String> newTickersSymbols = tickers.stream()
            .map(TickerInfo::getSymbol)
            .collect(Collectors.toSet());

        for (TickerInfo savedLeadingTicker : savedLeadingTickers) {
            if (!newTickersSymbols.contains(savedLeadingTicker.getSymbol())) {
                notifyDeletedLeadingTicker(savedLeadingTicker);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyUpdatedLeadingTickers(@NonNull List<TickerInfo> newLeadingTickers, List<TickerInfo> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) {
            for (TickerInfo newLeadingTicker : newLeadingTickers) {
                notifyNewLeadingTicker(newLeadingTicker);
            }
        } else {
            // Check for new or changed tickers
            for (TickerInfo newLeadingTicker : newLeadingTickers) {
                boolean isNew = true;
                TickerInfo match = null;

                for (TickerInfo savedLeadingTicker : savedLeadingTickers) {
                    if (newLeadingTicker.getSymbol().equals(savedLeadingTicker.getSymbol())) {
                        isNew = false;
                        match = savedLeadingTicker;
                        break;
                    }
                }

                if (isNew) {
                    notifyNewLeadingTicker(newLeadingTicker);
                } else {
                    notifyLeadingTickerChanges(newLeadingTicker, match);
                }
            }
        }
    }
}