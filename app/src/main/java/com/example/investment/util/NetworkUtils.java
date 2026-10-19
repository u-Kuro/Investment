package com.example.investment.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ThreadLocalRandom;

public class NetworkUtils {
    public static long getThrottleDelay(long minimumDelayMillis) {
        // Adds a random variance between 0 and 15% of the base delay
        long maxJitter = (long) (minimumDelayMillis * 0.15);
        long jitter = ThreadLocalRandom.current().nextLong(0, maxJitter + 1);

        return minimumDelayMillis + jitter;
    }

    public static boolean isOffline(@NonNull Context context) {
        @NonNull ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        @Nullable Network network = manager.getActiveNetwork();
        if (network == null) return true;

        @Nullable NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
