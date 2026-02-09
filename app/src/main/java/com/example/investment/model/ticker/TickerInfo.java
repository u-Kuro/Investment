package com.example.investment.model.ticker;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String symbol;
    private final String exchange;
    private final String name;
    private final String type;
    private final double lastPrice;
    private final double densePrice;
    private final double stopLossPrice;
    private final double percentageIncreasePerDay;
    private final long firstTradeTimestampSeconds;
    private final long marketCap;
    public TickerInfo(
        String symbol,
        String exchange,
        String name,
        String type,
        double lastPrice,
        double densePrice,
        double stopLossPrice,
        double percentageIncreasePerDay,
        long firstTradeTimestampSeconds,
        long marketCap
    ) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.name = name;
        this.type = type;
        this.lastPrice = lastPrice;
        this.densePrice = densePrice;
        this.stopLossPrice = stopLossPrice;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.firstTradeTimestampSeconds = firstTradeTimestampSeconds;
        this.marketCap = marketCap;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getExchange() {
        return exchange;
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

    public double getStopLossPrice() {
        return stopLossPrice;
    }

    public double getPercentageIncreasePerDay() {
        return percentageIncreasePerDay;
    }

    public long getFirstTradeTimestampSeconds() {
        return firstTradeTimestampSeconds;
    }

    public long getMarketCap() {
        return marketCap;
    }

    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
            && Objects.equals(this.exchange, tickerInfo.getExchange())
            && Objects.equals(this.name, tickerInfo.getName(false))
            && Objects.equals(this.type, tickerInfo.getType())
            && this.lastPrice == tickerInfo.getLastPrice()
            && this.densePrice == tickerInfo.getDensePrice()
            && this.stopLossPrice == tickerInfo.getStopLossPrice()
            && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
            && this.firstTradeTimestampSeconds == tickerInfo.getFirstTradeTimestampSeconds()
            && this.marketCap == tickerInfo.getMarketCap();
    }
}