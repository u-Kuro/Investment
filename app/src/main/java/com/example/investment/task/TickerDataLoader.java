package com.example.investment.task;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.repository.YahooFinanceRepository;

import java.util.List;

public class TickerDataLoader {
    private static TickerDataLoader instance;
    private final YahooFinanceRepository repository;

    private TickerDataLoader() {
        repository = new YahooFinanceRepository();
    }

    public static synchronized TickerDataLoader getInstance() {
        if (instance == null) {
            instance = new TickerDataLoader();
        }
        return instance;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateTickers(Context context, boolean isFullRefresh, TickerUpdateListener listener) {
        repository.updateTickers(context, isFullRefresh, listener);
    }

    public List<TickerData> getTickers(Context context) {
        return repository.getTickersData(context);
    }

    public long getLastUpdateTime(Context context) {
        return repository.getLastUpdateTime(context);
    }
}