package com.example.investment.service;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.investment.R;
import com.example.investment.model.TickerInfo;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.repository.cache.CacheModels;
import com.example.investment.MainActivity;
import com.example.investment.util.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TickerService extends Service {
    private static final String CHANNEL_ID = "ticker_updates";
    private static final String PREFS_NAME = "TickerPrefs";
    private static final String KEY_HIGHEST_TICKER = "highest_ticker";
    private static final String KEY_HIGHEST_PERCENTAGE = "highest_percentage";
    private final String STOP_SERVICE_ACTION = "stop_service";
    private static final int NOTIFICATION_ID = 1;
    private TickerDataLoader dataLoader;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private Future<?> future;
    private TickerInfo potentialHighestTicker = null;

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
            startForeground(NOTIFICATION_ID, createNotification("Ticker service running").build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            Logger.error("Failed to Start Service", e);
            stopForeground(true);
            stopSelf();
            return;
        }

        updateTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_SERVICE_ACTION.equals(intent.getAction())) {
            Logger.info("hereeee");
            stopForeground(true);
            stopSelf();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            checkAndNotifyHighestTicker(potentialHighestTicker);
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateTickers() {
        future = executorService.submit(() -> dataLoader.loadTickers(getApplicationContext(), new CacheModels.Listener() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onTickerUpdate(@NonNull List<TickerInfo> tickers) {
                if (!tickers.isEmpty()) {
                    // Just store the highest ticker
                    updatePotentialHighestTicker(tickers.get(0));
                }

                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateTickerList(tickers);
                }
            }
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onProgress(int current, int total) {
                if (total > 0 && current < total) {
                    updateNotification(String.format(Locale.US, "Processing tickers: %d/%d", current, total));
                } else {
                    updateNotification(getString(R.string.successful_ticker_progress));
                }
                if (total > 0) {
                    double progress = ((double) current / total) * 100;
                    if (current < total) {
                        updateNotification(String.format(Locale.US, "%.2f%% Processing Tickers", progress));
                    } else {
                        updateNotification(getString(R.string.successful_ticker_progress));
                    }
                } else {
                    updateNotification(getString(R.string.fetching_tickers));
                }
                MainActivity activity = MainActivity.getInstanceActivity();
                if (activity != null) {
                    activity.updateProgress(current, total);
                }
            }
            @Override
            public void onFinish() {
                checkAndNotifyHighestTicker(potentialHighestTicker);

                TickerService.this.stopForeground(true);
                TickerService.this.stopSelf();
            }
        }));
    }

    private void updatePotentialHighestTicker(TickerInfo ticker) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double savedHighestPercentageIncreasePerDay = prefs.getFloat(KEY_HIGHEST_PERCENTAGE, 0);

        if (ticker.getPercentageIncreasePerDay() > savedHighestPercentageIncreasePerDay) {
            potentialHighestTicker = ticker;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void checkAndNotifyHighestTicker(TickerInfo ticker) {
        if (ticker == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        double savedHighestPercentageIncreasePerDay = prefs.getFloat(KEY_HIGHEST_PERCENTAGE, 0);

        if (ticker.getPercentageIncreasePerDay() > savedHighestPercentageIncreasePerDay) {
            String name = ticker.getName();
            if (name == null || name.isEmpty()) {
                name = ticker.getSymbol();
            }
            
            // Save new highest
            prefs.edit()
                .putString(KEY_HIGHEST_TICKER, name)
                .putFloat(KEY_HIGHEST_PERCENTAGE, (float) ticker.getPercentageIncreasePerDay())
                .apply();

            // Show high priority notification
            NotificationChannel channel = new NotificationChannel(
                "highest_ticker",
                "Highest Ticker Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.enableVibration(true);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "highest_ticker")
                .setContentTitle("New Highest Performing Ticker!")
                .setContentText(String.format(Locale.US, "%s: %.2f%% per day", name, ticker.getPercentageIncreasePerDay()))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

            manager.notify(2, builder.build());
            
            // Clear the potential highest ticker after notification
            potentialHighestTicker = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationCompat.Builder createNotification(String title) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Ticker Updates",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        Intent stopIntent = new Intent(this.getApplicationContext(), TickerService.class);
        stopIntent.setAction(STOP_SERVICE_ACTION);
        PendingIntent stopPendingIntent = PendingIntent.getService(this.getApplicationContext(), 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_launcher_foreground, "EXIT", stopPendingIntent)
                .setVibrate(null);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void updateNotification(String message) {
        NotificationCompat.Builder builder = createNotification(message);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, builder.build());
    }
} 