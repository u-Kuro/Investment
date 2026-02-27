package com.example.investment.model.adapter.viewholder;

import static com.example.investment.util.BigDecimalUtils.formatNumber;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THOUSAND_BI;

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
import com.example.investment.util.PriceUtils;
import com.google.android.material.card.MaterialCardView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public class TickerViewHolder extends RecyclerView.ViewHolder {
    private final TextView nameText;
    private final ImageView typeIconImage;
    private final TextView marketCapText;
    private final TextView lastPriceText;
    private final TextView percentageIncreasePerDayText;
    private final TextView dailyDollarVolumeText;
    private final TextView upperBandText;
    private final TextView timeSpanText;
    private TickerData currentTicker;

    public TickerViewHolder(@NonNull View itemView, OnTickerClickListener listener, OnTickerLongClickListener longListener) {
        super(itemView);
        nameText = itemView.findViewById(R.id.nameText);
        typeIconImage = itemView.findViewById(R.id.typeIconImage);

        marketCapText = itemView.findViewById(R.id.marketCapText);
        lastPriceText = itemView.findViewById(R.id.lastPriceText);
        percentageIncreasePerDayText = itemView.findViewById(R.id.percentageIncreasePerDayText);

        dailyDollarVolumeText = itemView.findViewById(R.id.dailyDollarVolumeText);
        upperBandText = itemView.findViewById(R.id.upperBandText);
        timeSpanText = itemView.findViewById(R.id.timeSpanText);

        if (listener != null) {
            itemView.setOnClickListener(v -> listener.onTickerClick(currentTicker));
        }

        if (longListener != null) {
            itemView.setOnLongClickListener(v -> longListener.onTickerLongClick(currentTicker));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void bind(TickerData ticker) {
        this.currentTicker = ticker;
        TickerData.FixedHistoricalData newestFixedHistoricalData = ticker.getNewestFixedHistoricalData();

        boolean isDataStale = ticker.areAllFixedHistoricalDataStale();
        String symbol = ticker.getSymbol();
        String name = ticker.getName(false);
        if (name == null || name.isEmpty()) {
            name = symbol;
        } else {
            name = symbol + " | " + name;
        }
        String exchange = ticker.getExchange();
        if (exchange == null || exchange.isEmpty()) {
            nameText.setText(name);
        } else {
            nameText.setText(
                new StringBuilder()
                .append(name)
                .append(" | ")
                .append(exchange)
            );
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

        marketCapText.setText(PriceUtils.formatPrice(ticker.getMarketCap()));
        lastPriceText.setText(PriceUtils.formatPrice(ticker.getLastAdjustedClose()));
        percentageIncreasePerDayText.setText(
            new StringBuilder()
            .append(formatNumber(ticker.getPercentageIncreasePerDay(newestFixedHistoricalData)))
            .append("%")
        );

        dailyDollarVolumeText.setText(PriceUtils.formatPrice(ticker.getDailyDollarVolume()));
        upperBandText.setText(PriceUtils.formatPrice(ticker.getAdjustedUpperBand(newestFixedHistoricalData)));
        timeSpanText.setText(getTimeAgo(ticker.getFirstTradeTimestampSeconds()));

        if (isDataStale) {
            nameText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.slate_grey));
        } else {
            nameText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

        if (ticker.getLastAdjustedClose().compareTo(ticker.getAdjustedLowerBand(newestFixedHistoricalData)) < 0) {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else if (ticker.getPriceState(newestFixedHistoricalData).equals(PRICE_BETWEEN)) {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.profit_taking));
        } else {
            lastPriceText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }
        if (ticker.getPercentageIncreasePerDay(newestFixedHistoricalData).compareTo(BigDecimal.ZERO) <= 0) {
            percentageIncreasePerDayText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else {
            percentageIncreasePerDayText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

        if (ticker.getAdjustedUpperBand(newestFixedHistoricalData).compareTo(ticker.getAdjustedLowerBand(newestFixedHistoricalData)) < 0) {
            upperBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.bad));
        } else {
            upperBandText.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.white));
        }

        MaterialCardView cardView = (MaterialCardView) itemView;
        cardView.setStrokeColor(getCardColor(ticker, newestFixedHistoricalData));
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

    private static final BigInteger SECONDS_PER_MINUTE = new BigInteger("60");
    private static final BigInteger SECONDS_PER_HOUR = new BigInteger("3600");
    private static final BigInteger SECONDS_PER_DAY = new BigInteger("86400");
    private static final BigInteger AVG_SECONDS_PER_MONTH = new BigInteger("2629800"); // 30.44 days
    private static final BigInteger AVG_SECONDS_PER_YEAR = new BigInteger("31557600"); // 365.25 days
    @RequiresApi(api = Build.VERSION_CODES.S)
    public static String getTimeAgo(@NonNull BigInteger timestampSeconds) {
        try {
            LocalDateTime now = LocalDateTime.now();
            Instant pastInstant = Instant.ofEpochSecond(timestampSeconds.longValueExact());
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
        } catch (Exception ignored) {
            BigInteger nowMillis = BigInteger.valueOf(System.currentTimeMillis());
            BigInteger nowSeconds = nowMillis.divide(THOUSAND_BI);

            // Calculate difference in seconds
            BigInteger diffSeconds = nowSeconds.subtract(timestampSeconds);

            if (diffSeconds.compareTo(BigInteger.ZERO) < 0) {
                diffSeconds = BigInteger.ZERO;
            }

            // Calculate years
            BigInteger years = diffSeconds.divide(AVG_SECONDS_PER_YEAR);
            if (years.compareTo(BigInteger.ONE) >= 0) {
                return formatTimeUnit(years, "year", "years");
            }

            // Calculate months
            BigInteger months = diffSeconds.divide(AVG_SECONDS_PER_MONTH);
            if (months.compareTo(BigInteger.ONE) >= 0) {
                return formatTimeUnit(months, "month", "months");
            }

            // Calculate days
            BigInteger days = diffSeconds.divide(SECONDS_PER_DAY);
            if (days.compareTo(BigInteger.ONE) >= 0) {
                return formatTimeUnit(days, "day", "days");
            }

            // Calculate hours
            BigInteger hours = diffSeconds.divide(SECONDS_PER_HOUR);
            if (hours.compareTo(BigInteger.ONE) >= 0) {
                return formatTimeUnit(hours, "hour", "hours");
            }

            // Calculate minutes
            BigInteger minutes = diffSeconds.divide(SECONDS_PER_MINUTE);
            if (minutes.compareTo(BigInteger.ONE) >= 0) {
                return formatTimeUnit(minutes, "minute", "minutes");
            }

            // Return seconds
            return formatTimeUnit(diffSeconds, "second", "seconds");
        }
    }
    private static String formatTimeUnit(BigInteger value, String singular, String plural) {
        String valueStr = value.toString();
        boolean isOne = value.compareTo(BigInteger.ONE) == 0;
        return valueStr + (isOne ? " " + singular : " " + plural);
    }

    private int getCardColor(TickerData ticker, TickerData.FixedHistoricalData newestFixedHistoricalData) {
        int colorResId;

        @NonNull String priceState = ticker.getPriceState(newestFixedHistoricalData);
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