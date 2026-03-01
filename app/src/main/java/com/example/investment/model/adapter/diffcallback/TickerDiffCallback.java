package com.example.investment.model.adapter.diffcallback;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.DiffUtil;

import com.example.investment.model.ticker.TickerData;

import java.util.List;

public class TickerDiffCallback extends DiffUtil.Callback {
    private final String oldInvestmentType;
    private final @NonNull List<TickerData> oldTickers;
    private final @NonNull String investmentType;
    private final @NonNull List<TickerData> newTickers;

    public TickerDiffCallback(
        String oldInvestmentType, @NonNull List<TickerData> oldTickers,
        @NonNull String investmentType, @NonNull List<TickerData> newTickers
    ) {
        this.oldInvestmentType = oldInvestmentType;
        this.oldTickers = oldTickers;
        this.investmentType = investmentType;
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

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        TickerData oldTicker = oldTickers.get(oldItemPosition);
        TickerData newTicker = newTickers.get(newItemPosition);
        
        if (oldTicker == null || newTicker == null) {
            return false;
        }

        if (investmentType.equals(oldInvestmentType)) {
            return oldTicker.customEquals(investmentType, newTicker);
        }

        return false;
    }
} 