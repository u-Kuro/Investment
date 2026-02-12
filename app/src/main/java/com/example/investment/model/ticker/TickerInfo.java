package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L; // Incremented due to structural change

    @NonNull private final String symbol;
    private final String exchange;
    private final String name;
    private final String type;
    private final double lastPrice;
    private final double densePrice;
    private final double upperBand;
    private final double lowerBand;
    private final double percentageIncreasePerDay;
    private final long firstTradeTimestampSeconds;
    private final long marketCap;
    private final long lastUpdateTimestamp;
    private final long oldestDataTimestampSeconds;

    // Summed metrics instead of arrays
    private final long adjPricesCount;
    private final Double firstAdjPrice;
    private final long ohlcvCount;
    private final double dpSumNum;
    private final double dpSumDen;
    private final double dbSumNum;
    private final double dbSumDen;

    public TickerInfo(
            @NonNull String symbol,
            String exchange,
            String name,
            String type,
            double lastPrice,
            double densePrice,
            double upperBand,
            double lowerBand,
            double percentageIncreasePerDay,
            long firstTradeTimestampSeconds,
            long marketCap,
            long lastUpdateTimestamp,
            long oldestDataTimestampSeconds,
            long adjPricesCount,
            Double firstAdjPrice,
            long ohlcvCount,
            double dpSumNum,
            double dpSumDen,
            double dbSumNum,
            double dbSumDen
    ) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.name = name;
        this.type = type;
        this.lastPrice = lastPrice;
        this.densePrice = densePrice;
        this.upperBand = upperBand;
        this.lowerBand = lowerBand;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.firstTradeTimestampSeconds = firstTradeTimestampSeconds;
        this.marketCap = marketCap;
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.oldestDataTimestampSeconds = oldestDataTimestampSeconds;
        this.adjPricesCount = adjPricesCount;
        this.firstAdjPrice = firstAdjPrice;
        this.ohlcvCount = ohlcvCount;
        this.dpSumNum = dpSumNum;
        this.dpSumDen = dpSumDen;
        this.dbSumNum = dbSumNum;
        this.dbSumDen = dbSumDen;
    }

    public @NonNull String getSymbol() { return symbol; }
    public String getExchange() { return exchange; }
    public String getName(boolean defaultToSymbol) {
        if (defaultToSymbol && (name == null || name.isEmpty())) {
            return symbol;
        }
        return name;
    }
    public String getType() { return type; }
    public double getLastPrice() { return lastPrice; }
    public double getDensePrice() { return densePrice; }
    public double getUpperBand() { return upperBand; }
    public double getLowerBand() { return lowerBand; }
    public double getPercentageIncreasePerDay() { return percentageIncreasePerDay; }
    public long getFirstTradeTimestampSeconds() { return firstTradeTimestampSeconds; }
    public long getMarketCap() { return marketCap; }
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public long getOldestDataTimestampSeconds() { return oldestDataTimestampSeconds; }

    // Getters for summed metrics
    public long getAdjPricesCount() { return adjPricesCount; }
    public Double getFirstAdjPrice() { return firstAdjPrice; }
    public long getOhlcvCount() { return ohlcvCount; }
    public double getDpSumNum() { return dpSumNum; }
    public double getDpSumDen() { return dpSumDen; }
    public double getDbSumNum() { return dbSumNum; }
    public double getDbSumDen() { return dbSumDen; }

    public @NonNull String getPriceState() {
        if (this.percentageIncreasePerDay <= 0 || this.lastPrice < this.lowerBand || this.upperBand < this.lowerBand) {
            return PRICE_BELOW;
        }
        if (this.lastPrice <= this.upperBand) return PRICE_BETWEEN;
        return PRICE_ABOVE;
    }

    public boolean isDataStale() {
        if (lastUpdateTimestamp == 0) return true;
        long timeDifference = System.currentTimeMillis() - lastUpdateTimestamp;
        return timeDifference > TimeUnit.DAYS.toMillis(1);
    }

    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
                && Objects.equals(this.exchange, tickerInfo.getExchange())
                && Objects.equals(this.name, tickerInfo.getName(false))
                && Objects.equals(this.type, tickerInfo.getType())
                && this.lastPrice == tickerInfo.getLastPrice()
                && this.densePrice == tickerInfo.getDensePrice()
                && this.upperBand == tickerInfo.getUpperBand()
                && this.lowerBand == tickerInfo.getLowerBand()
                && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
                && this.firstTradeTimestampSeconds == tickerInfo.getFirstTradeTimestampSeconds()
                && this.marketCap == tickerInfo.getMarketCap()
                && this.lastUpdateTimestamp == tickerInfo.getLastUpdateTimestamp()
                && this.oldestDataTimestampSeconds == tickerInfo.getOldestDataTimestampSeconds()
                && this.adjPricesCount == tickerInfo.getAdjPricesCount()
                && Objects.equals(this.firstAdjPrice, tickerInfo.getFirstAdjPrice())
                && this.ohlcvCount == tickerInfo.getOhlcvCount()
                && this.dpSumNum == tickerInfo.getDpSumNum()
                && this.dpSumDen == tickerInfo.getDpSumDen()
                && this.dbSumNum == tickerInfo.getDbSumNum()
                && this.dbSumDen == tickerInfo.getDbSumDen();
    }
}