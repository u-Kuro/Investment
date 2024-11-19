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

import java.io.IOException;
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
    private final OkHttpClient client = new OkHttpClient();
    private final Random random = new Random();

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void loadTickerScores(Context context, TickerUpdateListener listener) {
        List<TickerInfo> tickers = new ArrayList<>();

        try {
            listener.onProgress(0, 0);

            // Parse tickers from HTML
            Thread.sleep(1000 + random.nextInt(2000));
            List<String> tickerHTMLs = new ArrayList<>(parseTickersFromHtml(
                Jsoup.connect(Constants.YAHOO_STOCKS_URL).get().html()
            ));
            Thread.sleep(1000 + random.nextInt(2000));
            tickerHTMLs.addAll(parseTickersFromHtml(
                Jsoup.connect(Constants.YAHOO_CRYPTO_URL).get().html()
            ));

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

                    JSONArray adjCloses = result.getJSONObject("indicators")
                            .getJSONArray("adjclose")
                            .getJSONObject(0)
                            .getJSONArray("adjclose");

                    // Get Indicators and Prices
                    long numberOfDays = adjCloses.length();
                    double firstPrice = adjCloses.getDouble(0);
                    double lastPrice = 0;
                    double sumOfClose = 0;
                    double sumOfPercentageDifference = 0;
                    for (int i = 0; i < numberOfDays; i++) {
                        double closePrice;
                        // Get and Include Close Price if Available
                        try {
                            closePrice = adjCloses.getDouble(i);
                        } catch (Exception ignored) {
                            --numberOfDays;
                            continue;
                        }
                        sumOfClose += closePrice;
                        // Calculate and Sum Percentage Difference of Mean and Price
                        double densePrice = sumOfClose / (i + 1);
                        sumOfPercentageDifference += Math.abs(closePrice - densePrice) / ((closePrice + densePrice) / 2);
                        // Set Last Price Available
                        lastPrice = closePrice;
                    }
                    // Check Invalid Last Price
                    if (lastPrice == 0) throw new Exception("Invalid Last Price");
                    if (numberOfDays < 1) throw new Exception("Invalid Number Of Days");
                    // Calculate Indicators
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
                    } catch (Exception ignored) {
                    }
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
                            name,
                            type,
                            lastPrice,
                            densePrice,
                            upperPriceBoundary,
                            lowerPriceBoundary,
                            percentageIncreasePerDay,
                            adjCloses.length()
                    ));

                    tickers.sort((a, b) -> Double.compare(b.getPercentageIncreasePerDay(), a.getPercentageIncreasePerDay()));
                    listener.onTickerUpdate(tickers);
                } catch (InterruptedException e) {
                    Logger.error("InterruptedException processing " + symbol, e);
                    Thread.currentThread().interrupt();
                    listener.onError();
                    return;
                } catch (Exception e) {
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
        String url = Constants.YAHOO_BASE_URL + symbol + "?interval=1d&range=max";
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