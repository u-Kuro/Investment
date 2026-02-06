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
import com.example.investment.util.Constants;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.PriceUtils;
import com.google.android.material.card.MaterialCardView;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

public class TickerViewHolder extends RecyclerView.ViewHolder {
    private final TextView nameText;
    private final ImageView typeIcon;
    private final TextView timeSpan;
    private final TextView lastPrice;
    private final TextView percentageIncreasePerDay;
    private final TextView marketCapText;
    private final TextView upperPriceBoundaryText;
    private final TextView densePriceText;
    private TickerInfo currentTicker;

    public TickerViewHolder(@NonNull View itemView, OnTickerClickListener listener, OnTickerLongClickListener longListener) {
        super(itemView);
        nameText = itemView.findViewById(R.id.nameText);
        typeIcon = itemView.findViewById(R.id.typeIcon);
        timeSpan = itemView.findViewById(R.id.timeSpan);
        lastPrice = itemView.findViewById(R.id.lastPrice);
        percentageIncreasePerDay = itemView.findViewById(R.id.percentageIncreasePerDay);
        marketCapText = itemView.findViewById(R.id.marketCapText);
        upperPriceBoundaryText = itemView.findViewById(R.id.upperPriceBoundaryText);
        densePriceText = itemView.findViewById(R.id.densePriceText);

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
        String symbol = ticker.getSymbol();
        String name = ticker.getName(false);
        if (name == null || name.isEmpty()) {
            name = symbol;
        } else {
            name = String.format("%s | %s", symbol, name);
        }
        String exchange = ticker.getExchange();
        if (exchange == null || exchange.isEmpty()) {
            nameText.setText(name);
        } else {
            nameText.setText(String.format("%s | %s ", name, exchange));
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

        timeSpan.setText(getTimeAgo(ticker.getFirstTradeTimestampSeconds()));
        lastPrice.setText(PriceUtils.formatPrice(ticker.getLastPrice()));
        percentageIncreasePerDay.setText(
            String.format(
                Locale.US,
                "%s%%",
                NumberUtils.formatNumber(ticker.getPercentageIncreasePerDay(), 2)
            )
        );

        marketCapText.setText(NumberUtils.formatNumber(ticker.getMarketCap()));
        upperPriceBoundaryText.setText(PriceUtils.formatPrice(ticker.getUpperPriceBoundary()));
        densePriceText.setText(PriceUtils.formatPrice(ticker.getDensePrice()));

        MaterialCardView cardView = (MaterialCardView) itemView;
        cardView.setStrokeColor(getCardColor(ticker));
    }

    /** @noinspection unused*/
    private String formatTimeSpan(long timestampSeconds) {
        if (timestampSeconds == 0) {
            return itemView.getContext().getString(R.string.na);
        }
        Date date = new Date(timestampSeconds * 1000L); // Convert seconds to milliseconds
        SimpleDateFormat formatter = new SimpleDateFormat("MMM d yyyy", Locale.US);
        return formatter.format(date);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static String getTimeAgo(long timestampSeconds) {
        LocalDateTime now = LocalDateTime.now();
        Instant pastInstant = Instant.ofEpochSecond(timestampSeconds);
        LocalDateTime past = pastInstant.atZone(ZoneId.systemDefault()).toLocalDateTime();

        long years = ChronoUnit.YEARS.between(past, now);
        if (years > 0) return years + (years == 1 ? " year" : " years");

        long months = ChronoUnit.MONTHS.between(past, now);
        if (months > 0) return months + (months == 1 ? " month" : " months");

        long days = ChronoUnit.DAYS.between(past, now);
        if (days > 0) return days + (days == 1 ? " day" : " days");

        long hours = ChronoUnit.HOURS.between(past, now);
        if (hours > 0) return hours + (hours == 1 ? " hour" : " hours");

        long minutes = ChronoUnit.MINUTES.between(past, now);
        if (minutes > 0) return minutes + (minutes == 1 ? " minute" : " minutes");

        long seconds = ChronoUnit.SECONDS.between(past, now);
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    private int getCardColor(TickerInfo ticker) {
        int colorResId;

        @NonNull String priceState = PriceUtils.getPriceState(ticker);
        if (priceState.equals(Constants.PRICE_BETWEEN)) {
            colorResId = R.color.profit_taking;
        } else if (priceState.equals(Constants.PRICE_ABOVE)) {
            colorResId = R.color.momentum;
        } else {
            colorResId = R.color.bad;
        }
        
        return itemView.getContext().getColor(colorResId);
    }
} 