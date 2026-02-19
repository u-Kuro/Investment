package com.example.investment.util;

import java.util.Calendar;
import java.util.TimeZone;

public class DateUtils {
    private static final String MARKET_TZ = "America/New_York";
    private static final int MARKET_OPEN_HOUR = 9;
    private static final int MARKET_OPEN_MINUTE = 30;
    private static final int MARKET_CLOSE_HOUR = 16;
    public static long calculateMarketActiveMillis(long startMs, long endMs) {
        if (startMs >= endMs) return 0;

        long totalActiveMs = 0;
        Calendar current = createEasternCalendar(startMs);
        Calendar end = createEasternCalendar(endMs);

        while (current.before(end)) {
            if (isWeekday(current)) {

                long windowStart = getDailyActiveWindowStart(current);
                long windowEnd = getDailyActiveWindowEnd(current);

                long effectiveStart = Math.max(current.getTimeInMillis(), windowStart);
                long effectiveEnd = Math.min(end.getTimeInMillis(), windowEnd);

                if (effectiveStart < effectiveEnd) {
                    totalActiveMs += (effectiveEnd - effectiveStart);
                }
            }

            // Move cursor to start of next day (Midnight)
            current.add(Calendar.DAY_OF_MONTH, 1);
            current.set(Calendar.HOUR_OF_DAY, 0);
            current.set(Calendar.MINUTE, 0);
            current.set(Calendar.SECOND, 0);
            current.set(Calendar.MILLISECOND, 0);
        }
        return totalActiveMs;
    }

    private static long getDailyActiveWindowStart(Calendar date) {
        Calendar cal = (Calendar) date.clone();
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

        // Monday: start is 09:30
        if (dayOfWeek == Calendar.MONDAY) {
            cal.set(Calendar.HOUR_OF_DAY, MARKET_OPEN_HOUR);
            cal.set(Calendar.MINUTE, MARKET_OPEN_MINUTE);
        } else {
        // Others: start is 00:00
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
        }

        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static long getDailyActiveWindowEnd(Calendar date) {
        Calendar cal = (Calendar) date.clone();
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

        // Friday: end is 16:00 (4 PM)
        if (dayOfWeek == Calendar.FRIDAY) {
            cal.set(Calendar.HOUR_OF_DAY, MARKET_CLOSE_HOUR);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
        } else {
        // Others: end is next day 00:00
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
        }

        return cal.getTimeInMillis();
    }

    private static boolean isWeekday(Calendar date) {
        int dayOfWeek = date.get(Calendar.DAY_OF_WEEK);
        return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY;
    }

    private static Calendar createEasternCalendar(long ms) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(MARKET_TZ));
        cal.setTimeInMillis(ms);
        return cal;
    }
}