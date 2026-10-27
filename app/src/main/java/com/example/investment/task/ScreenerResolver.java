package com.example.investment.task;

import static com.example.investment.util.Constants.CRYPTOCURRENCY_TYPE;
import static com.example.investment.util.Constants.SCREENER_CRYPTO;
import static com.example.investment.util.Constants.SCREENER_STOCKS;
import static com.example.investment.util.Constants.STOCK_TYPE;
import static com.example.investment.util.DateUtils.getStartOfDayMillis;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ScreenerRecord;
import com.example.investment.model.ScreenerSymbol;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.task.TickerUpdateEngine.OfflineException;
import com.example.investment.util.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class ScreenerResolver {
    @FunctionalInterface
    public interface ScreenerFetcher {
        @NonNull List<ScreenerSymbol> fetch(@NonNull String screenerId) throws Exception;
    }
    public record Resolution(@NonNull List<ScreenerSymbol> universe, @NonNull Set<String> authoritativeTypes) {
        public @NonNull Set<String> symbols() {
            @NonNull Set<String> symbols = new HashSet<>();
            for (ScreenerSymbol screenerSymbol : universe) symbols.add(screenerSymbol.symbol());
            return symbols;
        }

        public @NonNull List<TickerData> pruneRemoved(@NonNull List<TickerData> savedTickers) {
            @NonNull Set<String> symbols = symbols();
            @NonNull List<TickerData> keptTickers = new ArrayList<>();
            for (TickerData ticker : savedTickers) {
                if (authoritativeTypes.contains(ticker.getType()) && !symbols.contains(ticker.getSymbol())) continue;
                keptTickers.add(ticker);
            }
            return keptTickers;
        }
    }

    public static @NonNull String getAssetType(@NonNull String screenerId) {
        return SCREENER_CRYPTO.equals(screenerId) ? CRYPTOCURRENCY_TYPE : STOCK_TYPE;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static @NonNull Resolution resolve(
        @NonNull Map<String, ScreenerRecord> savedRecords,
        @NonNull List<TickerData> savedTickers,
        long nowMillis,
        @NonNull ScreenerFetcher fetcher,
        @NonNull Consumer<ScreenerRecord> recordSaver
    ) throws Exception {
        final long dayStartMillis = getStartOfDayMillis(nowMillis);
        @NonNull List<ScreenerSymbol> universe = new ArrayList<>();
        @NonNull Set<String> authoritativeTypes = new HashSet<>();

        for (String screenerId : new String[]{ SCREENER_CRYPTO, SCREENER_STOCKS }) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted while resolving screeners");
            }

            @NonNull String type = getAssetType(screenerId);

            try {
                @NonNull List<ScreenerSymbol> symbols = fetcher.fetch(screenerId);
                if (symbols.isEmpty()) throw new IllegalStateException("Screener has no symbols");
                recordSaver.accept(new ScreenerRecord(screenerId, new ArrayList<>(symbols), nowMillis));
                universe.addAll(symbols);
                authoritativeTypes.add(type);
            } catch (InterruptedException | OfflineException e) {
                throw e;
            } catch (Exception e) {
                Logger.error(screenerId + ": Error fetching screener", e);

                @Nullable ScreenerRecord record = savedRecords.get(screenerId);
                if (record != null && record.retrievedAtMillis() >= dayStartMillis) {
                    universe.addAll(record.symbols());
                    authoritativeTypes.add(type);
                    continue;
                }

                throw e;
            }
        }

        return new Resolution(universe, authoritativeTypes);
    }
}