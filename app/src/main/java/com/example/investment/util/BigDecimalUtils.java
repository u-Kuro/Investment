package com.example.investment.util;

import static com.example.investment.util.Constants.THOUSAND_BD;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

public class BigDecimalUtils {
    private static final int SIGNIFICANT_DIGITS = 5;
    private record BigDecimalRange(BigDecimal value, String suffix) {}
    private static final BigDecimalRange[] BIG_DECIMAL_RANGES = {
        new BigDecimalRange(new BigDecimal("1000000000000000000"), "Qi"),   // Quintillion
        new BigDecimalRange(new BigDecimal("1000000000000000"), "Q"),       // Quadrillion
        new BigDecimalRange(new BigDecimal("1000000000000"), "T"),          // Trillion
        new BigDecimalRange(new BigDecimal("1000000000"), "B"),             // Billion
        new BigDecimalRange(new BigDecimal("1000000"), "M"),                // Million
        new BigDecimalRange(THOUSAND_BD, "K")                                   // Thousand
    };
    private static final BigDecimalRange DEFAULT_RANGE = new BigDecimalRange(BigDecimal.ONE, "");

    public static @NonNull String formatNumber(@NonNull BigDecimal number) {
        if (number.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }

        BigDecimal absNumber = number.abs();
        boolean isNegative = number.compareTo(BigDecimal.ZERO) < 0;

        // Find appropriate range
        BigDecimalRange range = Arrays.stream(BIG_DECIMAL_RANGES)
            .filter(r -> absNumber.compareTo(r.value()) >= 0)
            .findFirst()
            .orElse(DEFAULT_RANGE);

        // Scale number by range
        BigDecimal scaled = absNumber.divide(range.value(), 10, RoundingMode.HALF_UP);

        // Format to exactly 5 significant digits
        String formatted = formatToSignificantDigits(scaled);

        return (isNegative ? "-" : "") + formatted + range.suffix();
    }

    private static String formatToSignificantDigits(@NonNull BigDecimal number) {
        // Count whole number digits
        int wholeDigits = number.toBigInteger().toString().length();

        if (wholeDigits >= SIGNIFICANT_DIGITS) {
            // Round to significant digits (no decimals needed)
            int excessDigits = wholeDigits - SIGNIFICANT_DIGITS;
            BigDecimal factor = BigDecimal.TEN.pow(excessDigits);
            return number.divide(factor, 0, RoundingMode.HALF_UP)
                .multiply(factor)
                .toBigInteger()
                .toString();
        } else {
            // Add decimal places to reach significant digits
            int decimalPlaces = SIGNIFICANT_DIGITS - wholeDigits;
            return number.setScale(decimalPlaces, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        }
    }

    public static BigDecimal getSafeBigDecimalPrefs(@NonNull SharedPreferences sharedPreferences, String key, String defaultValue) {
        try {
            String value = sharedPreferences.getString(key, null);
            if (value == null) return new BigDecimal(defaultValue);
            return new BigDecimal(value);
        } catch (Exception e) {
            return new BigDecimal(defaultValue);
        }
    }

    private static double toDoubleSafe(BigDecimal bd) {
        if (bd == null) {
            return 0.0;
        }

        // Check if it exceeds max positive
        if (bd.compareTo(BigDecimal.valueOf(Double.MAX_VALUE)) > 0) {
            return Double.MAX_VALUE;
        }

        // Check if it exceeds max negative
        if (bd.compareTo(BigDecimal.valueOf(-Double.MAX_VALUE)) < 0) {
            return -Double.MAX_VALUE;
        }

        // Check if it's too small positive (underflow to zero)
        if (bd.compareTo(BigDecimal.ZERO) > 0 && bd.compareTo(BigDecimal.valueOf(Double.MIN_VALUE)) < 0) {
            return Double.MIN_VALUE;
        }

        // Check if it's too small negative (underflow to zero)
        if (bd.compareTo(BigDecimal.ZERO) < 0 && bd.compareTo(BigDecimal.valueOf(-Double.MIN_VALUE)) > 0) {
            return -Double.MIN_VALUE;
        }

        // Safe to convert
        return bd.doubleValue();
    }

    public static double safeLog(BigDecimal bd) {
        if (bd == null || bd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Cannot take log of non-positive number: " + bd);
        }

        double value = toDoubleSafe(bd);

        if (value <= 0) {
            throw new IllegalArgumentException("toDoubleSafe returned non-positive value: " + value);
        }

        return Math.log(value);
    }
}