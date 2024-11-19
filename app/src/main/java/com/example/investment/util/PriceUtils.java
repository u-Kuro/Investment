package com.example.investment.util;

import java.util.Locale;

public class PriceUtils {
    public static String formatPrice(double price) {
        if (price == 0) return "$ 0";
        
        long wholeNumber = (long) price;
        
        if (wholeNumber == 0) {
            String decimalStr = String.format(Locale.US, "%.20f", price).split("\\.")[1];
            int leadingZeros = 0;
            while (leadingZeros < decimalStr.length() && decimalStr.charAt(leadingZeros) == '0') {
                leadingZeros++;
            }
            String format = "$ %." + (leadingZeros + 5) + "f";
            return String.format(Locale.US, format, price);
        } else {
            long wholeNumberDigits = String.valueOf(wholeNumber).length();
            if (wholeNumberDigits >= 5) {
                return "$ " + Math.floor(wholeNumber);
            } else {
                long decimalsNeeded = Math.max(5 - wholeNumberDigits, 0);
                String format = "$ %." + decimalsNeeded + "f";
                return String.format(Locale.US, format, price);
            }
        }
    }
    
    public static String getPriceState(double lastPrice, double upperBound, double lowerBound) {
        if (lastPrice >= upperBound) return Constants.PRICE_ABOVE;
        if (lastPrice <= lowerBound) return Constants.PRICE_BELOW;
        return Constants.PRICE_BETWEEN;
    }
} 