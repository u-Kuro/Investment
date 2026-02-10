package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
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
        long marketCap
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
    }

    public @NonNull String getSymbol() {
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

    public double getUpperBand() {
        return upperBand;
    }

    public double getLowerBand() {
        return lowerBand;
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

    public @NonNull String getPriceState() {
        if (
            this.percentageIncreasePerDay <= 0
            || this.lastPrice < this.lowerBand
            || this.upperBand < this.lowerBand
        ) return PRICE_BELOW;
        if (this.lastPrice <= this.upperBand) return PRICE_BETWEEN;
        return PRICE_ABOVE;
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
            && this.marketCap == tickerInfo.getMarketCap();
    }
}