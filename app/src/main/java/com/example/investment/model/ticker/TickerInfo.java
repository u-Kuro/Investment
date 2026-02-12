package com.example.investment.model.ticker;

import static com.example.investment.util.Constants.PRICE_ABOVE;
import static com.example.investment.util.Constants.PRICE_BELOW;
import static com.example.investment.util.Constants.PRICE_BETWEEN;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class TickerInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    @NonNull private final String symbol;
    private final String exchange;
    private final String name;
    private final String type;
    private final double lastPrice;
    private final double densePrice;
    private final double upperBand;
    private final double lowerBand;
    private final double percentageIncreasePerDay;
    private final long firstTradeTimestampSeconds;
    private final long marketCap;
    private final long lastUpdateTimestamp;
    private final long oldestDataTimestamp;
    private final String timestampArrayJson;
    private final String adjCloseArrayJson;
    private final String highArrayJson;
    private final String lowArrayJson;
    private final String volumeArrayJson;

    public TickerInfo(
        @NonNull String symbol,
        String exchange,
        String name,
        String type,
        double lastPrice,
        double densePrice,
        double upperBand,
        double lowerBand,
        double percentageIncreasePerDay,
        long firstTradeTimestampSeconds,
        long marketCap,
        long lastUpdateTimestamp,
        long oldestDataTimestamp,
        JSONArray timestampArray,
        JSONArray adjCloseArray,
        JSONArray highArray,
        JSONArray lowArray,
        JSONArray volumeArray
    ) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.name = name;
        this.type = type;
        this.lastPrice = lastPrice;
        this.densePrice = densePrice;
        this.upperBand = upperBand;
        this.lowerBand = lowerBand;
        this.percentageIncreasePerDay = percentageIncreasePerDay;
        this.firstTradeTimestampSeconds = firstTradeTimestampSeconds;
        this.marketCap = marketCap;
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.oldestDataTimestamp = oldestDataTimestamp;
        this.timestampArrayJson = timestampArray != null ? timestampArray.toString() : "[]";
        this.adjCloseArrayJson = adjCloseArray != null ? adjCloseArray.toString() : "[]";
        this.highArrayJson = highArray != null ? highArray.toString() : "[]";
        this.lowArrayJson = lowArray != null ? lowArray.toString() : "[]";
        this.volumeArrayJson = volumeArray != null ? volumeArray.toString() : "[]";
    }

    public @NonNull String getSymbol() { return symbol; }
    public String getExchange() { return exchange; }
    public String getName(boolean defaultToSymbol) {
        if (defaultToSymbol && (name == null || name.isEmpty())) {
            return symbol;
        }
        return name;
    }
    public String getType() { return type; }
    public double getLastPrice() { return lastPrice; }
    public double getDensePrice() { return densePrice; }
    public double getUpperBand() { return upperBand; }
    public double getLowerBand() { return lowerBand; }
    public double getPercentageIncreasePerDay() { return percentageIncreasePerDay; }
    public long getFirstTradeTimestampSeconds() { return firstTradeTimestampSeconds; }
    public long getMarketCap() { return marketCap; }
    public long getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public long getOldestDataTimestamp() { return oldestDataTimestamp; }
    public JSONArray getTimestampArray() {
        try { return new JSONArray(timestampArrayJson); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public JSONArray getAdjCloseArray() {
        try { return new JSONArray(adjCloseArrayJson); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public JSONArray getHighArray() {
        try { return new JSONArray(highArrayJson); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public JSONArray getLowArray() {
        try { return new JSONArray(lowArrayJson); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public JSONArray getVolumeArray() {
        try { return new JSONArray(volumeArrayJson); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public String getTimestampArrayJson() {
        return timestampArrayJson;
    }
    public String getAdjCloseArrayJson() {
        return adjCloseArrayJson;
    }
    public String getHighArrayJson() {
        return highArrayJson;
    }
    public String getLowArrayJson() {
        return lowArrayJson;
    }
    public String getVolumeArrayJson() {
        return volumeArrayJson;
    }

    public @NonNull String getPriceState() {
        if (this.percentageIncreasePerDay <= 0 || this.lastPrice < this.lowerBand || this.upperBand < this.lowerBand) {
            return PRICE_BELOW;
        }
        if (this.lastPrice <= this.upperBand) return PRICE_BETWEEN;
        return PRICE_ABOVE;
    }

    public boolean isDataStale() {
        if (lastUpdateTimestamp == 0) return true;
        long timeDifference = System.currentTimeMillis() - lastUpdateTimestamp;
        return timeDifference > TimeUnit.DAYS.toMillis(1);
    }

    public boolean equals(TickerInfo tickerInfo) {
        return Objects.equals(this.symbol, tickerInfo.getSymbol())
            && Objects.equals(this.exchange, tickerInfo.getExchange())
            && Objects.equals(this.name, tickerInfo.getName(false))
            && Objects.equals(this.type, tickerInfo.getType())
            && this.lastPrice == tickerInfo.getLastPrice()
            && this.densePrice == tickerInfo.getDensePrice()
            && this.upperBand == tickerInfo.getUpperBand()
            && this.lowerBand == tickerInfo.getLowerBand()
            && this.percentageIncreasePerDay == tickerInfo.getPercentageIncreasePerDay()
            && this.firstTradeTimestampSeconds == tickerInfo.getFirstTradeTimestampSeconds()
            && this.marketCap == tickerInfo.getMarketCap()
            && this.lastUpdateTimestamp == tickerInfo.getLastUpdateTimestamp()
            && this.oldestDataTimestamp == tickerInfo.getOldestDataTimestamp()
            && Objects.equals(this.timestampArrayJson, tickerInfo.getTimestampArrayJson())
            && Objects.equals(this.adjCloseArrayJson, tickerInfo.getAdjCloseArrayJson())
            && Objects.equals(this.highArrayJson, tickerInfo.getHighArrayJson())
            && Objects.equals(this.lowArrayJson, tickerInfo.getLowArrayJson())
            && Objects.equals(this.volumeArrayJson, tickerInfo.getVolumeArrayJson());
    }
}