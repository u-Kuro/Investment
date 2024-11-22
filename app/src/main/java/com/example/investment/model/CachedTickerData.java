package com.example.investment.model;

import com.example.investment.model.ticker.TickerInfo;

import java.io.Serializable;
import java.util.List;

public class CachedTickerData implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long timestamp;
    public final List<TickerInfo> data;
    
    public CachedTickerData(List<TickerInfo> data, long timestamp) {
        this.data = data;
        this.timestamp = timestamp;
    }
} 