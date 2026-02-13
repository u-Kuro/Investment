package com.example.investment.model;

import com.example.investment.model.ticker.TickerData;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record TickerDataRecord(List<TickerData> tickers, long timestamp) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}