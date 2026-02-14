package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.FILE_RECORD_LEADING_TICKERS;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;
import static com.example.investment.util.Constants.CHANNEL_NAME_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_NAME_PROFIT_TAKING;
import static com.example.investment.util.Constants.OUTDATED_TICKER_NOTIFICATION_SUBTEXT;
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
import com.example.investment.model.ticker.TickerData;
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
    private String dataRefreshType = null;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onCreate() {
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
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateError();
                }
                return START_NOT_STICKY;
            } else {
                if (FULL_DATA_REFRESH_ACTION.equals(action)) {
                    if (INCREMENTAL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
                        if (future != null && !future.isDone()) {
                            future.cancel(true);
                        }
                    }
                    dataRefreshType = FULL_DATA_REFRESH_ACTION;
                    updateTickers(FULL_DATA_REFRESH_ACTION);
                } else if (INCREMENTAL_DATA_REFRESH_ACTION.equals(action)) {
                    if (future == null || future.isDone() || !FULL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
                        dataRefreshType = INCREMENTAL_DATA_REFRESH_ACTION;
                        updateTickers(INCREMENTAL_DATA_REFRESH_ACTION);
                    }
                }
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
        future = executorService.submit(() -> dataLoader.updateTickers(
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
                public void onTickerUpdate(@NonNull List<TickerData> tickers) {
                    if (isFinished.get()) return;

                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateTickerList(tickers);
                    }
                }

                @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                @Override
                public void onFinish(@NonNull List<TickerData> tickers, long updateTime) {
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
    private void checkAndNotifyTickerChanges(List<TickerData> tickers) {
        // Get saved leading tickers
        List<TickerData> savedLeadingTickers = getSavedLeadingTickers();

        // Notify about deleted leading tickers
        notifyDeletedLeadingTickers(tickers, savedLeadingTickers);

        // Get new leading tickers
        long filterMarketCapThreshold = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        List<TickerData> validNewLeadingTickers = tickers.stream()
            .filter(t ->
                // Market Cap is Above Limit (Default of $10B)
                t.getMarketCap() >= filterMarketCapThreshold
                // Asset is Not a Risk
                && !t.getPriceState().equals(PRICE_BELOW)
            )
            .toList();
        List<TickerData> currentLeadingTickers = validNewLeadingTickers.subList(0, Math.min(validNewLeadingTickers.size(), SAVED_LEADING_TICKERS_COUNT));

        // Notify about updated leading tickers
        notifyUpdatedLeadingTickers(currentLeadingTickers, savedLeadingTickers);

        // Save New Leading Tickers
        saveLeadingTickers(currentLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyUpdatedLeadingTickers(@NonNull List<TickerData> currentLeadingTickers, List<TickerData> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) {
            for (TickerData currentLeadingTicker : currentLeadingTickers) {
                String subtext;
                Long when = null;
                if (currentLeadingTicker.areAllFixedHistoricalDataStale()) {
                    when = currentLeadingTicker.getNewestFixedHistoricalDataTimestampSeconds();
                    notifyOutdatedLeadingTicker(currentLeadingTicker, when);
                    // Below outdated data notification to avoid redundant info
                    subtext = OUTDATED_TICKER_NOTIFICATION_SUBTEXT;
                } else {
                    subtext = currentLeadingTicker.getSymbol();
                }

                notifyNewLeadingTicker(currentLeadingTicker, subtext, when);
            }
        } else {
            // Check for new or changed tickers
            for (TickerData currentLeadingTicker : currentLeadingTickers) {
                String subtext;
                Long when = null;
                if (currentLeadingTicker.areAllFixedHistoricalDataStale()) {
                    when = currentLeadingTicker.getNewestFixedHistoricalDataTimestampSeconds();
                    notifyOutdatedLeadingTicker(currentLeadingTicker, when);
                    // Below outdated data notification to avoid redundant info
                    subtext = OUTDATED_TICKER_NOTIFICATION_SUBTEXT;
                } else {
                    subtext = currentLeadingTicker.getSymbol();
                }

                boolean isCurrentLeadingTickerNew = true;
                TickerData matchedSavedLeadingTicker = null;

                for (TickerData savedLeadingTicker : savedLeadingTickers) {
                    if (currentLeadingTicker.getSymbol().equals(savedLeadingTicker.getSymbol())) {
                        isCurrentLeadingTickerNew = false;
                        matchedSavedLeadingTicker = savedLeadingTicker;
                        break;
                    }
                }

                if (isCurrentLeadingTickerNew) {
                    notifyNewLeadingTicker(currentLeadingTicker, subtext, when);
                } else {
                    notifyLeadingTickerChanges(currentLeadingTicker, matchedSavedLeadingTicker, subtext, when);
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyOutdatedLeadingTicker(TickerData leadingTicker, Long when) {
        showHighPriorityNotification(
            this,
            CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA,
            CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA,
            "Outdated Data",
            String.format(
                "%s | Historical data is out of date, check now.",
                leadingTicker.getSymbol()
            ),
            leadingTicker.getSymbol(),
            when,
            leadingTicker.getSymbol(),
            NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA
        );
        showGroupSummary(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyNewLeadingTicker(TickerData newLeadingTicker, String subtext, Long when) {
        showHighPriorityNotification(
            this,
            CHANNEL_ID_NEW_LEADING_ASSET,
            CHANNEL_NAME_NEW_LEADING_ASSET,
            "Invest Now",
            String.format(
                Locale.US,
                "%s | Leads for investment, check now.",
                newLeadingTicker.getSymbol()
            ),
            subtext,
            when,
            newLeadingTicker.getSymbol(),
            NOTIFICATION_ID_NEW_LEADING_ASSET
        );
        showGroupSummary(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyLeadingTickerChanges(TickerData leadingTicker, TickerData savedLeadingTicker, String subtext, Long when) {
        @NonNull String currentState = leadingTicker.getPriceState();
        @NonNull String previousState = savedLeadingTicker.getPriceState();

        if (!previousState.equals(currentState)) {
            switch (currentState) {
                case PRICE_BETWEEN:
                    if (PRICE_ABOVE.equals(previousState)) {
                        showHighPriorityNotification(
                            this,
                            CHANNEL_ID_PROFIT_TAKING,
                            CHANNEL_NAME_PROFIT_TAKING,
                            "Profit Taking",
                            String.format(
                                "%s | Trend slowed down, good time to take some profits.",
                                leadingTicker.getName(true)
                            ),
                            subtext,
                            when,
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_PROFIT_TAKING
                        );
                    } else if (PRICE_BELOW.equals(previousState)) {
                        showHighPriorityNotification(
                            this,
                            CHANNEL_ID_LEADING_ASSET_RECOVERY,
                            CHANNEL_NAME_LEADING_ASSET_RECOVERY,
                            "Slow Recovery",
                            String.format(
                                "%s | Price is moving back up to normal.",
                                leadingTicker.getSymbol()
                            ),
                            subtext,
                            when,
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_LEADING_ASSET_RECOVERY
                        );
                    }
                    break;

                case PRICE_ABOVE:
                    showHighPriorityNotification(
                        this,
                        CHANNEL_ID_INVESTMENT_OPPORTUNITY,
                        CHANNEL_NAME_INVESTMENT_OPPORTUNITY,
                        "Invest Now",
                        String.format(
                            "%s | Trend is starting, check now.",
                            leadingTicker.getSymbol()
                        ),
                        subtext,
                        when,
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_INVESTMENT_OPPORTUNITY
                    );
                    break;

                case PRICE_BELOW:
                    showHighPriorityNotification(
                        this,
                        CHANNEL_ID_LEADING_ASSET_RISK,
                        CHANNEL_NAME_LEADING_ASSET_RISK,
                        "Risk Alert",
                        String.format(
                            "%s | Price dropped significantly, check now.",
                            leadingTicker.getSymbol()
                        ),
                        subtext,
                        when,
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_LEADING_ASSET_RISK
                    );
                    break;
            }
            showGroupSummary(this);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyDeletedLeadingTickers(@NonNull List<TickerData> tickers, List<TickerData> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) return;

        Set<String> newTickersSymbols = tickers.stream()
            .map(TickerData::getSymbol)
            .collect(Collectors.toSet());

        for (TickerData savedLeadingTicker : savedLeadingTickers) {
            if (!newTickersSymbols.contains(savedLeadingTicker.getSymbol())) {
                notifyDeletedLeadingTicker(savedLeadingTicker);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyDeletedLeadingTicker(TickerData deletedTicker) {
        showHighPriorityNotification(
            this,
            CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET,
            CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET,
            "Risk Alert",
            String.format(
                "%s | Asset is removed/missing, check now.",
                deletedTicker.getSymbol()
            ),
            deletedTicker.getSymbol(),
            null,
            deletedTicker.getSymbol(),
            NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET
        );
        showGroupSummary(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveLeadingTickers(List<TickerData> leadingTickers) {
        try {
            saveObject(
                getApplicationContext(),
                FILE_RECORD_LEADING_TICKERS,
                new ArrayList<>(leadingTickers)
            );
        } catch (IOException e) {
            Logger.error("Error saving leading tickers", e);
        }
    }

    private List<TickerData> getSavedLeadingTickers() {
        try {
            //noinspection unchecked
            return (List<TickerData>) loadObject(getApplicationContext(), FILE_RECORD_LEADING_TICKERS);
        } catch (Exception e) {
            Logger.error("Error loading leading tickers", e);
            return null;
        }
    }
}