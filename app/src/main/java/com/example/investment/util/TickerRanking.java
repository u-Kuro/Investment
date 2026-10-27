package com.example.investment.util;

import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.ticker.TickerData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class TickerRanking {
    public static boolean passesFilters(@NonNull TickerData ticker, @NonNull InvestmentConfig config, long nowMillis) {
        return passesFilters(ticker, ticker.getNewestFixedHistoricalDataWithInterval(), config, nowMillis);
    }

    public static @NonNull List<TickerData> getLeadingTickers(@NonNull List<TickerData> tickers, @NonNull InvestmentConfig config, long nowMillis) {
        @NonNull List<TickerData> validLeadingTickers = tickers.stream()
            .filter(t -> {
                @Nullable TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord = t.getNewestFixedHistoricalDataWithInterval();
                return (
                    passesFilters(t, newestFixedHistoricalDataRecord, config, nowMillis)
                    && t.getPriceState(
                        config.investmentType(),
                        Optional.ofNullable(newestFixedHistoricalDataRecord)
                            .map(TickerData.FixedHistoricalRecord::fixedHistoricalData)
                            .orElse(null),
                        nowMillis
                    ).equals(PRICE_ABOVE)
                );
            })
            .sorted(rankingComparator(config, nowMillis))
            .collect(Collectors.toList());

        return new ArrayList<>(validLeadingTickers.subList(0, Math.min(validLeadingTickers.size(), config.numberOfTopHoldings())));
    }

    public static @NonNull Comparator<TickerData> rankingComparator(@NonNull InvestmentConfig config, long nowMillis) {
        return (a, b) -> b.getPercentageIncreasePerDay(config.investmentType(), nowMillis)
            .compareTo(a.getPercentageIncreasePerDay(config.investmentType(), nowMillis));
    }

    // Helpers
    private static boolean passesFilters(
        @NonNull TickerData ticker,
        @Nullable TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord,
        @NonNull InvestmentConfig config,
        long nowMillis
    ) {
        return (
            // Asset type is invested
            (config.assetType().equals(ALL_TYPE) || config.assetType().equals(ticker.getType()))
            // Market cap is above or equal to threshold
            && ticker.getMarketCap().compareTo(config.marketCapThreshold()) >= 0
            // Daily dollar volume is above or equal to threshold
            && ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(config.dailyDollarVolumeThreshold()) >= 0
            // Age is above or equal to threshold
            && ticker.getAgeInSeconds(nowMillis).compareTo(config.ageInSecondsThreshold()) >= 0
        );
    }
}