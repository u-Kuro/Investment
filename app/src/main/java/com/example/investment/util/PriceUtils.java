package com.example.investment.util;

import java.util.Locale;

public class PriceUtils {
    public static String formatPrice(double price) {
        return String.format(Locale.US, "$ %s", NumberUtils.formatNumber(price));
    }
} 