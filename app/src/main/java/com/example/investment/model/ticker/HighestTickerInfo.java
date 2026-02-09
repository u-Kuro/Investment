package com.example.investment.model.ticker;

import androidx.annotation.NonNull;

import java.io.Serial;
import java.io.Serializable;

public class HighestTickerInfo extends TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final @NonNull String priceState;
    public HighestTickerInfo(@NonNull TickerInfo tickerInfo, @NonNull String priceState) {
        super(tickerInfo.getSymbol(), tickerInfo.getExchange(), tickerInfo.getName(false), tickerInfo.getType(), tickerInfo.getLastPrice(), tickerInfo.getDensePrice(), tickerInfo.getStopLossPrice(), tickerInfo.getPercentageIncreasePerDay(), tickerInfo.getFirstTradeTimestampSeconds(), tickerInfo.getMarketCap());
        this.priceState = priceState;
    }
    @NonNull
    public String getPriceState() { return priceState; }
}