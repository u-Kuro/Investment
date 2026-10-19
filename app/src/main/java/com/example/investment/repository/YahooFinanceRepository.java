package com.example.investment.repository;

import static com.example.investment.util.Constants.ASSET_INTERVALS;
import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.FIRST_TRADE_TIMESTAMP_SECONDS;
import static com.example.investment.util.Constants.MINIMUM_REQUEST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.MAX_SCREENER_COUNT;
import static com.example.investment.util.Constants.NO_DATA_CONFIRMATION_COUNT;
import static com.example.investment.util.Constants.REQUEST_CALL_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.REQUEST_CONNECT_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.REQUEST_READ_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.Constants.THOUSAND_BI;
import static com.example.investment.util.Constants.YAHOO_CHART_URL;
import static com.example.investment.util.Constants.YAHOO_CHART_URL_ALT;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL;
import static com.example.investment.util.Constants.YAHOO_SCREENER_URL_ALT;
import static com.example.investment.util.NetworkUtils.getThrottleDelay;
import static com.example.investment.util.NetworkUtils.isOffline;
import static com.example.investment.util.BigDecimalUtils.safeLog;
import static java.math.MathContext.DECIMAL128;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ticker.TickerData;
import com.example.investment.model.ticker.TickerData.FixedHistoricalData;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.util.Logger;
import com.example.investment.util.UrlBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    @FunctionalInterface
    private interface ResponseParser<T> {
        T parse(@NonNull Response response) throws Exception;
    }
    // Yahoo answered properly but with nothing in it (empty page, 404). A throttled IP gets the same answer, so it is never believed from a single IP
    private static class NoDataException extends Exception {
        NoDataException(@NonNull String message) {
            super(message);
        }
    }
    // No network at all, going around the candidates would only waste time. The update is retried once there is one
    private static class OfflineException extends Exception {
        OfflineException(@NonNull String message) {
            super(message);
        }
    }

    @NonNull private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(REQUEST_CONNECT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .readTimeout(REQUEST_READ_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .callTimeout(REQUEST_CALL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .build();
    @NonNull private static final BigDecimal TWO = BigDecimal.valueOf(2);
    @NonNull private static final BigDecimal FOUR = BigDecimal.valueOf(4);

    @NonNull private final ProxyRepository proxyRepository = new ProxyRepository();

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void updateTickers(
        @NonNull Context context,
        @Nullable List<TickerData> oldTickers,
        boolean isFullRefresh,
        @NonNull TickerUpdateListener listener
    ) {
        @NonNull List<TickerData> result = new ArrayList<>();
        boolean effectiveFullRefresh = isFullRefresh || oldTickers == null;
        @NonNull Map<String, TickerData> oldTickersMap = buildOldTickersMap(oldTickers, effectiveFullRefresh);

        try {
            listener.onTickersProgress(0, 0);

            Map<String, FetchSymbolsResult> newSymbolsMap = fetchSymbols(context, listener);

            int total = newSymbolsMap.size();
            int current = 0;

            for (Map.Entry<String, FetchSymbolsResult> entry : newSymbolsMap.entrySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted in main ticker loop");
                }

                @NonNull String symbol = entry.getKey();
                @Nullable TickerData oldTicker = oldTickersMap.get(symbol);

                try {
                    result.add(processTickerData(context, entry, oldTicker, effectiveFullRefresh));
                    listener.onTickerUpdate(result);
                    listener.onTickersProgress(++current, total);
                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    return;
                } catch (OfflineException e) {
                    throw e;
                } catch (Exception e) {
                    Logger.error("Error processing " + symbol, e);
                    if (oldTicker != null) {
                        result.add(oldTicker);
                        listener.onTickerUpdate(result);
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
            listener.onError(oldTickers);
            return;
        }

        if (result.isEmpty()) {
            listener.onError(oldTickers);
        } else {
            listener.onFinish(result, System.currentTimeMillis());
        }
    }

    // Symbol Fetching
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private Map<String, FetchSymbolsResult> fetchSymbols(@NonNull Context context, @NonNull TickerUpdateListener listener) throws Exception {
        Map<String, FetchSymbolsResult> symbols = new HashMap<>();

        for (String screener : new String[]{ SCREENER_CRYPTO, SCREENER_STOCKS }) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted while fetching symbols");
            }

            boolean useAlternativeUrl = false;
            BigInteger startIndex = BigInteger.ZERO;
            String prevFirstSymbol = null;

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Thread interrupted during symbol pagination");
                }

                String url = new UrlBuilder(useAlternativeUrl ? YAHOO_SCREENER_URL_ALT : YAHOO_SCREENER_URL)
                    .addParam("count", Integer.toString(MAX_SCREENER_COUNT))
                    .addParam("scrIds", screener)
                    .addParam("sortField", "intradaymarketcap")
                    .addParam("sortType", "DESC")
                    .addParam("start", startIndex.toString())
                    .build();

                throttle();

                try {
                    JSONArray quotes = fetchResponse(context, url, response -> parseScreenerResponse(screener, response));
                    int length = quotes.length();

                    String firstSymbol = quotes.getJSONObject(0).optString("symbol", "");
                    if (firstSymbol.equals(prevFirstSymbol)) throw new Exception("Duplicate symbol found.");
                    prevFirstSymbol = firstSymbol;

                    for (int i = 0; i < length; i++) {
                        try {
                            JSONObject obj = quotes.getJSONObject(i);
                            String symbol = obj.getString("symbol");
                            @NonNull BigDecimal marketCap = new BigDecimal(obj.getString("marketCap"));
                            if (!symbol.isEmpty() && marketCap.compareTo(BigDecimal.ZERO) > 0) {
                                symbols.put(
                                    symbol,
                                    new FetchSymbolsResult(
                                        SCREENER_CRYPTO.equals(screener) ? CRYPTOCURRENCY_TYPE : STOCK_TYPE,
                                        marketCap
                                    )
                                );
                            }
                        } catch (Exception e) {
                            Logger.error(e.getMessage(), e);
                        }
                    }

                    useAlternativeUrl = false;
                    startIndex = startIndex.add(BigInteger.valueOf(length));
                    listener.onSymbolsProgress(symbols.size());

                } catch (InterruptedException | OfflineException e) {
                    throw e;
                } catch (Exception e) {
                    // An empty page after the first one is the end of the list, once another IP agrees (or there is none left to ask)
                    if (e instanceof NoDataException && startIndex.signum() > 0) break;
                    // Every candidate already had its turn, only the other host is left
                    if (useAlternativeUrl) throw e;
                    useAlternativeUrl = true;
                }
            }
        }

        return symbols;
    }

    private JSONArray parseScreenerResponse(@NonNull String screener, @NonNull Response response) throws Exception {
        if (!response.isSuccessful()) throw new Exception("Unexpected " + screener + " response: " + response);
        ResponseBody body = response.body();
        if (body == null) throw new Exception("Empty response body");
        JSONArray quotes = new JSONObject(body.string())
            .getJSONObject("finance")
            .getJSONArray("result")
            .getJSONObject(0)
            .getJSONArray("quotes");
        if (quotes.length() < 1) throw new NoDataException("Empty " + screener + " page");
        return quotes;
    }

    // Ticker Fetching
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private FetchTickerResult fetchTickerWithInterval(
        @NonNull Context context,
        @NonNull String symbol,
        @Nullable TickerData oldTicker,
        boolean isFullRefresh
    ) throws Exception {
        List<FetchTickerAttempt> attempts = new ArrayList<>();
        for (String interval : ASSET_INTERVALS) {
            attempts.add(new FetchTickerAttempt(interval, YAHOO_CHART_URL));
            attempts.add(new FetchTickerAttempt(interval, YAHOO_CHART_URL_ALT));
        }

        for (int i = 0; i < attempts.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted during fetchTickerData");
            }

            FetchTickerAttempt attempt = attempts.get(i);
            @NonNull String interval = attempt.interval();
            boolean hasFixedHistoricalData;
            @NonNull BigInteger period1;

            if (isFullRefresh || oldTicker == null) {
                period1 = FIRST_TRADE_TIMESTAMP_SECONDS;
                hasFixedHistoricalData = false;
            } else {
                FixedHistoricalData fixedData = oldTicker.getFixedHistoricalData(interval);
                if (fixedData == null) {
                    period1 = FIRST_TRADE_TIMESTAMP_SECONDS;
                    hasFixedHistoricalData = false;
                } else {
                    period1 = fixedData.updatedAtTimestampSeconds();
                    hasFixedHistoricalData = true;
                }
            }

            String url = new UrlBuilder(attempt.baseUrl())
                .addPath(symbol)
                .addParam("period1", period1.toString())
                .addParam("period2", Long.toString((System.currentTimeMillis() / 1000L) + 2678400L))
                .addParam("interval", interval)
                .addParam("includePrePost", "true")
                .addParam("events", "div|split|earn")
                .addParam("lang", "en-US")
                .addParam("region", "US")
                .addParam("source", "cosaic")
                .build();

            throttle();

            try {
                JSONObject resultObj = fetchResponse(context, url, response -> parseChartResponse(symbol, response));

                return new FetchTickerResult(resultObj, interval, hasFixedHistoricalData);

            } catch (InterruptedException | OfflineException e) {
                throw e;
            } catch (Exception e) {
                if (i == attempts.size() - 1) throw e;
            }
        }

        throw new Exception("Failed to fetch ticker data (no attempts succeeded)");
    }

    private JSONObject parseChartResponse(@NonNull String symbol, @NonNull Response response) throws Exception {
        if (!response.isSuccessful()) throw new Exception("Unexpected " + symbol + " response: " + response);
        ResponseBody body = response.body();
        if (body == null) throw new Exception("Empty response body");
        return new JSONObject(body.string())
            .getJSONObject("chart")
            .getJSONArray("result")
            .getJSONObject(0);
    }

    // Request Routing (every candidate once in random order, the own IP being one of them)
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private <T> T fetchResponse(
        @NonNull Context context,
        @NonNull String url,
        @NonNull ResponseParser<T> parser
    ) throws Exception {
        if (isOffline(context)) throw new OfflineException("No network connection");

        @Nullable Exception lastError = null;
        int noDataAnswers = 0;

        for (Proxy candidate : proxyRepository.getCandidates(context)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted while rotating proxies");
            }

            try {
                return send(candidate, url, parser);
            } catch (NoDataException e) {
                lastError = e;
                if (++noDataAnswers >= NO_DATA_CONFIRMATION_COUNT) throw e;
            } catch (Exception e) {
                // Dead proxies are expected, a lost network is not worth going around the rest
                if (isOffline(context)) throw new OfflineException("Network connection lost");
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new Exception("No candidate available");
    }

    private <T> T send(
        @NonNull Proxy proxy,
        @NonNull String url,
        @NonNull ResponseParser<T> parser
    ) throws Exception {
        @NonNull OkHttpClient proxyClient = client.newBuilder().proxy(proxy).build();
        try (Response response = proxyClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (response.code() == 404) throw new NoDataException("Unexpected response: " + response);
            return parser.parse(response);
        }
    }

    // Ticker Processing
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private TickerData processTickerData(
        @NonNull Context context,
        @NonNull Map.Entry<String, FetchSymbolsResult> entry,
        @Nullable TickerData oldTicker,
        boolean isFullRefresh
    ) throws Exception {
        @NonNull String symbol = entry.getKey();
        @NonNull FetchSymbolsResult symbolResult = entry.getValue();

        FetchTickerResult fetchResult = fetchTickerWithInterval(context, symbol, oldTicker, isFullRefresh);
        @NonNull JSONObject result = fetchResult.result();
        @NonNull String interval = fetchResult.interval();

        final boolean shouldUpdateAllData = isFullRefresh || oldTicker == null;
        final boolean hasFixedHistoricalData = fetchResult.hasFixedHistoricalData();

        // Parse API arrays
        JSONArray apiTimestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray apiAdjCloses = indicators.getJSONArray("adjclose").getJSONObject(0).getJSONArray("adjclose");
        JSONArray apiCloses = quote.getJSONArray("close");
        JSONArray apiOpens = quote.getJSONArray("open");
        JSONArray apiHighs = quote.getJSONArray("high");
        JSONArray apiLows = quote.getJSONArray("low");
        JSONArray apiVolumes = symbolResult.type().equals(CRYPTOCURRENCY_TYPE)
            ? convertCryptoVolumeToUnits(quote.getJSONArray("volume"), apiCloses)
            : quote.getJSONArray("volume");

        // Initialize fields
        String name = null;
        String exchange = null;
        BigInteger firstTradeTimestampSeconds = null;
        BigDecimal firstTradeOpenPrice = null;
        BigDecimal lastAdjustedClosePrice = null;
        BigDecimal adjustmentFactor = null;
        final @NonNull Map<String, FixedHistoricalData> fixedHistoricalDataMap;

        // Initialize running sums
        BigInteger updatedAtTimestampSeconds = null;
        @NonNull BigDecimal dpSumNum = BigDecimal.ZERO;
        @NonNull BigDecimal dpSumDen = BigDecimal.ZERO;
        @NonNull BigDecimal dbSumNum = BigDecimal.ZERO;
        @NonNull BigDecimal dbSumDen = BigDecimal.ZERO;
        @NonNull BigDecimal sumDollarVol = BigDecimal.ZERO;
        @NonNull BigDecimal sumDollarVolLen = BigDecimal.ZERO;
        BigDecimal startPrice = null;
        BigInteger startPriceTimestampSeconds = null;
        BigDecimal prevUpperBand = null;

        if (shouldUpdateAllData) {
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

            if (hasFixedHistoricalData) {
                FixedHistoricalData fixedData = oldTicker.getFixedHistoricalData(interval);
                if (fixedData == null) throw new NullPointerException("Fixed historical data \"" + interval + "\" expected non-null");
                updatedAtTimestampSeconds = fixedData.updatedAtTimestampSeconds();
                dpSumNum = fixedData.dpSumNum();
                dpSumDen = fixedData.dpSumDen();
                dbSumNum = fixedData.dbSumNum();
                dbSumDen = fixedData.dbSumDen();
                sumDollarVol = fixedData.sumDollarVol();
                sumDollarVolLen = fixedData.sumDollarVolLen();
                startPrice = fixedData.startPrice();
                startPriceTimestampSeconds = fixedData.startPriceTimestampSeconds();
                prevUpperBand = dpSumNum.divide(dpSumDen, DECIMAL128)
                    .multiply(
                        dbSumNum
                        .divide(dbSumDen, DECIMAL128)
                        .abs()
                        .add(BigDecimal.ONE), DECIMAL128
                    );
            }
        }

        // Last adjusted close + first trade date
        int startIndex = shouldUpdateAllData ? 0 : 1;
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
                    if (firstTradeTimestampSeconds == null || firstTradeTimestampSeconds.compareTo(bTimestamp) > 0) {
                        firstTradeOpenPrice = bOpen;
                        firstTradeTimestampSeconds = bTimestamp;
                    }
                    lastAdjustedClosePrice = bAdjClose;
                    adjustmentFactor = lastAdjustedClosePrice.divide(bClose, DECIMAL128);
                }
            } catch (Exception e) {
                Logger.error(e.getMessage(), e);
            }
        }

        // Fixed historical data (excludes last bar)
        for (int i = startIndex; i < apiHighs.length() - 1; i++) {
            try {
                @NonNull BigInteger bTimestamp = new BigInteger(apiTimestamps.getString(i));

                if (
                    bTimestamp.compareTo(BigInteger.ZERO) > 0
                    && (updatedAtTimestampSeconds == null || bTimestamp.compareTo(updatedAtTimestampSeconds) > 0)
                ) {
                    updatedAtTimestampSeconds = bTimestamp;

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

                            @NonNull BigDecimal dDenInc = barRange.compareTo(BigDecimal.ZERO) > 0
                                ? bVol.multiply(barRange, DECIMAL128)
                                : bVol;
                            @NonNull BigDecimal dpNumInc = dDenInc.multiply(midPoint, DECIMAL128);
                            dpSumNum = dpSumNum.add(dpNumInc, DECIMAL128);
                            dpSumDen = dpSumDen.add(dDenInc, DECIMAL128);

                            @NonNull BigDecimal densePrice = dpSumNum.divide(dpSumDen, DECIMAL128);
                            @NonNull BigDecimal pctDif;

                            if (barRange.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal logRatio = BigDecimal.valueOf(
                                    safeLog(
                                        bHigh.add(densePrice, DECIMAL128)
                                        .divide(bLow.add(densePrice, DECIMAL128), DECIMAL128)
                                    )
                                );
                                BigDecimal scaledLogTerm = FOUR
                                    .multiply(densePrice, DECIMAL128)
                                    .multiply(logRatio, DECIMAL128)
                                    .divide(barRange, DECIMAL128);

                                if (densePrice.compareTo(bLow) < 0) {
                                    pctDif = TWO.subtract(scaledLogTerm, DECIMAL128);
                                } else if (densePrice.compareTo(bHigh) > 0) {
                                    pctDif = scaledLogTerm.subtract(TWO, DECIMAL128);
                                } else {
                                    pctDif = TWO
                                    .multiply(
                                        bHigh.add(bLow, DECIMAL128)
                                        .subtract(TWO.multiply(densePrice, DECIMAL128), DECIMAL128),
                                        DECIMAL128
                                    )
                                    .add(
                                        FOUR.multiply(densePrice, DECIMAL128).multiply(
                                            BigDecimal.valueOf(
                                                safeLog(
                                                    FOUR.multiply(densePrice, DECIMAL128).multiply(densePrice, DECIMAL128)
                                                    .divide(
                                                        bLow.add(densePrice, DECIMAL128)
                                                        .multiply(bHigh.add(densePrice, DECIMAL128), DECIMAL128),
                                                        DECIMAL128
                                                    )
                                                )
                                            ),
                                            DECIMAL128
                                        ),
                                        DECIMAL128
                                    )
                                    .divide(barRange, DECIMAL128);
                                }
                                dbSumNum = dbSumNum.add(bVol.multiply(pctDif, DECIMAL128).multiply(barRange, DECIMAL128), DECIMAL128);
                            } else {
                                pctDif = midPoint.subtract(densePrice, DECIMAL128).abs()
                                    .divide(midPoint.add(densePrice, DECIMAL128).divide(TWO, DECIMAL128), DECIMAL128);
                                dbSumNum = dbSumNum.add(bVol.multiply(pctDif, DECIMAL128), DECIMAL128);
                            }
                            dbSumDen = dbSumDen.add(dDenInc, DECIMAL128);

                            @NonNull BigDecimal upperBand = densePrice.multiply(
                                dbSumNum.divide(dbSumDen, DECIMAL128).abs().add(BigDecimal.ONE), DECIMAL128
                            );
                            if (prevUpperBand != null && upperBand.compareTo(prevUpperBand) <= 0) {
                                startPrice = upperBand;
                                startPriceTimestampSeconds = bTimestamp;
                            }
                            prevUpperBand = upperBand;
                        }
                    } catch (Exception e) {
                        Logger.error(e.getMessage(), e);
                    }

                    @NonNull BigDecimal bClose = new BigDecimal(apiCloses.getString(i));
                    if (bClose.compareTo(BigDecimal.ZERO) > 0 && bVol.compareTo(BigDecimal.ZERO) > 0) {
                        sumDollarVol = sumDollarVol.add(bClose.multiply(bVol, DECIMAL128), DECIMAL128);
                        sumDollarVolLen = sumDollarVolLen.add(BigDecimal.ONE, DECIMAL128);
                    }
                }
            } catch (Exception e) {
                Logger.error(e.getMessage(), e);
            }
        }

        // Validate before building TickerData
        if (
            firstTradeTimestampSeconds == null || firstTradeOpenPrice == null
            || lastAdjustedClosePrice == null || adjustmentFactor == null
            || adjustmentFactor.compareTo(BigDecimal.ZERO) <= 0
            || updatedAtTimestampSeconds == null
            || dpSumNum.compareTo(BigDecimal.ZERO) == 0 || dpSumDen.compareTo(BigDecimal.ZERO) == 0
            || dbSumNum.compareTo(BigDecimal.ZERO) == 0 || dbSumDen.compareTo(BigDecimal.ZERO) == 0
            || sumDollarVol.compareTo(BigDecimal.ZERO) == 0 || sumDollarVolLen.compareTo(BigDecimal.ZERO) == 0
            || startPrice == null || startPriceTimestampSeconds == null
            || startPrice.compareTo(BigDecimal.ZERO) <= 0
        ) {
            throw new Exception("Invalid History: Insufficient valid data points.");
        }

        fixedHistoricalDataMap.put(interval, new FixedHistoricalData(
            updatedAtTimestampSeconds,
            dpSumNum,
            dpSumDen,
            dbSumNum,
            dbSumDen,
            sumDollarVol,
            sumDollarVolLen,
            startPrice,
            startPriceTimestampSeconds
        ));

        return new TickerData(
            symbol,
            symbolResult.type(),
            name,
            exchange,
            symbolResult.marketCap(),
            firstTradeTimestampSeconds,
            firstTradeOpenPrice,
            lastAdjustedClosePrice,
            adjustmentFactor,
            fixedHistoricalDataMap
        );
    }

    // Helpers
    private @NonNull Map<String, TickerData> buildOldTickersMap(@Nullable List<TickerData> oldTickers, boolean effectiveFullRefresh) {
        Map<String, TickerData> map = new HashMap<>();
        if (effectiveFullRefresh || oldTickers == null) return map;
        for (TickerData ticker : oldTickers) map.put(ticker.getSymbol(), ticker);
        return map;
    }
    private @NonNull JSONArray convertCryptoVolumeToUnits(@NonNull JSONArray apiVolumes, @NonNull JSONArray apiCloses) {
        JSONArray converted = new JSONArray();
        for (int i = 0; i < apiVolumes.length(); i++) {
            try {
                @NonNull BigDecimal vol = new BigDecimal(apiVolumes.getString(i));
                @NonNull BigDecimal close = new BigDecimal(apiCloses.getString(i));
                if (vol.compareTo(BigDecimal.ZERO) > 0 && close.compareTo(BigDecimal.ZERO) > 0) {
                    converted.put(vol.divide(close, DECIMAL128).toPlainString());
                } else {
                    converted.put(JSONObject.NULL);
                }
            } catch (Exception ignored) {
                converted.put(JSONObject.NULL);
            }
        }
        return converted;
    }
    private void throttle() throws InterruptedException {
        try {
            Thread.sleep(getThrottleDelay(MINIMUM_REQUEST_TIMEOUT_MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}