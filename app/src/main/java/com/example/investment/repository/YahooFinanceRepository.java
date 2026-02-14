package com.example.investment.repository;

import static com.example.investment.util.Constants.FILE_RECORD_TICKERS;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.DEFAULT_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.FIRST_TRADE_TIMESTAMP_SECONDS;
import static com.example.investment.util.Constants.MAX_FETCH_RETRY_COUNT;
import static com.example.investment.util.Constants.MINIMUM_REQUEST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.ONE_DAY_INTERVAL;
import static com.example.investment.util.Constants.ONE_MONTH_INTERVAL;
import static com.example.investment.util.Constants.ONE_WEEK_INTERVAL;
import static com.example.investment.util.Constants.PREFS_NAME;
import static com.example.investment.util.Constants.PREF_FILTER_MARKET_CAP_THRESHOLD;
import static com.example.investment.util.Constants.SCREENER_COUNT;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THREE_MONTHS_INTERVAL;
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

import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.ticker.TickerData.FixedHistoricalData;
import com.example.investment.util.Logger;
import com.example.investment.model.TickerDataRecord;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.NumberUtils;
import com.example.investment.util.UrlBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
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
    private record FetchSymbolsResult(@NonNull String type, long marketCap) {}
    private record FetchTickerResult(@NonNull JSONObject result, @NonNull String interval, boolean hasFixedHistoricalData) {}
    private record FetchTickerAttempt(@NonNull String interval, @NonNull String baseUrl) {}
    private final OkHttpClient client = new OkHttpClient();
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateTickers(Context context, boolean isFullRefresh, TickerUpdateListener listener) {
        List<TickerData> tickersList = new ArrayList<>();
        Map<String, TickerData> oldTickersMap = new HashMap<>();
        Set<String> deletedTickersSet = new HashSet<>();

        try {
            listener.onProgress(0, 0);

            // Get new tickers from screeners
            Map<String, FetchSymbolsResult> newSymbolsMap = fetchTickers(context);

            // Load old ticker data record if doing partial refresh
            if (!isFullRefresh) {
                List<TickerData> oldTickersList = getTickersData(context);
                if (oldTickersList != null) {
                    for (TickerData ticker : oldTickersList) {
                        oldTickersMap.put(ticker.getSymbol(), ticker);
                    }

                    // Find deleted tickers
                    if (!oldTickersMap.isEmpty()) {
                        deletedTickersSet = getDeletedTickers(oldTickersMap, newSymbolsMap);
                    }
                } else {
                    isFullRefresh = true;
                }
            }

            int total = newSymbolsMap.size();
            int current = 0;

            for (Map.Entry<String, FetchSymbolsResult> newSymbolEntry : newSymbolsMap.entrySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted in main ticker loop");
                }

                @NonNull String symbol = newSymbolEntry.getKey();
                TickerData oldTicker = oldTickersMap.get(symbol);
                try {
                    // Fetch and process data
                    TickerData ticker = processTickerData(
                        newSymbolEntry,
                        oldTicker,
                        isFullRefresh
                    );

                    // If ticker update succeeded, put its new data in the list
                    tickersList.add(ticker);
                    tickersList.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                    listener.onTickerUpdate(tickersList);
                    listener.onProgress(++current, total);

                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    Logger.error("Error processing " + symbol, e);

                    // If ticker update failed, put back its old data in the list
                    if (oldTicker != null) {
                        tickersList.add(oldTicker);
                        tickersList.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                        listener.onTickerUpdate(tickersList);
                    }
                    listener.onProgress(++current, total);
                }
            }
        } catch (InterruptedException e) {
            Logger.error("InterruptedException fetching ticker data", e);
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
            listener.onError();
            return;
        }

        // Delete missing tickers
        Set<String> finalDeletedTickersSet = deletedTickersSet;
        tickersList.removeIf(tickerData -> finalDeletedTickersSet.contains(tickerData.getSymbol()));

        tickersList.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));

        if (tickersList.isEmpty()) {
            listener.onError();
        } else {
            try {
                long updateTime = System.currentTimeMillis();
                saveTickerDataRecord(context, tickersList, updateTime);
                listener.onFinish(tickersList, updateTime);
            } catch (Exception e) {
                Logger.error("Error saving data", e);
                listener.onError();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private TickerData processTickerData(
        Map.Entry<String, FetchSymbolsResult> newSymbolEntry,
        TickerData oldTicker,
        boolean isFullRefresh
    ) throws Exception {

        FetchSymbolsResult newSymbolEntryValue = newSymbolEntry.getValue();
        @NonNull String symbol = newSymbolEntry.getKey();

        FetchTickerResult fetchTickerResult = fetchTickerDataWithInterval(symbol, oldTicker, isFullRefresh);
        @NonNull JSONObject result = fetchTickerResult.result;
        @NonNull String interval = fetchTickerResult.interval;

        final boolean SHOULD_UPDATE_ALL_DATA = isFullRefresh || oldTicker == null;
        final boolean HAS_FIXED_HISTORICAL_DATA = fetchTickerResult.hasFixedHistoricalData;

        // Get arrays from API response (YFinance)
        JSONArray apiTimestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray apiAdjCloses = indicators.getJSONArray("adjclose").getJSONObject(0).getJSONArray("adjclose");
        JSONArray apiHighs = quote.getJSONArray("high");
        JSONArray apiLows = quote.getJSONArray("low");
        JSONArray apiVolumes = quote.getJSONArray("volume");

        // Initialize ticker data
        String name = null;
        String exchange = null;
        Long firstTradeTimestampSeconds = null;
        Double firstPrice = null;
        Double lastPrice = null;
        final @NonNull Map<String, FixedHistoricalData> fixedHistoricalDataMap;

        // Initialize fixed historical data
        Long updatedAtTimestampSeconds = null;
        @NonNull BigDecimal DP_S_num = BigDecimal.ZERO;
        @NonNull BigDecimal DP_S_den = BigDecimal.ZERO;
        @NonNull BigDecimal DB_S_num = BigDecimal.ZERO;
        @NonNull BigDecimal DB_S_den = BigDecimal.ZERO;

        // Update all data
        if (SHOULD_UPDATE_ALL_DATA) {
            try {
                JSONObject meta = result.getJSONObject("meta");
                try { name = meta.optString("longName", meta.getString("shortName")); } catch (Exception ignored) {}
                try { exchange = meta.getString("fullExchangeName"); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            fixedHistoricalDataMap = new HashMap<>();
        } else {
            name = oldTicker.getName(false);
            exchange = oldTicker.getExchange();
            firstTradeTimestampSeconds = oldTicker.getFirstTradeTimestampSeconds();
            firstPrice = oldTicker.getFirstPrice();
            lastPrice = oldTicker.getLastPrice();
            fixedHistoricalDataMap = oldTicker.getFixedHistoricalDataMap();
            if (HAS_FIXED_HISTORICAL_DATA) {
                FixedHistoricalData fixedHistoricalData = oldTicker.getFixedHistoricalData(interval);
                if (fixedHistoricalData == null) {
                    throw new NullPointerException("Fixed historical data \""+interval+"\" is expected to be Non-Null");
                }
                updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds();
                DP_S_num = fixedHistoricalData.dpSumNum();
                DP_S_den = fixedHistoricalData.dpSumDen();
                DB_S_num = fixedHistoricalData.dbSumNum();
                DB_S_den = fixedHistoricalData.dbSumDen();
            }
        }

        // For full refresh: start from 0
        // For incremental: skip first element to avoid duplicate
        int startIndex = SHOULD_UPDATE_ALL_DATA ? 0 : 1;
        for (int i = startIndex; i < apiAdjCloses.length(); i++) {
            try {
                double currentAC = apiAdjCloses.getDouble(i);
                long currentTs = apiTimestamps.getLong(i);

                if (
                    currentAC > 0 && isFinite(currentAC)
                    && currentTs > 0 && isFinite(currentTs) && System.currentTimeMillis() > (currentTs * 1000)
                ) {

                    // Track very first trade date and its corresponding price
                    if (firstTradeTimestampSeconds == null || firstTradeTimestampSeconds > currentTs) {
                        firstTradeTimestampSeconds = currentTs;
                        firstPrice = currentAC;
                    }

                    // Always update last price with the latest value
                    lastPrice = currentAC;
                }
            } catch (Exception ignored) {}
        }

        // For fixed historical data only use non changing data (before last bar || length - 1)
        for (int i = startIndex; i < apiHighs.length() - 1; i++) {
            try {
                long currentTs = apiTimestamps.getLong(i);

                // If timestamp is valid then its assumed to be updated even if other data are null
                if (currentTs > 0 && isFinite(currentTs) && (updatedAtTimestampSeconds == null || currentTs > updatedAtTimestampSeconds)) {
                    updatedAtTimestampSeconds = currentTs;

                    // Still, only add historical summed values if its valid (it's a skipped bar if either one is null)
                    double currentHigh = apiHighs.getDouble(i);
                    double currentLow = apiLows.getDouble(i);
                    long currentVol = apiVolumes.getLong(i);
                    if (
                        currentHigh > 0 && isFinite(currentHigh)
                        && currentLow > 0 && isFinite(currentLow)
                        && currentVol > 0 && isFinite(currentVol)
                    ) {
                        double bar_range = currentHigh - currentLow;
                        double mid_point = (currentHigh + currentLow) / 2.0;

                        // Calculate DP increments
                        double DP_num_inc = bar_range > 0 ? (currentVol * bar_range * mid_point) : (currentVol * mid_point);
                        double D_den_inc = bar_range > 0 ? (currentVol * bar_range) : currentVol;

                        if (!isNaN(DP_num_inc) && !isNaN(D_den_inc)) {
                            DP_S_num = DP_S_num.add(BigDecimal.valueOf(DP_num_inc), MathContext.DECIMAL128);
                            DP_S_den = DP_S_den.add(BigDecimal.valueOf(D_den_inc), MathContext.DECIMAL128);
                        }

                        // Calculate DB increments
                        if (DP_S_num.compareTo(BigDecimal.ZERO) != 0 && DP_S_den.compareTo(BigDecimal.ZERO) != 0) {

                            @NonNull BigDecimal densePrice = DP_S_num.divide(DP_S_den, MathContext.DECIMAL128);
                            double densePriceDouble = densePrice.doubleValue();

                            double pct_dif = 2 - (4 * densePriceDouble * Math.log((currentHigh + densePriceDouble) / (currentLow + densePriceDouble)) / bar_range);

                            double DB_num_inc = bar_range > 0 ? (currentVol * bar_range * pct_dif) : (currentVol * pct_dif);

                            if (!isNaN(DB_num_inc) && !isNaN(D_den_inc)) {
                                DB_S_num = DB_S_num.add(BigDecimal.valueOf(DB_num_inc), MathContext.DECIMAL128);
                                DB_S_den = DB_S_den.add(BigDecimal.valueOf(D_den_inc), MathContext.DECIMAL128);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Final Validation
        if (
            lastPrice == null || firstPrice == null || firstTradeTimestampSeconds == null
            // Checks for Historical Data
            || updatedAtTimestampSeconds == null
            || DP_S_num.compareTo(BigDecimal.ZERO) == 0 || DP_S_den.compareTo(BigDecimal.ZERO) == 0
            || DB_S_num.compareTo(BigDecimal.ZERO) == 0 || DB_S_den.compareTo(BigDecimal.ZERO) == 0
        ) {
            throw new Exception("Invalid History: Insufficient valid data points.");
        }

        // Add/Update historical data for the interval
        fixedHistoricalDataMap.put(
            interval,
            new FixedHistoricalData(
                updatedAtTimestampSeconds,
                DP_S_num,
                DP_S_den,
                DB_S_num,
                DB_S_den
            )
        );

        // Create and return TickerData
        return new TickerData(
            symbol,
            newSymbolEntryValue.type,
            name,
            exchange,
            newSymbolEntryValue.marketCap,
            firstTradeTimestampSeconds,
            firstPrice,
            lastPrice,
            fixedHistoricalDataMap
        );
    }

    private Set<String> getDeletedTickers(Map<String, TickerData> oldTickersMap, Map<String, FetchSymbolsResult> newTickers) {
        Set<String> deleted = new HashSet<>();
        for (Map.Entry<String, TickerData> oldTickerEntry : oldTickersMap.entrySet()) {
            String oldSymbol = oldTickerEntry.getKey();
            if (!newTickers.containsKey(oldSymbol)) {
                deleted.add(oldSymbol);
            }
        }
        return deleted;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveTickerDataRecord(@NonNull Context context, @NonNull List<TickerData> tickers, long updateTime) throws IOException {
        TickerDataRecord tickerDataRecord = new TickerDataRecord(tickers, updateTime);
        saveObject(context, FILE_RECORD_TICKERS, tickerDataRecord);
    }

    public List<TickerData> getTickersData(@NonNull Context context) {
        try {
            TickerDataRecord tickerDataRecord = (TickerDataRecord) loadObject(context, FILE_RECORD_TICKERS);
            return tickerDataRecord != null ? tickerDataRecord.tickers() : null;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error getting tickers", e);
            return null;
        }
    }

    public long getLastUpdateTime(@NonNull Context context) {
        try {
            TickerDataRecord tickerDataRecord = (TickerDataRecord) loadObject(context, FILE_RECORD_TICKERS);
            return tickerDataRecord != null ? tickerDataRecord.timestamp() : 0;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading tickers last update timestamp", e);
            return 0;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private FetchTickerResult fetchTickerDataWithInterval(String symbol, TickerData oldTicker, boolean isFullRefresh) throws Exception {

        List<FetchTickerAttempt> fetchTickerAttempts = Arrays.asList(
            new FetchTickerAttempt(ONE_DAY_INTERVAL, YAHOO_CHART_URL),
            new FetchTickerAttempt(ONE_DAY_INTERVAL, YAHOO_CHART_URL_ALT),
            new FetchTickerAttempt(ONE_WEEK_INTERVAL, YAHOO_CHART_URL),
            new FetchTickerAttempt(ONE_WEEK_INTERVAL, YAHOO_CHART_URL_ALT),
            new FetchTickerAttempt(ONE_MONTH_INTERVAL, YAHOO_CHART_URL),
            new FetchTickerAttempt(ONE_MONTH_INTERVAL, YAHOO_CHART_URL_ALT),
            new FetchTickerAttempt(THREE_MONTHS_INTERVAL, YAHOO_CHART_URL),
            new FetchTickerAttempt(THREE_MONTHS_INTERVAL, YAHOO_CHART_URL_ALT)
        );

        for (int i = 0; i < fetchTickerAttempts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted during fetchTickerData");
            }

            FetchTickerAttempt fetchTickerAttempt = fetchTickerAttempts.get(i);
            final @NonNull String interval = fetchTickerAttempt.interval;
            final @NonNull String baseUrl = fetchTickerAttempt.baseUrl;
            final long oldUpdatedAtTimestampSeconds;
            final boolean hasFixedHistoricalData;
            if (isFullRefresh || oldTicker == null) {
                oldUpdatedAtTimestampSeconds = FIRST_TRADE_TIMESTAMP_SECONDS;
                hasFixedHistoricalData = false;
            } else {
                FixedHistoricalData fixedHistoricalData = oldTicker.getFixedHistoricalData(interval);
                if (fixedHistoricalData == null) {
                    oldUpdatedAtTimestampSeconds = FIRST_TRADE_TIMESTAMP_SECONDS;
                    hasFixedHistoricalData = false;
                } else {
                    oldUpdatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds();
                    hasFixedHistoricalData = true;
                }
            }

            String url = new UrlBuilder(baseUrl)
                .addPath(symbol)
                .addParam("period1", NumberUtils.toFullNumericString(oldUpdatedAtTimestampSeconds))
                .addParam("period2", NumberUtils.toFullNumericString(Math.floor(System.currentTimeMillis() / 1000D) + 2678400L))
                .addParam("interval", interval)
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
                JSONObject resultObj = new JSONObject(body.string())
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0);

                return new FetchTickerResult(resultObj, interval, hasFixedHistoricalData);

            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (i == fetchTickerAttempts.size() - 1) {
                    throw e;
                }
            }
        }

        throw new Exception("Failed to fetch ticker data (no fetchTickerAttempts performed)");
    }
    private Map<String, FetchSymbolsResult> fetchTickers(Context context) throws Exception {
        Map<String, FetchSymbolsResult> symbols = new HashMap<>();

        String[] screeners = new String[]{ SCREENER_CRYPTO, SCREENER_STOCKS };

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
                                symbols.put(symbol, new FetchSymbolsResult(SCREENER_CRYPTO.equals(screener) ? CRYPTOCURRENCY_TYPE : STOCK_TYPE, marketCap));
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