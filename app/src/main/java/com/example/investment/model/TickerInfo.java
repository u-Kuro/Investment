package com.example.investment.model;

import java.io.Serializable;
import java.util.Objects;

public class TickerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String symbol;
    private final String name;
    private final String type;
    private final double lastPrice;
    private final double densePrice;
    private final double upperPriceBoundary;
    private final double lowerPriceBoundary;
    private final double percentageIncreasePerDay;
    private final long days;
    public TickerInfo(
        String symbol,
        String name,
        String type,
        double lastPrice,
        double densePrice,
        double upperPriceBoundary,
        double lowerPriceBoundary,
        double percentageIncreasePerDay,
        long days
    ) {
        this.symbol = symbol;
        this.name = name;
        this.type = type;
        this.lastPrice = lastPrice;
        this.densePrice = densePrice;
        this.upperPriceBoundary = upperPriceBoundary;
        this.lowerPriceBoundary = lowerPriceBoundary;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.days = days;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
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

    public long getDays() {
        return days;
    }

    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
                && Objects.equals(this.name, tickerInfo.getName())
                && Objects.equals(this.type, tickerInfo.getType())
                && this.lastPrice == tickerInfo.getLastPrice()
                && this.densePrice == tickerInfo.getDensePrice()
                && this.upperPriceBoundary == tickerInfo.getUpperPriceBoundary()
                && this.lowerPriceBoundary == tickerInfo.getLowerPriceBoundary()
                && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
                && this.days == tickerInfo.getDays();
    }
} 