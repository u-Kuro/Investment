package com.example.investment.task;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.investment.model.ScreenerRecord;
import com.example.investment.model.TickerFailure;
import com.example.investment.model.ticker.TickerData;

import java.util.List;
import java.util.Map;

public interface TickerUpdateStore {
    void saveProgress(@NonNull List<TickerData> tickers, long updateTime) throws Exception;
    void saveTickerFailures(@NonNull Map<String, TickerFailure> failures, long nowMillis);
    @Nullable ScreenerRecord getScreenerRecord(@NonNull String screenerId);
    void saveScreenerRecord(@NonNull ScreenerRecord record);
}