package com.example.investment.repository;

import static com.example.investment.util.Constants.CACHE_FILE_DATA;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.FIRST_TRADE_TIMESTAMP_SECONDS;
import static com.example.investment.util.Constants.MAX_FETCH_RETRY_COUNT;
import static com.example.investment.util.Constants.MINIMUM_REQUEST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.SCREENER_COUNT;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.YAHOO_CHART_URL;
import static com.example.investment.util.Constants.YAHOO_CHART_URL_ALT;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL_ALT;
import static com.example.investment.util.NetworkUtils.getThrottleDelay;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;
import static java.lang.Double.isFinite;
import static java.lang.Double.isNaN;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerInfo;
import com.example.investment.util.Logger;
import com.example.investment.model.CachedTickerData;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.UrlBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

public class YahooFinanceRepository {
    private final OkHttpClient client = new OkHttpClient();

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void loadTickerScores(Context context, boolean isFullRefresh, TickerUpdateListener listener) {
        List<TickerInfo> tickers = new ArrayList<>();
        Map<String, TickerInfo> existingTickersMap = new HashMap<>();
        Set<String> deletedTickers = new HashSet<>();

        try {
            listener.onProgress(0, 0);

            // Get current ticker symbols from screeners
            Map<String, Long> currentSymbols = fetchSymbols(context);

            // Load existing cache if doing partial refresh
            if (!isFullRefresh) {
                List<TickerInfo> cachedTickers = loadCache(context);
                if (cachedTickers != null) {
                    for (TickerInfo ticker : cachedTickers) {
                        existingTickersMap.put(ticker.getSymbol(), ticker);
                    }

                    // Find deleted tickers
                    if (!existingTickersMap.isEmpty()) {
                        deletedTickers = getDeletedTickers(existingTickersMap, currentSymbols);
                    }
                } else {
                    isFullRefresh = true;
                }
            }

            int total = currentSymbols.size();
            int current = 0;

            for (Map.Entry<String, Long> ticker : currentSymbols.entrySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted in main ticker loop");
                }

                @NonNull String symbol = ticker.getKey();
                long marketCap = ticker.getValue();

                try {
                    TickerInfo existingTicker = existingTickersMap.get(symbol);
                    long oldestDataTimestamp;

                    // Determine starting timestamp
                    if (isFullRefresh || existingTicker == null) {
                        oldestDataTimestamp = FIRST_TRADE_TIMESTAMP_SECONDS;
                    } else {
                        oldestDataTimestamp = existingTicker.getOldestDataTimestampSeconds();
                    }

                    long currentUpdateTimestamp = System.currentTimeMillis();

                    // Fetch and process data
                    TickerInfo newTicker = processTickerData(
                        symbol,
                        marketCap,
                        oldestDataTimestamp,
                        currentUpdateTimestamp,
                        existingTicker,
                        isFullRefresh
                    );

                    // Remove existing ticker from map immediately to free memory
                    existingTickersMap.remove(symbol);

                    tickers.add(newTicker);
                    tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                    listener.onTickerUpdate(tickers);
                    listener.onProgress(++current, total);

                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    listener.onError();
                    return;
                } catch (OutOfMemoryError e) {
                    Logger.error("OOM processing " + symbol, e);
                    listener.onProgress(++current, total);
                    System.gc();
                } catch (Exception e) {
                    listener.onProgress(++current, total);
                    Logger.error("Error processing " + symbol, e);
                }
            }
        } catch (InterruptedException e) {
            Logger.error("InterruptedException fetching ticker data", e);
            Thread.currentThread().interrupt();
            listener.onError();
            return;
        } catch (OutOfMemoryError e) {
            Logger.error("OOM in main loop", e);
            listener.onError();
            return;
        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
            listener.onError();
            return;
        } finally {
            existingTickersMap.clear();
        }

        // Delete missing tickers
        Set<String> finalDeletedTickers = deletedTickers;
        tickers.removeIf(tickerInfo -> finalDeletedTickers.contains(tickerInfo.getSymbol()));

        tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));

        if (tickers.isEmpty()) {
            listener.onError();
        } else {
            try {
                long updateTime = System.currentTimeMillis();
                saveCache(context, tickers, updateTime);
                listener.onFinish(tickers, updateTime);
            } catch (Exception e) {
                Logger.error("Error saving data", e);
                listener.onError();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private TickerInfo processTickerData(
        String symbol,
        long marketCap,
        long oldestDataTimestamp,
        long currentUpdateTimestamp,
        TickerInfo existingTicker,
        boolean isFullRefresh
    ) throws Exception {

        JSONObject result = fetchTickerData(symbol, oldestDataTimestamp);

        // Extract metadata
        JSONObject meta = result.getJSONObject("meta");
        String exchange = meta.getString("fullExchangeName");

        // Get name
        String name = null;
        try {
            name = meta.getString("longName");
        } catch (Exception ignored) {}
        if (name == null) {
            try {
                name = meta.getString("shortName");
            } catch (Exception ignored) {}
        }

        // Get type
        String type = null;
        try {
            type = meta.getString("instrumentType");
        } catch (Exception ignored) {}

        // Get arrays from API response
        JSONArray apiTimestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray apiAdjCloses = indicators.getJSONArray("adjclose").getJSONObject(0).getJSONArray("adjclose");
        JSONArray apiHighs = quote.getJSONArray("high");
        JSONArray apiLows = quote.getJSONArray("low");
        JSONArray apiVolumes = quote.getJSONArray("volume");

        // Prepare final arrays (either merged or new)
        JSONArray finalTimestamps;
        JSONArray finalAdjCloses;
        JSONArray finalHighs;
        JSONArray finalLows;
        JSONArray finalVolumes;

        if (!isFullRefresh && existingTicker != null) {
            // For incremental: merge old + new (removing first new item as it's duplicate)
            JSONArray oldTimestamps = existingTicker.getTimestampArray();
            JSONArray oldAdjCloses = existingTicker.getAdjCloseArray();
            JSONArray oldHighs = existingTicker.getHighArray();
            JSONArray oldLows = existingTicker.getLowArray();
            JSONArray oldVolumes = existingTicker.getVolumeArray();

            // Remove first item from new data (duplicate of last old item)
            if (apiTimestamps.length() > 0) apiTimestamps.remove(0);
            if (apiAdjCloses.length() > 0) apiAdjCloses.remove(0);
            if (apiHighs.length() > 0) apiHighs.remove(0);
            if (apiLows.length() > 0) apiLows.remove(0);
            if (apiVolumes.length() > 0) apiVolumes.remove(0);

            // Merge arrays in-place (old array + new items)
            finalTimestamps = oldTimestamps;
            finalAdjCloses = oldAdjCloses;
            finalHighs = oldHighs;
            finalLows = oldLows;
            finalVolumes = oldVolumes;

            // Append new items to old arrays
            for (int i = 0; i < apiTimestamps.length(); i++) {
                finalTimestamps.put(apiTimestamps.get(i));
            }
            for (int i = 0; i < apiAdjCloses.length(); i++) {
                finalAdjCloses.put(apiAdjCloses.get(i));
            }
            for (int i = 0; i < apiHighs.length(); i++) {
                finalHighs.put(apiHighs.get(i));
            }
            for (int i = 0; i < apiLows.length(); i++) {
                finalLows.put(apiLows.get(i));
            }
            for (int i = 0; i < apiVolumes.length(); i++) {
                finalVolumes.put(apiVolumes.get(i));
            }
        } else {
            // For full refresh: use new data directly
            finalTimestamps = apiTimestamps;
            finalAdjCloses = apiAdjCloses;
            finalHighs = apiHighs;
            finalLows = apiLows;
            finalVolumes = apiVolumes;
        }

        // Calculate metrics using final arrays
        long adjPricesLen = 0;
        Double firstAdjPrice = null;
        Double lastAdjPrice = null;
        Long firstTradeDateTimestampSeconds = null;
        Long lastValidTimestampSeconds = null;

        long ohlcvLen = 0;
        double DP_S_num = 0.0;
        double DP_S_den = 0.0;
        double DB_S_num = 0.0;
        double DB_S_den = 0.0;

        for (int i = 0; i < finalAdjCloses.length(); i++) {
            try {
                double currentAC = finalAdjCloses.getDouble(i);
                long currentTs = finalTimestamps.getLong(i);
                if (currentAC > 0 && isFinite(currentAC)
                        && currentTs > 0 && isFinite(currentTs) && (System.currentTimeMillis() / 1000L) > currentTs) {
                    adjPricesLen++;

                    if (firstAdjPrice == null) {
                        firstAdjPrice = currentAC;
                        firstTradeDateTimestampSeconds = currentTs;
                    }

                    lastAdjPrice = currentAC;

                    if (lastValidTimestampSeconds == null || currentTs < lastValidTimestampSeconds) {
                        lastValidTimestampSeconds = currentTs;
                    }
                }
            } catch (Exception ignored) {}

            try {
                double currentHigh = finalHighs.getDouble(i);
                double currentLow = finalLows.getDouble(i);
                double currentVol = finalVolumes.getLong(i);
                long currentTs = finalTimestamps.getLong(i);

                if (currentHigh > 0 && isFinite(currentHigh)
                        && currentLow > 0 && isFinite(currentLow)
                        && currentVol > 0 && isFinite(currentVol)) {
                    ohlcvLen++;
                    double bar_range = currentHigh - currentLow;
                    double mid_point = (currentHigh + currentLow) / 2.0;
                    double DP_num_inc = bar_range > 0 ? (currentVol * bar_range * mid_point) : (currentVol * mid_point);
                    double D_den_inc = bar_range > 0 ? (currentVol * bar_range) : currentVol;

                    if (!isNaN(DP_num_inc) && !isNaN(D_den_inc)) {
                        DP_S_num += DP_num_inc;
                        DP_S_den += D_den_inc;
                    }

                    if (DP_S_num != 0 && DP_S_den != 0) {
                        double densePrice = DP_S_num / DP_S_den;
                        double pct_dif = 2 - (4 * densePrice * Math.log((currentHigh + densePrice) / (currentLow + densePrice)) / bar_range);
                        double DB_num_inc = bar_range > 0 ? (currentVol * bar_range * pct_dif) : (currentVol * pct_dif);

                        if (!isNaN(DB_num_inc) && !isNaN(D_den_inc)) {
                            DB_S_num += DB_num_inc;
                            DB_S_den += D_den_inc;
                        }
                    }

                    if (lastValidTimestampSeconds == null || currentTs < lastValidTimestampSeconds) {
                        lastValidTimestampSeconds = currentTs;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Final Validation
        if (
            adjPricesLen < 2 || ohlcvLen < 2
            || firstTradeDateTimestampSeconds == null || lastValidTimestampSeconds == null
            || DP_S_num == 0 || DP_S_den == 0 || DB_S_num == 0 || DB_S_den == 0
        ) {
            throw new Exception("Invalid History: Insufficient valid data points.");
        }

        // Final Metrics
        double percentageIncreasePerDay = calculatePercentageIncreasePerDay(firstAdjPrice, lastAdjPrice, firstTradeDateTimestampSeconds);
        double densePrice = DP_S_num / DP_S_den;
        double densePctDif = Math.abs(DB_S_num / DB_S_den);
        double upperBand = densePrice * (1 + densePctDif);
        double lowerBand = densePrice / (1 + densePctDif);

        // Create and return TickerInfo
        return new TickerInfo(
            symbol,
            exchange,
            name,
            type,
            lastAdjPrice,
            densePrice,
            upperBand,
            lowerBand,
            percentageIncreasePerDay,
            firstTradeDateTimestampSeconds,
            marketCap,
            currentUpdateTimestamp,
            lastValidTimestampSeconds,
            finalTimestamps,
            finalAdjCloses,
            finalHighs,
            finalLows,
            finalVolumes
        );
    }

    private Set<String> getDeletedTickers(Map<String, TickerInfo> existingTickersMap, Map<String, Long> currentSymbols) {
        Set<String> deleted = new HashSet<>();
        for (Map.Entry<String, TickerInfo> entry : existingTickersMap.entrySet()) {
            String symbol = entry.getKey();
            if (!currentSymbols.containsKey(symbol)) {
                deleted.add(symbol);
            }
        }
        return deleted;
    }

    private double calculatePercentageIncreasePerDay(
        double firstPrice,
        double lastPrice,
        long firstTradeDateTimestampSeconds
    ) throws Exception {
        double numberOfDays = (System.currentTimeMillis() - (firstTradeDateTimestampSeconds * 1000)) / 86400000D;
        double percentageIncreasePerDay = (((lastPrice - firstPrice) / firstPrice) * 100) / numberOfDays;
        if (isFinite(percentageIncreasePerDay)) {
            return percentageIncreasePerDay;
        } else {
            throw new Exception("Invalid Percentage Increase Per Day");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveCache(@NonNull Context context, @NonNull List<TickerInfo> tickers, long updateTime) throws IOException {
        CachedTickerData cache = new CachedTickerData(tickers, updateTime);
        saveObject(context, CACHE_FILE_DATA, cache);
    }

    public List<TickerInfo> loadCache(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) loadObject(context, CACHE_FILE_DATA);
            return cache != null ? cache.tickers() : null;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache", e);
            return null;
        }
    }

    public long getCacheTimestamp(@NonNull Context context) {
        try {
            CachedTickerData cache = (CachedTickerData) loadObject(context, CACHE_FILE_DATA);
            return cache != null ? cache.timestamp() : 0;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache timestamp", e);
            return 0;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private @NonNull JSONObject fetchTickerData(String symbol, long startTimestampSeconds) throws Exception {
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

        for (int i = 0; i < attempts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted during fetchTickerData");
            }

            Attempt attempt = attempts.get(i);
            String url = new UrlBuilder(attempt.baseUrl)
                .addPath(symbol)
                .addParam("period1", NumberUtils.toFullNumericString(startTimestampSeconds))
                .addParam("period2", NumberUtils.toFullNumericString(Math.floor(System.currentTimeMillis() / 1000D) + 2678400L))
                .addParam("interval", attempt.interval)
                .addParam("includePrePost", "true")
                .addParam("events", "div|split|earn")
                .addParam("lang", "en-US")
                .addParam("region", "US")
                .addParam("source", "cosaic")
                .build();

            Request request = new Request.Builder().url(url).build();

            try {
                //noinspection BusyWait
                Thread.sleep(getThrottleDelay(MINIMUM_REQUEST_TIMEOUT_MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }

            try (Response response = client.newCall(request).execute()) {

                if (!response.isSuccessful()) {
                    throw new Exception("Unexpected " + symbol + " response: " + response);
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new Exception("Empty response body");
                }

                // Read and parse response
                return new JSONObject(body.string())
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0);

            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (i == attempts.size() - 1) {
                    throw e;
                }
            }
        }

        throw new Exception("Failed to fetch ticker data (no attempts performed)");
    }

    private Map<String, Long> fetchSymbols(Context context) throws Exception {
        Map<String, Long> symbols = new HashMap<>();

        String[] screeners = new String[]{
            SCREENER_CRYPTO,
            SCREENER_STOCKS
        };

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long minMarketCap = prefs.getLong(PREF_FILTER_MARKET_CAP_THRESHOLD, DEFAULT_MARKET_CAP_THRESHOLD);

        for (String screener : screeners) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted while fetching symbols");
            }

            int retryCount = 0;
            long start_index = 0;
            String prevFirstSymbol = null;

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted during symbol pagination");
                }

                String url = new UrlBuilder(retryCount > 0 ? YAHOO_SCREENER_URL_ALT : YAHOO_SCREENER_URL)
                    .addParam("count", NumberUtils.toFullNumericString(SCREENER_COUNT))
                    .addParam("scrIds", screener)
                    .addParam("sortField", "intradaymarketcap")
                    .addParam("sortType", "DESC")
                    .addParam("start", NumberUtils.toFullNumericString(start_index))
                    .build();

                Request request = new Request.Builder().url(url).build();

                try {
                    //noinspection BusyWait
                    Thread.sleep(getThrottleDelay(MINIMUM_REQUEST_TIMEOUT_MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }

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

                    int length = quotes.length();
                    if (length < 1) break;

                    String firstSymbol = quotes.getJSONObject(0).getString("symbol");
                    if (firstSymbol.equals(prevFirstSymbol)) break;
                    prevFirstSymbol = firstSymbol;

                    boolean stopPaging = false;
                    for (int i = 0; i < length; i++) {
                        try {
                            JSONObject object = quotes.getJSONObject(i);
                            String symbol = object.getString("symbol");
                            long marketCap = object.getLong("marketCap");
                            if (!symbol.isEmpty() && marketCap > 0 && isFinite(marketCap)) {
                                if (marketCap < minMarketCap) {
                                    stopPaging = true;
                                    break;
                                }
                                symbols.put(symbol, marketCap);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (stopPaging) break;

                    retryCount = 0;
                    start_index += length;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    if (retryCount >= MAX_FETCH_RETRY_COUNT) {
                        throw e;
                    } else {
                        retryCount++;
                    }
                }
            }
        }

        return symbols;
    }
}