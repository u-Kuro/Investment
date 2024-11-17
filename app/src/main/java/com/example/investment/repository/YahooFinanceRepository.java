package com.example.investment.repository;

import static java.lang.Double.isFinite;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.investment.model.TickerInfo;
import com.example.investment.util.Logger;
import com.example.investment.repository.cache.CacheModels.CachedData;
import com.example.investment.repository.cache.CacheModels.HtmlCache;
import com.example.investment.repository.cache.CacheModels.Listener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class YahooFinanceRepository {
    private static final String CRYPTO_URL = "https://finance.yahoo.com/markets/crypto/all/?start=0&count=250";
    private static final String STOCKS_URL = "https://finance.yahoo.com/markets/stocks/most-active/?start=0&count=250";
    private static final String CACHE_FILE = "ticker_cache.ser";
    private static final String STOCKS_CACHE_FILE = "stocks_html_cache.ser";
    private static final String CRYPTO_CACHE_FILE = "crypto_html_cache.ser";
    private static final long HTML_CACHE_DURATION = TimeUnit.DAYS.toMillis(1);
    private static final String TICKER_CACHE_DIR = "ticker_cache";
    private static final long TICKER_CACHE_DURATION = TimeUnit.DAYS.toMillis(1);
    private final Random random = new Random();
    private static final String BASE_URL = "https://query2.finance.yahoo.com/v8/finance/chart/";
    private final OkHttpClient client = new OkHttpClient();

    private @NonNull File getCacheFile(@NonNull Context context, String filename) {
        return new File(context.getApplicationContext().getCacheDir(), filename);
    }

    public void loadTickerScores(Context context, Listener listener) {
        CachedData cachedResults = loadCachedData(context);
        if (cachedResults != null) {
            Logger.debug("Using cached ticker data from: " + new Date(cachedResults.timestamp));
            listener.onTickerUpdate(cachedResults.data);
        }

        List<TickerInfo> tickers = new ArrayList<>();
        List<String> tickerHTMLs = new ArrayList<>();

        try {
            listener.onProgress(0, 0);

            // Try to load cached HTML first
            String stocksHtml = loadHtmlCache(context, STOCKS_CACHE_FILE);
            String cryptoHtml = loadHtmlCache(context, CRYPTO_CACHE_FILE);

            // Fetch fresh data if cache is missing or expired
            if (stocksHtml == null) {
                Thread.sleep(1000 + random.nextInt(2000));
                Document stocksDoc = Jsoup.connect(STOCKS_URL).get();
                stocksHtml = stocksDoc.html();
                saveHtmlCache(context, stocksHtml, STOCKS_CACHE_FILE);
            }

            if (cryptoHtml == null) {
                Thread.sleep(1000 + random.nextInt(2000));
                Document cryptoDoc = Jsoup.connect(CRYPTO_URL).get();
                cryptoHtml = cryptoDoc.html();
                saveHtmlCache(context, cryptoHtml, CRYPTO_CACHE_FILE);
            }

            // Parse tickers from HTML
            tickerHTMLs.addAll(parseTickersFromHtml(stocksHtml));
            tickerHTMLs.addAll(parseTickersFromHtml(cryptoHtml));

            Logger.debug("Found " + tickerHTMLs.size() + " tickers to process");

            int total = tickerHTMLs.size();
            int current = 0;

            for (String symbol : tickerHTMLs) {
                try {
                    current++;
                    listener.onProgress(current, total);

                    JSONObject data = getCachedTicker(context, symbol);

                    if (data == null) {
                        Thread.sleep(1000 + random.nextInt(2000));
                        data = fetchTickerData(context, symbol);
                    }

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
                    double sumOfVolatility = 0;
                    for (int i = 0; i < numberOfDays; i++) {
                        double closePrice = closes.getDouble(i);
                        sumOfClose += closePrice;
                        // Calculate and Sum Volatility
                        if (i > 0) {
                            sumOfVolatility += Math.abs(closePrice - (sumOfClose / (i + 1)));
                        }
                    }
                    double densePrice = sumOfClose / numberOfDays;
                    double averageVolatility = sumOfVolatility / (numberOfDays - 1);
                    double upperPriceBoundary = densePrice + averageVolatility;
                    double lowerPriceBoundary = densePrice - averageVolatility;
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

        saveCache(context, tickers);

        listener.onFinish();
    }

    private void saveCache(@NonNull Context context, List<TickerInfo> results) {
        File cacheFile = getCacheFile(context, CACHE_FILE);
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
        File cacheFile = getCacheFile(context, CACHE_FILE);
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

        // Select all ticker cells using the data-testid attribute
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

    private @NonNull JSONObject fetchTickerData(@NonNull Context context, String symbol) throws Exception {
        String url = BASE_URL + symbol + "?interval=1d&range=max";
        Request request = new Request.Builder()
            .url(url)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("Unexpected response " + response);

            ResponseBody body = response.body();
            if (body == null) throw new Exception("Empty response body");

            String responseData = body.string();
            JSONObject jsonResponse = new JSONObject(responseData);

            // Save to cache if the JSON parsing was successful
            File cacheFile = getTickerCacheFile(context, symbol);
            if (cacheFile != null) {
                try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
                    HtmlCache cache = new HtmlCache(System.currentTimeMillis(), responseData);
                    oos.writeObject(cache);
                } catch (IOException e) {
                    Logger.error("Error saving ticker cache for " + symbol, e);
                }
            }

            return jsonResponse;
        }
    }

    private JSONObject getCachedTicker(@NonNull Context context, String symbol) throws JSONException {
        File cacheFile = getTickerCacheFile(context, symbol);
        if (cacheFile != null && cacheFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
                HtmlCache cache = (HtmlCache) ois.readObject();
                if (System.currentTimeMillis() - cache.timestamp < TICKER_CACHE_DURATION) {
                    return new JSONObject(cache.html);
                }
            } catch (IOException | ClassNotFoundException e) {
                Logger.error("Error loading ticker cache for " + symbol, e);
            }
        }
        return null;
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

    private void saveHtmlCache(@NonNull Context context, String html, String cacheFilename) {
        File cacheFile = getCacheFile(context, cacheFilename);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            HtmlCache cache = new HtmlCache(System.currentTimeMillis(), html);
            oos.writeObject(cache);
        } catch (IOException e) {
            Logger.error("Error saving HTML cache", e);
        }
    }

    private String loadHtmlCache(@NonNull Context context, String cacheFilename) {
        File cacheFile = getCacheFile(context, cacheFilename);
        try {
            if (!cacheFile.exists()) {
                return null;
            }
            
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
                HtmlCache cache = (HtmlCache) ois.readObject();
                if (System.currentTimeMillis() - cache.timestamp < HTML_CACHE_DURATION) {
                    return cache.html;
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error loading HTML cache", e);
        }
        return null;
    }

    private File getTickerCacheFile(@NonNull Context context, String symbol) {
        File cacheDir = getCacheFile(context, TICKER_CACHE_DIR);
        if (cacheDir.exists() || cacheDir.mkdirs()) {
            return new File(cacheDir, symbol + "_cache.ser");
        }
        return null;
    }
} 