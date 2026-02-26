package com.example.investment.model.adapter.diffcallback;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.example.investment.model.ticker.TickerData;

import java.util.List;

public class TickerDiffCallback extends DiffUtil.Callback {
    private final @NonNull List<TickerData> oldTickers;
    private final @NonNull List<TickerData> newTickers;

    public TickerDiffCallback(@NonNull List<TickerData> oldTickers, @NonNull List<TickerData> newTickers) {
        this.oldTickers = oldTickers;
        this.newTickers = newTickers;
    }

    @Override
    public int getOldListSize() {
        return oldTickers.size();
    }

    @Override
    public int getNewListSize() {
        return newTickers.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        TickerData oldTicker = oldTickers.get(oldItemPosition);
        TickerData newTicker = newTickers.get(newItemPosition);
        
        if (oldTicker == null || newTicker == null) {
            return false;
        }

        return oldTicker.getSymbol().equals(newTicker.getSymbol());
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        TickerData oldTicker = oldTickers.get(oldItemPosition);
        TickerData newTicker = newTickers.get(newItemPosition);
        
        if (oldTicker == null || newTicker == null) {
            return false;
        }

        return oldTicker.customEquals(newTicker);
    }
} 