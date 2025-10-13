package com.example.investment.model.ticker;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String symbol;
    private final String name;
    private final String type;
    private final double lastPrice;
    private final double densePrice;
    private final double upperPriceBoundary;
    private final double lowerPriceBoundary;
    private final double percentageIncreasePerDay;
    private final long timestampSeconds;
    private final long marketCap;
    public TickerInfo(
        String symbol,
        String name,
        String type,
        double lastPrice,
        double densePrice,
        double upperPriceBoundary,
        double lowerPriceBoundary,
        double percentageIncreasePerDay,
        long timestampSeconds,
        long marketCap
    ) {
        this.symbol = symbol;
        this.name = name;
        this.type = type;
        this.lastPrice = lastPrice;
        this.densePrice = densePrice;
        this.upperPriceBoundary = upperPriceBoundary;
        this.lowerPriceBoundary = lowerPriceBoundary;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.timestampSeconds = timestampSeconds;
        this.marketCap = marketCap;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName(boolean defaultToSymbol) {
        if (defaultToSymbol && (name == null || name.isEmpty())) {
            return symbol;
        }
        return name;
    }

    public String getType() {
        return type;
    }

    public double getLastPrice() {
        return lastPrice;
    }

    public double getDensePrice() {
        return densePrice;
    }

    public double getUpperPriceBoundary() {
        return upperPriceBoundary;
    }

    public double getLowerPriceBoundary() {
        return lowerPriceBoundary;
    }

    public double getPercentageIncreasePerDay() {
        return percentageIncreasePerDay;
    }

    public long getTimestampSeconds() {
        return timestampSeconds;
    }

    public long getMarketCap() {
        return marketCap;
    }

    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
            && Objects.equals(this.name, tickerInfo.getName(false))
            && Objects.equals(this.type, tickerInfo.getType())
            && this.lastPrice == tickerInfo.getLastPrice()
            && this.densePrice == tickerInfo.getDensePrice()
            && this.upperPriceBoundary == tickerInfo.getUpperPriceBoundary()
            && this.lowerPriceBoundary == tickerInfo.getLowerPriceBoundary()
            && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
            && this.timestampSeconds == tickerInfo.getTimestampSeconds()
            && this.marketCap == tickerInfo.getMarketCap();
    }
}