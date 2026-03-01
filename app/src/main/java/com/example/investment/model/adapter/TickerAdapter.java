package com.example.investment.model.adapter;

import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.PASSIVE_INVESTMENT_TYPE;

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

public class TickerAdapter extends RecyclerView.Adapter<TickerViewHolder> {
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private Future<?> future;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private List<TickerData> tickers = new ArrayList<>();
    private List<TickerData> originalTickers = new ArrayList<>();
    private String assetTypeFilter;
    private @NonNull String investmentType = PASSIVE_INVESTMENT_TYPE;
    private String oldInvestmentType;
    private BigDecimal marketCapThreshold;
    private BigDecimal dailyDollarVolumeThreshold;
    private BigDecimal ageInSecondsThreshold;
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
        holder.bind(ticker, investmentType);
    }

    @Override
    public int getItemCount() {
        return tickers.size();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setTickers(List<TickerData> newOriginalTickers) {
        mainHandler.post(() -> {
            originalTickers = new ArrayList<>(newOriginalTickers);
            originalTickers.sort((a, b) -> b.getPercentageIncreasePerDay(investmentType).compareTo(a.getPercentageIncreasePerDay(investmentType)));
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setAssetTypeFilter(@NonNull String assetTypeFilter) {
        mainHandler.post(() -> {
            this.assetTypeFilter = assetTypeFilter;
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setInvestmentType(@NonNull String investmentType) {
        mainHandler.post(() -> {
            this.investmentType = investmentType;
            originalTickers.sort((a, b) -> b.getPercentageIncreasePerDay(investmentType).compareTo(a.getPercentageIncreasePerDay(investmentType)));
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setMarketCapThreshold(@NonNull BigDecimal marketCapThreshold) {
        mainHandler.post(() -> {
            this.marketCapThreshold = marketCapThreshold;
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setDailyDollarVolumeThreshold(@NonNull BigDecimal dailyDollarVolumeThreshold) {
        mainHandler.post(() -> {
            this.dailyDollarVolumeThreshold = dailyDollarVolumeThreshold;
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setAgeInSecondsThreshold(@NonNull BigDecimal ageInSecondsThreshold) {
        mainHandler.post(() -> {
            this.ageInSecondsThreshold = ageInSecondsThreshold;
            applyFilters();
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
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

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void applyFilters() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        future = executor.submit(() -> {
            try {
                List<TickerData> filteredList = originalTickers.stream()
                    .filter(ticker -> {
                        boolean typeMatch = ALL_TYPE.equals(assetTypeFilter) || assetTypeFilter.equals(ticker.getType());
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

                        TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord = ticker.getNewestFixedHistoricalDataWithInterval();
                        return (
                            // Market cap is above or equal to threshold
                            ticker.getMarketCap().compareTo(marketCapThreshold) >= 0
                            // Daily dollar volume is above or equal to threshold
                            && ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(dailyDollarVolumeThreshold) >= 0
                            // Age is above or equal to threshold
                            && ticker.getAgeInSeconds().compareTo(ageInSecondsThreshold) >= 0
                        );
                    })
                    .toList();
                mainHandler.post(() -> setFilteredTickers(investmentType, filteredList));
            } catch (Exception ignored) {}
        });
    }

    private void setFilteredTickers(@NonNull String investmentType, List<TickerData> filteredTickers) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
            new TickerDiffCallback(oldInvestmentType, tickers, investmentType, filteredTickers),
            true
        );
        tickers = new ArrayList<>(filteredTickers);
        oldInvestmentType = investmentType;
        diffResult.dispatchUpdatesTo(this);
    }
} 