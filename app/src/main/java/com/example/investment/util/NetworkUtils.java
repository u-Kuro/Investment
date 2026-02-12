package com.example.investment.util;

import java.util.concurrent.ThreadLocalRandom;

public class NetworkUtils {
    public static long getThrottleDelay(long minimumDelayMillis) {
        // Adds a random variance between 0 and 15% of the base delay
        long maxJitter = (long) (minimumDelayMillis * 0.15);
        long jitter = ThreadLocalRandom.current().nextLong(0, maxJitter + 1);

        return minimumDelayMillis + jitter;
    }
}
