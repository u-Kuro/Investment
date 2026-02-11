package com.example.investment.model;

import com.example.investment.model.ticker.TickerInfo;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;

public record CachedTickerData(ArrayList<TickerInfo> tickers, long timestamp) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}