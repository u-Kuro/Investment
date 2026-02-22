package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.ASSET_DURATION_UNTIL_NEXT_UPDATE_MS;
import static com.example.investment.util.Constants.ASSET_INTERVALS;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.PERIODIC_WORKER_UPDATE_BUFFER_MS;
import static com.example.investment.util.DateUtils.calculateMarketActiveMillis;

import static java.math.MathContext.DECIMAL128;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TickerData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    @NonNull private final String symbol;
    @NonNull private final String type;
    private final String name;
    private final String exchange;
    private final long marketCap;
    private final long firstTradeTimestampSeconds;
    @NonNull private final BigDecimal firstOpen;
    @NonNull private final BigDecimal lastAdjustedClose;
    @NonNull private final BigDecimal adjustmentFactor;
    @NonNull private final Map<String, FixedHistoricalData> fixedHistoricalDataMap;
    public record FixedHistoricalData(
        long updatedAtTimestampSeconds,
        @NonNull BigDecimal dpSumNum,
        @NonNull BigDecimal dpSumDen,
        @NonNull BigDecimal dbSumNum,
        @NonNull BigDecimal dbSumDen
    ) implements Serializable { @Serial private static final long serialVersionUID = 1L; }

    public TickerData(
        @NonNull String symbol,
        @NonNull String type,
        String name,
        String exchange,
        long marketCap,
        long firstTradeTimestampSeconds,
        @NonNull BigDecimal firstOpen,
        @NonNull BigDecimal lastAdjustedClose,
        @NonNull BigDecimal adjustmentFactor,
        @NonNull Map<String, FixedHistoricalData> fixedHistoricalDataMap
    ) {
        this.symbol = symbol;
        this.type = type;
        this.name = name;
        this.exchange = exchange;
        this.marketCap = marketCap;
        this.firstTradeTimestampSeconds = firstTradeTimestampSeconds;
        this.firstOpen = firstOpen;
        this.lastAdjustedClose = lastAdjustedClose;
        this.adjustmentFactor = adjustmentFactor;
        this.fixedHistoricalDataMap = new HashMap<>(fixedHistoricalDataMap);
    }

    public @NonNull String getSymbol() { return symbol; }
    public @NonNull String getType() { return type; }
    public String getName(boolean defaultToSymbol) {
        if (defaultToSymbol && (name == null || name.isEmpty())) {
            return symbol;
        }
        return name;
    }
    public String getExchange() { return exchange; }
    public long getMarketCap() { return marketCap; }
    public long getFirstTradeTimestampSeconds() { return firstTradeTimestampSeconds; }
    public @NonNull BigDecimal getFirstOpen() { return firstOpen; }
    public @NonNull BigDecimal getLastAdjustedClose() { return lastAdjustedClose; }
    public @NonNull BigDecimal getAdjustmentFactor() { return adjustmentFactor; }
    public @NonNull Map<String, FixedHistoricalData> getFixedHistoricalDataMap() { return new HashMap<>(fixedHistoricalDataMap); }
    public FixedHistoricalData getFixedHistoricalData(@NonNull String interval) {
        return getFixedHistoricalDataMap().getOrDefault(interval, null);
    }

    public FixedHistoricalData getNewestFixedHistoricalData() {
        Long newestTimestampSeconds = null;
        FixedHistoricalData newestFixedHistoricalData = null;

        for (String interval : ASSET_INTERVALS) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);
            if (fixedHistoricalData != null) {
                long updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds();
                if (newestTimestampSeconds == null || updatedAtTimestampSeconds > newestTimestampSeconds) {
                    newestTimestampSeconds = updatedAtTimestampSeconds;
                    newestFixedHistoricalData = fixedHistoricalData;
                }
            }
        }

        return newestFixedHistoricalData;
    }
    public Long getNewestFixedHistoricalDataTimestampSeconds() {
        Long newestTimestampSeconds = null;

        for (String interval : ASSET_INTERVALS) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);
            if (fixedHistoricalData != null) {
                long updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds();
                if (newestTimestampSeconds == null || updatedAtTimestampSeconds > newestTimestampSeconds) {
                    newestTimestampSeconds = updatedAtTimestampSeconds;
                }
            }
        }

        return newestTimestampSeconds;
    }

    public boolean areAllFixedHistoricalDataStale() {
        long nowMs = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : ASSET_DURATION_UNTIL_NEXT_UPDATE_MS.entrySet()) {
            String interval = entry.getKey();
            long assetDurationUntilNextUpdateMs = entry.getValue();
            FixedHistoricalData data = getFixedHistoricalDataMap().get(interval);

            if (data == null) {
                continue;
            }

            long lastUpdateMs = data.updatedAtTimestampSeconds() * 1000L;

            boolean isDataFresh = calculateMarketActiveMillis(lastUpdateMs, nowMs) <= (assetDurationUntilNextUpdateMs + PERIODIC_WORKER_UPDATE_BUFFER_MS);
            if (isDataFresh) {
                return false; // at least one interval is fresh — no notification
            }
        }

        // Every interval failed all freshness checks — data is genuinely stale
        return true;
    }

    public @NonNull BigDecimal getAdjustedDensePrice() {
        FixedHistoricalData newestFixedHistoricalData = getNewestFixedHistoricalData();
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return newestFixedHistoricalData.dpSumNum()
            .divide(newestFixedHistoricalData.dpSumDen(), DECIMAL128)
            .multiply(adjustmentFactor, DECIMAL128);
    }
    public @NonNull BigDecimal getUpperBand() {
        FixedHistoricalData newestFixedHistoricalData = getNewestFixedHistoricalData();
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return getAdjustedDensePrice()
            .multiply(
                newestFixedHistoricalData.dbSumNum()
                .divide(newestFixedHistoricalData.dbSumDen(), DECIMAL128)
                .abs()
                .add(BigDecimal.ONE), DECIMAL128
            );
    }
    public @NonNull BigDecimal getLowerBand() {
        FixedHistoricalData newestFixedHistoricalData = getNewestFixedHistoricalData();
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return getAdjustedDensePrice()
            .divide(
                newestFixedHistoricalData.dbSumNum()
                .divide(newestFixedHistoricalData.dbSumDen(), DECIMAL128)
                .abs()
                .add(BigDecimal.ONE), DECIMAL128
            );
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MILLIS_IN_DAY = new BigDecimal("86400000");
    public @NonNull BigDecimal getPercentageIncreasePerDay() {
        @NonNull BigDecimal numberOfDays = BigDecimal
            .valueOf(System.currentTimeMillis() - (firstTradeTimestampSeconds * 1000L))
            .divide(MILLIS_IN_DAY, DECIMAL128);

        if (numberOfDays.compareTo(BigDecimal.ZERO) <= 0 || firstOpen.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        @NonNull BigDecimal firstAdjustedOpen = firstOpen.multiply(adjustmentFactor);

        return lastAdjustedClose
            .subtract(firstAdjustedOpen, DECIMAL128)
            .divide(firstAdjustedOpen, DECIMAL128)
            .multiply(HUNDRED, DECIMAL128)
            .divide(numberOfDays, DECIMAL128);
    }

    public @NonNull String getPriceState() {
        @NonNull BigDecimal lowerBand = getLowerBand();
        @NonNull BigDecimal upperBand = getUpperBand();

        if (
            getPercentageIncreasePerDay().compareTo(BigDecimal.ZERO) <= 0
            || lastAdjustedClose.compareTo(lowerBand) < 0
            || upperBand.compareTo(lowerBand) < 0
        ) {
            return PRICE_BELOW;
        }

        if (lastAdjustedClose.compareTo(upperBand) <= 0) {
            return PRICE_BETWEEN;
        }

        return PRICE_ABOVE;
    }

    public boolean equals(TickerData tickerData) {
        return symbol.equals(tickerData.symbol)
            && type.equals(tickerData.type)
            && Objects.equals(name, tickerData.name)
            && Objects.equals(exchange, tickerData.exchange)
            && marketCap == tickerData.marketCap
            && firstTradeTimestampSeconds == tickerData.firstTradeTimestampSeconds
            && tickerData.firstOpen.compareTo(firstOpen) == 0
            && tickerData.lastAdjustedClose.compareTo(lastAdjustedClose) == 0
            && tickerData.adjustmentFactor.compareTo(adjustmentFactor) == 0
            && Objects.equals(fixedHistoricalDataMap, tickerData.fixedHistoricalDataMap);
    }
}