package com.example.investment.model.adapter;

import static com.example.investment.util.Constants.PRICE_ABOVE;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.model.adapter.viewholder.TickerViewHolder;
import com.example.investment.model.adapter.diffcallback.TickerDiffCallback;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TickerAdapter extends RecyclerView.Adapter<TickerViewHolder> {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TickerInfo> tickers = new ArrayList<>();
    private final List<TickerInfo> originalTickers = new ArrayList<>();
    private String typeFilter = "All";
    private boolean statusFilter = false;
    private long marketCapThresholdFilter;
    private OnTickerClickListener clickListener;
    private OnTickerLongClickListener longClickListener;

    public void setOnTickerClickListener(OnTickerClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnTickerLongClickListener(OnTickerLongClickListener listener) {
        this.longClickListener = listener;
    }

    @NonNull
    @Override
    public TickerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TickerViewHolder(
            LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ticker, parent, false),
            clickListener,
            longClickListener
        );
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onBindViewHolder(@NonNull TickerViewHolder holder, int position) {
        TickerInfo ticker = tickers.get(position);
        holder.bind(ticker);
    }

    @Override
    public int getItemCount() {
        return tickers.size();
    }

    public void setTickers(List<TickerInfo> newOriginalTickers) {
        mainHandler.post(() -> {
            originalTickers.clear();
            originalTickers.addAll(newOriginalTickers);
            applyFilters();
        });
    }

    public void setTypeFilter(String type) {
        mainHandler.post(() -> {
            this.typeFilter = type;
            applyFilters();
        });
    }

    public void setStatusFilter(boolean status) {
        mainHandler.post(() -> {
            this.statusFilter = status;
            applyFilters();
        });
    }

    public void setMarketCapThresholdFilter(long marketCapThreshold) {
        mainHandler.post(() -> {
            this.marketCapThresholdFilter = marketCapThreshold;
            applyFilters();
        });
    }

    private void applyFilters() {
        List<TickerInfo> filteredList = originalTickers.stream()
                .filter(ticker -> {
                    boolean typeMatch = typeFilter.equals("All")
                        || typeFilter.equals("Crypto") && "cryptocurrency".equalsIgnoreCase(ticker.getType())
                        || typeFilter.equals("Stock") && "equity".equalsIgnoreCase(ticker.getType());

                    boolean statusMatch = !statusFilter
                        || ticker.getPriceState().equals(PRICE_ABOVE);

                    boolean marketCapThresholdMatch = ticker.getMarketCap() >= marketCapThresholdFilter;

                    return typeMatch && statusMatch && marketCapThresholdMatch;
                })
                .collect(Collectors.toList());

        setFilteredTickers(filteredList);
    }

    private void setFilteredTickers(List<TickerInfo> filteredTickers) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
            new TickerDiffCallback(tickers, filteredTickers),
            true
        );

        tickers.clear();
        tickers.addAll(filteredTickers);

        diffResult.dispatchUpdatesTo(this);
    }
} 