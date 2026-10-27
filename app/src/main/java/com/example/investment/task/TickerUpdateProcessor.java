package com.example.investment.task;

import static com.example.investment.util.Constants.CHANNEL_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.CHANNEL_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_NAME_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_NAME_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.CHANNEL_NAME_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.CHANNEL_NAME_PROFIT_TAKING;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.MOMENTUM_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RECOVERY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING_ASSET_RISK;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_MOMENTUM_INVESTMENT_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_NEW_LEADING_ASSET;
import static com.example.investment.util.Constants.NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA;
import static com.example.investment.util.Constants.NOTIFICATION_ID_OUTDATED_LEADING_ASSET_DATA_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PASSIVE_INVESTMENT_GROUP_SUMMARY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.OUTDATED_TICKER_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PASSIVE_NOTIFICATION_SUBTEXT;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.NotificationUtils.showGroupSummary;
import static com.example.investment.util.NotificationUtils.showHighPriorityNotification;
import static com.example.investment.util.TickerRanking.getLeadingTickers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.ticker.TickerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TickerUpdateProcessor {
    @NonNull private final Context context;
    @NonNull private final SharedPreferences sharedPreferences;
    @NonNull private final TickerDataLoader tickerDataLoader;

    public TickerUpdateProcessor(@NonNull Context context) {
        this.context = context;
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.tickerDataLoader = TickerDataLoader.getInstance();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void processChangedTickers(@NonNull List<TickerData> tickers) {
        // Get saved investment configurations
        @NonNull InvestmentConfig config = InvestmentConfig.fromPreferences(sharedPreferences);
        @NonNull String investmentType = config.investmentType();

        // Get saved leading tickers
        @Nullable List<TickerData> savedLeadingTickers = getSavedLeadingTickers(investmentType);

        // Notify about deleted leading tickers
        notifyDeletedLeadingTickers(investmentType, tickers, savedLeadingTickers);

        // Sort and get leading tickers for specific investment
        @NonNull List<TickerData> currentLeadingTickers = getLeadingTickers(tickers, config, System.currentTimeMillis());

        // Notify about updated leading tickers
        notifyUpdatedLeadingTickers(investmentType, currentLeadingTickers, savedLeadingTickers);

        // Save New Leading Tickers
        saveLeadingTickers(investmentType, currentLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void processUnchangedTickers(@Nullable List<TickerData> oldTickers) {
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
            context,
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
            context,
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
            context,
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
            context,
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
                context,
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
                context,
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
            context,
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
            context,
            channelId,
            channelName,
            title,
            groupKey,
            NOTIFICATION_ID_MISSING_REMOVED_LEADING_ASSET_GROUP_SUMMARY
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveLeadingTickers(@NonNull String investmentType, @NonNull List<TickerData> tickers) {
        tickerDataLoader.saveLeadingTickers(context.getApplicationContext(), investmentType, tickers);
    }
    private @Nullable List<TickerData> getSavedLeadingTickers(String investmentType) {
        return tickerDataLoader.getLeadingTickers(context.getApplicationContext(), investmentType);
    }
}