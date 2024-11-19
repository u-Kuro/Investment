package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.Service;
import android.content.Intent;
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
import com.example.investment.util.Constants;
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
                Constants.ID_SERVICE, 
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
            @Override
            public void onProgress(int current, int total) {
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
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateTickerList(tickers);
                }
            }
            
            @Override
            public void onFinish(@NonNull List<TickerInfo> tickers, long updateTime) {
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
    private void checkAndNotifyTickerChanges(List<TickerInfo> currentTickers) {
        HighestTickerInfo savedHighest = getSavedHighestTicker();
        TickerInfo highestTicker = currentTickers.get(0);
        
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
                Constants.CHANNEL_WARNING,
                Constants.NAME_MISSING,
                String.format(
                    "Previous highest performer %s is not found",
                    savedHighest.getName(true)
                ),
                Constants.ID_MISSING
            );
            FileUtils.deleteFile(getApplicationContext(), Constants.CACHE_FILE_HIGHEST);
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
        String currentState = getPriceState(highestTicker);
        String previousState = savedHighest.getPriceState();

        if (!previousState.equals(currentState)) {
            if (isReturnToTradingRange(currentState, previousState)) {
                NotificationUtils.showHighPriorityNotification(
                    this,
                    Constants.CHANNEL_PRICE_CROSS,
                    Constants.NAME_PRICE_CROSS,
                    String.format(
                            "%s price is now within trading range",
                            highestTicker.getName(true)
                    ),
                    Constants.ID_PRICE_CROSS
                );
            } else if (currentState.equals(Constants.PRICE_BELOW)) {
                NotificationUtils.showHighPriorityNotification(
                    this,
                    Constants.CHANNEL_WARNING,
                    Constants.NAME_WARNING,
                    String.format(
                            "%s price has dropped below threshold",
                            highestTicker.getName(true)
                    ),
                    Constants.ID_WARNING
                );
            }
        }

        saveHighestTicker(highestTicker, currentState);
    }

    private boolean isReturnToTradingRange(String currentState, String previousState) {
        return currentState.equals(Constants.PRICE_BETWEEN)
            && (previousState.equals(Constants.PRICE_ABOVE) 
                || previousState.equals(Constants.PRICE_BELOW));
    }

    private boolean isNewHighestPerformer(TickerInfo highestTicker, HighestTickerInfo savedHighest) {
        return savedHighest == null
            || highestTicker.getPercentageIncreasePerDay() > savedHighest.getPercentageIncreasePerDay();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void notifyAndSaveNewHighestPerformer(TickerInfo highestTicker) {
        NotificationUtils.showHighPriorityNotification(
            this,
            Constants.CHANNEL_HIGHEST,
            Constants.NAME_HIGHEST,
            String.format(
                Locale.US,
                "%s: %.2f%% Per Day",
                highestTicker.getName(true),
                highestTicker.getPercentageIncreasePerDay()
            ),
            Constants.ID_HIGHEST
        );
        saveHighestTicker(highestTicker, getPriceState(highestTicker));
    }

    private @NonNull String getPriceState(TickerInfo tickerInfo) {
        return PriceUtils.getPriceState(
            tickerInfo.getLastPrice(),
            tickerInfo.getUpperPriceBoundary(),
            tickerInfo.getLowerPriceBoundary()
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveHighestTicker(TickerInfo ticker, @NonNull String priceState) {
        try {
            FileUtils.saveObject(
                getApplicationContext(),
                Constants.CACHE_FILE_HIGHEST,
                new HighestTickerInfo(ticker, priceState)
            );
        } catch (IOException e) {
            Logger.error("Error saving highest ticker", e);
        }
    }

    private HighestTickerInfo getSavedHighestTicker() {
        try {
            return (HighestTickerInfo) FileUtils.loadObject(getApplicationContext(), Constants.CACHE_FILE_HIGHEST);
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading highest ticker", e);
            return null;
        }
    }
} 