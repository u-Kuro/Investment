package com.example.investment.util;

import static com.example.investment.util.Constants.DAYS_PER_WEEK;
import static com.example.investment.util.Constants.MILLIS_PER_DAY;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.math.BigInteger;
import java.util.Calendar;
import java.util.TimeZone;

public class DateUtils {
    private static final String MARKET_TZ = "America/New_York";
    private static final int MARKET_OPEN_HOUR = 9;
    private static final int MARKET_OPEN_MINUTE = 30;
    private static final int MARKET_CLOSE_HOUR = 16;
    @RequiresApi(api = Build.VERSION_CODES.S)
    public static BigInteger calculateMarketActiveMillis(@NonNull BigInteger startMs, @NonNull BigInteger endMs) {
        try {
            return BigInteger.valueOf(calculateMarketActiveMillis(startMs.longValueExact(), endMs.longValueExact()));
        } catch (Exception ignored) {
            return calculateMarketActiveMillisBD(startMs, endMs);
        }
    }
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

    // Time constants as BigDecimal
    private static final BigInteger BI_MARKET_OPEN_HOUR = BigInteger.valueOf(9);
    private static final BigInteger BI_MARKET_OPEN_MINUTE = BigInteger.valueOf(30);
    private static final BigInteger BI_MARKET_CLOSE_HOUR = BigInteger.valueOf(16);
    private static final BigInteger MILLIS_PER_MINUTE = BigInteger.valueOf(60_000);
    private static final BigInteger MILLIS_PER_HOUR = BigInteger.valueOf(3_600_000);

    // Unix epoch started on Thursday, January 1, 1970
    private static final BigInteger EPOCH_DAY_OF_WEEK = BigInteger.valueOf(4); // Thursday = 4 (Sunday = 0)
    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger calculateMarketActiveMillisBD(BigInteger startMs, BigInteger endMs) {
        if (startMs.compareTo(endMs) >= 0) {
            return BigInteger.ZERO;
        }

        BigInteger totalActiveMs = BigInteger.ZERO;
        BigInteger currentMs = startMs;

        // Iterate day by day
        while (currentMs.compareTo(endMs) < 0) {
            // Get day of week for current timestamp
            int dayOfWeek = getDayOfWeek(currentMs);

            // Skip weekends
            if (dayOfWeek != 0 && dayOfWeek != 6) { // 0=Sunday, 6=Saturday
                BigInteger windowStart = getDailyActiveWindowStart(currentMs, dayOfWeek);
                BigInteger windowEnd = getDailyActiveWindowEnd(currentMs, dayOfWeek);

                // effectiveStart = max(currentMs, windowStart)
                BigInteger effectiveStart = currentMs.max(windowStart);

                // effectiveEnd = min(endMs, windowEnd)
                BigInteger effectiveEnd = endMs.min(windowEnd);

                // Add to total if valid range
                if (effectiveStart.compareTo(effectiveEnd) < 0) {
                    BigInteger duration = effectiveEnd.subtract(effectiveStart);
                    totalActiveMs = totalActiveMs.add(duration);
                }
            }

            // Move to start of next day
            currentMs = getStartOfNextDay(currentMs);

            // Safety check to prevent infinite loop with very large numbers
            if (currentMs.compareTo(endMs) >= 0) {
                break;
            }
        }

        return totalActiveMs;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static int getDayOfWeek(BigInteger timestampMs) {
        // Convert to Eastern Time
        BigInteger easternMs = timestampMs.add(getEasternOffsetMillis(timestampMs));

        // Calculate days since epoch
        BigInteger daysSinceEpoch = easternMs.divide(MILLIS_PER_DAY);

        // Calculate day of week (epoch was Thursday = 4)
        BigInteger dayOfWeekBD = daysSinceEpoch.add(EPOCH_DAY_OF_WEEK)
            .remainder(DAYS_PER_WEEK);

        return dayOfWeekBD.intValue();
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger getDailyActiveWindowStart(BigInteger timestampMs, int dayOfWeek) {
        BigInteger startOfDay = getStartOfDay(timestampMs);

        // Monday (1): start is 09:30
        if (dayOfWeek == 1) {
            BigInteger openTime = BI_MARKET_OPEN_HOUR
                .multiply(MILLIS_PER_HOUR)
                .add(BI_MARKET_OPEN_MINUTE.multiply(MILLIS_PER_MINUTE));
            return startOfDay.add(openTime);
        } else {
            // Other weekdays: start is 00:00
            return startOfDay;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger getDailyActiveWindowEnd(BigInteger timestampMs, int dayOfWeek) {
        BigInteger startOfDay = getStartOfDay(timestampMs);

        // Friday (5): end is 16:00
        if (dayOfWeek == 5) {
            BigInteger closeTime = BI_MARKET_CLOSE_HOUR.multiply(MILLIS_PER_HOUR);
            return startOfDay.add(closeTime);
        } else {
            // Other weekdays: end is next day 00:00
            return startOfDay.add(MILLIS_PER_DAY);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger getStartOfDay(BigInteger timestampMs) {
        // Convert to Eastern Time
        BigInteger easternMs = timestampMs.add(getEasternOffsetMillis(timestampMs));

        // Get milliseconds since start of day
        BigInteger msIntoDay = easternMs.remainder(MILLIS_PER_DAY);

        // Subtract to get midnight in Eastern, then convert back to UTC
        return timestampMs.subtract(msIntoDay);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger getStartOfNextDay(BigInteger timestampMs) {
        BigInteger startOfDay = getStartOfDay(timestampMs);
        return startOfDay.add(MILLIS_PER_DAY);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static BigInteger getEasternOffsetMillis(BigInteger timestampMs) {
        long tsLong = timestampMs.longValueExact();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(MARKET_TZ));
        cal.setTimeInMillis(tsLong);
        long offsetMillis = (long) cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET);
        return BigInteger.valueOf(offsetMillis);
    }
}