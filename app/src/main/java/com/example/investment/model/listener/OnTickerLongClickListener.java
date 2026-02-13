package com.example.investment.model.listener;

import com.example.investment.model.ticker.TickerData;

public interface OnTickerLongClickListener {
    /** @noinspection SameReturnValue*/
    boolean onTickerLongClick(TickerData ticker);
} 