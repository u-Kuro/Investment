package com.example.investment.repository;

import static java.lang.Double.isFinite;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.investment.model.TickerInfo;
import com.example.investment.util.Logger;
import com.example.investment.repository.cache.CacheModels.CachedData;
import com.example.investment.repository.cache.CacheModels.Listener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class YahooFinanceRepository {
    private static final String CRYPTO_URL = "https://finance.yahoo.com/markets/crypto/all/?start=0&count=250";
    private static final String STOCKS_URL = "https://finance.yahoo.com/markets/stocks/most-active/?start=0&count=250";
    private static final String CACHE_FILE = "ticker_cache.ser";
    private static final String BASE_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    private final OkHttpClient client = new OkHttpClient();
    private final Random random = new Random();

    private @NonNull File getCacheFile(@NonNull Context context) {
        return new File(context.getApplicationContext().getCacheDir(), YahooFinanceRepository.CACHE_FILE);
    }

    public void loadTickerScores(Context context, Listener listener) {
        List<TickerInfo> tickers = new ArrayList<>();

        try {
            listener.onProgress(0, 0);

            // Parse tickers from HTML
            Thread.sleep(1000 + random.nextInt(2000));
            List<String> tickerHTMLs = new ArrayList<>(parseTickersFromHtml(Jsoup.connect(STOCKS_URL).get().html()));
            Thread.sleep(1000 + random.nextInt(2000));
            tickerHTMLs.addAll(parseTickersFromHtml(Jsoup.connect(CRYPTO_URL).get().html()));

            int total = tickerHTMLs.size();
            int current = 0;

            for (String symbol : tickerHTMLs) {
                try {
                    current++;
                    listener.onProgress(current, total);

                    Thread.sleep(1000 + random.nextInt(2000));

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

                    JSONObject quote = result.getJSONObject("indicators")
                            .getJSONArray("quote")
                            .getJSONObject(0);
                    JSONArray closes = quote.getJSONArray("close");

                    // Get Indicators and Prices
                    double firstPrice = quote.getJSONArray("open").getDouble(0);
                    long numberOfDays = closes.length();
                    double sumOfClose = 0;
                    double sumOfPercentageDifference = 0;
                    for (int i = 0; i < numberOfDays; i++) {
                        double closePrice = closes.getDouble(i);
                        sumOfClose += closePrice;
                        // Calculate and Sum Percentage Difference of Mean and Price
                        double densePrice = sumOfClose / (i + 1);
                        sumOfPercentageDifference += Math.abs(closePrice - densePrice) / ((closePrice + densePrice) / 2);
                    }
                    double densePrice = sumOfClose / numberOfDays;
                    double averagePercentageDifference = sumOfPercentageDifference / numberOfDays;
                    double priceBoundary = averagePercentageDifference * densePrice;
                    double upperPriceBoundary = densePrice + priceBoundary;
                    double lowerPriceBoundary = densePrice - priceBoundary;
                    double percentageIncreasePerDay = calculatePercentageIncreasePerDay(firstPrice, lowerPriceBoundary, numberOfDays);

                    // Get Name
                    String name = null;
                    try {
                        name = meta.getString("longName");
                    } catch (Exception ignored) {}
                    if (name == null) {
                        try {
                            name = meta.getString("shortName");
                        } catch (Exception ignored) {}
                    }

                    // Get Type
                    String type = null;
                    try {
                        type = meta.getString("instrumentType");
                    } catch (Exception ignored) {}

                    tickers.add(new TickerInfo(
                        symbol,
                        name,
                        type,
                        closes.getDouble(closes.length() - 1),
                        densePrice,
                        upperPriceBoundary,
                        lowerPriceBoundary,
                        percentageIncreasePerDay,
                        closes.length()
                    ));

                    tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                    listener.onTickerUpdate(tickers);
                } catch (Exception e) {
                    Logger.error("Unexpected error processing " + symbol, e);
                }
            }

        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
        }

        tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));

        if (!tickers.isEmpty()) {
            saveCache(context, tickers);
        }

        listener.onFinish();
    }

    private void saveCache(@NonNull Context context, List<TickerInfo> results) {
        File cacheFile = getCacheFile(context);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            CachedData cache = new CachedData(System.currentTimeMillis(), results);
            oos.writeObject(cache);
        } catch (IOException e) {
            Logger.error("Error saving cache", e);
        }
    }

    public List<TickerInfo> loadCache(@NonNull Context context) {
        CachedData cache = loadCachedData(context);
        return cache != null ? cache.data : null;
    }

    private CachedData loadCachedData(@NonNull Context context) {
        File cacheFile = getCacheFile(context);
        if (!cacheFile.exists()) {
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
            return (CachedData) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading cache", e);
            return null;
        }
    }

    public long getCacheTimestamp(@NonNull Context context) {
        CachedData cachedData = loadCachedData(context);
        if (cachedData != null) {
            return cachedData.timestamp;
        } else {
            return 0;
        }
    }

    public List<String> parseTickersFromHtml(String html) {
        List<String> tickers = new ArrayList<>();

        // Use JSoup to parse HTML
        Document doc = Jsoup.parse(html);

        // Select all ticker cells using the attribute
        Elements tickerCells = doc.select("a[data-testid='table-cell-ticker']");

        // Extract ticker symbols
        for (Element cell : tickerCells) {
            Element symbolSpan = cell.select("span.symbol").first();
            if (symbolSpan != null) {
                String ticker = symbolSpan.text().trim();
                // Remove any trailing whitespace that might be present
                ticker = ticker.replaceAll("\\s+$", "");
                tickers.add(ticker);
            }
        }

        return tickers;
    }

    private @NonNull JSONObject fetchTickerData(String symbol) throws Exception {
        String url = BASE_URL + symbol + "?interval=1d&range=max";
        Request request = new Request.Builder()
            .url(url)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("Unexpected response " + response);

            ResponseBody body = response.body();
            if (body == null) throw new Exception("Empty response body");

            String responseData = body.string();

            return new JSONObject(responseData);
        }
    }

    private double calculatePercentageIncreasePerDay(
        double firstPrice,
        double lowerPriceBoundary,
        long numberOfDays
    ) throws Exception {
        double priceChange = (((lowerPriceBoundary - firstPrice) / firstPrice) * 100) / numberOfDays;
        if (isFinite(priceChange)) {
            return priceChange;
        } else {
            throw new Exception("Invalid Price Change");
        }
    }
} 