package com.example.investment.model.adapter.viewholder;

import static androidx.core.content.ContextCompat.getColor;
import static com.example.investment.util.BigDecimalUtils.formatNumber;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.PriceUtils.formatPrice;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;
import com.example.investment.R;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.listener.OnTickerClickListener;
import com.example.investment.model.listener.OnTickerLongClickListener;
import com.google.android.material.card.MaterialCardView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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
    public void bind(TickerData ticker, @NonNull String investmentType) {
        currentTicker = ticker;

        final long currentTimeMillis = System.currentTimeMillis();
        final boolean isDataStale = ticker.areAllFixedHistoricalDataStale(currentTimeMillis);
        TickerData.FixedHistoricalRecord newestFixedHistoricalDataRecord = ticker.getNewestFixedHistoricalDataWithInterval();
        TickerData.FixedHistoricalData newestFixedHistoricalData = Optional
            .ofNullable(newestFixedHistoricalDataRecord)
            .map(TickerData.FixedHistoricalRecord::fixedHistoricalData)
            .orElse(null);

        @NonNull String assetType = ticker.getType();
        @NonNull BigDecimal lastAdjustedClosePrice = ticker.getLastAdjustedClosePrice();
        @NonNull BigDecimal adjustedDensePrice = ticker.getAdjustedDensePrice(newestFixedHistoricalData);
        @NonNull BigDecimal adjustedLowerBand = ticker.getAdjustedLowerBand(newestFixedHistoricalData, adjustedDensePrice);
        @NonNull BigDecimal adjustedUpperBand = ticker.getAdjustedUpperBand(newestFixedHistoricalData, adjustedDensePrice);
        @NonNull BigDecimal percentageIncreasePerDay = ticker.getPercentageIncreasePerDay(investmentType, newestFixedHistoricalData, adjustedUpperBand, currentTimeMillis);
        @NonNull String priceState = ticker.getPriceState(newestFixedHistoricalData, percentageIncreasePerDay, adjustedLowerBand, adjustedUpperBand);

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

        if (CRYPTOCURRENCY_TYPE.equals(assetType)) {
            typeIconImage.setImageResource(R.drawable.crypto);
            typeIconImage.setVisibility(View.VISIBLE);
        } else if (STOCK_TYPE.equals(assetType)) {
            typeIconImage.setImageResource(R.drawable.stock);
            typeIconImage.setVisibility(View.VISIBLE);
        } else {
            typeIconImage.setVisibility(View.GONE);
        }

        marketCapText.setText(formatPrice(ticker.getMarketCap()));
        lastPriceText.setText(formatPrice(lastAdjustedClosePrice));
        percentageIncreasePerDayText.setText(
            new StringBuilder()
            .append(formatNumber(percentageIncreasePerDay))
            .append("%")
        );

        dailyDollarVolumeText.setText(formatPrice(ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord)));
        upperBandText.setText(formatPrice(adjustedUpperBand));
        timeSpanText.setText(getTimeAgo(ticker.getFirstTradeTimestampSeconds(), currentTimeMillis));

        if (isDataStale) {
            nameText.setTextColor(getColor(itemView.getContext(), R.color.slate_grey));
        } else {
            nameText.setTextColor(getColor(itemView.getContext(), R.color.white));
        }

        if (lastAdjustedClosePrice.compareTo(adjustedLowerBand) < 0) {
            lastPriceText.setTextColor(getColor(itemView.getContext(), R.color.bad));
        } else if (priceState.equals(PRICE_BETWEEN)) {
            lastPriceText.setTextColor(getColor(itemView.getContext(), R.color.profit_taking));
        } else {
            lastPriceText.setTextColor(getColor(itemView.getContext(), R.color.white));
        }
        if (percentageIncreasePerDay.compareTo(BigDecimal.ZERO) <= 0) {
            percentageIncreasePerDayText.setTextColor(getColor(itemView.getContext(), R.color.bad));
        } else {
            percentageIncreasePerDayText.setTextColor(getColor(itemView.getContext(), R.color.white));
        }

        if (adjustedUpperBand.compareTo(adjustedLowerBand) < 0) {
            upperBandText.setTextColor(getColor(itemView.getContext(), R.color.bad));
        } else {
            upperBandText.setTextColor(getColor(itemView.getContext(), R.color.white));
        }

        MaterialCardView cardView = (MaterialCardView) itemView;
        cardView.setStrokeColor(getCardColor(priceState));
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
    public static String getTimeAgo(@NonNull BigInteger timestampSeconds, long currentTimeMillis) {
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
            BigInteger nowMillis = BigInteger.valueOf(currentTimeMillis);
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

    private int getCardColor(@NonNull String priceState) {
        int colorResId;

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