package com.example.investment.task;

import static com.example.investment.util.Constants.FILE_RECORD_FAILED_TICKERS;
import static com.example.investment.util.Constants.FILE_RECORD_MOMENTUM_LEADING_TICKERS;
import static com.example.investment.util.Constants.FILE_RECORD_PASSIVE_LEADING_TICKERS;
import static com.example.investment.util.Constants.FILE_RECORD_REFRESH_POINT;
import static com.example.investment.util.Constants.FILE_RECORD_SCREENER_CRYPTO;
import static com.example.investment.util.Constants.FILE_RECORD_SCREENER_STOCKS;
import static com.example.investment.util.Constants.FILE_RECORD_TICKERS;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.DateUtils.getStartOfDayMillis;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.RefreshPoint;
import com.example.investment.model.ScreenerRecord;
import com.example.investment.model.ScreenerSymbol;
import com.example.investment.model.TickerDataRecord;
import com.example.investment.model.TickerFailure;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.repository.YahooFinanceRepository;
import com.example.investment.util.Logger;
import com.example.investment.util.PendingTickers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public synchronized void invalidateCache() {
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
        final long nowMillis = System.currentTimeMillis();

        @Nullable RefreshPoint refreshPoint;
        if (isFullRefresh) {
            refreshPoint = new RefreshPoint(nowMillis, true);
            saveRefreshPoint(context, refreshPoint);
        } else {
            refreshPoint = getRefreshPoint(context, nowMillis);
        }
        final long refreshSinceMillis = refreshPoint != null ? refreshPoint.sinceMillis() : 0;
        final boolean isFullRefreshing = refreshPoint != null && refreshPoint.isFullRefresh();

        @Nullable List<TickerData> savedTickers = getTickers(context);

        repository.updateTickers(
            context,
            savedTickers != null ? new ArrayList<>(savedTickers) : new ArrayList<>(),
            getTickerFailures(context),
            InvestmentConfig.fromPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
            refreshSinceMillis,
            isFullRefreshing,
            nowMillis,
            new TickerUpdateStore() {
                @Override
                public void saveProgress(@NonNull List<TickerData> tickers, long updateTime) throws Exception {
                    TickerDataLoader.this.saveProgress(context, tickers, updateTime);
                }
                @Override
                public void saveTickerFailures(@NonNull Map<String, TickerFailure> failures, long nowMillis) {
                    TickerDataLoader.this.saveTickerFailures(context, failures, nowMillis);
                }
                @Override
                public @Nullable ScreenerRecord getScreenerRecord(@NonNull String screenerId) {
                    return TickerDataLoader.this.getScreenerRecord(context, screenerId);
                }
                @Override
                public void saveScreenerRecord(@NonNull ScreenerRecord record) {
                    TickerDataLoader.this.saveScreenerRecord(context, record);
                }
            },
            new TickerUpdateListener() {
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
                    // Only a finished update moves the last update time, progress saves keep the previous one
                    try {
                        saveAndCache(context, tickers, updateTime);
                        clearRefreshPoint(context);
                    } catch (Exception e) {
                        Logger.error("Error saving ticker record", e);
                        listener.onError(tickers);
                        return;
                    }
                    listener.onFinish(tickers, updateTime);
                }
                @Override
                public void onError(@Nullable List<TickerData> oldTickers) {
                    listener.onError(oldTickers);
                }
            }
        );
    }

    private synchronized @Nullable TickerDataRecord loadRecord(@NonNull Context context) {
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void saveProgress(@NonNull Context context, @NonNull List<TickerData> tickers, long updateTime) throws Exception {
        if (tickers.isEmpty()) throw new IllegalStateException("Tickers must not be empty.");
        @Nullable TickerDataRecord currentRecord = loadRecord(context);
        saveAndCache(context, tickers, currentRecord != null ? currentRecord.timestamp() : 0);
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public boolean hasPendingTickers(@NonNull Context context, long nowMillis) {
        @Nullable List<TickerData> tickers = getTickers(context);
        if (tickers == null || tickers.isEmpty()) return true;

        final long dayStartMillis = getStartOfDayMillis(nowMillis);
        @NonNull Set<String> universeSymbols = new HashSet<>();
        for (String screenerId : new String[] { SCREENER_CRYPTO, SCREENER_STOCKS }) {
            @Nullable ScreenerRecord record = getScreenerRecord(context, screenerId);
            if (record == null || record.retrievedAtMillis() < dayStartMillis) continue;

            for (ScreenerSymbol screenerSymbol : record.symbols()) {
                universeSymbols.add(screenerSymbol.symbol());
            }
        }

        @Nullable RefreshPoint refreshPoint = getRefreshPoint(context, nowMillis);
        return PendingTickers.count(
            tickers,
            universeSymbols,
            getTickerFailures(context),
            nowMillis,
            refreshPoint != null ? refreshPoint.sinceMillis() : 0
        ) > 0;
    }

    // Screener Records
    public @Nullable ScreenerRecord getScreenerRecord(@NonNull Context context, @NonNull String screenerId) {
        try {
            return (ScreenerRecord) loadObject(context, screenerFileFor(screenerId));
        } catch (Exception e) {
            Logger.error(screenerId + ": Error loading screener record", e);
            return null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void saveScreenerRecord(@NonNull Context context, @NonNull ScreenerRecord record) {
        try {
            saveObject(
                context,
                screenerFileFor(record.screenerId()),
                new ScreenerRecord(record.screenerId(), new ArrayList<>(record.symbols()), record.retrievedAtMillis())
            );
        } catch (Exception e) {
            Logger.error(record.screenerId() + ": Error saving screener record", e);
        }
    }
    private @NonNull String screenerFileFor(@NonNull String screenerId) {
        return SCREENER_CRYPTO.equals(screenerId) ? FILE_RECORD_SCREENER_CRYPTO : FILE_RECORD_SCREENER_STOCKS;
    }

    // Refresh Point
    @RequiresApi(api = Build.VERSION_CODES.O)
    public @Nullable RefreshPoint getRefreshPoint(@NonNull Context context, long nowMillis) {
        try {
            if (!(loadObject(context, FILE_RECORD_REFRESH_POINT) instanceof RefreshPoint refreshPoint)) return null;

            final long sinceMillis = refreshPoint.sinceMillis();
            if (sinceMillis <= 0 || sinceMillis > nowMillis) return null;
            return sinceMillis >= getStartOfDayMillis(nowMillis) ? refreshPoint : null;
        } catch (Exception e) {
            Logger.error("Error loading refresh point", e);
            return null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void saveRefreshPoint(@NonNull Context context, @NonNull RefreshPoint refreshPoint) {
        try {
            saveObject(context, FILE_RECORD_REFRESH_POINT, refreshPoint);
        } catch (Exception e) {
            Logger.error("Error saving refresh point", e);
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void clearRefreshPoint(@NonNull Context context) {
        try {
            if (loadObject(context, FILE_RECORD_REFRESH_POINT) instanceof RefreshPoint refreshPoint && refreshPoint.sinceMillis() > 0) {
                saveObject(context, FILE_RECORD_REFRESH_POINT, new RefreshPoint(0, false));
            }
        } catch (Exception e) {
            Logger.error("Error clearing refresh point", e);
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void requestManualRefresh(@NonNull Context context, boolean isFullRefresh) {
        saveRefreshPoint(context, new RefreshPoint(System.currentTimeMillis(), isFullRefresh));
    }

    // Ticker Failures
    public @NonNull Map<String, TickerFailure> getTickerFailures(@NonNull Context context) {
        try {
            //noinspection unchecked
            @Nullable Map<String, TickerFailure> failures = (Map<String, TickerFailure>) loadObject(context, FILE_RECORD_FAILED_TICKERS);
            return failures != null ? new HashMap<>(failures) : new HashMap<>();
        } catch (Exception e) {
            Logger.error("Error loading ticker failures", e);
            return new HashMap<>();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void saveTickerFailures(@NonNull Context context, @NonNull Map<String, TickerFailure> failures, long nowMillis) {
        final long dayStartMillis = getStartOfDayMillis(nowMillis);
        @NonNull Map<String, TickerFailure> todayFailures = new HashMap<>();
        for (Map.Entry<String, TickerFailure> entry : failures.entrySet()) {
            if (entry.getValue().lastFailedAtMillis() < dayStartMillis) continue;
            todayFailures.put(entry.getKey(), entry.getValue());
        }

        try {
            saveObject(context, FILE_RECORD_FAILED_TICKERS, todayFailures);
        } catch (Exception e) {
            Logger.error("Error saving ticker failures", e);
        }
    }
}