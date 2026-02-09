package com.example.investment.util;

import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;

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
            || tickerInfo.getStopLossPrice() <= tickerInfo.getDensePrice()
            || tickerInfo.getLastPrice() < tickerInfo.getDensePrice()
        ) return PRICE_BELOW;
        if (tickerInfo.getLastPrice() >= tickerInfo.getStopLossPrice()) return PRICE_ABOVE;
        return PRICE_BETWEEN;
    }
} 