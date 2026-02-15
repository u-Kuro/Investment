package com.example.investment.util;

import java.util.Calendar;
import java.util.TimeZone;

public class DateUtils {
    public static long calculateMarketClosedDuration(long nowMs) {
        Calendar estNow = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        estNow.setTimeInMillis(nowMs);

        // If market is currently open, return 0
        if (isMarketCurrentlyOpen(estNow)) {
            return 0;
        }

        // Find the most recent market close at 4:00:00 PM ET
        Calendar mostRecentCloseTime = (Calendar) estNow.clone();
        mostRecentCloseTime.set(Calendar.HOUR_OF_DAY, 16);
        mostRecentCloseTime.set(Calendar.MINUTE, 0);
        mostRecentCloseTime.set(Calendar.SECOND, 0);
        mostRecentCloseTime.set(Calendar.MILLISECOND, 0);

        // If current time is before today's 4 PM, last close was yesterday or earlier
        if (estNow.before(mostRecentCloseTime)) {
            mostRecentCloseTime.add(Calendar.DAY_OF_MONTH, -1);
        }

        // Roll back past any weekends
        while (isWeekend(mostRecentCloseTime)) {
            mostRecentCloseTime.add(Calendar.DAY_OF_MONTH, -1);
        }

        // Duration is time elapsed since last close
        long durationSinceCloseMs = nowMs - mostRecentCloseTime.getTimeInMillis();

        return Math.max(0, durationSinceCloseMs);
    }

    private static boolean isMarketCurrentlyOpen(Calendar estCal) {
        if (isWeekend(estCal)) {
            return false;
        }

        int hour = estCal.get(Calendar.HOUR_OF_DAY);
        int minute = estCal.get(Calendar.MINUTE);
        int totalMinutesIntoDay = (hour * 60) + minute;

        int marketOpenMinutes = (9 * 60) + 30;  // 09:30 AM
        int marketCloseMinutes = 16 * 60;       // 04:00 PM

        // Market is open: [09:30:00, 16:00:00)
        // Open at 09:30:00, closed at 16:00:00
        return totalMinutesIntoDay >= marketOpenMinutes && totalMinutesIntoDay < marketCloseMinutes;
    }

    private static boolean isWeekend(Calendar cal) {
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        return (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY);
    }
}
