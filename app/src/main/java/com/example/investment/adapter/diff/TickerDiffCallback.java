package com.example.investment.adapter.diff;

import androidx.recyclerview.widget.DiffUtil;
import com.example.investment.model.TickerInfo;

import java.util.List;

public class TickerDiffCallback extends DiffUtil.Callback {
    private final List<TickerInfo> oldTickers;
    private final List<TickerInfo> newTickers;

    public TickerDiffCallback(List<TickerInfo> oldTickers, List<TickerInfo> newTickers) {
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
        return oldTickers.get(oldItemPosition).equals(newTickers.get(newItemPosition));
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        return oldTickers.get(oldItemPosition).equals(newTickers.get(newItemPosition));
    }
} 