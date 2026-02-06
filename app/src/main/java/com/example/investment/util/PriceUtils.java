package com.example.investment.util;

import androidx.annotation.NonNull;

import com.example.investment.model.ticker.TickerInfo;

import java.util.Locale;

public class PriceUtils {
    public static String formatPrice(double price) {
        return String.format(Locale.US, "$ %s", NumberUtils.formatNumber(price));
    }

    public static @NonNull String getPriceState(TickerInfo tickerInfo) {
        if (
            tickerInfo.getPercentageIncreasePerDay() <= 0
            || tickerInfo.getLastPrice() <= tickerInfo.getDensePrice()
        ) return Constants.PRICE_BELOW;
        if (tickerInfo.getLastPrice() >= tickerInfo.getUpperPriceBoundary()) return Constants.PRICE_ABOVE;
        return Constants.PRICE_BETWEEN;
    }
} 