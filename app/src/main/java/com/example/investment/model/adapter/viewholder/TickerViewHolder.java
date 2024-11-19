package com.example.investment.model.adapter.viewholder;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;
import com.example.investment.util.PriceUtils;
import com.google.android.material.card.MaterialCardView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TickerViewHolder extends RecyclerView.ViewHolder {
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

    public void bind(TickerInfo ticker) {
        this.currentTicker = ticker;
        String name = ticker.getName(true);
        if (name == null || name.isEmpty()) {
            nameText.setText(R.string.na);
        } else {
            nameText.setText(name);
        }
        if ("cryptocurrency".equalsIgnoreCase(ticker.getType())) {
            typeIcon.setImageResource(R.drawable.crypto);
            typeIcon.setVisibility(View.VISIBLE);
        } else if ("equity".equalsIgnoreCase(ticker.getType())) {
            typeIcon.setImageResource(R.drawable.stock);
            typeIcon.setVisibility(View.VISIBLE);
        } else {
            typeIcon.setVisibility(View.GONE);
        }

        timeSpan.setText(formatTimeSpan(ticker.getTimestampSeconds()));
        percentageIncreasePerDay.setText(String.format(Locale.US, "%.2f%% | D", ticker.getPercentageIncreasePerDay()));

        upperPriceBoundaryText.setText(PriceUtils.formatPrice(ticker.getUpperPriceBoundary()));
        densePriceText.setText(PriceUtils.formatPrice(ticker.getDensePrice()));
        lowerPriceBoundaryText.setText(PriceUtils.formatPrice(ticker.getLowerPriceBoundary()));

        MaterialCardView cardView = (MaterialCardView) itemView;
        cardView.setStrokeColor(getCardColor(
            ticker.getUpperPriceBoundary(),
            ticker.getLowerPriceBoundary(),
            ticker.getLastPrice()
        ));
    }

    private String formatTimeSpan(long timestampSeconds) {
        if (timestampSeconds == 0) {
            return itemView.getContext().getString(R.string.na);
        }
        Date date = new Date(timestampSeconds * 1000L); // Convert seconds to milliseconds
        SimpleDateFormat formatter = new SimpleDateFormat("MMM d yyyy", Locale.US);
        return formatter.format(date);
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