package com.example.investment.util;

import static com.example.investment.util.Constants.UPDATE_TIER_LEADING;
import static com.example.investment.util.Constants.UPDATE_TIER_NEW;
import static com.example.investment.util.Constants.UPDATE_TIER_OTHER;
import static com.example.investment.util.DateUtils.getStartOfDayMillis;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.ScreenerSymbol;
import com.example.investment.model.TickerFailure;
import com.example.investment.model.ticker.TickerData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TickerUpdatePlanner {
    public record UpdateTarget(
        @NonNull String symbol,
        @NonNull String type,
        @NonNull BigDecimal marketCap,
        @Nullable TickerData oldTicker,
        int tier
    ) {}
    private record RankedTicker(@NonNull TickerData ticker, @NonNull BigDecimal percentageIncreasePerDay) {}

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static @NonNull List<UpdateTarget> plan(
        @NonNull List<TickerData> savedTickers,
        @NonNull List<ScreenerSymbol> universe,
        @NonNull Map<String, TickerFailure> failures,
        @NonNull InvestmentConfig config,
        long nowMillis,
        boolean isFullRefresh
    ) {
        final long dayStartMillis = getStartOfDayMillis(nowMillis);

        @NonNull Map<String, ScreenerSymbol> universeBySymbol = new LinkedHashMap<>();
        for (ScreenerSymbol screenerSymbol : universe) {
            universeBySymbol.putIfAbsent(screenerSymbol.symbol(), screenerSymbol);
        }

        @NonNull Map<String, TickerData> savedBySymbol = new HashMap<>();
        for (TickerData ticker : savedTickers) {
            if (universeBySymbol.containsKey(ticker.getSymbol())) savedBySymbol.put(ticker.getSymbol(), ticker);
        }
        @NonNull List<TickerData> universeTickers = new ArrayList<>();
        for (String symbol : universeBySymbol.keySet()) {
            @Nullable TickerData ticker = savedBySymbol.get(symbol);
            if (ticker != null) universeTickers.add(ticker);
        }

        @NonNull Set<String> tieredSymbols = new HashSet<>();
        @NonNull List<UpdateTarget> targets = new ArrayList<>();

        // Tier 1: the leading tickers the user is investing into
        for (TickerData ticker : TickerRanking.getLeadingTickers(universeTickers, config, nowMillis)) {
            @NonNull String symbol = ticker.getSymbol();
            tieredSymbols.add(symbol);
            if (isNotDue(symbol, ticker, failures, dayStartMillis, isFullRefresh)) continue;
            targets.add(toTarget(requireUniverseSymbol(universeBySymbol, symbol), ticker, UPDATE_TIER_LEADING));
        }

        // Tier 2: symbols that are not saved yet
        for (ScreenerSymbol screenerSymbol : universeBySymbol.values()) {
            @NonNull String symbol = screenerSymbol.symbol();
            if (savedBySymbol.containsKey(symbol)) continue;
            tieredSymbols.add(symbol);
            if (isNotDue(symbol, null, failures, dayStartMillis, isFullRefresh)) continue;
            targets.add(toTarget(screenerSymbol, null, UPDATE_TIER_NEW));
        }

        // Tier 3: everything else by the ranking of the user, tickers that pass the filters of the user come first
        @NonNull List<RankedTicker> passingTickers = new ArrayList<>();
        @NonNull List<RankedTicker> otherTickers = new ArrayList<>();
        for (TickerData ticker : universeTickers) {
            @NonNull String symbol = ticker.getSymbol();
            if (tieredSymbols.contains(symbol)) continue;
            if (isNotDue(symbol, ticker, failures, dayStartMillis, isFullRefresh)) continue;

            @NonNull RankedTicker rankedTicker = new RankedTicker(
                ticker,
                ticker.getPercentageIncreasePerDay(config.investmentType(), nowMillis)
            );
            if (TickerRanking.passesFilters(ticker, config, nowMillis)) {
                passingTickers.add(rankedTicker);
            } else {
                otherTickers.add(rankedTicker);
            }
        }
        // The sort is stable, so equal rankings keep the order of the universe
        passingTickers.sort((a, b) -> b.percentageIncreasePerDay().compareTo(a.percentageIncreasePerDay()));
        otherTickers.sort((a, b) -> b.percentageIncreasePerDay().compareTo(a.percentageIncreasePerDay()));
        for (RankedTicker rankedTicker : passingTickers) {
            @NonNull TickerData ticker = rankedTicker.ticker();
            targets.add(toTarget(requireUniverseSymbol(universeBySymbol, ticker.getSymbol()), ticker, UPDATE_TIER_OTHER));
        }
        for (RankedTicker rankedTicker : otherTickers) {
            @NonNull TickerData ticker = rankedTicker.ticker();
            targets.add(toTarget(requireUniverseSymbol(universeBySymbol, ticker.getSymbol()), ticker, UPDATE_TIER_OTHER));
        }

        return targets;
    }

    // Helpers
    private static boolean isNotDue(
        @NonNull String symbol,
        @Nullable TickerData ticker,
        @NonNull Map<String, TickerFailure> failures,
        long dayStartMillis,
        boolean isFullRefresh
    ) {
        if (isFullRefresh) return false;

        @Nullable TickerFailure failure = failures.get(symbol);
        if (failure != null && failure.isGivenUp(dayStartMillis)) return true;

        return ticker != null && ticker.isUpdatedSince(dayStartMillis);
    }
    private static @NonNull ScreenerSymbol requireUniverseSymbol(
        @NonNull Map<String, ScreenerSymbol> universeBySymbol,
        @NonNull String symbol
    ) {
        @Nullable ScreenerSymbol screenerSymbol = universeBySymbol.get(symbol);
        if (screenerSymbol == null) {
            throw new IllegalStateException("Ticker missing from universe: " + symbol);
        }
        return screenerSymbol;
    }
    private static @NonNull UpdateTarget toTarget(@NonNull ScreenerSymbol screenerSymbol, @Nullable TickerData oldTicker, int tier) {
        return new UpdateTarget(screenerSymbol.symbol(), screenerSymbol.type(), screenerSymbol.marketCap(), oldTicker, tier);
    }
}