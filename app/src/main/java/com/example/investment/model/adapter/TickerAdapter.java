package com.example.investment.model.adapter;

import static com.example.investment.util.Constants.ALL_TYPE;

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
    private Future<?> currentTask;
    private volatile Object currentToken = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private @NonNull List<TickerData> tickers = new ArrayList<>();
    private @NonNull List<TickerData> originalTickers = new ArrayList<>();
    private @NonNull String assetTypeFilter;
    private @NonNull String investmentType;
    private String oldInvestmentType;
    private @NonNull BigDecimal marketCapThreshold;
    private @NonNull BigDecimal dailyDollarVolumeThreshold;
    private @NonNull BigDecimal ageInSecondsThreshold;
    private String searchQuery;
    private @NonNull final OnTickerClickListener clickListener;
    private @NonNull final OnTickerLongClickListener longClickListener;

    public TickerAdapter(
        @NonNull String assetTypeFilter,
        @NonNull String investmentType,
        @NonNull BigDecimal marketCapThreshold,
        @NonNull BigDecimal dailyDollarVolumeThreshold,
        @NonNull BigDecimal ageInSecondsThreshold,
        @NonNull OnTickerClickListener clickListener,
        @NonNull OnTickerLongClickListener longClickListener
    ) {
        this.assetTypeFilter = assetTypeFilter;
        this.investmentType = investmentType;
        this.marketCapThreshold = marketCapThreshold;
        this.dailyDollarVolumeThreshold = dailyDollarVolumeThreshold;
        this.ageInSecondsThreshold = ageInSecondsThreshold;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
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
    public void setTickers(@NonNull List<TickerData> newOriginalTickers) {
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
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }

        final Object token = new Object();
        currentToken = token;

        @NonNull final List<TickerData> originalSnapshot = new ArrayList<>(originalTickers);
        @NonNull final List<TickerData> oldTickersSnapshot = new ArrayList<>(tickers);
        @NonNull final String currentInvestmentType = investmentType;
        final String oldType = this.oldInvestmentType;
        @NonNull final String filterAssetType = this.assetTypeFilter;
        final String filterSearchQuery = this.searchQuery;
        @NonNull final BigDecimal filterMarketCap = this.marketCapThreshold;
        @NonNull final BigDecimal filterDailyVolume = this.dailyDollarVolumeThreshold;
        @NonNull final BigDecimal filterAge = this.ageInSecondsThreshold;

        currentTask = executor.submit(() -> {
            try {
                @NonNull List<TickerData> currentFilteredSnapshot = originalSnapshot.stream()
                    .filter(ticker -> {
                        boolean typeMatch = ALL_TYPE.equals(filterAssetType)
                            || filterAssetType.equals(ticker.getType());
                        if (!typeMatch) return false;

                        boolean searchMatch = true;
                        if (filterSearchQuery != null && !filterSearchQuery.trim().isEmpty()) {
                            searchMatch = false;
                            String query = filterSearchQuery.trim();
                            boolean exactMatch = query.startsWith("\"") && query.endsWith("\"");

                            if (exactMatch) {
                                String exactQuery = query.substring(1, query.length() - 1);
                                if (!exactQuery.isEmpty()) {
                                    String symbol = ticker.getSymbol();
                                    String name = ticker.getName(false);
                                    String exchange = ticker.getExchange();
                                    searchMatch = symbol.contains(exactQuery)
                                        || (name != null && name.contains(exactQuery))
                                        || (exchange != null && exchange.contains(exactQuery));
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
                            ticker.getMarketCap().compareTo(filterMarketCap) >= 0
                            && ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(filterDailyVolume) >= 0
                            && ticker.getAgeInSeconds().compareTo(filterAge) >= 0
                        );
                    })
                    .toList();

                @NonNull DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
                    new TickerDiffCallback(
                        oldType, oldTickersSnapshot,
                        currentInvestmentType, currentFilteredSnapshot
                    ),
                    true
                );

                mainHandler.post(() -> {
                    if (token == currentToken) {
                        tickers = new ArrayList<>(currentFilteredSnapshot);
                        this.oldInvestmentType = currentInvestmentType;
                        diffResult.dispatchUpdatesTo(TickerAdapter.this);
                    }
                });
            } catch (Exception ignored) {}
        });
    }
}