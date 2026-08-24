package com.example.investment.model.listener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.investment.model.ticker.TickerData;
import java.util.List;

public interface TickerUpdateListener {
    void onSymbolsProgress(int current);
    void onTickersProgress(int current, int total);
    void onTickerUpdate(@NonNull List<TickerData> tickers);
    void onFinish(@NonNull List<TickerData> tickers, long updateTime);
    void onError(@Nullable List<TickerData> oldTickers);
} 