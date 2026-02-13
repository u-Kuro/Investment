package com.example.investment.util;

import java.math.BigDecimal;
import java.util.Locale;

public class PriceUtils {
    public static String formatPrice(double price) {
        return String.format(Locale.US, "$ %s", NumberUtils.formatNumber(price));
    }
    public static String formatPrice(BigDecimal price) {
        return String.format(Locale.US, "$ %s", NumberUtils.formatNumber(price));
    }
} 