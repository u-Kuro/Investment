package com.example.investment.util;

import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.HUNDRED_BD;
import static com.example.investment.util.Constants.MARKET_DISTRIBUTION_ABOVE;
import static com.example.investment.util.Constants.MARKET_DISTRIBUTION_BELOW;
import static com.example.investment.util.Constants.MARKET_DISTRIBUTION_MIDDLE;
import static com.example.investment.util.Constants.MOMENTUM_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.TickerRanking.passesFilters;

import static java.math.MathContext.DECIMAL128;

import android.content.Context;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.example.investment.R;
import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.ticker.TickerData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class MarketDistribution {
    public record Result(@NonNull String state, @NonNull BigDecimal score) {}

    // Price state scores (Percentage)
    private static final BigDecimal PRICE_ABOVE_SCORE = HUNDRED_BD; // 100%
    private static final BigDecimal PRICE_BETWEEN_SCORE = BigDecimal.valueOf(50); // 50%
    private static final BigDecimal PRICE_BELOW_SCORE = BigDecimal.ZERO; // 0%

    // Market distribution thresholds (Percentage)
    private static final BigDecimal NUMBER_OF_STATES = BigDecimal.valueOf(3);
    private static final BigDecimal LOWER_THRESHOLD = HUNDRED_BD.divide(NUMBER_OF_STATES, DECIMAL128);
    private static final BigDecimal UPPER_THRESHOLD = HUNDRED_BD.multiply(BigDecimal.valueOf(2)).divide(NUMBER_OF_STATES, DECIMAL128);

    public static @Nullable Result calculate(@NonNull List<TickerData> tickers, @NonNull InvestmentConfig config, long nowMillis) {
        @NonNull BigDecimal scoreSum = BigDecimal.ZERO;
        int tickerCount = 0;

        @NonNull InvestmentConfig marketConfig = new InvestmentConfig(
            config.investmentType(),
            ALL_TYPE,
            config.numberOfTopHoldings(),
            config.marketCapThreshold(),
            config.dailyDollarVolumeThreshold(),
            config.ageInSecondsThreshold()
        );

        for (TickerData ticker : tickers) {
            if (!passesFilters(ticker, marketConfig, nowMillis)) continue;

            @NonNull String priceState = ticker.getPriceState(
                MOMENTUM_INVESTMENT_TYPE,
                ticker.getNewestFixedHistoricalData(),
                nowMillis
            );

            scoreSum = scoreSum.add(getPriceStateScore(priceState));
            tickerCount++;
        }

        if (tickerCount == 0) return null;

        @NonNull BigDecimal score = scoreSum.divide(BigDecimal.valueOf(tickerCount), DECIMAL128);
        return new Result(getState(score), score);
    }

    public static boolean isState(@Nullable String state) {
        return MARKET_DISTRIBUTION_ABOVE.equals(state)
            || MARKET_DISTRIBUTION_MIDDLE.equals(state)
            || MARKET_DISTRIBUTION_BELOW.equals(state);
    }

    public static @NonNull String getLabel(@NonNull Context context, @NonNull Result result) {
        return context.getString(getLabelFormat(result.state()), getRoundedScore(result));
    }

    public static @ColorInt int getSignalColor(@NonNull Context context, @NonNull Result result) {
        final @ColorInt int red = ContextCompat.getColor(context, R.color.signal_red);
        final @ColorInt int yellow = ContextCompat.getColor(context, R.color.signal_yellow);
        final @ColorInt int green = ContextCompat.getColor(context, R.color.signal_green);

        final float fraction = Math.max(0f, Math.min(1f, result.score().floatValue() / HUNDRED_BD.floatValue()));
        if (fraction < 0.5f) return ColorUtils.blendARGB(red, yellow, fraction / 0.5f);
        return ColorUtils.blendARGB(yellow, green, (fraction - 0.5f) / 0.5f);
    }

    // Helpers
    private static @NonNull BigDecimal getPriceStateScore(@NonNull String priceState) {
        if (priceState.equals(PRICE_ABOVE)) return PRICE_ABOVE_SCORE;
        if (priceState.equals(PRICE_BETWEEN)) return PRICE_BETWEEN_SCORE;
        return PRICE_BELOW_SCORE;
    }

    private static @NonNull String getState(@NonNull BigDecimal score) {
        if (score.compareTo(LOWER_THRESHOLD) < 0) return MARKET_DISTRIBUTION_BELOW;
        if (score.compareTo(UPPER_THRESHOLD) < 0) return MARKET_DISTRIBUTION_MIDDLE;
        return MARKET_DISTRIBUTION_ABOVE;
    }

    private static @StringRes int getLabelFormat(@NonNull String state) {
        if (state.equals(MARKET_DISTRIBUTION_ABOVE)) return R.string.market_distribution_above_format;
        if (state.equals(MARKET_DISTRIBUTION_MIDDLE)) return R.string.market_distribution_middle_format;
        return R.string.market_distribution_below_format;
    }

    private static int getRoundedScore(@NonNull Result result) {
        return result.score().setScale(0, RoundingMode.HALF_UP).intValue();
    }
}