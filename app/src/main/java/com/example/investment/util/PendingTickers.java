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
        return count(tickers, universeSymbols, failures, nowMillis, 0L);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static int count(
        @NonNull List<TickerData> tickers,
        @NonNull Collection<String> universeSymbols,
        @NonNull Map<String, TickerFailure> failures,
        long nowMillis,
        long refreshSinceMillis
    ) {
        final long dayStartMillis = getStartOfDayMillis(nowMillis);
        final long dueSinceMillis = getDueSinceMillis(dayStartMillis, refreshSinceMillis);
        @NonNull Set<String> countedSymbols = new HashSet<>();
        int count = 0;

        for (TickerData ticker : tickers) {
            @NonNull String symbol = ticker.getSymbol();
            if (!countedSymbols.add(symbol)) continue;
            if (ticker.isUpdatedSince(dueSinceMillis) || isGivenUp(failures, symbol, dueSinceMillis)) continue;
            count++;
        }

        for (String symbol : universeSymbols) {
            if (!countedSymbols.add(symbol)) continue;
            if (isGivenUp(failures, symbol, dueSinceMillis)) continue;
            count++;
        }

        return count;
    }

    public static long getDueSinceMillis(long dayStartMillis, long refreshSinceMillis) {
        return Math.max(dayStartMillis, refreshSinceMillis);
    }

    // Helpers
    private static boolean isGivenUp(@NonNull Map<String, TickerFailure> failures, @NonNull String symbol, long sinceMillis) {
        TickerFailure failure = failures.get(symbol);
        return failure != null && failure.isGivenUp(sinceMillis);
    }
}