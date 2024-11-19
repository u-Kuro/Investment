package com.example.investment.model.listener;

import com.example.investment.model.ticker.TickerInfo;

public interface OnTickerLongClickListener {
    /** @noinspection SameReturnValue*/
    boolean onTickerLongClick(TickerInfo ticker);
} 