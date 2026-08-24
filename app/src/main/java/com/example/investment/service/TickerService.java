package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.BACKGROUND_THREAD;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.DEFAULT_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.MOMENTUM_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MOMENTUM_INVESTMENT_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PASSIVE_INVESTMENT_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;
import static com.example.investment.util.Constants.CHANNEL_NAME_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_NAME_PROFIT_TAKING;
import static com.example.investment.util.Constants.OUTDATED_TICKER_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PASSIVE_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.PREF_ASSET_TYPE;
import static com.example.investment.util.Constants.PREF_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.PREF_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREF_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREF_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.DEFAULT_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.Constants.UI_THREAD;
import static com.example.investment.util.NotificationUtils.createLowPriorityServiceNotification;
import static com.example.investment.util.NotificationUtils.showGroupSummary;
import static com.example.investment.util.NotificationUtils.showHighPriorityNotification;
import static com.example.investment.util.NotificationUtils.showLowPriorityServiceNotification;
import static com.example.investment.util.BigDecimalUtils.getSafeBigDecimalPrefs;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class TickerService extends Service {
    public static final String STOP_SERVICE_ACTION = "stop_service";
    public static final String FULL_DATA_REFRESH_ACTION = "Full";
    public static final String INCREMENTAL_DATA_REFRESH_ACTION = "Incremental";
    private static SharedPreferences sharedPreferences;
    private static TickerDataLoader tickerDataLoader;

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onCreate() {
        super.onCreate();

        tickerDataLoader = TickerDataLoader.getInstance();
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

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

    private String dataRefreshType = null;
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (STOP_SERVICE_ACTION.equals(action)) {
                UI_THREAD.post(() -> {
                    MainActivity activity = MainActivity.getInstanceActivity();
                    if (activity != null) {
                        activity.updateCancel();
                    }
                });
                stopForeground(true);
                stopSelf();
            } else if (FULL_DATA_REFRESH_ACTION.equals(action)) {
                if (!FULL_DATA_REFRESH_ACTION.equals(dataRefreshType)) {
                    dataRefreshType = FULL_DATA_REFRESH_ACTION;
                    updateTickers(FULL_DATA_REFRESH_ACTION);
                }
            } else if (INCREMENTAL_DATA_REFRESH_ACTION.equals(action)) {
                if (dataRefreshType == null) {
                    dataRefreshType = INCREMENTAL_DATA_REFRESH_ACTION;
                    updateTickers(INCREMENTAL_DATA_REFRESH_ACTION);
                }
            }
        }
        return START_NOT_STICKY;
    }

    private final Object UPDATE_TICKERS_TASK_ID = new Object();
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateTickers(@NonNull final String dataRefreshType) {
        BACKGROUND_THREAD.replaceUniqueTask(UPDATE_TICKERS_TASK_ID, ui ->
            tickerDataLoader.updateTickers(
                getApplicationContext(),
                dataRefreshType.equals(FULL_DATA_REFRESH_ACTION),
                new TickerUpdateListener() {
                private final AtomicBoolean isFinished = new AtomicBoolean(false);
                @Override
                public void onSymbolsProgress(final int current) {
                    if (isFinished.get()) return;

                    if (current > 0) {
                        showLowPriorityServiceNotification(
                            TickerService.this,
                            String.format(
                                getString(R.string.fetching_tickers_found),
                                dataRefreshType,
                                current
                            )
                        );
                    } else {
                        showLowPriorityServiceNotification(
                            TickerService.this,
                            String.format(
                                getString(R.string.fetching_tickers),
                                dataRefreshType
                            )
                        );
                    }

                    ui.post(() -> {
                        MainActivity activity = MainActivity.getInstanceActivity();
                        if (activity != null) {
                           activity.updateSymbolsProgress(current, dataRefreshType);
                        }
                    });
                }
                @Override
                public void onTickersProgress(final int current, final int total) {
                    if (isFinished.get()) return;

                    if (total > 0) {
                        double progress = ((double) current / total) * 100;
                        if (current < total) {
                            showLowPriorityServiceNotification(
                                TickerService.this,
                                String.format(
                                    getString(R.string.processing_tickers),
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
                                getString(R.string.fetching_tickers),
                                dataRefreshType
                            )
                        );
                    }

                    ui.post(() -> {
                        MainActivity activity = MainActivity.getInstanceActivity();
                        if (activity != null) {
                            activity.updateTickersProgress(current, total, dataRefreshType);
                        }
                    });
                }

                @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                @Override
                public void onTickerUpdate(final @NonNull List<TickerData> tickers) {
                    if (isFinished.get()) return;

                    ui.post(() -> {
                        MainActivity activity = MainActivity.getInstanceActivity();
                        if (activity != null) {
                            activity.updateTickerList(tickers);
                        }
                    });
                }

                @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                @Override
                public void onFinish(@NonNull List<TickerData> tickers, final long updateTime) {
                    isFinished.set(true);

                    processChangedTickers(new ArrayList<>(tickers));

                    ui.post(() -> {
                        MainActivity activity = MainActivity.getInstanceActivity();
                        if (activity != null) {
                            activity.updateFinished(updateTime);
                        }
                    });

                    TickerService.this.stopForeground(true);
                    TickerService.this.stopSelf();
                }

                @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                @Override
                public void onError(@Nullable List<TickerData> oldTickers) {
                    isFinished.set(true);

                    processUnchangedTickers(oldTickers);

                    ui.post(() -> {
                        MainActivity activity = MainActivity.getInstanceActivity();
                        if (activity != null) {
                            activity.updateError();
                        }
                    });

                    TickerService.this.stopForeground(true);
                    TickerService.this.stopSelf();
                }
            }
            )
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void processChangedTickers(@NonNull List<TickerData> tickers) {
        // Get saved investment configurations
        @NonNull String investmentType = sharedPreferences.getString(PREF_INVESTMENT_TYPE, PASSIVE_INVESTMENT_TYPE);
        @NonNull String assetType = sharedPreferences.getString(PREF_ASSET_TYPE, ALL_TYPE);
        int numberOfTopHoldings = sharedPreferences.getInt(PREF_NUMBER_OF_TOP_HOLDINGS, DEFAULT_NUMBER_OF_TOP_HOLDINGS);
        @NonNull BigDecimal marketCapThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        @NonNull BigDecimal dailyDollarVolumeThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD);
        @NonNull BigDecimal ageInSecondsThreshold = getSafeBigDecimalPrefs(sharedPreferences, PREF_AGE_IN_SECONDS_THRESHOLD, DEFAULT_AGE_IN_SECONDS_THRESHOLD);

        // Get saved leading tickers
        @Nullable List<TickerData> savedLeadingTickers = getSavedLeadingTickers(investmentType);

        // Notify about deleted leading tickers
        notifyDeletedLeadingTickers(investmentType, tickers, savedLeadingTickers);

        // Sort and get leading tickers for specific investment
        @NonNull List<TickerData> validNewLeadingTickers = tickers.stream()
            .filter(t -> {
                TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord = t.getNewestFixedHistoricalDataWithInterval();
                return (
                    // Asset type is invested
                    (assetType.equals(ALL_TYPE) || assetType.equals(t.getType()))
                    // Market cap is above or equal to threshold
                    && t.getMarketCap().compareTo(marketCapThreshold) >= 0
                    // Daily dollar volume is above or equal to threshold
                    && t.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(dailyDollarVolumeThreshold) >= 0
                    // Age is above or equal to threshold
                    && t.getAgeInSeconds().compareTo(ageInSecondsThreshold) >= 0
                    // Asset price is trending above
                    && t.getPriceState(
                        investmentType,
                        Optional.ofNullable(newestFixedHistoricalDataRecord)
                            .map(TickerData.FixedHistoricalRecord::fixedHistoricalData)
                            .orElse(null)
                    ).equals(PRICE_ABOVE)
                );
            })
            .sorted((a, b) -> b.getPercentageIncreasePerDay(investmentType).compareTo(a.getPercentageIncreasePerDay(investmentType)))
            .toList();
        @NonNull List<TickerData> currentLeadingTickers = validNewLeadingTickers.subList(0, Math.min(validNewLeadingTickers.size(), numberOfTopHoldings));

        // Notify about updated leading tickers
        notifyUpdatedLeadingTickers(investmentType, currentLeadingTickers, savedLeadingTickers);

        // Save New Leading Tickers
        saveLeadingTickers(investmentType, currentLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void processUnchangedTickers(@Nullable List<TickerData> oldTickers) {
        // Get saved investment configurations
        @NonNull String investmentType = sharedPreferences.getString(PREF_INVESTMENT_TYPE, PASSIVE_INVESTMENT_TYPE);

        // Get saved leading tickers
        @Nullable List<TickerData> savedLeadingTickers = getSavedLeadingTickers(investmentType);

        // Notify any unexpected loss of leading tickers in old tickers data
        if (oldTickers != null) {
            notifyDeletedLeadingTickers(investmentType, new ArrayList<>(oldTickers), savedLeadingTickers);
        }

        // Notify any outdated leading tickers
        notifyOutdatedLeadingTickers(investmentType, savedLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyUpdatedLeadingTickers(@NonNull String investmentType, @NonNull List<TickerData> currentLeadingTickers, @Nullable List<TickerData> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) {
            for (TickerData currentLeadingTicker : currentLeadingTickers) {
                if (currentLeadingTicker == null) continue;

                boolean isDataStale = currentLeadingTicker.areAllFixedHistoricalDataStale();
                if (isDataStale) {
                    notifyOutdatedLeadingTicker(investmentType, currentLeadingTicker);
                }

                notifyNewLeadingTicker(investmentType, currentLeadingTicker, isDataStale);
            }
        } else {
            // Check for new or changed tickers
            for (TickerData currentLeadingTicker : currentLeadingTickers) {
                if (currentLeadingTicker == null) continue;

                boolean isDataStale = currentLeadingTicker.areAllFixedHistoricalDataStale();
                if (isDataStale) {
                    notifyOutdatedLeadingTicker(investmentType, currentLeadingTicker);
                }

                TickerData matchedSavedLeadingTicker = null;
                for (TickerData savedLeadingTicker : savedLeadingTickers) {
                    if (savedLeadingTicker == null) continue;
                    if (currentLeadingTicker.getSymbol().equals(savedLeadingTicker.getSymbol())) {
                        matchedSavedLeadingTicker = savedLeadingTicker;
                        break;
                    }
                }

                if (matchedSavedLeadingTicker != null) {
                    notifyLeadingTickerChanges(investmentType, currentLeadingTicker, matchedSavedLeadingTicker, isDataStale);
                } else {
                    notifyNewLeadingTicker(investmentType, currentLeadingTicker, isDataStale);
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void notifyOutdatedLeadingTickers(@NonNull String investmentType, @Nullable List<TickerData> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) return;

        for (TickerData savedLeadingTicker : savedLeadingTickers) {
            if (savedLeadingTicker == null) continue;
            boolean isDataStale = savedLeadingTicker.areAllFixedHistoricalDataStale();
            if (isDataStale) {
                notifyOutdatedLeadingTicker(investmentType, savedLeadingTicker);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void notifyOutdatedLeadingTicker(@NonNull String investmentType, @NonNull TickerData leadingTicker) {
        // Construct when timestamp
        Long when = null;
        try {
            when = leadingTicker.getNewestFixedHistoricalDataTimestampSeconds().multiply(THOUSAND_BI).longValueExact();
        } catch (Exception ignored) {}

        // Construct subtext
        @NonNull String investmentTypeSubText = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? MOMENTUM_NOTIFICATION_SUBTEXT
            : PASSIVE_NOTIFICATION_SUBTEXT;
        @NonNull String subtext = leadingTicker.getSymbol() + " | " + investmentTypeSubText;

        String channelId = CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA;
        String channelName = CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA;
        String title = "Outdated Data";
        String content = String.format(
            "%s | Historical data may be out of date, %s may have recently closed for a holiday, check now.",
            leadingTicker.getSymbol(),
            leadingTicker.getExchange() == null || leadingTicker.getExchange().isEmpty()
            ? "its exchange" : leadingTicker.getExchange().replace(" ", "-")
        );
        String groupKey = CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA;
        String notificationTag = leadingTicker.getSymbol();

        showHighPriorityNotification(
            this,
            channelId,
            channelName,
            title,
            content,
            subtext,
            when,
            groupKey,
            notificationTag,
            NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA
        );
        showGroupSummary(
            this,
            channelId,
            channelName,
            title,
            groupKey,
            NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA_GROUP_SUMMARY
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void notifyNewLeadingTicker(@NonNull String investmentType, @NonNull TickerData newLeadingTicker, boolean isDataStale) {
        // Construct when timestamp (only if data is stale)
        Long when = null;
        if (isDataStale) {
            try {
                when = newLeadingTicker.getNewestFixedHistoricalDataTimestampSeconds().multiply(THOUSAND_BI).longValueExact();
            } catch (Exception ignored) {}
        }

        // Construct subtext (different format if data is stale)
        @NonNull String investmentTypeSubText = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? MOMENTUM_NOTIFICATION_SUBTEXT
            : PASSIVE_NOTIFICATION_SUBTEXT;
        @NonNull String subtext = isDataStale
            ? OUTDATED_TICKER_NOTIFICATION_SUBTEXT + " | " + investmentTypeSubText
            : newLeadingTicker.getSymbol() + " | " + investmentTypeSubText;

        String channelId = CHANNEL_ID_NEW_LEADING_ASSET;
        String channelName = CHANNEL_NAME_NEW_LEADING_ASSET;
        String title = "Invest Now";
        String content = String.format(
            "%s | Leads for investment, check now.",
            newLeadingTicker.getSymbol()
        );
        String groupKey = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? MOMENTUM_INVESTMENT_TYPE
            : PASSIVE_INVESTMENT_TYPE;
        String notificationTag = newLeadingTicker.getSymbol();
        showHighPriorityNotification(
            this,
            channelId,
            channelName,
            title,
            content,
            subtext,
            when,
            groupKey,
            notificationTag,
            NOTIFICATION_ID_NEW_LEADING_ASSET
        );

        String summaryTitle = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? MOMENTUM_INVESTMENT_TYPE
            : PASSIVE_INVESTMENT_TYPE;
        int summaryNotificationId = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? NOTIFICATION_ID_MOMENTUM_INVESTMENT_GROUP_SUMMARY
            : NOTIFICATION_ID_PASSIVE_INVESTMENT_GROUP_SUMMARY;
        showGroupSummary(
            this,
            channelId,
            channelName,
            summaryTitle,
            groupKey,
            summaryNotificationId
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void notifyLeadingTickerChanges(@NonNull String investmentType, @NonNull TickerData leadingTicker, @NonNull TickerData savedLeadingTicker, boolean isDataStale) {
        @NonNull String currentState = leadingTicker.getPriceState(investmentType);
        @NonNull String previousState = savedLeadingTicker.getPriceState(investmentType);

        if (!previousState.equals(currentState)) {
            // Construct when timestamp (only if data is stale)
            Long when = null;
            if (isDataStale) {
                try {
                    when = leadingTicker.getNewestFixedHistoricalDataTimestampSeconds().multiply(THOUSAND_BI).longValueExact();
                } catch (Exception ignored) {}
            }

            // Construct subtext (different format if data is stale)
            @NonNull String investmentTypeSubText = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
                ? MOMENTUM_NOTIFICATION_SUBTEXT
                : PASSIVE_NOTIFICATION_SUBTEXT;
            @NonNull String subtext = isDataStale
                ? OUTDATED_TICKER_NOTIFICATION_SUBTEXT + " | " + investmentTypeSubText
                : leadingTicker.getSymbol() + " | " + investmentTypeSubText;

            String channelId = null;
            String channelName = null;
            String title = null;
            String content = null;
            String groupKey = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
                ? MOMENTUM_INVESTMENT_TYPE
                : PASSIVE_INVESTMENT_TYPE;
            String notificationTag = leadingTicker.getSymbol();
            int notificationId = 0;

            switch (currentState) {
                case PRICE_BETWEEN:
                    if (PRICE_ABOVE.equals(previousState)) {
                        channelId = CHANNEL_ID_PROFIT_TAKING;
                        channelName = CHANNEL_NAME_PROFIT_TAKING;
                        title = "Profit Taking";
                        content = String.format(
                            "%s | Trend slowed down, good time to take some profits.",
                            leadingTicker.getName(true)
                        );
                        notificationId = NOTIFICATION_ID_PROFIT_TAKING;
                    } else if (PRICE_BELOW.equals(previousState)) {
                        channelId = CHANNEL_ID_LEADING_ASSET_RECOVERY;
                        channelName = CHANNEL_NAME_LEADING_ASSET_RECOVERY;
                        title = "Slow Recovery";
                        content = String.format(
                            "%s | Price is moving back up to normal.",
                            leadingTicker.getSymbol()
                        );
                        notificationId = NOTIFICATION_ID_LEADING_ASSET_RECOVERY;
                    }
                    break;

                case PRICE_ABOVE:
                    channelId = CHANNEL_ID_INVESTMENT_OPPORTUNITY;
                    channelName = CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
                    title = "Invest Now";
                    content = String.format(
                        "%s | Trend is starting, check now.",
                        leadingTicker.getSymbol()
                    );
                    notificationId = NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
                    break;

                default:
                    channelId = CHANNEL_ID_LEADING_ASSET_RISK;
                    channelName = CHANNEL_NAME_LEADING_ASSET_RISK;
                    title = "Risk Alert";
                    content = String.format(
                        "%s | Price dropped significantly, check now.",
                        leadingTicker.getSymbol()
                    );
                    notificationId = NOTIFICATION_ID_LEADING_ASSET_RISK;
                    break;
            }
            showHighPriorityNotification(
                this,
                channelId,
                channelName,
                title,
                content,
                subtext,
                when,
                groupKey,
                notificationTag,
                notificationId
            );

            String summaryTitle = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
                ? MOMENTUM_INVESTMENT_TYPE
                : PASSIVE_INVESTMENT_TYPE;
            int summaryNotificationId = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
                ? NOTIFICATION_ID_MOMENTUM_INVESTMENT_GROUP_SUMMARY
                : NOTIFICATION_ID_PASSIVE_INVESTMENT_GROUP_SUMMARY;
            showGroupSummary(
                this,
                channelId,
                channelName,
                summaryTitle,
                groupKey,
                summaryNotificationId
            );
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void notifyDeletedLeadingTickers(@NonNull String investmentType, @NonNull List<TickerData> tickers, @Nullable List<TickerData> savedLeadingTickers) {
        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) return;

        Set<String> tickersSymbols = tickers.stream()
            .map(TickerData::getSymbol)
            .collect(Collectors.toSet());

        for (TickerData savedLeadingTicker : savedLeadingTickers) {
            if (savedLeadingTicker == null) continue;
            if (!tickersSymbols.contains(savedLeadingTicker.getSymbol())) {
                notifyDeletedLeadingTicker(investmentType, savedLeadingTicker);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyDeletedLeadingTicker(@NonNull String investmentType, @NonNull TickerData deletedTicker) {
        // Construct subtext
        @NonNull String investmentTypeSubText = investmentType.equals(MOMENTUM_INVESTMENT_TYPE)
            ? MOMENTUM_NOTIFICATION_SUBTEXT
            : PASSIVE_NOTIFICATION_SUBTEXT;
        @NonNull String subtext = deletedTicker.getSymbol() + " | " + investmentTypeSubText;

        String channelId = CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET;
        String channelName = CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET;
        String title = "Risk Alert";
        String content = String.format(
            "%s | Asset is removed/missing, check now.",
            deletedTicker.getSymbol()
        );
        String groupKey = CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET;
        String notificationTag = deletedTicker.getSymbol();

        showHighPriorityNotification(
            this,
            channelId,
            channelName,
            title,
            content,
            subtext,
            null,
            groupKey,
            notificationTag,
            NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET
        );
        showGroupSummary(
            this,
            channelId,
            channelName,
            title,
            groupKey,
            NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET_GROUP_SUMMARY
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveLeadingTickers(@NonNull String investmentType, @NonNull List<TickerData> tickers) {
        tickerDataLoader.saveLeadingTickers(getApplicationContext(), investmentType, tickers);
    }
    private @Nullable List<TickerData> getSavedLeadingTickers(String investmentType) {
        return tickerDataLoader.getLeadingTickers(getApplicationContext(), investmentType);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        BACKGROUND_THREAD.cancelAll();
        super.onDestroy();
    }
}