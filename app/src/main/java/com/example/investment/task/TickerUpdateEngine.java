package com.example.investment.task;

import static com.example.investment.util.Constants.PROGRESS_SAVE_INTERVAL_MILLISECONDS;
import static com.example.investment.util.Constants.PROGRESS_SAVE_TICKER_COUNT;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.InvestmentConfig;
import com.example.investment.model.TickerFailure;
import com.example.investment.model.listener.TickerUpdateListener;
import com.example.investment.model.ticker.TickerData;
import com.example.investment.task.ScreenerResolver.Resolution;
import com.example.investment.util.Logger;
import com.example.investment.util.PendingTickers;
import com.example.investment.util.TickerUpdatePlanner;
import com.example.investment.util.TickerUpdatePlanner.UpdateTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public class TickerUpdateEngine {
    public static class OfflineException extends Exception {
        public OfflineException(@NonNull String message) {
            super(message);
        }
    }
    public static class DefinitiveFailureException extends Exception {
        public DefinitiveFailureException(@NonNull String message) {
            super(message);
        }
    }
    @FunctionalInterface
    public interface TickerProcessor {
        @NonNull TickerData process(@NonNull UpdateTarget target, boolean isFullRefresh) throws Exception;
    }
    private enum Outcome { COMPLETED, OFFLINE, INTERRUPTED, FAILED }
    private static class RunState {
        @NonNull private final Map<String, TickerData> tickers = new LinkedHashMap<>();
        @NonNull private final Map<String, TickerFailure> failures;
        @Nullable private List<TickerData> latestSavedTickers;
        private boolean hasUnsavedTickers;
        private boolean hasUnsavedFailures = false;
        private int unsavedTickerCount = 0;
        private long lastSaveAttemptAtMillis;
        private long lastSavedAtMillis = 0;

        RunState(
            @NonNull List<TickerData> savedTickers,
            @NonNull List<TickerData> keptTickers,
            @NonNull Map<String, TickerFailure> failures,
            long startedAtMillis
        ) {
            for (TickerData ticker : keptTickers) tickers.put(ticker.getSymbol(), ticker);
            this.failures = new HashMap<>(failures);
            this.latestSavedTickers = savedTickers.isEmpty() ? null : new ArrayList<>(savedTickers);
            this.hasUnsavedTickers = tickers.size() != savedTickers.size();
            this.lastSaveAttemptAtMillis = startedAtMillis;
        }
    }

    @NonNull private final TickerProcessor processor;
    @NonNull private final TickerUpdateStore store;
    @NonNull private final LongSupplier clock;

    public TickerUpdateEngine(
        @NonNull TickerProcessor processor,
        @NonNull TickerUpdateStore store,
        @NonNull LongSupplier clock
    ) {
        this.processor = processor;
        this.store = store;
        this.clock = clock;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void run(
        @NonNull List<TickerData> savedTickers,
        @NonNull Resolution resolution,
        @NonNull Map<String, TickerFailure> failures,
        @NonNull InvestmentConfig config,
        boolean isFullRefresh,
        long nowMillis,
        @NonNull TickerUpdateListener listener
    ) {
        @NonNull RunState state = new RunState(
            savedTickers,
            resolution.pruneRemoved(savedTickers),
            failures,
            clock.getAsLong()
        );

        @NonNull Outcome outcome = Outcome.FAILED;
        boolean isSaved;
        try {
            outcome = processTargets(state, resolution, config, isFullRefresh, nowMillis, listener);
        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
        } finally {
            isSaved = flush(state);
        }

        if (outcome == Outcome.INTERRUPTED) {
            Thread.currentThread().interrupt();
            return;
        }

        if (outcome != Outcome.COMPLETED) {
            listener.onError(state.latestSavedTickers);
            return;
        }

        @NonNull List<TickerData> tickers = new ArrayList<>(state.tickers.values());
        if (
            !isSaved
            || tickers.isEmpty()
            || PendingTickers.count(tickers, resolution.symbols(), state.failures, nowMillis) > 0
        ) {
            listener.onError(state.latestSavedTickers);
            return;
        }

        listener.onFinish(tickers, state.lastSavedAtMillis > 0 ? state.lastSavedAtMillis : clock.getAsLong());
    }

    // Target Processing
    @RequiresApi(api = Build.VERSION_CODES.O)
    private @NonNull Outcome processTargets(
        @NonNull RunState state,
        @NonNull Resolution resolution,
        @NonNull InvestmentConfig config,
        boolean isFullRefresh,
        long nowMillis,
        @NonNull TickerUpdateListener listener
    ) {
        @NonNull List<UpdateTarget> targets = TickerUpdatePlanner.plan(
            new ArrayList<>(state.tickers.values()),
            resolution.universe(),
            state.failures,
            config,
            nowMillis,
            isFullRefresh
        );

        int total = targets.size();
        int current = 0;
        if (total > 0) listener.onTickersProgress(current, total);

        for (UpdateTarget target : targets) {
            if (Thread.currentThread().isInterrupted()) return Outcome.INTERRUPTED;

            @NonNull String symbol = target.symbol();
            @Nullable TickerData ticker = null;
            try {
                ticker = processor.process(target, isFullRefresh);
            } catch (InterruptedException e) {
                Logger.error("InterruptedException processing " + symbol, e);
                return Outcome.INTERRUPTED;
            } catch (OfflineException e) {
                Logger.error("Offline while processing " + symbol, e);
                return Outcome.OFFLINE;
            } catch (Exception e) {
                Logger.error("Error processing " + symbol, e);

                if (Thread.currentThread().isInterrupted()) return Outcome.INTERRUPTED;

                state.failures.put(
                    symbol,
                    TickerFailure.afterFailure(state.failures.get(symbol), clock.getAsLong(), e instanceof DefinitiveFailureException)
                );
                state.hasUnsavedFailures = true;
            }

            if (ticker != null) {
                state.tickers.put(symbol, ticker);
                state.hasUnsavedTickers = true;
                state.unsavedTickerCount++;

                if (state.failures.remove(symbol) != null) state.hasUnsavedFailures = true;

                listener.onTickerUpdate(new ArrayList<>(state.tickers.values()));
            }

            flushIfDue(state);
            listener.onTickersProgress(++current, total);
        }

        return Outcome.COMPLETED;
    }

    // Progress Saving
    private void flushIfDue(@NonNull RunState state) {
        if (!state.hasUnsavedTickers && !state.hasUnsavedFailures) return;

        if (
            state.unsavedTickerCount < PROGRESS_SAVE_TICKER_COUNT
            && clock.getAsLong() - state.lastSaveAttemptAtMillis < PROGRESS_SAVE_INTERVAL_MILLISECONDS
        ) return;

        flush(state);
    }
    private boolean flush(@NonNull RunState state) {
        if (!state.hasUnsavedTickers && !state.hasUnsavedFailures) return true;

        boolean wasInterrupted = Thread.interrupted();
        long savedAtMillis = clock.getAsLong();
        state.lastSaveAttemptAtMillis = savedAtMillis;
        state.unsavedTickerCount = 0;
        boolean isSaved = true;

        try {
            if (state.hasUnsavedTickers) {
                @NonNull List<TickerData> tickers = new ArrayList<>(state.tickers.values());
                if (tickers.isEmpty()) {
                    state.hasUnsavedTickers = false;
                } else {
                    try {
                        store.saveProgress(tickers, savedAtMillis);
                        state.latestSavedTickers = tickers;
                        state.lastSavedAtMillis = savedAtMillis;
                        state.hasUnsavedTickers = false;
                    } catch (Exception e) {
                        Logger.error("Error saving ticker progress", e);
                        isSaved = false;
                    }
                }
            }

            if (state.hasUnsavedFailures) {
                store.saveTickerFailures(new HashMap<>(state.failures), savedAtMillis);
                state.hasUnsavedFailures = false;
            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }

        return isSaved;
    }
}