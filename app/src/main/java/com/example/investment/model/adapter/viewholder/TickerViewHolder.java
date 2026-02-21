package com.example.investment.model.adapter.viewholder;

import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.STOCK_TYPE;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.PriceUtils;
import com.google.android.material.card.MaterialCardView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;

public class TickerViewHolder extends RecyclerView.ViewHolder {
    private final TextView nameText;
    private final ImageView typeIconImage;
    private final TextView timeSpanText;
    private final TextView lastPriceText;
    private final TextView percentageIncreasePerDayText;
    private final TextView marketCapText;
    private final TextView upperBandText;
    private final TextView lowerBandText;
    private TickerData currentTicker;

    public TickerViewHolder(@NonNull View itemView, OnTickerClickListener listener, OnTickerLongClickListener longListener) {
        super(itemView);
        nameText = itemView.findViewById(R.id.nameText);
        typeIconImage = itemView.findViewById(R.id.typeIconImage);
        timeSpanText = itemView.findViewById(R.id.timeSpanText);
        lastPriceText = itemView.findViewById(R.id.lastPriceText);
        percentageIncreasePerDayText = itemView.findViewById(R.id.percentageIncreasePerDayText);
        marketCapText = itemView.findViewById(R.id.marketCapText);
        upperBandText = itemView.findViewById(R.id.upperBandText);
        lowerBandText = itemView.findViewById(R.id.lowerBandText);

        if (listener != null) {
            itemView.setOnClickListener(v -> listener.onTickerClick(currentTicker));
        }

        if (longListener != null) {
            itemView.setOnLongClickListener(v -> longListener.onTickerLongClick(currentTicker));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void bind(TickerData ticker) {
        this.currentTicker = ticker;
        boolean isDataStale = ticker.areAllFixedHistoricalDataStale();
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

        if (CRYPTOCURRENCY_TYPE.equals(ticker.getType())) {
            typeIconImage.setImageResource(R.drawable.crypto);
            typeIconImage.setVisibility(View.VISIBLE);
        } else if (STOCK_TYPE.equals(ticker.getType())) {
            typeIconImage.setImageResource(R.drawable.stock);
            typeIconImage.setVisibility(View.VISIBLE);
        } else {
            typeIconImage.setVisibility(View.GONE);
        }

        timeSpanText.setText(getTimeAgo(ticker.getFirstTradeTimestampSeconds()));
        lastPriceText.setText(PriceUtils.formatPrice(ticker.getLastAdjustedClose()));
        percentageIncreasePerDayText.setText(
            String.format(
                Locale.US,
                "%s%%",
                NumberUtils.formatNumber(ticker.getPercentageIncreasePerDay(), 2)
            )
        );

        marketCapText.setText(NumberUtils.formatNumber(ticker.getMarketCap()));
        upperBandText.setText(PriceUtils.formatPrice(ticker.getUpperBand()));
        lowerBandText.setText(PriceUtils.formatPrice(ticker.getLowerBand()));

        if (isDataStale) {
            nameText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.slate_grey));
        } else {
            nameText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

        if (ticker.getLastAdjustedClose().compareTo(ticker.getLowerBand()) < 0) {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else if (ticker.getPriceState().equals(PRICE_BETWEEN)) {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.profit_taking));
        } else {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }
        if (ticker.getPercentageIncreasePerDay().compareTo(BigDecimal.ZERO) <= 0) {
            percentageIncreasePerDayText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else {
            percentageIncreasePerDayText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

        if (ticker.getUpperBand().compareTo(ticker.getLowerBand()) < 0) {
            upperBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
            lowerBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else {
            upperBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
            lowerBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

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

    private int getCardColor(TickerData ticker) {
        int colorResId;

        @NonNull String priceState = ticker.getPriceState();
        if (priceState.equals(PRICE_BETWEEN)) {
            colorResId = R.color.profit_taking;
        } else if (priceState.equals(PRICE_ABOVE)) {
            colorResId = R.color.momentum;
        } else {
            colorResId = R.color.bad;
        }
        
        return itemView.getContext().getColor(colorResId);
    }
} 