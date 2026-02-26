package com.example.investment.model.adapter;

import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.STOCK_TYPE;

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
import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.adapter.viewholder.TickerViewHolder;
import com.example.investment.model.adapter.diffcallback.TickerDiffCallback;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class TickerAdapter extends RecyclerView.Adapter<TickerViewHolder> {
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private Future<?> future;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TickerData> tickers = new ArrayList<>();
    private final List<TickerData> originalTickers = new ArrayList<>();
    private String typeFilter = "All";
    private BigDecimal marketCapThresholdFilter;
    private BigDecimal dailyDollarVolumeThresholdFilter;
    private BigDecimal ageInSecondsThresholdFilter;
    private String searchQuery = "";
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

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    public void onBindViewHolder(@NonNull TickerViewHolder holder, int position) {
        TickerData ticker = tickers.get(position);
        holder.bind(ticker);
    }

    @Override
    public int getItemCount() {
        return tickers.size();
    }

    public void setTickers(List<TickerData> newOriginalTickers) {
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

    public void setMarketCapThresholdFilter(@NonNull BigDecimal marketCapThreshold) {
        mainHandler.post(() -> {
            this.marketCapThresholdFilter = marketCapThreshold;
            applyFilters();
        });
    }

    public void setDailyDollarVolumeThresholdFilter(@NonNull BigDecimal dailyDollarVolumeThreshold) {
        mainHandler.post(() -> {
            this.dailyDollarVolumeThresholdFilter = dailyDollarVolumeThreshold;
            applyFilters();
        });
    }

    public void setAgeInSecondsThresholdFilter(@NonNull BigDecimal ageInSecondsThreshold) {
        mainHandler.post(() -> {
            this.ageInSecondsThresholdFilter = ageInSecondsThreshold;
            applyFilters();
        });
    }

    public void setSearchQuery(String query) {
        mainHandler.post(() -> {
            this.searchQuery = query;
            applyFilters();
        });
    }

    private String cleanString(String string) {
        if (string == null) return "";
        return string.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private void applyFilters() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        future = executor.submit(() -> {
            try {
                List<TickerData> filteredList = originalTickers.stream()
                    .filter(ticker -> {

                        boolean typeMatch = typeFilter.equals("All")
                            || typeFilter.equals("Crypto") && CRYPTOCURRENCY_TYPE.equals(ticker.getType())
                            || typeFilter.equals("Stock") && STOCK_TYPE.equals(ticker.getType());
                        if (!typeMatch) return false;

                        boolean searchMatch = true;
                        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                            searchMatch = false;

                            String query = searchQuery.trim();
                            boolean exactMatch = query.startsWith("\"") && query.endsWith("\"");

                            if (exactMatch) {
                                String exactQuery = query.substring(1, query.length() - 1);
                                if (!exactQuery.isEmpty()) {
                                    String symbol = ticker.getSymbol();
                                    String name = ticker.getName(false);
                                    String exchange = ticker.getExchange();

                                    searchMatch = symbol.contains(exactQuery)
                                        || (name != null && name.contains(exactQuery))
                                        || (exchange != null &&exchange.contains(exactQuery));
                                }
                            } else {
                                String cleanedQuery = cleanString(query).toLowerCase();
                                if (!cleanedQuery.isEmpty()) {
                                    String cleanedSymbol = cleanString(ticker.getSymbol()).toLowerCase();
                                    String cleanedName = cleanString(ticker.getName(false)).toLowerCase();
                                    String cleanedExchange = cleanString(ticker.getExchange()).toLowerCase();

                                    searchMatch = cleanedSymbol.contains(cleanedQuery)
                                        || cleanedName.contains(cleanedQuery)
                                        || cleanedExchange.contains(cleanedQuery);
                                }
                            }
                        }
                        if (!searchMatch) return false;

                        boolean marketCapThresholdMatch = ticker.getMarketCap().compareTo(marketCapThresholdFilter) >= 0;
                        if (!marketCapThresholdMatch) return false;

                        boolean ageInSecondsThresholdMatch = ticker.getAgeInSeconds().compareTo(ageInSecondsThresholdFilter) >= 0;
                        if (!ageInSecondsThresholdMatch) return false;

                        // Daily Dollar Volume Threshold Match
                        return ticker.getDailyDollarVolume().compareTo(dailyDollarVolumeThresholdFilter) >= 0;
                    })
                    .collect(Collectors.toList());
                mainHandler.post(() -> setFilteredTickers(filteredList));
            } catch (Exception ignored) {}
        });
    }

    private void setFilteredTickers(List<TickerData> filteredTickers) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
            new TickerDiffCallback(tickers, filteredTickers),
            true
        );

        tickers.clear();
        tickers.addAll(filteredTickers);

        diffResult.dispatchUpdatesTo(this);
    }
} 