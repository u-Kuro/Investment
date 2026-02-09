package com.example.investment.repository;

import static com.example.investment.util.Constants.CACHE_FILE_DATA;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.FIRST_TRADE_TIMESTAMP_SECONDS;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.SCREENER_COUNT;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.YAHOO_CHART_URL;
import static com.example.investment.util.Constants.YAHOO_CHART_URL_ALT;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL_ALT;
import static java.lang.Double.isFinite;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.util.Logger;
import com.example.investment.model.CachedTickerData;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.FileUtils;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.UrlBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
            Map<String, Long> symbols = fetchSymbols(context);
            int total = symbols.size();
            int current = 0;

            for (Map.Entry<String, Long> ticker : symbols.entrySet()) {
                String symbol = ticker.getKey();
                long marketCap = ticker.getValue();
                try {
                    JSONObject result = fetchTickerData(symbol);

                    // Metadata & Base Arrays
                    JSONObject meta = result.getJSONObject("meta");
                    String exchange = meta.getString("fullExchangeName");
                    JSONArray timestampArr = result.getJSONArray("timestamp");

                    JSONObject indicators = result.getJSONObject("indicators");
                    JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
                    JSONArray highArr = quote.getJSONArray("high");
                    JSONArray lowArr = quote.getJSONArray("low");
                    JSONArray volArr = quote.getJSONArray("volume");
                    JSONArray adjCloses = indicators.getJSONArray("adjclose").getJSONObject(0).getJSONArray("adjclose");

                    // States for Adjusted Close
                    long adjPricesLen = 0;
                    double sumOfAdjPrice = 0;
                    double sumOfPercentageChangeGrowthFactor = 0;
                    Double firstAdjPrice = null;
                    Double lastAdjPrice = null;
                    Long firstTradeDateTimestampSeconds = null;

                    // States for OHLCV
                    long ohlcvLen = 0;
                    double S_num = 0.0;
                    double S_den = 0.0;
                    double weightedAveragePctChangeGrowthFactor = 0.0;
                    Double lastLowPrice = null;

                    for (int i = 0; i < adjCloses.length(); i++) {

                        try {
                            // Check Adjusted Close
                            double currentAC = adjCloses.getDouble(i);
                            long currentTs = timestampArr.getLong(i);
                            if (
                                currentAC > 0 && isFinite(currentAC)
                                && currentTs > 0 && isFinite(currentTs) && (System.currentTimeMillis() / 1000L) > currentTs
                            ) {
                                // Set first price and timestamp once
                                if (firstAdjPrice == null) {
                                    firstAdjPrice = currentAC;
                                    firstTradeDateTimestampSeconds = currentTs;
                                }

                                adjPricesLen++;
                                sumOfAdjPrice += currentAC;
                                sumOfPercentageChangeGrowthFactor += (currentAC / (sumOfAdjPrice / adjPricesLen));
                                lastAdjPrice = currentAC;
                            }
                        } catch (Exception ignored) {}

                        try {
                            // Check OHLCV
                            double currentHigh = highArr.getDouble(i);
                            double currentLow = lowArr.getDouble(i);
                            double currentVol = volArr.getLong(i);
                            if (
                                currentHigh > 0 && isFinite(currentHigh)
                                && currentLow > 0 && isFinite(currentLow)
                                && currentVol > 0 && isFinite(currentVol)
                            ) {
                                ohlcvLen++;
                                double barRange = currentHigh - currentLow;
                                double midpoint = (currentHigh + currentLow) / 2.0;
                                double numInc = barRange > 0 ? (currentVol * barRange * midpoint) : (currentVol * midpoint);
                                double denInc = barRange > 0 ? (currentVol * barRange) : currentVol;

                                S_num += numInc;
                                S_den += denInc;

                                if (S_den != 0) {
                                    double densePrice = S_num / S_den;
                                    weightedAveragePctChangeGrowthFactor += midpoint / densePrice;
                                }
                            }
                            lastLowPrice = currentLow;
                        } catch (Exception ignored) {}
                    }

                    // Final Validation
                    if (
                        adjPricesLen < 2 || firstAdjPrice == null || lastAdjPrice == null  || firstTradeDateTimestampSeconds == 0
                        || ohlcvLen < 2 || lastLowPrice == null
                    ) {
                        throw new Exception("Invalid History: Insufficient valid data points.");
                    }

                    // Final Metrics
                    double percentageIncreasePerDay = calculatePercentageIncreasePerDay(firstAdjPrice, lastAdjPrice, firstTradeDateTimestampSeconds);
                    double densePrice = S_num / S_den;
                    double stopLossPrice = 0.7 * (weightedAveragePctChangeGrowthFactor / ohlcvLen) * densePrice;

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

                    tickers.add(new TickerInfo(
                        symbol,
                        exchange,
                        name,
                        type,
                        lastLowPrice, // Set Last Low as Current Price To Be Used for Crossing Boundaries
                        densePrice,
                        stopLossPrice,
                        percentageIncreasePerDay,
                        firstTradeDateTimestampSeconds,
                        marketCap
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
        double lastPrice,
        long firstTradeDateTimestampSeconds
    ) throws Exception {
        double numberOfDays = (System.currentTimeMillis() - (firstTradeDateTimestampSeconds * 1000)) / 86400000D; // Decimals allowed
        double percentageIncreasePerDay = (((lastPrice - firstPrice) / firstPrice) * 100) / numberOfDays;
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
            FileUtils.saveObject(context, CACHE_FILE_DATA, cache);
            listener.onFinish(tickers, updateTime);
        } catch (IOException e) {
            Logger.error("Error saving cache", e);
        }
    }

    public List<TickerInfo> loadCache(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) FileUtils.loadObject(context, CACHE_FILE_DATA);
            return cache != null ? cache.data() : null;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache", e);
            return null;
        }
    }

    public long getCacheTimestamp(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) FileUtils.loadObject(context, CACHE_FILE_DATA);
            return cache != null ? cache.timestamp() : 0;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache timestamp", e);
            return 0;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private @NonNull JSONObject fetchTickerData(String symbol) throws Exception {
        class Attempt {
            final String interval;
            final String baseUrl;
            Attempt(String interval, String baseUrl){
                this.interval = interval;
                this.baseUrl = baseUrl;
            }
        }
        List<Attempt> attempts = Arrays.asList(
            new Attempt("1d", YAHOO_CHART_URL),
            new Attempt("1d", YAHOO_CHART_URL_ALT),
            new Attempt("1mo", YAHOO_CHART_URL),
            new Attempt("1mo", YAHOO_CHART_URL_ALT),
            new Attempt("3mo", YAHOO_CHART_URL),
            new Attempt("3mo", YAHOO_CHART_URL_ALT)
        );

        Exception lastException = null;

        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            try {
                String url = new UrlBuilder(attempt.baseUrl)
                    .addPath(symbol)
                    .addParam("period1", NumberUtils.toFullNumericString(FIRST_TRADE_TIMESTAMP_SECONDS))
                    // Add a month (31 days) buffer
                    .addParam("period2", NumberUtils.toFullNumericString(Math.floor(System.currentTimeMillis() / 1000D) + 2678400L))
                    .addParam("interval", attempt.interval)
                    .addParam("includePrePost", "true")
                    .addParam("events", "div|split|earn")
                    .addParam("lang", "en-US")
                    .addParam("region", "US")
                    .addParam("source", "cosaic")
                    .build();

                Request request = new Request.Builder().url(url).build();

                // Add Delay Between Requests
                //noinspection BusyWait
                Thread.sleep(1000 + random.nextInt(2000));

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("Unexpected " + symbol + " response: " + response);
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new Exception("Empty response body");
                    }

                    // Success
                    return new JSONObject(body.string())
                        .getJSONObject("chart")
                        .getJSONArray("result")
                        .getJSONObject(0);
                }
            } catch (Exception exception) {
                if (i == attempts.size() - 1) {
                    lastException = exception;
                }
            }
        }

        // Shouldn't reach here, but satisfy compiler
        if (lastException != null) {
            throw lastException;
        } else {
            throw new Exception("Failed to fetch ticker data (no attempts performed)");
        }
    }


    private Map<String, Long> fetchSymbols(Context context) throws Exception {
        Map<String, Long> symbols = new HashMap<>();

        // Fetch crypto symbols
        String[] screeners = new String[]{
            SCREENER_CRYPTO,
            SCREENER_STOCKS
        };

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long minMarketCap = prefs.getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);

        for (String screener : screeners) {
            int retryCount = 0;
            long start_index = 0;
            String prevFirstSymbol = null;

            while (true) {
                String url = new UrlBuilder(retryCount > 0 ? YAHOO_SCREENER_URL_ALT : YAHOO_SCREENER_URL)
                    .addParam("count", NumberUtils.toFullNumericString(SCREENER_COUNT))
                    .addParam("scrIds", screener)
                    .addParam("sortField", "intradaymarketcap")
                    .addParam("sortType", "DESC")
                    .addParam("start", NumberUtils.toFullNumericString(start_index))
                    .build();

                Request request = new Request.Builder()
                    .url(url)
                    .build();

                // Add Delay Between Requests
                //noinspection BusyWait
                Thread.sleep(1000 + random.nextInt(2000));

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("Unexpected " + screener + " response: " + response);
                    }

                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new Exception("Empty response body");
                    }

                    JSONArray quotes = new JSONObject(body.string())
                        .getJSONObject("finance")
                        .getJSONArray("result")
                        .getJSONObject(0)
                        .getJSONArray("quotes");

                    // No More Items
                    int length = quotes.length();
                    if (length < 1) break;

                    // Check for Repeated Page
                    String firstSymbol = quotes.getJSONObject(0).getString("symbol");
                    if (firstSymbol.equals(prevFirstSymbol)) break;
                    prevFirstSymbol = firstSymbol;

                    // Only Allow Stocks Above Minimum Market Cap Threshold
                    boolean stopPaging = false;
                    for (int i = 0; i < length; i++) {
                        try {
                            JSONObject object = quotes.getJSONObject(i);
                            long marketCap = object.getLong("marketCap");
                            if (marketCap > 0) {
                                if (marketCap < minMarketCap) {
                                    stopPaging = true;
                                    break;
                                }
                                symbols.put(object.getString("symbol"), marketCap);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (stopPaging) break;

                    // Successful Page
                    retryCount = 0;
                    start_index += length;
                } catch (Exception exception) {
                    if (retryCount >= 1) {
                        break;
                    } else {
                        retryCount++;
                    }
                }
            }
        }

        return symbols;
    }
}