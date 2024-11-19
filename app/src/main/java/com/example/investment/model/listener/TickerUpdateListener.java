package com.example.investment.model.listener;

import androidx.annotation.NonNull;
import com.example.investment.model.ticker.TickerInfo;
import java.util.List;

public interface TickerUpdateListener {
    void onProgress(int current, int total);
    void onTickerUpdate(@NonNull List<TickerInfo> tickers);
    void onFinish(@NonNull List<TickerInfo> tickers, long updateTime);
    void onError();
} 