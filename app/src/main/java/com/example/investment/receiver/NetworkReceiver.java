package com.example.investment.receiver;

import static com.example.investment.service.TickerService.FULL_DATA_REFRESH_ACTION;
import static com.example.investment.service.TickerService.INCREMENTAL_DATA_REFRESH_ACTION;
import static com.example.investment.util.Constants.EVENT_THREAD;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerData;
import com.example.investment.service.TickerService;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.util.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public class NetworkReceiver extends ConnectivityManager.NetworkCallback {
    private static @Nullable NetworkReceiver instance;

    @NonNull private final Context context;

    private NetworkReceiver(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    // Waits for the next network once, it is not a listener for every network change
    public static synchronized void retryOnNetworkAvailable(@NonNull Context context) {
        @NonNull ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (instance != null) manager.unregisterNetworkCallback(instance);

        instance = new NetworkReceiver(context);
        manager.registerDefaultNetworkCallback(instance);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onAvailable(@NonNull Network network) {
        synchronized (NetworkReceiver.class) {
            if (instance != this) return;
            instance = null;
        }
        context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(this);

        // Same check as the initial load in MainActivity, the service is only called when an update is due
        EVENT_THREAD.execute(() -> {
            try {
                @NonNull TickerDataLoader tickerDataLoader = TickerDataLoader.getInstance();
                final long lastUpdateTime = tickerDataLoader.getLastUpdateTime(context);
                final @Nullable List<TickerData> tickers = tickerDataLoader.getTickers(context);

                @NonNull String action;
                if (lastUpdateTime == 0 || tickers == null || tickers.isEmpty()) {
                    action = FULL_DATA_REFRESH_ACTION;
                } else {
                    LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(lastUpdateTime),
                        ZoneId.systemDefault()
                    );
                    if (!lastUpdate.isBefore(LocalDateTime.now().minusDays(1))) return;
                    action = INCREMENTAL_DATA_REFRESH_ACTION;
                }

                Intent serviceIntent = new Intent(context, TickerService.class);
                serviceIntent.setAction(action);
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                Logger.error("Error updating on network available", e);
            }
        });
    }
}
