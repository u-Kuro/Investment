package com.example.investment.repository;

import static com.example.investment.util.Constants.ASSET_INTERVALS;
import static com.example.investment.util.Constants.FILE_RECORD_TICKERS;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.FIRST_TRADE_TIMESTAMP_SECONDS;
import static com.example.investment.util.Constants.MAX_FETCH_RETRY_COUNT;
import static com.example.investment.util.Constants.MINIMUM_REQUEST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.SCREENER_COUNT;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.Constants.YAHOO_CHART_URL;
import static com.example.investment.util.Constants.YAHOO_CHART_URL_ALT;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL_ALT;
import static com.example.investment.util.NetworkUtils.getThrottleDelay;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;
import static com.example.investment.util.BigDecimalUtils.safeLog;
import static java.math.MathContext.DECIMAL128;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.ticker.TickerData.FixedHistoricalData;
import com.example.investment.util.Logger;
import com.example.investment.model.TickerDataRecord;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.UrlBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
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
    private record FetchSymbolsResult(@NonNull String type, BigDecimal marketCap) {}
    private record FetchTickerResult(@NonNull JSONObject result, @NonNull String interval, boolean hasFixedHistoricalData) {}
    private record FetchTickerAttempt(@NonNull String interval, @NonNull String baseUrl) {}
    @NonNull private static final OkHttpClient client = new OkHttpClient();
    @NonNull private static final BigDecimal TWO = new BigDecimal("2");
    @NonNull private static final BigDecimal FOUR = new BigDecimal("4");

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void updateTickers(Context context, boolean isFullRefresh, TickerUpdateListener listener) {
        List<TickerData> tickersList = new ArrayList<>();
        Map<String, TickerData> oldTickersMap = new HashMap<>();
        Set<String> deletedTickersSet = new HashSet<>();

        try {
            listener.onTickersProgress(0, 0);

            // Get new tickers from screeners
            Map<String, FetchSymbolsResult> newSymbolsMap = fetchTickers(listener);

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
                    listener.onTickerUpdate(tickersList);
                    listener.onTickersProgress(++current, total);

                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    Logger.error("Error processing " + symbol, e);

                    // If ticker update failed, put back its old data in the list
                    if (oldTicker != null) {
                        tickersList.add(oldTicker);
                        listener.onTickerUpdate(tickersList);
                    }
                    listener.onTickersProgress(++current, total);
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
        JSONArray apiCloses = quote.getJSONArray("close");
        JSONArray apiOpens = quote.getJSONArray("open");
        JSONArray apiHighs = quote.getJSONArray("high");
        JSONArray apiLows = quote.getJSONArray("low");
        // Convert since Cryptocurrency volume unit is in USD
        JSONArray apiVolumes = quote.getJSONArray("volume");
        if (newSymbolEntryValue.type.equals(CRYPTOCURRENCY_TYPE)) {
            apiVolumes = convertCryptoVolumeToUnits(apiVolumes, apiCloses);
        }

        // Initialize ticker data
        String name = null;
        String exchange = null;
        BigInteger firstTradeTimestampSeconds = null;
        BigDecimal firstTradeOpenPrice = null;
        BigDecimal lastAdjustedClosePrice = null;
        BigDecimal adjustmentFactor = null;
        final @NonNull Map<String, FixedHistoricalData> fixedHistoricalDataMap;

        // Initialize fixed historical data
        BigInteger updatedAtTimestampSeconds = null;
        @NonNull BigDecimal dpSumNum = BigDecimal.ZERO;
        @NonNull BigDecimal dpSumDen = BigDecimal.ZERO;
        @NonNull BigDecimal dbSumNum = BigDecimal.ZERO;
        @NonNull BigDecimal dbSumDen = BigDecimal.ZERO;
        @NonNull BigDecimal sumDollarVol = BigDecimal.ZERO;
        @NonNull BigDecimal sumDollarVolLen = BigDecimal.ZERO;
        BigDecimal startPrice = null;
        BigInteger startPriceTimestampSeconds = null;

        // Lag variable
        BigDecimal prevUpperBand = null;

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
            firstTradeOpenPrice = oldTicker.getFirstTradeOpenPrice();
            lastAdjustedClosePrice = oldTicker.getLastAdjustedClosePrice();
            adjustmentFactor = oldTicker.getAdjustmentFactor();
            fixedHistoricalDataMap = oldTicker.getFixedHistoricalDataMap();
            if (HAS_FIXED_HISTORICAL_DATA) {
                FixedHistoricalData fixedHistoricalData = oldTicker.getFixedHistoricalData(interval);
                if (fixedHistoricalData == null) {
                    throw new NullPointerException("Fixed historical data \""+interval+"\" is expected to be Non-Null");
                }
                updatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds();
                dpSumNum = fixedHistoricalData.dpSumNum();
                dpSumDen = fixedHistoricalData.dpSumDen();
                dbSumNum = fixedHistoricalData.dbSumNum();
                dbSumDen = fixedHistoricalData.dbSumDen();
                sumDollarVol = fixedHistoricalData.sumDollarVol();
                sumDollarVolLen = fixedHistoricalData.sumDollarVolLen();
                startPrice = fixedHistoricalData.startPrice();
                startPriceTimestampSeconds = fixedHistoricalData.startPriceTimestampSeconds();
                // Fill lag variable
                prevUpperBand = dpSumNum.divide(dpSumDen, DECIMAL128)
                    .multiply(
                        dbSumNum
                        .divide(dbSumDen, DECIMAL128)
                        .abs()
                        .add(BigDecimal.ONE), DECIMAL128
                    );
            }
        }

        // For full refresh: start from 0
        // For incremental: skip first element to avoid duplicate
        int startIndex = SHOULD_UPDATE_ALL_DATA ? 0 : 1;
        for (int i = startIndex; i < apiAdjCloses.length(); i++) {
            try {
                @NonNull BigInteger bTimestamp = new BigInteger(apiTimestamps.getString(i));
                @NonNull BigDecimal bOpen = new BigDecimal(apiOpens.getString(i));
                @NonNull BigDecimal bAdjClose = new BigDecimal(apiAdjCloses.getString(i));
                @NonNull BigDecimal bClose = new BigDecimal(apiCloses.getString(i));

                if (
                    bAdjClose.compareTo(BigDecimal.ZERO) > 0
                    && bClose.compareTo(BigDecimal.ZERO) > 0
                    && bTimestamp.compareTo(BigInteger.ZERO) > 0
                    && bOpen.compareTo(BigDecimal.ZERO) > 0
                    && BigInteger.valueOf(System.currentTimeMillis()).compareTo(bTimestamp.multiply(THOUSAND_BI)) > 0
                ) {
                    // Track very first trade date
                    if (firstTradeTimestampSeconds == null || firstTradeTimestampSeconds.compareTo(bTimestamp) > 0) {
                        firstTradeOpenPrice = bOpen;
                        firstTradeTimestampSeconds = bTimestamp;
                    }

                    // Always update last adjusted close and adjustment factor with the latest value
                    lastAdjustedClosePrice = bAdjClose;
                    adjustmentFactor = lastAdjustedClosePrice.divide(bClose, DECIMAL128);
                }
            } catch (Exception error) {
                Logger.error(error.getMessage(), error);
            }
        }

        // For fixed historical data only use non changing data (before last bar || length - 1)
        for (int i = startIndex; i < apiHighs.length() - 1; i++) {
            try {
                @NonNull BigInteger bTimestamp = new BigInteger(apiTimestamps.getString(i));

                // If timestamp is valid then its assumed to be updated even if other data are null
                if (bTimestamp.compareTo(BigInteger.ZERO) > 0 && (updatedAtTimestampSeconds == null || bTimestamp.compareTo(updatedAtTimestampSeconds) > 0)) {
                    updatedAtTimestampSeconds = bTimestamp;

                    // Still, only add historical summed values if its valid (it's a skipped bar if either one is null)
                    @NonNull BigDecimal bVol = new BigDecimal(apiVolumes.getString(i));
                    try {
                        @NonNull BigDecimal bHigh = new BigDecimal(apiHighs.getString(i));
                        @NonNull BigDecimal bLow = new BigDecimal(apiLows.getString(i));
                        if (
                            bVol.compareTo(BigDecimal.ZERO) > 0
                            && bHigh.compareTo(BigDecimal.ZERO) > 0
                            && bLow.compareTo(BigDecimal.ZERO) > 0
                        ) {
                            @NonNull BigDecimal barRange = bHigh.subtract(bLow, DECIMAL128);
                            @NonNull BigDecimal midPoint = bHigh.add(bLow, DECIMAL128).divide(TWO, DECIMAL128);

                            // Calculate DP increments
                            @NonNull BigDecimal dpNumInc;
                            @NonNull BigDecimal dDenInc;
                            if (barRange.compareTo(BigDecimal.ZERO) > 0) {
                                dDenInc = bVol.multiply(barRange, DECIMAL128);
                            } else {
                                dDenInc = bVol;
                            }
                            dpNumInc = dDenInc.multiply(midPoint, DECIMAL128);

                            dpSumNum = dpSumNum.add(dpNumInc, DECIMAL128);
                            dpSumDen = dpSumDen.add(dDenInc, DECIMAL128);

                            // Calculate DB increments
                            @NonNull BigDecimal densePrice = dpSumNum.divide(dpSumDen, DECIMAL128);
                            @NonNull BigDecimal dbNumInc;
                            @NonNull BigDecimal pctDif;

                            if (barRange.compareTo(BigDecimal.ZERO) > 0) {
                                // Calculate logarithmic ratio: ln((H+DP)/(L+DP))
                                BigDecimal logRatio = BigDecimal.valueOf(
                                    safeLog(
                                        bHigh.add(densePrice, DECIMAL128)
                                        .divide(bLow.add(densePrice, DECIMAL128), DECIMAL128)
                                    )
                                );

                                // Calculate scaled term: 4DP * ln((H+DP)/(L+DP)) / (H-L)
                                BigDecimal scaledLogTerm = FOUR
                                    .multiply(densePrice, DECIMAL128)
                                    .multiply(logRatio, DECIMAL128)
                                    .divide(barRange, DECIMAL128);

                                if (densePrice.compareTo(bLow) < 0) {
                                    // Case 1: All prices above dense price
                                    pctDif = TWO.subtract(scaledLogTerm, DECIMAL128); // 2 - (4DP * ln((H+DP)/(L+DP)) / (H-L))
                                } else if (densePrice.compareTo(bHigh) > 0) {
                                    // Case 2: All prices below dense price
                                    pctDif = scaledLogTerm.subtract(TWO, DECIMAL128); // (4DP * ln((H+DP)/(L+DP)) / (H-L)) - 2
                                } else {
                                    // Case 3: Dense price within range
                                    // Formula: [2(H+L-2DP) + 4DP * ln(4DP² / ((H+DP) * (L+DP)))] / (H-L)
                                    pctDif = TWO
                                        .multiply(
                                            bHigh
                                            .add(bLow, DECIMAL128)
                                            .subtract(
                                                TWO.multiply(densePrice, DECIMAL128), DECIMAL128
                                            ), DECIMAL128
                                        )
                                        .add(
                                            FOUR.multiply(densePrice, DECIMAL128)
                                            .multiply(
                                                BigDecimal.valueOf(
                                                    safeLog(
                                                        FOUR.multiply(densePrice, DECIMAL128).multiply(densePrice, DECIMAL128)
                                                        .divide(
                                                            bLow.add(densePrice, DECIMAL128)
                                                            .multiply(bHigh.add(densePrice, DECIMAL128), DECIMAL128), DECIMAL128
                                                        )
                                                    )
                                                ), DECIMAL128
                                            ), DECIMAL128
                                        )
                                        .divide(barRange, DECIMAL128);
                                }
                                dbNumInc = bVol.multiply(pctDif, DECIMAL128).multiply(barRange, DECIMAL128);
                            } else {
                                // Zero bar range
                                pctDif = midPoint.subtract(densePrice, DECIMAL128).abs()
                                    .divide(
                                        midPoint.add(densePrice, DECIMAL128)
                                        .divide(TWO, DECIMAL128), DECIMAL128
                                    );
                                dbNumInc = bVol.multiply(pctDif, DECIMAL128);
                            }

                            dbSumNum = dbSumNum.add(dbNumInc, DECIMAL128);
                            dbSumDen = dbSumDen.add(dDenInc, DECIMAL128);

                            @NonNull BigDecimal upperBand = densePrice
                                .multiply(
                                    dbSumNum
                                    .divide(dbSumDen, DECIMAL128)
                                    .abs()
                                    .add(BigDecimal.ONE), DECIMAL128
                                );

                            // Trend restarts
                            if (prevUpperBand != null && upperBand.compareTo(prevUpperBand) <= 0) {
                                startPrice = upperBand;
                                startPriceTimestampSeconds = bTimestamp;
                            }

                            prevUpperBand = upperBand;
                        }
                    } catch (Exception error) {
                        Logger.error(error.getMessage(), error);
                    }

                    // For dollar volume
                    @NonNull BigDecimal bClose = new BigDecimal(apiCloses.getString(i));
                    if (
                        bClose.compareTo(BigDecimal.ZERO) > 0
                        && bVol.compareTo(BigDecimal.ZERO) > 0
                    ) {
                        @NonNull BigDecimal dollarVolume = bClose.multiply(bVol, DECIMAL128);

                        sumDollarVol = sumDollarVol.add(dollarVolume, DECIMAL128);
                        sumDollarVolLen = sumDollarVolLen.add(BigDecimal.ONE, DECIMAL128);
                    }
                }
            } catch (Exception error) {
                Logger.error(error.getMessage(), error);
            }
        }

        // Final Validation
        if (
            firstTradeTimestampSeconds == null || firstTradeOpenPrice == null || lastAdjustedClosePrice == null
            || adjustmentFactor == null || adjustmentFactor.compareTo(BigDecimal.ZERO) <= 0
            // Checks for Historical Data
            || updatedAtTimestampSeconds == null
            || dpSumNum.compareTo(BigDecimal.ZERO) == 0 || dpSumDen.compareTo(BigDecimal.ZERO) == 0
            || dbSumNum.compareTo(BigDecimal.ZERO) == 0 || dbSumDen.compareTo(BigDecimal.ZERO) == 0
            || sumDollarVol.compareTo(BigDecimal.ZERO) == 0 || sumDollarVolLen.compareTo(BigDecimal.ZERO) == 0
            || startPrice == null || startPriceTimestampSeconds == null
            || startPrice.compareTo(BigDecimal.ZERO) <= 0
        ) {
            throw new Exception("Invalid History: Insufficient valid data points.");
        }

        // Add/Update historical data for the interval
        fixedHistoricalDataMap.put(
            interval,
            new FixedHistoricalData(
                updatedAtTimestampSeconds,
                dpSumNum,
                dpSumDen,
                dbSumNum,
                dbSumDen,
                sumDollarVol,
                sumDollarVolLen,
                startPrice,
                startPriceTimestampSeconds
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
            firstTradeOpenPrice,
            lastAdjustedClosePrice,
            adjustmentFactor,
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
        TickerDataRecord tickerDataRecord = new TickerDataRecord(new ArrayList<>(tickers), updateTime);
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

        List<FetchTickerAttempt> fetchTickerAttempts = new ArrayList<>();
        for (String interval : ASSET_INTERVALS) {
            fetchTickerAttempts.add(new FetchTickerAttempt(interval, YAHOO_CHART_URL));
            fetchTickerAttempts.add(new FetchTickerAttempt(interval, YAHOO_CHART_URL_ALT));
        }

        for (int i = 0; i < fetchTickerAttempts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted during fetchTickerData");
            }

            FetchTickerAttempt fetchTickerAttempt = fetchTickerAttempts.get(i);
            final @NonNull String interval = fetchTickerAttempt.interval;
            final @NonNull String baseUrl = fetchTickerAttempt.baseUrl;
            final @NonNull String oldUpdatedAtTimestampSeconds;
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
                    oldUpdatedAtTimestampSeconds = fixedHistoricalData.updatedAtTimestampSeconds().toString();
                    hasFixedHistoricalData = true;
                }
            }

            String url = new UrlBuilder(baseUrl)
                .addPath(symbol)
                .addParam("period1", oldUpdatedAtTimestampSeconds)
                .addParam("period2", String.valueOf((System.currentTimeMillis() / 1000L) + 2678400L))
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
    private Map<String, FetchSymbolsResult> fetchTickers(TickerUpdateListener listener) throws Exception {
        Map<String, FetchSymbolsResult> symbols = new HashMap<>();

        String[] screeners = new String[]{ SCREENER_CRYPTO, SCREENER_STOCKS };

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
                    .addParam("count", String.valueOf(SCREENER_COUNT))
                    .addParam("scrIds", screener)
                    .addParam("sortField", "intradaymarketcap")
                    .addParam("sortType", "DESC")
                    .addParam("start", String.valueOf(start_index))
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

                    String firstSymbol = quotes.getJSONObject(0).optString("symbol", "");
                    if (firstSymbol.equals(prevFirstSymbol)) {
                        throw new Exception("Duplicate symbol was found.");
                    }
                    prevFirstSymbol = firstSymbol;

                    for (int i = 0; i < length; i++) {
                        try {
                            JSONObject object = quotes.getJSONObject(i);
                            String symbol = object.getString("symbol");
                            @NonNull BigDecimal marketCap = new BigDecimal(object.getString("marketCap"));
                            if (!symbol.isEmpty() && marketCap.compareTo(BigDecimal.ZERO) > 0) {
                                symbols.put(symbol, new FetchSymbolsResult(SCREENER_CRYPTO.equals(screener) ? CRYPTOCURRENCY_TYPE : STOCK_TYPE, marketCap));
                            }
                        } catch (Exception error) {
                            Logger.error(error.getMessage(), error);
                        }
                    }

                    retryCount = 0;
                    start_index += length;

                    listener.onSymbolsProgress(symbols.size());
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

    private JSONArray convertCryptoVolumeToUnits(JSONArray apiVolumes, JSONArray apiCloses) {
        JSONArray convertedVolumes = new JSONArray();
        for (int i = 0; i < apiVolumes.length(); i++) {
            try {
                @NonNull BigDecimal bVol = new BigDecimal(apiVolumes.getString(i));
                @NonNull BigDecimal bClose = new BigDecimal(apiCloses.getString(i));
                if (bVol.compareTo(BigDecimal.ZERO) > 0 && bClose.compareTo(BigDecimal.ZERO) > 0) {
                    convertedVolumes.put(bVol.divide(bClose, DECIMAL128).toPlainString());
                } else {
                    convertedVolumes.put(JSONObject.NULL);
                }
            } catch (Exception ignored) {
                convertedVolumes.put(JSONObject.NULL);
            }
        }

        return convertedVolumes;
    }
}