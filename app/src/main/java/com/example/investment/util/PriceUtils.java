package com.example.investment.util;

import static com.example.investment.util.BigDecimalUtils.formatNumber;

import java.math.BigDecimal;

public class PriceUtils {
    public static String formatPrice(BigDecimal price) {
        return String.format("$ %s", formatNumber(price));
    }
} 