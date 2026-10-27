package com.example.investment.model;

import static com.example.investment.util.Constants.MAX_TICKER_FAILURES_PER_DAY;
import static com.example.investment.util.DateUtils.getStartOfDayMillis;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.Serial;
import java.io.Serializable;

public record TickerFailure(long lastFailedAtMillis, int failureCount) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public boolean isGivenUp(long dayStartMillis) {
        return lastFailedAtMillis >= dayStartMillis && failureCount >= MAX_TICKER_FAILURES_PER_DAY;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static @NonNull TickerFailure afterFailure(@Nullable TickerFailure previous, long nowMillis, boolean isDefinitive) {
        if (isDefinitive) return new TickerFailure(nowMillis, MAX_TICKER_FAILURES_PER_DAY);

        int previousFailureCount = previous != null && previous.lastFailedAtMillis >= getStartOfDayMillis(nowMillis)
            ? previous.failureCount
            : 0;
        return new TickerFailure(nowMillis, previousFailureCount + 1);
    }
}