package com.example.investment.receiver;

import static com.example.investment.util.Constants.EVENT_THREAD;
import static com.example.investment.util.NetworkUtils.isOffline;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerData;
import com.example.investment.task.TickerDataLoader;
import com.example.investment.task.UpdateLauncher;
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
        try {
            @NonNull ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            @Nullable NetworkReceiver oldInstance = instance;
            instance = null;
            if (oldInstance != null) manager.unregisterNetworkCallback(oldInstance);

            NetworkReceiver receiver = new NetworkReceiver(context);
            manager.registerDefaultNetworkCallback(receiver);
            instance = receiver;
        } catch (Exception e) {
            Logger.error("Failed to retry on network available", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onAvailable(@NonNull Network network) {
        resumeIfOnline();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
        resumeIfOnline();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onBlockedStatusChanged(@NonNull Network network, boolean blocked) {
        if (!blocked) resumeIfOnline();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void resumeIfOnline() {
        synchronized (NetworkReceiver.class) {
            if (instance != this) return;

            try {
                if (isOffline(context)) return;
            } catch (Exception e) {
                Logger.error("Failed to check the network", e);
                return;
            }
            instance = null;
        }
        try {
            context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(this);
        } catch (Exception e) {
            Logger.error("Failed to unregister the network callback", e);
        }

        EVENT_THREAD.execute(() -> {
            try {
                TickerDataLoader loader = TickerDataLoader.getInstance();
                if (!loader.hasPendingTickers(context, System.currentTimeMillis())) {
                    WorkerManager.cancelRetryWork(context);
                    return;
                }

                @Nullable List<TickerData> tickers = loader.getTickers(context);
                UpdateLauncher.startIfIdle(context, UpdateLauncher.getAction(tickers == null || tickers.isEmpty()));
            } catch (Exception e) {
                Logger.error("Error updating on network available", e);
                WorkerManager.scheduleRetryWork(context);
            }
        });
    }
}