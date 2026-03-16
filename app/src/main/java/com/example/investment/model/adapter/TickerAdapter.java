package com.example.investment.model.adapter;

import static com.example.investment.util.Constants.ALL_TYPE;
import static com.example.investment.util.Constants.EVENT_THREAD;
import static com.example.investment.util.SafeSingleTaskRunner.ensureActive;

import android.os.Build;
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
import java.util.List;

public class TickerAdapter extends RecyclerView.Adapter<TickerViewHolder> {
    private @NonNull List<TickerData> shownTickers = List.of();
    private @NonNull List<TickerData> originalTickers = List.of();
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
    public void onBindViewHolder(final @NonNull TickerViewHolder holder, final int position) {
        final @NonNull TickerData ticker = shownTickers.get(position);
        holder.bind(position, ticker, investmentType);
    }

    @Override
    public int getItemCount() {
        return shownTickers.size();
    }

    private final Object SET_TICKERS_TASK_ID = new Object();
    private Runnable pendingInitialCallback = null;
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setInitialTickers(final @NonNull List<TickerData> newTickers, final @NonNull Runnable initialCallback) {
        pendingInitialCallback = initialCallback;
        setTickers(newTickers);
    }
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setTickers(final @NonNull List<TickerData> newTickers) {
        EVENT_THREAD.replaceUniqueTask(SET_TICKERS_TASK_ID, ui -> {
            final @NonNull List<TickerData> newOriginalTickers = List.copyOf(newTickers);
            ui.post(() -> {
                originalTickers = newOriginalTickers;
                updateShownTickers();
            });
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setAssetTypeFilter(@NonNull String assetTypeFilter) {
        this.assetTypeFilter = assetTypeFilter;
        updateShownTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setInvestmentType(@NonNull String investmentType) {
        this.investmentType = investmentType;
        updateShownTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setMarketCapThreshold(@NonNull BigDecimal marketCapThreshold) {
        this.marketCapThreshold = marketCapThreshold;
        updateShownTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setDailyDollarVolumeThreshold(@NonNull BigDecimal dailyDollarVolumeThreshold) {
        this.dailyDollarVolumeThreshold = dailyDollarVolumeThreshold;
        updateShownTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setAgeInSecondsThreshold(@NonNull BigDecimal ageInSecondsThreshold) {
        this.ageInSecondsThreshold = ageInSecondsThreshold;
        updateShownTickers();
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void setSearchQuery(String query) {
        this.searchQuery = query;
        updateShownTickers();
    }

    private String cleanString(String string) {
        if (string == null) return "";
        return string.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    final Object UPDATE_SHOWN_TICKERS_TASK_ID = new Object();
    final Object UPDATE_SHOWN_TICKERS_SNAPSHOT_TASK_ID = new Object();
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void updateShownTickers() {
        EVENT_THREAD.replaceUniqueTask(UPDATE_SHOWN_TICKERS_SNAPSHOT_TASK_ID, () -> {
            @NonNull final List<TickerData> finalOriginalTickers = List.copyOf(originalTickers);
            ensureActive();
            @NonNull final List<TickerData> finalOldShownTickers = List.copyOf(shownTickers);
            @NonNull final String finalInvestmentType = investmentType;
            final String finalOldInvestmentType = oldInvestmentType;
            @NonNull final String finalAssetTypeFilter = assetTypeFilter;
            final String finalSearchQuery = searchQuery;
            @NonNull final BigDecimal finalMarketCapThreshold = marketCapThreshold;
            @NonNull final BigDecimal filterDailyDollarVolumeThreshold = dailyDollarVolumeThreshold;
            @NonNull final BigDecimal finalAgeInSecondsThreshold = ageInSecondsThreshold;

            EVENT_THREAD.replaceUniqueTask(UPDATE_SHOWN_TICKERS_TASK_ID, ui -> {
                final @NonNull List<TickerData> newShownTickers = finalOriginalTickers.stream()
                    .filter(ticker -> {
                        boolean typeMatch = ALL_TYPE.equals(finalAssetTypeFilter) || finalAssetTypeFilter.equals(ticker.getType());
                        if (!typeMatch) return false;

                        boolean searchMatch = true;
                        if (finalSearchQuery != null && !finalSearchQuery.trim().isEmpty()) {
                            searchMatch = false;
                            String query = finalSearchQuery.trim();
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
                            ticker.getMarketCap().compareTo(finalMarketCapThreshold) >= 0
                            && ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord).compareTo(filterDailyDollarVolumeThreshold) >= 0
                            && ticker.getAgeInSeconds().compareTo(finalAgeInSecondsThreshold) >= 0
                        );
                    })
                    .sorted((a, b) -> b.getPercentageIncreasePerDay(finalInvestmentType).compareTo(a.getPercentageIncreasePerDay(finalInvestmentType)))
                    .toList();

                ensureActive();
                final @NonNull DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
                    new TickerDiffCallback(
                        finalOldInvestmentType, finalOldShownTickers,
                        finalInvestmentType, newShownTickers
                    ),
                    !finalInvestmentType.equals(finalOldInvestmentType) // Item sort changes
                );

                ui.post(() -> {
                    shownTickers = newShownTickers;
                    oldInvestmentType = finalInvestmentType;
                    diffResult.dispatchUpdatesTo(TickerAdapter.this);

                    if (pendingInitialCallback != null) {
                        pendingInitialCallback.run();
                        pendingInitialCallback = null;
                    }
                });
            });
        });
    }
}