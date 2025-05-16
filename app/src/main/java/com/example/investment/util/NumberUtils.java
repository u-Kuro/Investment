package com.example.investment.util;

import com.example.investment.model.NumberRange;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public class NumberUtils {
    private static final int MAX_NUMBER_OF_DIGITS = 5;
    private static final NumberRange[] NUMBER_RANGES = {
        new NumberRange(1e18, "Qi"), // Quintillion
        new NumberRange(1e15, "Q"),  // Quadrillion
        new NumberRange(1e12, "T"),  // Trillion
        new NumberRange(1e9,  "B"),  // Billion
        new NumberRange(1e6,  "M"),  // Million
        new NumberRange(1e3,  "K")   // Thousand
    };

    public static String formatNumber(double number, Integer desiredNumberOfDecimal) {
        if (Math.abs(number) < 1 && number != 0) {
            return truncateDecimal(number, getDecimalsNeeded(number, desiredNumberOfDecimal));
        }

        NumberRange currentRange = Arrays
            .stream(NUMBER_RANGES)
            .filter(range -> number >= range.value())
            .findFirst()
            .orElse(new NumberRange(1, ""));

        double numberAbbrev = number / currentRange.value();
        String wholeNumberAbbrevStr = toFullNumericString((long) Math.floor(numberAbbrev));

        String valueStr;
        if (
            numberAbbrev % 1 == 0 ||
            toFullNumericString(numberAbbrev)
                .contains("." + "0".repeat(MAX_NUMBER_OF_DIGITS - wholeNumberAbbrevStr.length()))
        ) {
            valueStr = wholeNumberAbbrevStr;
        } else {
            int decimalsNeeded = Objects.requireNonNullElseGet(
                desiredNumberOfDecimal,
                () -> MAX_NUMBER_OF_DIGITS - wholeNumberAbbrevStr.length() - currentRange.suffix().length()
            );
            valueStr = truncateDecimal(numberAbbrev, decimalsNeeded);
        }

        return valueStr + currentRange.suffix();
    }

    private static int getDecimalsNeeded(double number, Integer desiredNumberOfDecimal) {
        String decimalStr = toFullNumericString(number).split("\\.")[1];

        int leadingZeros = 0;
        while (leadingZeros < decimalStr.length() && decimalStr.charAt(leadingZeros) == '0') {
            leadingZeros++;
        }

        int decimalsNeeded;
        if (desiredNumberOfDecimal == null) {
            decimalsNeeded = Math.max(leadingZeros + 1, Math.min(decimalStr.length(), MAX_NUMBER_OF_DIGITS - 1));
        } else {
            decimalsNeeded = Math.min(leadingZeros + 1, decimalStr.length());
        }

        return decimalsNeeded;
    }

    public static String formatNumber(double number) {
        return formatNumber(number, null);
    }

    public static String toFullNumericString(double number) {
        DecimalFormat df = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(340);
        return df.format(number);
    }

    private static String truncateDecimal(double number, int decimalDigits) {
        String numericStr = toFullNumericString(number);
        int decimalIndex = numericStr.indexOf(".");
        if (decimalIndex == -1) return numericStr;

        int endIndex = Math.min(decimalIndex + decimalDigits + 1, numericStr.length());
        String truncatedDecimalStr = numericStr.substring(0, endIndex);
        
        // Remove Trailing Zeros
        int lastNonZeroIndex = truncatedDecimalStr.length() - 1;
        while (lastNonZeroIndex > decimalIndex && truncatedDecimalStr.charAt(lastNonZeroIndex) == '0') {
            lastNonZeroIndex--;
        }

        if (lastNonZeroIndex == decimalIndex) {
            // Remove Decimal Point If All Trails are Zero
            return truncatedDecimalStr.substring(0, decimalIndex);
        } else {
            return truncatedDecimalStr.substring(0, lastNonZeroIndex + 1);
        }
    }
}
