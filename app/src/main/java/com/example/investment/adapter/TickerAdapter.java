package com.example.investment.adapter;

import android.os.Build;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TickerAdapter extends RecyclerView.Adapter<TickerAdapter.TickerViewHolder> {
    private final List<TickerInfo> tickers = new ArrayList<>();
    private OnTickerClickListener clickListener;
    private OnTickerLongClickListener longClickListener;

    // Add interfaces for both click events
    public interface OnTickerClickListener {
        void onTickerClick(TickerInfo ticker);
    }

    public interface OnTickerLongClickListener {
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

    public void setTickers(List<TickerInfo> newTickers) {
        // Calculate the difference between old and new lists
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new TickerDiffCallback(this.tickers, newTickers), true);
        diffResult.dispatchUpdatesTo(this);

        this.tickers.clear();
        this.tickers.addAll(newTickers);
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

            String timeSpanText = formatTimeSpan(ticker.getDays());
            timeSpan.setText(timeSpanText);
            percentageIncreasePerDay.setText(String.format(Locale.US, "%.4f%%", ticker.getPercentageIncreasePerDay()));

            upperPriceBoundaryText.setText(String.format(Locale.US, "%.4e", ticker.getUpperPriceBoundary()));
            densePriceText.setText(String.format(Locale.US, "%.4e", ticker.getDensePrice()));
            lowerPriceBoundaryText.setText(String.format(Locale.US, "%.4e", ticker.getLowerPriceBoundary()));
        }

        private String formatTimeSpan(long days) {
            if (days < 7) {
                return days + (days == 1 ? " day" : " days");
            } 
            
            if (days < 14) {
                return "1 week " + (days - 7) + " days";
            }
            
            if (days < 30) {
                long weeks = days / 7;
                long remainingDays = days % 7;
                if (remainingDays > 0) {
                    return weeks + " weeks " + remainingDays + (remainingDays == 1 ? " day" : " days");
                }
                return weeks + " weeks";
            }
            
            if (days < 365) {
                long months = days / 30;
                long remainingDays = days % 30;
                if (remainingDays >= 7) {
                    long remainingWeeks = remainingDays / 7;
                    return months + (months == 1 ? " month " : " months ") +
                           remainingWeeks + (remainingWeeks == 1 ? " week" : " weeks");
                }
                if (remainingDays > 0) {
                    return months + (months == 1 ? " month " : " months ") +
                           remainingDays + (remainingDays == 1 ? " day" : " days");
                }
                return months + (months == 1 ? " month" : " months");
            }
            
            // For years
            long years = days / 365;
            long remainingDays = days % 365;
            
            if (remainingDays == 0) {
                return years + (years == 1 ? " year" : " years");
            }

            long months = remainingDays / 30;
            if (months > 0) {
                remainingDays = remainingDays % 30;
                String result = years + (years == 1 ? " year " : " years ") +
                              months + (months == 1 ? " month" : " months");
                
                // Add weeks for more precision if there are remaining days
                if (remainingDays >= 7) {
                    long weeks = remainingDays / 7;
                    return result + " " + weeks + (weeks == 1 ? " week" : " weeks");
                }
                
                return result;
            }
            
            // If less than a month of remaining days, show in weeks
            long weeks = remainingDays / 7;
            if (weeks > 0) {
                return years + (years == 1 ? " year " : " years ") +
                       weeks + (weeks == 1 ? " week" : " weeks");
            }
            
            return years + (years == 1 ? " year " : " years ") +
                   remainingDays + (remainingDays == 1 ? " day" : " days");
        }
    }
} 