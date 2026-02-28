package com.example.investment.util;

import static com.example.investment.util.BigDecimalUtils.getSafeBigDecimalPrefs;
import static com.example.investment.util.Constants.DEFAULT_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.IQR_THRESHOLD;
import static com.example.investment.util.Constants.MAD_THRESHOLD;
import static com.example.investment.util.Constants.MEAN_THRESHOLD;
import static com.example.investment.util.Constants.PREF_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.PREF_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.PREF_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.SD_THRESHOLD;

import static java.math.MathContext.DECIMAL128;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.investment.model.ticker.TickerData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class TickerDataUtils {
    public static BigDecimal getOverboughtThreshold(Context context, List<TickerData> tickers, String overboughtThresholdFilter) {
        if (IQR_THRESHOLD.equals(overboughtThresholdFilter)) {
            return getOverboughtThresholdIQR(context, tickers);
        } else if (SD_THRESHOLD.equals(overboughtThresholdFilter)) {
            return getOverboughtThreshold2SD(context, tickers);
        } else if (MAD_THRESHOLD.equals(overboughtThresholdFilter)) {
            return getOverboughtThresholdMAD(context, tickers);
        } else if (MEAN_THRESHOLD.equals(overboughtThresholdFilter)) {
            return getOverboughtThresholdMean(context, tickers);
        }
        return null;
    }

    // Mean
    private static BigDecimal getOverboughtThresholdMean(Context context, List<TickerData> tickers) {
        List<BigDecimal> validValues = getValidPercentageIncreasePerDayOfTickers(context, tickers);

        if (validValues == null || validValues.isEmpty()) {
            return null;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : validValues) {
            sum = sum.add(value, DECIMAL128);
        }

        return sum.divide(BigDecimal.valueOf(validValues.size()), DECIMAL128);
    }

    // Mean + Mean Absolute Deviation
    private static BigDecimal getOverboughtThresholdMAD(Context context, List<TickerData> tickers) {
        List<BigDecimal> validValues = getValidPercentageIncreasePerDayOfTickers(context, tickers);

        if (validValues == null || validValues.isEmpty()) {
            return null;
        }

        if (validValues.size() == 1) {
            return validValues.get(0);
        }

        // Calculate mean
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : validValues) {
            sum = sum.add(value, DECIMAL128);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(validValues.size()), DECIMAL128);

        // Calculate MAD (mean of |x - mean|)
        BigDecimal madSum = BigDecimal.ZERO;
        for (BigDecimal value : validValues) {
            BigDecimal deviation = value.subtract(mean, DECIMAL128).abs();
            madSum = madSum.add(deviation, DECIMAL128);
        }
        BigDecimal mad = madSum.divide(BigDecimal.valueOf(validValues.size()), DECIMAL128);

        // Return mean + MAD
        return mean.add(mad, DECIMAL128);
    }

    // Mean + 2 Standard Deviations
    private static BigDecimal getOverboughtThreshold2SD(Context context, List<TickerData> tickers) {
        List<BigDecimal> validValues = getValidPercentageIncreasePerDayOfTickers(context, tickers);

        if (validValues == null || validValues.isEmpty()) {
            return null;
        }

        if (validValues.size() == 1) {
            return validValues.get(0);
        }

        // Calculate mean
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : validValues) {
            sum = sum.add(value, DECIMAL128);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(validValues.size()), DECIMAL128);

        // Calculate variance
        BigDecimal variance = BigDecimal.ZERO;
        for (BigDecimal value : validValues) {
            BigDecimal diff = value.subtract(mean, DECIMAL128);
            BigDecimal squared = diff.multiply(diff, DECIMAL128);
            variance = variance.add(squared, DECIMAL128);
        }
        variance = variance.divide(BigDecimal.valueOf(validValues.size()), DECIMAL128);

        // Calculate standard deviation
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        // Return mean + 2*stdDev
        return mean.add(stdDev.multiply(BigDecimal.valueOf(2), DECIMAL128), DECIMAL128);
    }

    // Q3 + 1.5*IQR
    private static BigDecimal getOverboughtThresholdIQR(Context context, List<TickerData> tickers) {
        List<BigDecimal> validValues = getValidPercentageIncreasePerDayOfTickers(context, tickers);

        if (validValues == null || validValues.size() < 4) {
            return validValues != null && !validValues.isEmpty() ?
                validValues.stream().max(BigDecimal::compareTo).orElse(null) : null;
        }

        List<BigDecimal> sorted = new ArrayList<>(validValues);
        sorted.sort(BigDecimal::compareTo);

        int size = sorted.size();

        // Calculate Q1 (25th percentile)
        int q1Index = size / 4;
        BigDecimal q1 = sorted.get(q1Index);

        // Calculate Q3 (75th percentile)
        int q3Index = (3 * size) / 4;
        BigDecimal q3 = sorted.get(q3Index);

        // Calculate IQR
        BigDecimal iqr = q3.subtract(q1, DECIMAL128);

        // Return Q3 + 1.5*IQR (standard outlier upper bound)
        return q3.add(iqr.multiply(BigDecimal.valueOf(1.5), DECIMAL128), DECIMAL128);
    }

    private static List<BigDecimal> getValidPercentageIncreasePerDayOfTickers(Context context, List<TickerData> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return null;
        } else {
            @NonNull BigDecimal filterMarketCapThreshold = getSafeBigDecimalPrefs(context, PREF_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);
            @NonNull BigDecimal filterAgeInSecondsThreshold = getSafeBigDecimalPrefs(context, PREF_AGE_IN_SECONDS_THRESHOLD, DEFAULT_AGE_IN_SECONDS_THRESHOLD);
            @NonNull BigDecimal filterDailyDollarVolumeThreshold = getSafeBigDecimalPrefs(context, PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD);

            List<BigDecimal> validPercentageIncreasePerDayOfTickers = new ArrayList<>();

            for (TickerData ticker : tickers) {
                TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord = ticker.getNewestFixedHistoricalDataWithInterval();

                if (ticker.getMarketCap().compareTo(filterMarketCapThreshold) < 0) continue;
                if (ticker.getAgeInSeconds().compareTo(filterAgeInSecondsThreshold) < 0) continue;
                if (ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(filterDailyDollarVolumeThreshold) < 0) continue;
                if (!PRICE_ABOVE.equals(ticker.getPriceState(newestFixedHistoricalDataRecord.fixedHistoricalData()))) continue;

                BigDecimal percentageIncreasePerDay = ticker.getPercentageIncreasePerDay(newestFixedHistoricalDataRecord.fixedHistoricalData());
                if (percentageIncreasePerDay.compareTo(BigDecimal.ZERO) > 0) {
                    validPercentageIncreasePerDayOfTickers.add(percentageIncreasePerDay);
                }
            }

            if (validPercentageIncreasePerDayOfTickers.isEmpty()) {
                return null;
            } else {
                return validPercentageIncreasePerDayOfTickers;
            }
        }
    }
}
