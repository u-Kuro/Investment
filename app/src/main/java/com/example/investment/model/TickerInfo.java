package com.example.investment.model;

import java.io.Serializable;
import java.util.Objects;

public class TickerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String symbol;
    private final String type;
    private final String name;
    private final long days;
    private final double percentageIncreasePerDay;

    public TickerInfo(String symbol, String type, String name, double percentageIncreasePerDay, long days) {
        this.symbol = symbol;
        this.type = type;
        this.name = name;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.days = days;
    }

    // Getters
    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public String getName() { return name; }
    public double getPercentageIncreasePerDay() { return percentageIncreasePerDay; }
    public long getDays() { return days; }
    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
                && Objects.equals(this.name, tickerInfo.getName())
                && Objects.equals(this.type, tickerInfo.getType())
                && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
                && this.days == tickerInfo.getDays();
    }
} 