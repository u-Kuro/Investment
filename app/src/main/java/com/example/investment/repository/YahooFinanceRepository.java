package com.example.investment.repository;

import static java.lang.Double.isFinite;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.util.Constants;
import com.example.investment.util.Logger;
import com.example.investment.model.CachedTickerData;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.FileUtils;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.UrlBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

public class YahooFinanceRepository {
    private final OkHttpClient client = new OkHttpClient();
    private final Random random = new Random();

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void loadTickerScores(Context context, TickerUpdateListener listener) {
        List<TickerInfo> tickers = new ArrayList<>();

        try {
            listener.onProgress(0, 0);

            // Get Ticker Symbols
            List<String> symbols = fetchSymbols();
            int total = symbols.size();
            int current = 0;

            // Get Dollar Volume Threshold
            SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            long dollarVolumeThreshold = prefs.getLong(Constants.PREF_DOLLAR_VOLUME_THRESHOLD, Constants.DEFAULT_VOLUME_THRESHOLD);

            for (String symbol : symbols) {
                try {
                    JSONObject data = fetchTickerData(symbol);

                    // Extract additional data as needed
                    JSONObject result = data.getJSONObject("chart")
                        .getJSONArray("result")
                        .getJSONObject(0);
                    JSONObject meta = result.getJSONObject("meta");
                    String currency = meta.getString("currency");
                    if (currency.isEmpty()) {
                        continue;
                    }
                    JSONObject indicators = result.getJSONObject("indicators");
                    JSONObject quotes = indicators.getJSONArray("quote").getJSONObject(0);
                    JSONArray volumes = quotes.getJSONArray("volume");
                    JSONArray lows = null;
                    try {
                        lows = quotes.getJSONArray("low");
                    } catch (Exception ignored) {}

                    // Get Indicators and Prices
                    JSONArray adjCloses = indicators
                        .getJSONArray("adjclose")
                        .getJSONObject(0)
                        .getJSONArray("adjclose");
                    final long adjClosesLen = adjCloses.length();
                    long pricesLen = 0;
                    Double firstPrice = null;
                    Double lastPrice = null;
                    Integer lastPriceIdx = null;
                    double sumOfPrice = 0;
                    double sumOfPercentageDifference = 0;
                    int currentRecurringNumberOfMissingData = 0;
                    boolean hadNoValidVolume = true;
                    for (int i = 0; i < adjClosesLen; i++) {
                        // Get and Include Close Price If Available
                        double adjClose;
                        try {
                            adjClose = adjCloses.getDouble(i);
                            // Check Valid Price
                            if (adjClose < 0 || !isFinite(adjClose)) {
                                throw new Exception("Invalid Adjusted Closing Price");
                            }
                            // Check Sufficient Volume
                            if (hadNoValidVolume) {
                                try {
                                    // At least $1M Traded Volume
                                    double dollarVolume = volumes.getDouble(i) * adjClose;
                                    if (dollarVolume >= dollarVolumeThreshold && isFinite(dollarVolume)) {
                                        hadNoValidVolume = false;
                                    } else {
                                        continue;
                                    }
                                } catch (Exception ignored) {
                                    continue;
                                }
                            }
                            // Then Get Valid First Price
                            if (firstPrice == null) {
                                // Only Accept Non-Zero First Price
                                // Since It's Used as a Divisor
                                if (adjClose > 0) {
                                    firstPrice = adjClose;
                                } else {
                                    continue;
                                }
                            }
                        } catch (Exception ignored) {
                            // Count Number of Missing Data
                            // Set Their Value as the Average of Last Available Price and Next Available Price
                            // Only If Theres a Previous price Already Set
                            if (lastPrice != null) {
                                ++currentRecurringNumberOfMissingData;
                            }
                            continue;
                        }
                        // Continue the Price Calculations
                        if (currentRecurringNumberOfMissingData > 0) {
                            // Add N (Missing Data) Average of Last Available Price and Current Valid Price
                            sumOfPrice += ((lastPrice + adjClose) / 2) * currentRecurringNumberOfMissingData;
                            pricesLen += currentRecurringNumberOfMissingData;
                            // Calculate and Sum N (Missing Data) Percentage Difference of Mean and Price
                            double densePrice = sumOfPrice / pricesLen;
                            sumOfPercentageDifference += (Math.abs(adjClose - densePrice) / ((adjClose + densePrice) / 2)) * currentRecurringNumberOfMissingData;
                            // Reset Recurring Number of Missing Data
                            currentRecurringNumberOfMissingData = 0;
                        }
                        // Add Valid Adjusted Close Price
                        sumOfPrice += adjClose;
                        ++pricesLen;
                        // Calculate and Sum Percentage Difference of Mean and Price
                        double densePrice = sumOfPrice / pricesLen;
                        sumOfPercentageDifference += Math.abs(adjClose - densePrice) / ((adjClose + densePrice) / 2);
                        // Set Last Price Available
                        lastPrice = adjClose;
                        lastPriceIdx = i;
                    }
                    // Remove Any Ticker with Invalid History
                    if (firstPrice == null) throw new Exception("Invalid First Price");
                    if (pricesLen < 2) throw new Exception("Invalid Number of Adjusted Closing Price");
                    // Calculate Ranking Indicator
                    double percentageIncreasePerDay = calculatePercentageIncreasePerDay(firstPrice, lastPrice, pricesLen);
                    // Calculate Long Indicator
                    double densePrice = sumOfPrice / pricesLen;
                    double averagePercentageDifference = sumOfPercentageDifference / (pricesLen - 1);
                    double priceBoundary = averagePercentageDifference * densePrice;
                    double upperPriceBoundary = densePrice + priceBoundary;
                    double lowerPriceBoundary = densePrice - priceBoundary;

                    // Get Used Value for Boundaries
                    if (lows != null) {
                        try {
                            double lastLowPrice = lows.getDouble(lastPriceIdx);
                            if (lastLowPrice >= 0 && isFinite(lastLowPrice)) {
                                lastPrice = lastLowPrice;
                            }
                        } catch (Exception ignored) {}
                    }

                    // Get Name
                    String name = null;
                    try {
                        name = meta.getString("longName");
                    } catch (Exception ignored) {}
                    if (name == null) {
                        try {
                            name = meta.getString("shortName");
                        } catch (Exception ignored) { }
                    }

                    // Get Type
                    String type = null;
                    try {
                        type = meta.getString("instrumentType");
                    } catch (Exception ignored) {}

                    // Get First Date
                    long timestampSeconds = 0;
                    try {
                        timestampSeconds = meta.getLong("firstTradeDate");
                        if (!isFinite(timestampSeconds)) {
                            timestampSeconds = 0;
                        }
                    } catch (Exception ignored) {}
                    if (timestampSeconds == 0) {
                        try {
                            timestampSeconds = result.getJSONArray("timestamp").getLong(0);
                            if (!isFinite(timestampSeconds)) {
                                timestampSeconds = 0;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (timestampSeconds == 0) {
                        try {
                            timestampSeconds = TimeUnit.MILLISECONDS.toSeconds(
                                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(adjClosesLen - 1)
                            );
                            if (!isFinite(timestampSeconds)) {
                                timestampSeconds = 0;
                            }
                        } catch (Exception ignored) {}
                    }

                    tickers.add(new TickerInfo(
                        symbol,
                        name,
                        type,
                        lastPrice,
                        densePrice,
                        upperPriceBoundary,
                        lowerPriceBoundary,
                        percentageIncreasePerDay,
                        timestampSeconds
                    ));

                    tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                    listener.onTickerUpdate(tickers);

                    listener.onProgress(++current, total);
                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    listener.onError();
                    return;
                } catch (Exception e) {
                    listener.onProgress(++current, total);
                    Logger.error("Unexpected error processing " + symbol, e);
                }
            }
        } catch (InterruptedException e) {
            Logger.error("InterruptedException fetching ticker data", e);
            Thread.currentThread().interrupt();
            listener.onError();
            return;
        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
        }

        tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));

        if (tickers.isEmpty()) {
            listener.onError();
        } else {
            saveCache(context, tickers, listener);
        }
    }

    private double calculatePercentageIncreasePerDay(
        double firstPrice,
        double lowerPriceBoundary,
        long numberOfDays
    ) throws Exception {
        double percentageIncreasePerDay = (((lowerPriceBoundary - firstPrice) / firstPrice) * 100) / numberOfDays;
        if (isFinite(percentageIncreasePerDay)) {
            return percentageIncreasePerDay;
        } else {
            throw new Exception("Invalid Percentage Increase Per Day");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveCache(@NonNull Context context, @NonNull List<TickerInfo> tickers, TickerUpdateListener listener) {
        try {
            long updateTime = System.currentTimeMillis();
            CachedTickerData cache = new CachedTickerData(tickers, updateTime);
            FileUtils.saveObject(context, Constants.CACHE_FILE_DATA, cache);
            listener.onFinish(tickers, updateTime);
        } catch (IOException e) {
            Logger.error("Error saving cache", e);
        }
    }

    public List<TickerInfo> loadCache(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) FileUtils.loadObject(context, Constants.CACHE_FILE_DATA);
            return cache != null ? cache.data : null;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache", e);
            return null;
        }
    }

    public long getCacheTimestamp(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) FileUtils.loadObject(context, Constants.CACHE_FILE_DATA);
            return cache != null ? cache.timestamp : 0;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache timestamp", e);
            return 0;
        }
    }

    private @NonNull JSONObject fetchTickerData(String symbol) throws Exception {
        String url = new UrlBuilder(Constants.YAHOO_CHART_URL)
            .addPath(symbol)
            .addParam("events", "capitalGain|div|split")
            .addParam("formatted", "true")
            .addParam("includeAdjustedClose", "true")
            .addParam("interval", "1d")
            .addParam("period1", "0")
            .addParam("period2", NumberUtils.toFullNumericString((long) Math.floor((double) System.currentTimeMillis() / 1000)))
            .addParam("symbol", symbol)
            .addParam("userYfid", "true")
            .addParam("lang", "en-US")
            .addParam("region", "US")
            .build();

        Request request = new Request.Builder()
            .url(url)
            .build();

        // Add delay between requests
        Thread.sleep(1000 + random.nextInt(2000));

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("Unexpected ticker response: " + response);

            ResponseBody body = response.body();
            if (body == null) throw new Exception("Empty response body");

            return new JSONObject(body.string());
        }
    }

    private List<String> fetchSymbols() throws Exception {
        List<String> symbols = new ArrayList<>();

        // Fetch crypto symbols
        String[] screeners = new String[]{
            Constants.SCREENER_CRYPTO,
            Constants.SCREENER_STOCKS
        };

        for (String screener : screeners) {
            String url = new UrlBuilder(Constants.YAHOO_SCREENER_URL)
                .addParam("count", NumberUtils.toFullNumericString(Constants.SCREENER_COUNT))
                .addParam("formatted", "true")
                .addParam("scrIds", screener)
                .addParam("sortField", "intradaymarketcap")
                .addParam("sortType", "DESC")
                .addParam("start", "0")
                .addParam("fields", "symbol")
                .addParam("lang", "en-US")
                .addParam("region", "US")
                .build();

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            // Add delay between requests
            Thread.sleep(1000 + random.nextInt(2000));

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected "+screener+" response: " + response);

                ResponseBody body = response.body();
                if (body == null) throw new Exception("Empty " + screener + " response body");

                JSONArray quotes = new JSONObject(body.string())
                    .getJSONObject("finance")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONArray("quotes");

                for (int i = 0; i < quotes.length(); i++) {
                    try {
                        symbols.add(quotes.getJSONObject(i).getString("symbol"));
                    } catch (Exception ignored) {}
                }
            }
        }

        return symbols;
    }
} 