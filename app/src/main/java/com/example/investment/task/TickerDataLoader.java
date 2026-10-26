package com.example.investment.task;

import static com.example.investment.util.Constants.FILE_RECORD_MOMENTUM_LEADING_TICKERS;
import static com.example.investment.util.Constants.FILE_RECORD_PASSIVE_LEADING_TICKERS;
import static com.example.investment.util.Constants.FILE_RECORD_TICKERS;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.TickerDataRecord;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.repository.YahooFinanceRepository;
import com.example.investment.util.Logger;

import java.util.ArrayList;
import java.util.List;

public class TickerDataLoader {
    private static TickerDataLoader instance;
    private TickerDataLoader() {}
    public static synchronized TickerDataLoader getInstance() {
        if (instance == null) instance = new TickerDataLoader();
        return instance;
    }

    private final YahooFinanceRepository repository = new YahooFinanceRepository();
    private volatile @Nullable TickerDataRecord cachedRecord = null;

    public @Nullable List<TickerData> getTickers(@NonNull Context context) {
        TickerDataRecord record = loadRecord(context);
        return record != null ? record.tickers() : null;
    }
    public void invalidateCache() {
        cachedRecord = null;
    }
    public long getLastUpdateTime(@NonNull Context context) {
        TickerDataRecord record = loadRecord(context);
        return record != null ? record.timestamp() : 0;
    }
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void updateTickers(
        @NonNull Context context,
        boolean isFullRefresh,
        @NonNull TickerUpdateListener listener
    ) {
        @Nullable List<TickerData> oldTickers = isFullRefresh ? null : getTickers(context);

        repository.updateTickers(context, oldTickers, isFullRefresh, new TickerUpdateListener() {
            @Override
            public void onSymbolsProgress(int current) {
                listener.onSymbolsProgress(current);
            }
            @Override
            public void onTickersProgress(int current, int total) {
                listener.onTickersProgress(current, total);
            }
            @Override
            public void onTickerUpdate(@NonNull List<TickerData> tickers) {
                listener.onTickerUpdate(tickers);
            }
            @Override
            public void onFinish(@NonNull List<TickerData> tickers, long updateTime) {
                try {
                    if (tickers.isEmpty()) throw new IllegalStateException("Tickers must not be empty.");
                    saveAndCache(context, tickers, updateTime);
                    listener.onFinish(tickers, updateTime);
                } catch (Exception e) {
                    Logger.error("Error saving ticker record", e);
                    listener.onError(oldTickers);
                }
            }
            @Override
            public void onError(@Nullable List<TickerData> oldTickers) {
                listener.onError(oldTickers);
            }
        });
    }

    private @Nullable TickerDataRecord loadRecord(@NonNull Context context) {
        if (cachedRecord == null) {
            try {
                cachedRecord = (TickerDataRecord) loadObject(context, FILE_RECORD_TICKERS);
            } catch (Exception e) {
                Logger.error("Error loading ticker record", e);
            }
        }
        return cachedRecord;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveAndCache(@NonNull Context context, @NonNull List<TickerData> tickers, long updateTime) throws Exception {
        TickerDataRecord record = new TickerDataRecord(new ArrayList<>(tickers), updateTime);
        saveObject(context, FILE_RECORD_TICKERS, record);
        cachedRecord = record;
    }

    public @Nullable List<TickerData> getLeadingTickers(@NonNull Context context, @NonNull String investmentType) {
        try {
            //noinspection unchecked
            return (List<TickerData>) loadObject(context, leadingTickersFileFor(investmentType));
        } catch (Exception e) {
            Logger.error(investmentType + ": Error loading leading tickers", e);
            return null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void saveLeadingTickers(@NonNull Context context, @NonNull String investmentType, @NonNull List<TickerData> tickers) {
        try {
            saveObject(context, leadingTickersFileFor(investmentType), new ArrayList<>(tickers));
        } catch (Exception e) {
            Logger.error(investmentType + ": Error saving leading tickers", e);
        }
    }
    private @NonNull String leadingTickersFileFor(@NonNull String investmentType) {
        return MOMENTUM_INVESTMENT_TYPE.equals(investmentType) ? FILE_RECORD_MOMENTUM_LEADING_TICKERS : FILE_RECORD_PASSIVE_LEADING_TICKERS;
    }
}