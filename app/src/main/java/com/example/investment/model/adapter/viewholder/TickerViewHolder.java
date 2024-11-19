package com.example.investment.model.adapter.viewholder;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;
import com.example.investment.util.PriceUtils;
import com.google.android.material.card.MaterialCardView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    @RequiresApi(api = Build.VERSION_CODES.O)
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

        timeSpan.setText(formatTimeSpan(ticker.getDays()));
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    private String formatTimeSpan(double days) {
        LocalDate startDate = LocalDate.now().minusDays((long) days);
        return startDate.format(DateTimeFormatter.ofPattern("MMM dd yyyy"));
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