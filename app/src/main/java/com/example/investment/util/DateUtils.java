package com.example.investment.util;

import java.util.Calendar;
import java.util.TimeZone;

public class DateUtils {
    private static final TimeZone EST = TimeZone.getTimeZone("America/New_York");
    // Market hours: 9:30 AM - 4:00 PM ET
    private static final int MARKET_OPEN_MINUTES  = 9 * 60 + 30;  // 570
    private static final int MARKET_CLOSE_MINUTES = 16 * 60;       // 960

    public static long getLastMarketCloseMs(long nowMs) {
        Calendar est = toEST(nowMs);

        Calendar lastClose = toEST(nowMs);
        lastClose.set(Calendar.HOUR_OF_DAY, 16);
        lastClose.set(Calendar.MINUTE, 0);
        lastClose.set(Calendar.SECOND, 0);
        lastClose.set(Calendar.MILLISECOND, 0);

        // If today's 4 PM hasn't occurred yet, go back one day
        if (!est.after(lastClose)) {
            lastClose.add(Calendar.DAY_OF_MONTH, -1);
        }

        // Roll back past weekends — market doesn't close on weekends
        while (isWeekend(lastClose)) {
            lastClose.add(Calendar.DAY_OF_MONTH, -1);
        }

        return lastClose.getTimeInMillis();
    }

    public static long getNextMarketOpenMs(long nowMs) {
        Calendar est = toEST(nowMs);

        if (isMarketOpen(est)) {
            return nowMs;
        }

        Calendar nextOpen = toEST(nowMs);
        nextOpen.set(Calendar.HOUR_OF_DAY, 9);
        nextOpen.set(Calendar.MINUTE, 30);
        nextOpen.set(Calendar.SECOND, 0);
        nextOpen.set(Calendar.MILLISECOND, 0);

        // If today's 9:30 AM has already passed, move to next day
        if (!nextOpen.after(est)) {
            nextOpen.add(Calendar.DAY_OF_MONTH, 1);
        }

        // Skip weekends — market doesn't open on weekends
        while (isWeekend(nextOpen)) {
            nextOpen.add(Calendar.DAY_OF_MONTH, 1);
        }

        return nextOpen.getTimeInMillis();
    }

    public static boolean isMarketOpen(Calendar estCal) {
        if (isWeekend(estCal)) return false;
        int totalMinutes = estCal.get(Calendar.HOUR_OF_DAY) * 60 + estCal.get(Calendar.MINUTE);
        return totalMinutes >= MARKET_OPEN_MINUTES && totalMinutes < MARKET_CLOSE_MINUTES;
    }

    public static boolean isWeekend(Calendar cal) {
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY;
    }

    private static Calendar toEST(long ms) {
        Calendar cal = Calendar.getInstance(EST);
        cal.setTimeInMillis(ms);
        return cal;
    }
}