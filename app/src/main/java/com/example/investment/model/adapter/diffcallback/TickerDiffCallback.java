package com.example.investment.model.adapter.diffcallback;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import com.example.investment.model.ticker.TickerInfo;
import java.util.List;

public class TickerDiffCallback extends DiffUtil.Callback {
    private final @NonNull List<TickerInfo> oldTickers;
    private final @NonNull List<TickerInfo> newTickers;

    public TickerDiffCallback(@NonNull List<TickerInfo> oldTickers, @NonNull List<TickerInfo> newTickers) {
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
        TickerInfo oldTicker = oldTickers.get(oldItemPosition);
        TickerInfo newTicker = newTickers.get(newItemPosition);
        
        if (oldTicker == null || newTicker == null) {
            return false;
        }
        return oldTicker.equals(newTicker);
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        TickerInfo oldTicker = oldTickers.get(oldItemPosition);
        TickerInfo newTicker = newTickers.get(newItemPosition);
        
        if (oldTicker == null || newTicker == null) {
            return false;
        }
        return oldTicker.equals(newTicker);
    }
} 