package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import static com.example.investment.util.Constants.CACHE_FILE_HIGHEST;
import static com.example.investment.util.Constants.CHANNEL_HIGHEST;
import static com.example.investment.util.Constants.CHANNEL_PRICE_CROSS;
import static com.example.investment.util.Constants.CHANNEL_WARNING;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.ID_HIGHEST;
import static com.example.investment.util.Constants.ID_MISSING;
import static com.example.investment.util.Constants.ID_PRICE_CROSS;
import static com.example.investment.util.Constants.ID_SERVICE;
import static com.example.investment.util.Constants.ID_WARNING;
import static com.example.investment.util.Constants.NAME_HIGHEST;
import static com.example.investment.util.Constants.NAME_MISSING;
import static com.example.investment.util.Constants.NAME_PRICE_CROSS;
import static com.example.investment.util.Constants.NAME_WARNING;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;

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
import com.example.investment.model.ticker.HighestTickerInfo;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.Logger;
import com.example.investment.util.FileUtils;
import com.example.investment.util.NotificationUtils;
import com.example.investment.util.PriceUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
                ID_SERVICE,
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void checkAndNotifyTickerChanges(List<TickerInfo> tickers) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long filterMarketCapThreshold = prefs.getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
        List<TickerInfo> currentTickers = tickers.stream()
            .filter(t -> t.getMarketCap() >= filterMarketCapThreshold)
            .collect(Collectors.toList());

        TickerInfo highestTicker = currentTickers.get(0);
        HighestTickerInfo savedHighest = getSavedHighestTicker();
        
        if (isPreviousHighestMissing(currentTickers, savedHighest)) {
            handleMissingTicker(highestTicker, savedHighest);
        } else if (highestTicker != null) {
            handleExistingTicker(highestTicker, savedHighest);
        }
    }

    private boolean isPreviousHighestMissing(List<TickerInfo> currentTickers, HighestTickerInfo savedHighest) {
        if (savedHighest == null) return false;
        return currentTickers.stream()
            .filter(t -> t.getSymbol().equals(savedHighest.getSymbol()))
            .findFirst().isEmpty();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handleMissingTicker(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        if (highestTicker != null) {
            notifyAndSaveNewHighestPerformer(highestTicker);
        } else {
            NotificationUtils.showHighPriorityNotification(
                this,
                CHANNEL_WARNING,
                NAME_MISSING,
                String.format(
                    "Previous highest performer %s is not found",
                    savedHighest.getName(true)
                ),
                ID_MISSING
            );
            FileUtils.deleteFile(getApplicationContext(), CACHE_FILE_HIGHEST);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handleExistingTicker(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        if (isSameSymbol(highestTicker, savedHighest)) {
            handlePriceStateChanges(highestTicker, savedHighest);
        } else if (isNewHighestPerformer(highestTicker, savedHighest)) {
            notifyAndSaveNewHighestPerformer(highestTicker);
        }
    }

    private boolean isSameSymbol(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        return savedHighest != null
            && savedHighest.getSymbol() != null
            && highestTicker.getSymbol() != null
            && Objects.equals(highestTicker.getSymbol(), savedHighest.getSymbol());
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handlePriceStateChanges(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        String currentState = PriceUtils.getPriceState(highestTicker);
        String previousState = savedHighest.getPriceState();

        if (!previousState.equals(currentState)) {
            if (isReturnToProfitTakingRange(currentState, previousState)) {
                NotificationUtils.showHighPriorityNotification(
                    this,
                    CHANNEL_PRICE_CROSS,
                    NAME_PRICE_CROSS,
                    String.format(
                        "%s price is now within trading range",
                        highestTicker.getName(true)
                    ),
                    ID_PRICE_CROSS
                );
            } else if (currentState.equals(PRICE_BELOW)) {
                NotificationUtils.showHighPriorityNotification(
                    this,
                    CHANNEL_WARNING,
                    NAME_WARNING,
                    String.format(
                            "%s price has dropped below threshold",
                            highestTicker.getName(true)
                    ),
                    ID_WARNING
                );
            }
        }

        saveHighestTicker(highestTicker, currentState);
    }

    private boolean isReturnToProfitTakingRange(String currentState, String previousState) {
        return previousState.equals(PRICE_ABOVE)
            && (currentState.equals(PRICE_BETWEEN)
                || currentState.equals(PRICE_BELOW));
    }

    private boolean isNewHighestPerformer(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        return savedHighest == null
            || highestTicker.getPercentageIncreasePerDay() > savedHighest.getPercentageIncreasePerDay();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyAndSaveNewHighestPerformer(TickerInfo highestTicker) {
        NotificationUtils.showHighPriorityNotification(
            this,
            CHANNEL_HIGHEST,
            NAME_HIGHEST,
            String.format(
                Locale.US,
                "%s: %.2f%% Per Day",
                highestTicker.getName(true),
                highestTicker.getPercentageIncreasePerDay()
            ),
            ID_HIGHEST
        );
        saveHighestTicker(highestTicker, PriceUtils.getPriceState(highestTicker));
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveHighestTicker(TickerInfo ticker, @NonNull String priceState) {
        try {
            FileUtils.saveObject(
                getApplicationContext(),
                CACHE_FILE_HIGHEST,
                new HighestTickerInfo(ticker, priceState)
            );
        } catch (IOException e) {
            Logger.error("Error saving highest ticker", e);
        }
    }

    private HighestTickerInfo getSavedHighestTicker() {
        try {
            return (HighestTickerInfo) FileUtils.loadObject(getApplicationContext(), CACHE_FILE_HIGHEST);
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading highest ticker", e);
            return null;
        }
    }
} 