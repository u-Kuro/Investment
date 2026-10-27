package com.example.investment.util;

import static com.example.investment.util.DateUtils.getStartOfDayMillis;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.TickerFailure;
import com.example.investment.model.ticker.TickerData;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PendingTickers {
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static int count(
        @NonNull List<TickerData> tickers,
        @NonNull Collection<String> universeSymbols,
        @NonNull Map<String, TickerFailure> failures,
        long nowMillis
    ) {
        final long dayStartMillis = getStartOfDayMillis(nowMillis);
        @NonNull Set<String> countedSymbols = new HashSet<>();
        int count = 0;

        for (TickerData ticker : tickers) {
            @NonNull String symbol = ticker.getSymbol();
            if (!countedSymbols.add(symbol)) continue;
            if (ticker.isUpdatedSince(dayStartMillis) || isGivenUp(failures, symbol, dayStartMillis)) continue;
            count++;
        }

        for (String symbol : universeSymbols) {
            if (!countedSymbols.add(symbol)) continue;
            if (isGivenUp(failures, symbol, dayStartMillis)) continue;
            count++;
        }

        return count;
    }

    // Helpers
    private static boolean isGivenUp(@NonNull Map<String, TickerFailure> failures, @NonNull String symbol, long dayStartMillis) {
        TickerFailure failure = failures.get(symbol);
        return failure != null && failure.isGivenUp(dayStartMillis);
    }
}