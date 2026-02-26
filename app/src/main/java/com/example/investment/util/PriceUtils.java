package com.example.investment.util;

import static com.example.investment.util.BigDecimalUtils.formatNumber;

import java.math.BigDecimal;
import java.util.Locale;

public class PriceUtils {
    public static String formatPrice(BigDecimal price) {
        return String.format(Locale.US, "$ %s", formatNumber(price));
    }
} 