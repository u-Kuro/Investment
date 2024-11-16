package com.example.investment.task;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.example.investment.model.TickerInfo;
import com.example.investment.repository.YahooFinanceRepository;
import com.example.investment.repository.cache.CacheModels;
import com.example.investment.util.Logger;

import java.util.List;

public class TickerDataLoader {
    private static volatile TickerDataLoader instance;
    private final YahooFinanceRepository repository;

    private TickerDataLoader() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }
        this.repository = new YahooFinanceRepository();
    }

    public static TickerDataLoader getInstance() {
        if (instance == null) {
            instance = new TickerDataLoader();
        }
        return instance;
    }

    public void loadTickers(@NonNull Context context, CacheModels.Listener listener) {
        repository.loadTickerScores(
            context,
            listener
        );
    }

    public List<TickerInfo> loadCachedTickers(@NonNull Context context) {
        return repository.loadCache(context);
    }

    public long getLastUpdateTime(@NonNull Context context) {
        return repository.getCacheTimestamp(context);
    }
} 