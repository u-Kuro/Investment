package com.example.investment.model;

import static com.example.investment.util.BigDecimalUtils.getSafeBigDecimalPrefs;
import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.DEFAULT_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.DEFAULT_NUMBER_OF_TOP_HOLDINGS;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREF_AGE_IN_SECONDS_THRESHOLD;
import static com.example.investment.util.Constants.PREF_ASSET_TYPE;
import static com.example.investment.util.Constants.PREF_DAILY_DOLLAR_VOLUME_THRESHOLD;
import static com.example.investment.util.Constants.PREF_INVESTMENT_TYPE;
import static com.example.investment.util.Constants.PREF_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.PREF_NUMBER_OF_TOP_HOLDINGS;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.math.BigDecimal;

public record InvestmentConfig(
    @NonNull String investmentType,
    @NonNull String assetType,
    int numberOfTopHoldings,
    @NonNull BigDecimal marketCapThreshold,
    @NonNull BigDecimal dailyDollarVolumeThreshold,
    @NonNull BigDecimal ageInSecondsThreshold
) {
    public static @NonNull InvestmentConfig fromPreferences(@NonNull SharedPreferences sharedPreferences) {
        return new InvestmentConfig(
            sharedPreferences.getString(PREF_INVESTMENT_TYPE, PASSIVE_INVESTMENT_TYPE),
            sharedPreferences.getString(PREF_ASSET_TYPE, ALL_TYPE),
            sharedPreferences.getInt(PREF_NUMBER_OF_TOP_HOLDINGS, DEFAULT_NUMBER_OF_TOP_HOLDINGS),
            getSafeBigDecimalPrefs(sharedPreferences, PREF_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD),
            getSafeBigDecimalPrefs(sharedPreferences, PREF_DAILY_DOLLAR_VOLUME_THRESHOLD, DEFAULT_DAILY_DOLLAR_VOLUME_THRESHOLD),
            getSafeBigDecimalPrefs(sharedPreferences, PREF_AGE_IN_SECONDS_THRESHOLD, DEFAULT_AGE_IN_SECONDS_THRESHOLD)
        );
    }
}