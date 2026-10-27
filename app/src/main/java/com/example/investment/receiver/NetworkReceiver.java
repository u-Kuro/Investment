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
import com.example.investment.task.WorkerManager;
import com.example.investment.util.Logger;

import java.util.List;

public class NetworkReceiver extends ConnectivityManager.NetworkCallback {
    private static @Nullable NetworkReceiver instance;

    @NonNull private final Context context;

    private NetworkReceiver(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

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

        EVENT_THREAD.execute(() -> {
            try {
                TickerDataLoader loader = TickerDataLoader.getInstance();
                if (!loader.hasPendingTickers(context, System.currentTimeMillis())) {
                    WorkerManager.cancelRetryWork(context);
                    return;
                }

                @Nullable List<TickerData> tickers = loader.getTickers(context);
                Intent serviceIntent = new Intent(context, TickerService.class);
                serviceIntent.setAction(tickers == null || tickers.isEmpty()
                    ? FULL_DATA_REFRESH_ACTION
                    : INCREMENTAL_DATA_REFRESH_ACTION);
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                Logger.error("Error updating on network available", e);
                WorkerManager.scheduleRetryWork(context);
            }
        });
    }
}
