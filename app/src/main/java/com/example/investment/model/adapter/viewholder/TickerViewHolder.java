package com.example.investment.model.adapter.viewholder;

import static androidx.core.content.ContextCompat.getColor;
import static com.example.investment.util.BigDecimalUtils.formatNumber;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BETWEEN;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.Constants.RENDER_THREAD;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class TickerViewHolder extends RecyclerView.ViewHolder {
    private @NonNull final TextView nameTextView;
    private @NonNull final ImageView typeIconImageView;
    private @NonNull final TextView marketCapTextView;
    private @NonNull final TextView lastPriceTextView;
    private @NonNull final TextView percentageIncreasePerDayTextView;
    private @NonNull final TextView dailyDollarVolumeTextView;
    private @NonNull final TextView upperBandTextView;
    private @NonNull final TextView timeSpanTextView;
    private @NonNull final OnTickerClickListener clickListener;
    private @NonNull final OnTickerLongClickListener longClickListener;

    public TickerViewHolder(@NonNull View itemView, @NonNull OnTickerClickListener listener, @NonNull OnTickerLongClickListener longListener) {
        super(itemView);
        nameTextView = itemView.findViewById(R.id.nameTextView);
        typeIconImageView = itemView.findViewById(R.id.typeIconImageView);

        marketCapTextView = itemView.findViewById(R.id.marketCapTextView);
        lastPriceTextView = itemView.findViewById(R.id.lastPriceTextView);
        percentageIncreasePerDayTextView = itemView.findViewById(R.id.percentageIncreasePerDayTextView);

        dailyDollarVolumeTextView = itemView.findViewById(R.id.dailyDollarVolumeTextView);
        upperBandTextView = itemView.findViewById(R.id.upperBandTextView);
        timeSpanTextView = itemView.findViewById(R.id.timeSpanTextView);

        clickListener = listener;
        longClickListener = longListener;
    }

    private final Map<Integer, Object> BIND_POSITION_TASK_IDS = new HashMap<>();
    @RequiresApi(api = Build.VERSION_CODES.S)
    public void bind(final int position, final @NonNull TickerData ticker, final @NonNull String investmentType) {
        itemView.setOnClickListener(v -> clickListener.onTickerClick(ticker));
        itemView.setOnLongClickListener(v -> longClickListener.onTickerLongClick(ticker));

        // Only cancel tasks with the same recycler view position
        @NonNull final Object BIND_POSITION_TASK_ID = BIND_POSITION_TASK_IDS.computeIfAbsent(position, p -> new Object());

        RENDER_THREAD.replaceUniqueTask(BIND_POSITION_TASK_ID, ui -> {
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
            final String exchange = ticker.getExchange();
            final String nameText;
            if (exchange == null || exchange.isEmpty()) {
                nameText = name;
            } else {
                nameText = name + " | " + exchange;
            }
            ui.post(() -> nameTextView.setText(nameText));

            if (CRYPTOCURRENCY_TYPE.equals(assetType)) {
                ui.post(() -> {
                    typeIconImageView.setImageResource(R.drawable.crypto);
                    typeIconImageView.setVisibility(View.VISIBLE);
                });
            } else if (STOCK_TYPE.equals(assetType)) {
                ui.post(() -> {
                    typeIconImageView.setImageResource(R.drawable.stock);
                    typeIconImageView.setVisibility(View.VISIBLE);
                });
            } else {
                ui.post(() -> typeIconImageView.setVisibility(View.GONE));
            }

            final String marketCapText = formatPrice(ticker.getMarketCap());
            ui.post(() -> marketCapTextView.setText(marketCapText));

            final String lastPriceText = formatPrice(lastAdjustedClosePrice);
            ui.post(() -> lastPriceTextView.setText(lastPriceText));

            final String percentageIncreasePerDayText = formatNumber(percentageIncreasePerDay) + "%";
            ui.post(() -> percentageIncreasePerDayTextView.setText(percentageIncreasePerDayText));

            final String dailyDollarVolumeText = formatPrice(ticker.getDailyDollarVolume(newestFixedHistoricalDataRecord));
            ui.post(() -> dailyDollarVolumeTextView.setText(dailyDollarVolumeText));
            final String upperBandText = formatPrice(adjustedUpperBand);
            ui.post(() -> upperBandTextView.setText(upperBandText));
            final String timeSpanText = getTimeAgo(ticker.getFirstTradeTimestampSeconds(), currentTimeMillis);
            ui.post(() -> timeSpanTextView.setText(timeSpanText));

            final int nameTextColor;
            if (isDataStale) {
                nameTextColor = getColor(itemView.getContext(), R.color.slate_grey);
            } else {
                nameTextColor = getColor(itemView.getContext(), R.color.white);
            }
            ui.post(() -> nameTextView.setTextColor(nameTextColor));

            final int lastPriceColor;
            if (lastAdjustedClosePrice.compareTo(adjustedLowerBand) < 0) {
                lastPriceColor = getColor(itemView.getContext(), R.color.bad);
            } else if (priceState.equals(PRICE_BETWEEN)) {
                lastPriceColor = getColor(itemView.getContext(), R.color.profit_taking);
            } else {
                lastPriceColor = getColor(itemView.getContext(), R.color.white);
            }
            ui.post(() -> lastPriceTextView.setTextColor(lastPriceColor));

            final int percentageIncreasePerDayTextColor;
            if (percentageIncreasePerDay.compareTo(BigDecimal.ZERO) <= 0) {
                percentageIncreasePerDayTextColor = getColor(itemView.getContext(), R.color.bad);
            } else {
                percentageIncreasePerDayTextColor = getColor(itemView.getContext(), R.color.white);
            }
            ui.post(() -> percentageIncreasePerDayTextView.setTextColor(percentageIncreasePerDayTextColor));

            final int upperBandTextColor;
            if (adjustedUpperBand.compareTo(adjustedLowerBand) < 0) {
                upperBandTextColor = getColor(itemView.getContext(), R.color.bad);
            } else {
                upperBandTextColor = getColor(itemView.getContext(), R.color.white);
            }
            ui.post(() -> upperBandTextView.setTextColor(upperBandTextColor));

            final int cardStrokeColor = getCardColor(priceState);
            MaterialCardView cardView = (MaterialCardView) itemView;
            ui.post(() -> cardView.setStrokeColor(cardStrokeColor));
        });
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