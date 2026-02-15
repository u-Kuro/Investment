package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.ASSET_BASE_EXPIRY_DURATIONS_MS;
import static com.example.investment.util.Constants.ASSET_INTERVALS;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.DateUtils.calculateMarketClosedDuration;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
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
    private final double firstPrice;
    private final double lastPrice;
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
        double firstPrice,
        double lastPrice,
        @NonNull Map<String, FixedHistoricalData> fixedHistoricalDataMap
    ) {
        this.symbol = symbol;
        this.type = type;
        this.name = name;
        this.exchange = exchange;
        this.marketCap = marketCap;
        this.firstTradeTimestampSeconds = firstTradeTimestampSeconds;
        this.firstPrice = firstPrice;
        this.lastPrice = lastPrice;
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
    public double getFirstPrice() { return firstPrice; }
    public double getLastPrice() { return lastPrice; }
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
        long currentTimeMs = System.currentTimeMillis();
        long marketClosedDurationMs = 0;

        // Add dynamic expiry buffer only for stock market being closed on weekends.
        // If market is closed: expiry buffer = (now - recent close time)
        // If market is open: expiry buffer = 0
        if (STOCK_TYPE.equals(type)) {
            marketClosedDurationMs = calculateMarketClosedDuration(currentTimeMs);
        }

        for (int i = 0; i < ASSET_INTERVALS.length; i++) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(ASSET_INTERVALS[i]);

            if (fixedHistoricalData != null) {
                long lastUpdateTimestampMs = fixedHistoricalData.updatedAtTimestampSeconds() * 1000L;
                long timeSinceLastUpdateMs = currentTimeMs - lastUpdateTimestampMs;

                // Dynamic threshold: base duration + expiry buffer when market closed
                long allowedStalenessMs = ASSET_BASE_EXPIRY_DURATIONS_MS[i] + marketClosedDurationMs;

                // Data is fresh if age is less than allowed staleness
                boolean isDataFresh = timeSinceLastUpdateMs < allowedStalenessMs;

                if (isDataFresh) {
                    return false; // At least one historical data is fresh
                }
            }
        }

        return true; // All historical data are stale
    }

    public @NonNull BigDecimal getDensePrice() {
        FixedHistoricalData data = getNewestFixedHistoricalData();
        if (data == null) return BigDecimal.ZERO;

        @NonNull BigDecimal dpSumDen = data.dpSumDen();
        if (dpSumDen.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return data.dpSumNum().divide(dpSumDen, MathContext.DECIMAL128);
    }
    public @NonNull BigDecimal getUpperBand() {
        FixedHistoricalData data = getNewestFixedHistoricalData();
        if (data == null) return BigDecimal.ZERO;

        @NonNull BigDecimal dense = getDensePrice();
        @NonNull BigDecimal dbSumDen = data.dbSumDen();

        if (dbSumDen.compareTo(BigDecimal.ZERO) == 0) return dense;

        @NonNull BigDecimal ratio = data.dbSumNum().divide(dbSumDen, MathContext.DECIMAL128).abs();
        @NonNull BigDecimal multiplier = BigDecimal.ONE.add(ratio);

        return dense.multiply(multiplier, MathContext.DECIMAL128);
    }
    public @NonNull BigDecimal getLowerBand() {
        FixedHistoricalData data = getNewestFixedHistoricalData();
        if (data == null) return BigDecimal.ZERO;

        @NonNull BigDecimal dense = getDensePrice();
        @NonNull BigDecimal dbSumDen = data.dbSumDen();

        if (dbSumDen.compareTo(BigDecimal.ZERO) == 0) return dense;

        @NonNull BigDecimal ratio = data.dbSumNum().divide(dbSumDen, MathContext.DECIMAL128).abs();
        @NonNull BigDecimal divisor = BigDecimal.ONE.add(ratio);

        if (divisor.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return dense.divide(divisor, MathContext.DECIMAL128);
    }
    public double getPercentageIncreasePerDay() {
        double numberOfDays = (System.currentTimeMillis() - (firstTradeTimestampSeconds * 1000)) / 86400000D;

        if (numberOfDays <= 0 || firstPrice <= 0) return 0;

        return (((lastPrice - firstPrice) / firstPrice) * 100) / numberOfDays;
    }

    public @NonNull String getPriceState() {
        @NonNull BigDecimal lowerBand = getLowerBand();
        @NonNull BigDecimal upperBand = getUpperBand();
        @NonNull BigDecimal lastPriceBD = BigDecimal.valueOf(lastPrice);

        if (
            getPercentageIncreasePerDay() <= 0
            || lastPriceBD.compareTo(lowerBand) < 0
            || upperBand.compareTo(lowerBand) < 0
        ) {
            return PRICE_BELOW;
        }

        if (lastPriceBD.compareTo(upperBand) <= 0) {
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
            && Double.compare(tickerData.lastPrice, lastPrice) == 0
            && Double.compare(tickerData.firstPrice, firstPrice) == 0
            && Objects.equals(fixedHistoricalDataMap, tickerData.fixedHistoricalDataMap);
    }
}