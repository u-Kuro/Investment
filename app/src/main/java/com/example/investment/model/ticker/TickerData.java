package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.ASSET_DURATION_DAYS;
import static com.example.investment.util.Constants.ASSET_DURATION_UNTIL_NEXT_UPDATE_MS;
import static com.example.investment.util.Constants.ASSET_INTERVALS;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.PERIODIC_WORKER_UPDATE_BUFFER_MS;
import static com.example.investment.util.Constants.THOUSAND_BD;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.DateUtils.calculateMarketActiveMillis;

import static java.math.MathContext.DECIMAL128;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    @NonNull private final BigDecimal marketCap;
    @NonNull private final BigInteger firstTradeTimestampSeconds;
    @NonNull private final BigDecimal lastAdjustedClose;
    @NonNull private final BigDecimal adjustmentFactor;
    @NonNull private final Map<String, FixedHistoricalData> fixedHistoricalDataMap;
    public record FixedHistoricalData(
        @NonNull BigInteger updatedAtTimestampSeconds,
        @NonNull BigDecimal dpSumNum,
        @NonNull BigDecimal dpSumDen,
        @NonNull BigDecimal dbSumNum,
        @NonNull BigDecimal dbSumDen,
        @NonNull BigDecimal sumDollarVol,
        @NonNull BigDecimal sumDollarVolLen,
        @NonNull BigDecimal startPrice,
        @NonNull BigInteger startPriceTimestampSeconds
    ) implements Serializable { @Serial private static final long serialVersionUID = 1L; }

    public TickerData(
        @NonNull String symbol,
        @NonNull String type,
        String name,
        String exchange,
        @NonNull BigDecimal marketCap,
        @NonNull BigInteger firstTradeTimestampSeconds,
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
    public @NonNull BigDecimal getMarketCap() { return marketCap; }
    public @NonNull BigInteger getFirstTradeTimestampSeconds() { return firstTradeTimestampSeconds; }
    public @NonNull BigDecimal getLastAdjustedClose() { return lastAdjustedClose; }
    public @NonNull BigDecimal getAdjustmentFactor() { return adjustmentFactor; }
    public @NonNull Map<String, FixedHistoricalData> getFixedHistoricalDataMap() { return new HashMap<>(fixedHistoricalDataMap); }
    public FixedHistoricalData getFixedHistoricalData(@NonNull String interval) {
        return getFixedHistoricalDataMap().getOrDefault(interval, null);
    }

    public record FixedHistoricalRecord(@NonNull String interval, @NonNull FixedHistoricalData fixedHistoricalData) {}
    public FixedHistoricalRecord getNewestFixedHistoricalDataWithInterval() {
        BigInteger newestTimestampSeconds = null;
        FixedHistoricalRecord newFixedHistoricalRecord = null;

        for (String interval : ASSET_INTERVALS) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);
            if (fixedHistoricalData != null) {
                @NonNull BigInteger updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds;
                if (newestTimestampSeconds == null || updatedAtTimestampSeconds.compareTo(newestTimestampSeconds) > 0) {
                    newestTimestampSeconds = updatedAtTimestampSeconds;
                    newFixedHistoricalRecord = new FixedHistoricalRecord(interval, fixedHistoricalData);
                }
            }
        }

        return newFixedHistoricalRecord;
    }
    public FixedHistoricalData getNewestFixedHistoricalData() {
        BigInteger newestTimestampSeconds = null;
        FixedHistoricalData newestFixedHistoricalData = null;

        for (String interval : ASSET_INTERVALS) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);
            if (fixedHistoricalData != null) {
                @NonNull BigInteger updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds;
                if (newestTimestampSeconds == null || updatedAtTimestampSeconds.compareTo(newestTimestampSeconds) > 0) {
                    newestTimestampSeconds = updatedAtTimestampSeconds;
                    newestFixedHistoricalData = fixedHistoricalData;
                }
            }
        }

        return newestFixedHistoricalData;
    }
    public BigInteger getNewestFixedHistoricalDataTimestampSeconds() {
        BigInteger newestTimestampSeconds = null;

        for (String interval : ASSET_INTERVALS) {
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);
            if (fixedHistoricalData != null) {
                @NonNull BigInteger updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds;
                if (newestTimestampSeconds == null || updatedAtTimestampSeconds.compareTo(newestTimestampSeconds) > 0) {
                    newestTimestampSeconds = updatedAtTimestampSeconds;
                }
            }
        }

        return newestTimestampSeconds;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public boolean areAllFixedHistoricalDataStale() {
        @NonNull BigInteger nowMs = BigInteger.valueOf(System.currentTimeMillis());

        for (Map.Entry<String, BigInteger> entry : ASSET_DURATION_UNTIL_NEXT_UPDATE_MS.entrySet()) {
            String interval = entry.getKey();
            BigInteger assetDurationUntilNextUpdateMs = entry.getValue();
            FixedHistoricalData fixedHistoricalData = getFixedHistoricalDataMap().get(interval);

            if (fixedHistoricalData == null) {
                continue;
            }

            @NonNull BigInteger lastUpdateMs = fixedHistoricalData.updatedAtTimestampSeconds.multiply(THOUSAND_BI);
            boolean isDataFresh = calculateMarketActiveMillis(lastUpdateMs, nowMs).compareTo(assetDurationUntilNextUpdateMs.add(PERIODIC_WORKER_UPDATE_BUFFER_MS)) <= 0;
            if (isDataFresh) {
                return false; // at least one interval is fresh — no notification
            }
        }

        // Every interval failed all freshness checks — data is genuinely stale
        return true;
    }

    private @NonNull BigDecimal getAdjustedDensePrice(FixedHistoricalData newestFixedHistoricalData) {
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return newestFixedHistoricalData.dpSumNum
            .divide(newestFixedHistoricalData.dpSumDen, DECIMAL128)
            .multiply(adjustmentFactor, DECIMAL128);
    }
    public @NonNull BigDecimal getAdjustedUpperBand(FixedHistoricalData newestFixedHistoricalData) {
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return getAdjustedDensePrice(newestFixedHistoricalData)
            .multiply(
                newestFixedHistoricalData.dbSumNum
                .divide(newestFixedHistoricalData.dbSumDen, DECIMAL128)
                .abs()
                .add(BigDecimal.ONE), DECIMAL128
            );
    }
    public @NonNull BigDecimal getAdjustedLowerBand(FixedHistoricalData newestFixedHistoricalData) {
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        return getAdjustedDensePrice(newestFixedHistoricalData)
            .divide(
                newestFixedHistoricalData.dbSumNum
                .divide(newestFixedHistoricalData.dbSumDen, DECIMAL128)
                .abs()
                .add(BigDecimal.ONE), DECIMAL128
            );
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MILLIS_PER_DAY = new BigDecimal("86400000");
    public @NonNull BigDecimal getPercentageIncreasePerDay() {
        FixedHistoricalData newestFixedHistoricalData = getNewestFixedHistoricalData();
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        @NonNull BigDecimal startPrice = newestFixedHistoricalData.startPrice;
        if (startPrice.compareTo(BigDecimal.ZERO) <= 0 || adjustmentFactor.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        @NonNull BigInteger startPriceTimestampSeconds = newestFixedHistoricalData.startPriceTimestampSeconds;
        @NonNull BigDecimal numberOfDays = new BigDecimal(BigInteger
            .valueOf(System.currentTimeMillis())
            .subtract(startPriceTimestampSeconds.multiply(THOUSAND_BI)))
            .divide(MILLIS_PER_DAY, DECIMAL128);
        if (numberOfDays.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        @NonNull BigDecimal firstAdjustedStartPrice = startPrice.multiply(adjustmentFactor);

        return getAdjustedUpperBand(newestFixedHistoricalData)
            .subtract(firstAdjustedStartPrice, DECIMAL128)
            .divide(firstAdjustedStartPrice, DECIMAL128)
            .multiply(HUNDRED, DECIMAL128)
            .divide(numberOfDays, DECIMAL128);
    }
    public @NonNull BigDecimal getPercentageIncreasePerDay(FixedHistoricalData newestFixedHistoricalData) {
        if (newestFixedHistoricalData == null) return BigDecimal.ZERO;

        @NonNull BigDecimal startPrice = newestFixedHistoricalData.startPrice;
        if (startPrice.compareTo(BigDecimal.ZERO) <= 0 || adjustmentFactor.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        @NonNull BigInteger startPriceTimestampSeconds = newestFixedHistoricalData.startPriceTimestampSeconds;
        @NonNull BigDecimal numberOfDays = new BigDecimal(BigInteger
            .valueOf(System.currentTimeMillis())
            .subtract(startPriceTimestampSeconds.multiply(THOUSAND_BI)))
            .divide(MILLIS_PER_DAY, DECIMAL128);
        if (numberOfDays.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        @NonNull BigDecimal firstAdjustedStartPrice = startPrice.multiply(adjustmentFactor);

        return getAdjustedUpperBand(newestFixedHistoricalData)
            .subtract(firstAdjustedStartPrice, DECIMAL128)
            .divide(firstAdjustedStartPrice, DECIMAL128)
            .multiply(HUNDRED, DECIMAL128)
            .divide(numberOfDays, DECIMAL128);
    }

    public @NonNull BigDecimal getDailyDollarVolume() {
        FixedHistoricalRecord newestFixedHistoricalDataRecord = getNewestFixedHistoricalDataWithInterval();
        if (newestFixedHistoricalDataRecord == null) return BigDecimal.ZERO;

        BigDecimal assetDurationDays = ASSET_DURATION_DAYS.get(newestFixedHistoricalDataRecord.interval);

        if (assetDurationDays != null) {
            FixedHistoricalData newestFixedHistoricalData = newestFixedHistoricalDataRecord.fixedHistoricalData;
            @NonNull BigDecimal sumDollarVol = newestFixedHistoricalData.sumDollarVol;
            @NonNull BigDecimal sumDollarVolLen = newestFixedHistoricalData.sumDollarVolLen;

            return sumDollarVol.divide(sumDollarVolLen, DECIMAL128)
                .divide(assetDurationDays, DECIMAL128);
        } else {
            return BigDecimal.ZERO;
        }
    }
    public @NonNull BigDecimal getDailyDollarVolume(FixedHistoricalRecord newestFixedHistoricalDataRecord) {
        if (newestFixedHistoricalDataRecord == null) return BigDecimal.ZERO;

        BigDecimal assetDurationDays = ASSET_DURATION_DAYS.get(newestFixedHistoricalDataRecord.interval);

        if (assetDurationDays != null) {
            FixedHistoricalData newestFixedHistoricalData = newestFixedHistoricalDataRecord.fixedHistoricalData;
            @NonNull BigDecimal sumDollarVol = newestFixedHistoricalData.sumDollarVol;
            @NonNull BigDecimal sumDollarVolLen = newestFixedHistoricalData.sumDollarVolLen;

            return sumDollarVol.divide(sumDollarVolLen, DECIMAL128)
                    .divide(assetDurationDays, DECIMAL128);
        } else {
            return BigDecimal.ZERO;
        }
    }

    public @NonNull BigDecimal getAgeInSeconds() {
        return BigDecimal.valueOf(System.currentTimeMillis()).divide(THOUSAND_BD, DECIMAL128)
            .subtract(new BigDecimal(firstTradeTimestampSeconds));
    }

    public @NonNull String getPriceState() {
        FixedHistoricalData newestFixedHistoricalData = getNewestFixedHistoricalData();
        if (newestFixedHistoricalData == null) return PRICE_BELOW;

        @NonNull BigDecimal adjustedLowerBand = getAdjustedLowerBand(newestFixedHistoricalData);
        @NonNull BigDecimal adjustedUpperBand = getAdjustedUpperBand(newestFixedHistoricalData);

        if (
            getPercentageIncreasePerDay(newestFixedHistoricalData).compareTo(BigDecimal.ZERO) <= 0
            || lastAdjustedClose.compareTo(adjustedLowerBand) < 0
            || adjustedUpperBand.compareTo(adjustedLowerBand) < 0
        ) {
            return PRICE_BELOW;
        }

        if (lastAdjustedClose.compareTo(adjustedUpperBand) <= 0) {
            return PRICE_BETWEEN;
        }

        return PRICE_ABOVE;
    }
    public @NonNull String getPriceState(FixedHistoricalData newestFixedHistoricalData) {
        if (newestFixedHistoricalData == null) return PRICE_BELOW;

        @NonNull BigDecimal adjustedLowerBand = getAdjustedLowerBand(newestFixedHistoricalData);
        @NonNull BigDecimal adjustedUpperBand = getAdjustedUpperBand(newestFixedHistoricalData);

        if (
            getPercentageIncreasePerDay(newestFixedHistoricalData).compareTo(BigDecimal.ZERO) <= 0
            || lastAdjustedClose.compareTo(adjustedLowerBand) < 0
            || adjustedUpperBand.compareTo(adjustedLowerBand) < 0
        ) {
            return PRICE_BELOW;
        }

        if (lastAdjustedClose.compareTo(adjustedUpperBand) <= 0) {
            return PRICE_BETWEEN;
        }

        return PRICE_ABOVE;
    }

    public boolean customEquals(TickerData tickerData) {
        return symbol.equals(tickerData.symbol)
            && type.equals(tickerData.type)
            && Objects.equals(name, tickerData.name)
            && Objects.equals(exchange, tickerData.exchange)
            && marketCap.compareTo(tickerData.marketCap) == 0
            && firstTradeTimestampSeconds.compareTo(tickerData.firstTradeTimestampSeconds) == 0
            && lastAdjustedClose.compareTo(tickerData.lastAdjustedClose) == 0
            && adjustmentFactor.compareTo(tickerData.adjustmentFactor) == 0
            && Objects.equals(fixedHistoricalDataMap, tickerData.fixedHistoricalDataMap);
    }
}