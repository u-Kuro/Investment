package com.example.investment.repository;

import static java.lang.Double.isFinite;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.util.Constants;
import com.example.investment.util.Logger;
import com.example.investment.model.ticker.CachedTickerData;
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

                    JSONArray adjCloses = result.getJSONObject("indicators")
                        .getJSONArray("adjclose")
                        .getJSONObject(0)
                        .getJSONArray("adjclose");

                    // Get Indicators and Prices
                    final long adjClosesLen = adjCloses.length();
                    long pricesLen = 0;
                    double firstPrice = 0;
                    double lastPrice = 0;
                    double sumOfPrice = 0;
                    double sumOfPercentageDifference = 0;
                    for (int i = 0; i < adjClosesLen; i++) {
                        double adjClose;
                        // Get and Include Close Price if Available
                        try {
                            adjClose = adjCloses.getDouble(i);
                            if (adjClose < 0) throw new Exception("Invalid Adjusted Closing Price");
                            if (firstPrice == 0) {
                                if (adjClose > 0) {
                                    firstPrice = adjClose;
                                } else {
                                    continue;
                                }
                            }
                        } catch (Exception ignored) {
                            continue;
                        }
                        // Add Valid Adjusted Close Price
                        sumOfPrice += adjClose;
                        ++pricesLen;
                        // Calculate and Sum Percentage Difference of Mean and Price
                        double densePrice = sumOfPrice / pricesLen;
                        sumOfPercentageDifference += Math.abs(adjClose - densePrice) / ((adjClose + densePrice) / 2);
                        // Set Last Price Available
                        lastPrice = adjClose;
                    }
                    // Check Invalid Last Price
                    if (lastPrice <= 0) throw new Exception("Invalid Last Price");
                    if (pricesLen < 1) throw new Exception("Invalid Number of Adjusted Closing Price");
                    // Calculate Indicators
                    double densePrice = sumOfPrice / pricesLen;
                    double averagePercentageDifference = sumOfPercentageDifference / pricesLen;
                    double priceBoundary = averagePercentageDifference * densePrice;
                    double upperPriceBoundary = densePrice + priceBoundary;
                    double lowerPriceBoundary = densePrice - priceBoundary;
                    double percentageIncreasePerDay = calculatePercentageIncreasePerDay(firstPrice, lowerPriceBoundary, pricesLen);

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
                                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(adjCloses.length() - 1)
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