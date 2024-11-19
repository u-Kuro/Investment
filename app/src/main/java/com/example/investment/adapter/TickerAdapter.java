package com.example.investment.adapter;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.investment.R;
import com.example.investment.adapter.diff.TickerDiffCallback;
import com.example.investment.model.TickerInfo;
import com.google.android.material.card.MaterialCardView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class TickerAdapter extends RecyclerView.Adapter<TickerAdapter.TickerViewHolder> {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TickerInfo> tickers = new ArrayList<>();
    private final List<TickerInfo> originalTickers = new ArrayList<>();
    private String typeFilter = "All";
    private boolean statusFilter = false;
    private OnTickerClickListener clickListener;
    private OnTickerLongClickListener longClickListener;

    // Add interfaces for both click events
    public interface OnTickerClickListener {
        void onTickerClick(TickerInfo ticker);
    }

    public interface OnTickerLongClickListener {
        /** @noinspection SameReturnValue*/
        boolean onTickerLongClick(TickerInfo ticker);
    }

    // Add setters for both listeners
    public void setOnTickerClickListener(OnTickerClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnTickerLongClickListener(OnTickerLongClickListener listener) {
        this.longClickListener = listener;
    }

    @NonNull
    @Override
    public TickerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ticker, parent, false);
        return new TickerViewHolder(view, clickListener, longClickListener);
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

    private void applyFilters() {
        List<TickerInfo> filteredList = originalTickers.stream()
                .filter(ticker -> {
                    // Type filter
                    boolean typeMatch = typeFilter.equals("All")
                            || typeFilter.equals("Crypto") && "cryptocurrency".equalsIgnoreCase(ticker.getType())
                            || typeFilter.equals("Stock") && "equity".equalsIgnoreCase(ticker.getType());

                    // Status filter
                    boolean statusMatch = !statusFilter
                            || (
                                ticker.getLastPrice() > ticker.getLowerPriceBoundary()
                                && ticker.getLastPrice() < ticker.getUpperPriceBoundary()
                            );

                    return typeMatch && statusMatch;
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

    public static class TickerViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameText;
        private final ImageView typeIcon;
        private final TextView timeSpan;
        private final TextView percentageIncreasePerDay;
        private final TextView upperPriceBoundaryText;
        private final TextView densePriceText;
        private final TextView lowerPriceBoundaryText;
        private TickerInfo currentTicker;

        public TickerViewHolder(@NonNull View itemView, OnTickerClickListener listener, OnTickerLongClickListener longListener) {
            super(itemView);
            nameText = itemView.findViewById(R.id.nameText);
            typeIcon = itemView.findViewById(R.id.typeIcon);
            timeSpan = itemView.findViewById(R.id.timeSpan);
            percentageIncreasePerDay = itemView.findViewById(R.id.percentageIncreasePerDay);
            upperPriceBoundaryText = itemView.findViewById(R.id.upperPriceBoundaryText);
            densePriceText = itemView.findViewById(R.id.densePriceText);
            lowerPriceBoundaryText = itemView.findViewById(R.id.lowerPriceBoundaryText);

            if (listener != null) {
                itemView.setOnClickListener(v -> listener.onTickerClick(currentTicker));
            }

            if (longListener != null) {
                itemView.setOnLongClickListener(v -> longListener.onTickerLongClick(currentTicker));
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        public void bind(TickerInfo ticker) {
            this.currentTicker = ticker;
            String name = ticker.getName();
            if (name == null || name.isEmpty()) {
                name = ticker.getSymbol();
            }
            nameText.setText(name);
            if ("cryptocurrency".equalsIgnoreCase(ticker.getType())) {
                typeIcon.setImageResource(R.drawable.crypto);
                typeIcon.setVisibility(View.VISIBLE);
            } else if ("equity".equalsIgnoreCase(ticker.getType())) {
                typeIcon.setImageResource(R.drawable.stock);
                typeIcon.setVisibility(View.VISIBLE);
            } else {
                typeIcon.setVisibility(View.GONE);
            }

            timeSpan.setText(formatTimeSpan(ticker.getDays()));
            percentageIncreasePerDay.setText(String.format(Locale.US, "%.2f%% | D", ticker.getPercentageIncreasePerDay()));

            upperPriceBoundaryText.setText(formatPrice(ticker.getUpperPriceBoundary()));
            densePriceText.setText(formatPrice(ticker.getDensePrice()));
            lowerPriceBoundaryText.setText(formatPrice(ticker.getLowerPriceBoundary()));

            // Get the MaterialCardView and set its stroke color
            MaterialCardView cardView = (MaterialCardView) itemView;

            // Get the base color based on price
            int baseColor = getCardColor(
                ticker.getUpperPriceBoundary(),
                ticker.getLowerPriceBoundary(),
                ticker.getLastPrice()
            );

            // Set stroke color
            cardView.setStrokeColor(baseColor);
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private String formatTimeSpan(double days) {
            // Format the start date
            LocalDate startDate = LocalDate.now().minusDays((long) days);
            return startDate.format(DateTimeFormatter.ofPattern("MMM dd yyyy"));
        }

        private String formatPrice(double price) {
            if (price == 0) return "$ 0";
            
            // Count significant digits needed for whole number part
            long wholeNumber = (long) price;
            
            if (wholeNumber == 0) {
                // For numbers < 1, count leading zeros after decimal
                String decimalStr = String.format(Locale.US, "%.20f", price).split("\\.")[1];
                int leadingZeros = 0;
                while (leadingZeros < decimalStr.length() && decimalStr.charAt(leadingZeros) == '0') {
                    leadingZeros++;
                }
                String formatPattern = "%." + (leadingZeros + 5) + "f";
                return "$ " + String.format(Locale.US, formatPattern, price);
            } else {
                // For numbers >= 1, handle as before
                long wholeNumberDigits = String.valueOf(wholeNumber).length();
                if (wholeNumberDigits >= 5) {
                    return "$ " + Math.floor(wholeNumber);
                } else {
                    long decimalsNeeded = Math.max(5 - wholeNumberDigits, 0);
                    String formatPattern = "%." + decimalsNeeded + "f";
                    return "$ " + String.format(Locale.US, formatPattern, price);
                }
            }
        }

        private int getCardColor(double upper, double lower, double lastPrice) {
            int colorResId;
            
            if (lastPrice >= upper) {
                colorResId = R.color.hold;
            } else if (lastPrice > lower) {
                colorResId = R.color.good;
            } else {
                colorResId = R.color.bad;
            }
            
            return itemView.getContext().getColor(colorResId);
        }
    }
} 