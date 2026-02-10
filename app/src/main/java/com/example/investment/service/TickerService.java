package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.example.investment.util.Constants.CACHE_FILE_LEADING_TICKERS;
import static com.example.investment.util.Constants.CHANNEL_ID_ASSET_IMPROVING;
import static com.example.investment.util.Constants.CHANNEL_ID_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.CHANNEL_ID_LEADING;
import static com.example.investment.util.Constants.CHANNEL_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.CHANNEL_NAME_ASSET_IMPROVING;
import static com.example.investment.util.Constants.CHANNEL_NAME_ASSET_RISK;
import static com.example.investment.util.Constants.CHANNEL_NAME_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.NOTIFICATION_ID_ASSET_IMPROVING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_ASSET_RISK;
import static com.example.investment.util.Constants.NOTIFICATION_ID_INVESTMENT_OPPORTUNITY;
import static com.example.investment.util.Constants.NOTIFICATION_ID_LEADING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_PROFIT_TAKING;
import static com.example.investment.util.Constants.NOTIFICATION_ID_SERVICE;
import static com.example.investment.util.Constants.CHANNEL_NAME_LEADING;
import static com.example.investment.util.Constants.CHANNEL_NAME_PROFIT_TAKING;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.SAVED_LEADING_TICKERS_COUNT;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.MainActivity;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.Logger;
import com.example.investment.util.FileUtils;
import com.example.investment.util.NotificationUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class TickerService extends Service {
    public static final String STOP_SERVICE_ACTION = "stop_service";
    private TickerDataLoader dataLoader;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private Future<?> future;

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @Override
    public void onCreate() {
        // Log Errors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> Logger.error("Uncaught Exception", e));
        }

        super.onCreate();
        dataLoader = TickerDataLoader.getInstance();

        try {
            startForeground(
                    NOTIFICATION_ID_SERVICE,
                NotificationUtils.createLowPriorityServiceNotification(this, "Ticker service running"),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } catch (Exception e) {
            Logger.error("Failed to Start Service", e);
            stopForeground(true);
            stopSelf();
        }

        updateTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (STOP_SERVICE_ACTION.equals(intent.getAction())) {
                stopForeground(true);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executorService.shutdownNow();
        if (future != null) {
            future.cancel(true);
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateTickers() {
        future = executorService.submit(() -> dataLoader.loadTickers(getApplicationContext(), new TickerUpdateListener() {
            private final AtomicBoolean isFinished = new AtomicBoolean(false);
            @Override
            public void onProgress(int current, int total) {
                if (isFinished.get()) return;

                if (total > 0) {
                    double progress = ((double) current / total) * 100;
                    if (current < total) {
                        NotificationUtils.showLowPriorityServiceNotification(
                            TickerService.this,
                            String.format(
                                Locale.US,
                                "%.2f%% Processing Tickers", progress
                            )
                        );
                    } else {
                        NotificationUtils.showLowPriorityServiceNotification(
                            TickerService.this,
                            getString(R.string.successful_ticker_progress)
                        );
                    }
                } else {
                    NotificationUtils.showLowPriorityServiceNotification(
                        TickerService.this,
                        getString(R.string.fetching_tickers)
                    );
                }
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateProgress(current, total);
                }
            }
            
            @Override
            public void onTickerUpdate(@NonNull List<TickerInfo> tickers) {
                if (isFinished.get()) return;

                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateTickerList(tickers);
                }
            }
            
            @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            @Override
            public void onFinish(@NonNull List<TickerInfo> tickers, long updateTime) {
                isFinished.set(true);

                if (!tickers.isEmpty()) {
                    checkAndNotifyTickerChanges(tickers);
                }

                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateFinished(updateTime);
                }

                TickerService.this.stopForeground(true);
                TickerService.this.stopSelf();
            }
            
            @Override
            public void onError() {
                isFinished.set(true);

                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateError();
                }

                TickerService.this.stopForeground(true);
                TickerService.this.stopSelf();
            }
        }));
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void checkAndNotifyTickerChanges(List<TickerInfo> tickers) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long filterMarketCapThreshold = prefs.getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        List<TickerInfo> currentTickers = tickers.stream()
            .filter(t ->
                // Market Cap is Above Limit (Default of $10B)
                t.getMarketCap() >= filterMarketCapThreshold
                // Asset is Not a Risk
                && !t.getPriceState().equals(PRICE_BELOW)
            )
            .toList();

        int currentLeadingTickersLen = Math.min(currentTickers.size(), SAVED_LEADING_TICKERS_COUNT);
        List<TickerInfo> currentLeadingTickers = tickers.subList(0, currentLeadingTickersLen);
        List<TickerInfo> savedLeadingTickers = getSavedLeadingTickers();

        if (savedLeadingTickers == null || savedLeadingTickers.isEmpty()) {
            for (TickerInfo currentLeadingTicker : currentLeadingTickers) {
                notifyNewLeadingTicker(currentLeadingTicker);
            }
        }else {
            for (TickerInfo currentLeadingTicker : currentLeadingTickers) {
                boolean isNew = true;
                TickerInfo match = null;

                for (TickerInfo savedLeadingTicker : savedLeadingTickers) {
                    if (currentLeadingTicker.getSymbol().equals(savedLeadingTicker.getSymbol())) {
                        isNew = false;
                        match = savedLeadingTicker;
                        break;
                    }
                }

                if (isNew) {
                    notifyNewLeadingTicker(currentLeadingTicker);
                } else {
                    notifyLeadingTickerChanges(currentLeadingTicker, match);
                }
            }
        }

        // Save New Leading Tickers
        saveLeadingTickers(currentLeadingTickers);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyNewLeadingTicker(TickerInfo newLeadingTicker) {
        NotificationUtils.showHighPriorityNotification(
            this,
                CHANNEL_ID_LEADING,
                CHANNEL_NAME_LEADING,
                CHANNEL_NAME_LEADING,
            String.format(
                Locale.US,
                "%s: %.2f%% Per Day",
                newLeadingTicker.getName(true),
                newLeadingTicker.getPercentageIncreasePerDay()
            ),
            newLeadingTicker.getSymbol(),
                NOTIFICATION_ID_LEADING
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyLeadingTickerChanges(TickerInfo leadingTicker, TickerInfo savedLeadingTicker) {
        String currentState = leadingTicker.getPriceState();
        String previousState = savedLeadingTicker.getPriceState();

        if (!previousState.equals(currentState)) {
            switch (currentState) {
                case PRICE_BETWEEN:
                    if (PRICE_ABOVE.equals(previousState)) {
                        NotificationUtils.showHighPriorityNotification(
                            this,
                            CHANNEL_ID_PROFIT_TAKING,
                            CHANNEL_NAME_PROFIT_TAKING,
                            CHANNEL_NAME_PROFIT_TAKING,
                            String.format(
                                "You can now take some profits off your \"%s\" investment",
                                leadingTicker.getName(true)
                            ),
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_PROFIT_TAKING
                        );
                    } else if (PRICE_BELOW.equals(previousState)) {
                        NotificationUtils.showHighPriorityNotification(
                            this,
                            CHANNEL_ID_ASSET_IMPROVING,
                            CHANNEL_NAME_ASSET_IMPROVING,
                            CHANNEL_NAME_ASSET_IMPROVING,
                            String.format(
                                "\"%s\" trend is slowly recovering.",
                                leadingTicker.getName(true)
                            ),
                            leadingTicker.getSymbol(),
                            NOTIFICATION_ID_ASSET_IMPROVING
                        );
                    }
                    break;

                case PRICE_ABOVE:
                    NotificationUtils.showHighPriorityNotification(
                        this,
                        CHANNEL_ID_INVESTMENT_OPPORTUNITY,
                        CHANNEL_NAME_INVESTMENT_OPPORTUNITY,
                        CHANNEL_NAME_INVESTMENT_OPPORTUNITY,
                        String.format(
                            "Invest/Hold your assets into \"%s\".",
                            leadingTicker.getName(true)
                        ),
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_INVESTMENT_OPPORTUNITY
                    );
                    break;

                case PRICE_BELOW:
                    NotificationUtils.showHighPriorityNotification(
                        this,
                        CHANNEL_ID_ASSET_RISK,
                        CHANNEL_NAME_ASSET_RISK,
                        CHANNEL_NAME_ASSET_RISK,
                        String.format(
                            "Risk Warning: \"%s\" moved below its usual price.",
                            leadingTicker.getName(true)
                        ),
                        leadingTicker.getSymbol(),
                        NOTIFICATION_ID_ASSET_RISK
                    );
                    break;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveLeadingTickers(List<TickerInfo> leadingTickers) {
        try {
            FileUtils.saveObject(
                getApplicationContext(),
                CACHE_FILE_LEADING_TICKERS,
                leadingTickers
            );
        } catch (IOException e) {
            Logger.error("Error saving leading tickers", e);
        }
    }

    private List<TickerInfo> getSavedLeadingTickers() {
        try {
            //noinspection unchecked
            return (List<TickerInfo>) FileUtils.loadObject(getApplicationContext(), CACHE_FILE_LEADING_TICKERS);
        } catch (Exception e) {
            Logger.error("Error loading leading tickers", e);
            return null;
        }
    }
} 