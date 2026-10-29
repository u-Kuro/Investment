package com.example.investment.task;

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
import com.example.investment.util.TickerUpdatePlanner;
import com.example.investment.util.TickerUpdatePlanner.UpdateTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        @NonNull private final ExecutorService writer = Executors.newSingleThreadExecutor();
        @NonNull private final AtomicBoolean isWriting = new AtomicBoolean(false);
        @Nullable private volatile List<TickerData> latestSavedTickers;
        private volatile long lastSavedAtMillis = 0;
        private volatile long savedTickersVersion = 0;
        private volatile long savedFailuresVersion = 0;
        private long tickersVersion;
        private long failuresVersion = 0;

        RunState(
            @NonNull List<TickerData> savedTickers,
            @NonNull List<TickerData> keptTickers,
            @NonNull Map<String, TickerFailure> failures
        ) {
            for (TickerData ticker : keptTickers) tickers.put(ticker.getSymbol(), ticker);
            this.failures = new HashMap<>(failures);
            this.latestSavedTickers = savedTickers.isEmpty() ? null : new ArrayList<>(savedTickers);
            this.tickersVersion = tickers.size() != savedTickers.size() ? 1 : 0;
        }

        boolean hasUnsavedTickers() {
            return tickersVersion != savedTickersVersion;
        }
        boolean hasUnsavedFailures() {
            return failuresVersion != savedFailuresVersion;
        }
    }
    private record Snapshot(
        @Nullable List<TickerData> tickers,
        long tickersVersion,
        @Nullable Map<String, TickerFailure> failures,
        long failuresVersion,
        long savedAtMillis
    ) {}

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
        long refreshSinceMillis,
        boolean isFullRefresh,
        long nowMillis,
        @NonNull TickerUpdateListener listener
    ) {
        @NonNull RunState state = new RunState(
            savedTickers,
            resolution.pruneRemoved(savedTickers),
            failures
        );

        @NonNull Outcome outcome = Outcome.FAILED;
        boolean isSaved;
        try {
            outcome = processTargets(state, resolution, config, refreshSinceMillis, isFullRefresh, nowMillis, listener);
        } catch (Exception e) {
            Logger.error("Error fetching ticker data", e);
        } finally {
            isSaved = saveAll(state);
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
        if (!isSaved || tickers.isEmpty()) {
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
        long refreshSinceMillis,
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
            refreshSinceMillis
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
                state.failuresVersion++;
            }

            if (ticker != null) {
                state.tickers.put(symbol, ticker);
                state.tickersVersion++;

                if (state.failures.remove(symbol) != null) state.failuresVersion++;

                listener.onTickerUpdate(new ArrayList<>(state.tickers.values()));
            }

            saveIfAvailable(state);
            listener.onTickersProgress(++current, total);
        }

        return Outcome.COMPLETED;
    }

    // Progress Saving
    private void saveIfAvailable(@NonNull RunState state) {
        if (!state.hasUnsavedTickers() && !state.hasUnsavedFailures()) return;
        if (!state.isWriting.compareAndSet(false, true)) return;

        @NonNull Snapshot snapshot = takeSnapshot(state);
        state.writer.execute(() -> {
            try {
                write(state, snapshot);
            } finally {
                state.isWriting.set(false);
            }
        });
    }
    private boolean saveAll(@NonNull RunState state) {
        boolean wasInterrupted = Thread.interrupted();

        try {
            state.writer.shutdown();
            while (true) {
                try {
                    if (state.writer.awaitTermination(1, TimeUnit.MINUTES)) break;
                } catch (InterruptedException e) {
                    wasInterrupted = true;
                }
            }

            if (!state.hasUnsavedTickers() && !state.hasUnsavedFailures()) return true;
            return write(state, takeSnapshot(state));
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }
    private @NonNull Snapshot takeSnapshot(@NonNull RunState state) {
        return new Snapshot(
            state.hasUnsavedTickers() ? new ArrayList<>(state.tickers.values()) : null,
            state.tickersVersion,
            state.hasUnsavedFailures() ? new HashMap<>(state.failures) : null,
            state.failuresVersion,
            clock.getAsLong()
        );
    }
    private boolean write(@NonNull RunState state, @NonNull Snapshot snapshot) {
        boolean isSaved = true;

        if (snapshot.tickers() != null) {
            try {
                if (!snapshot.tickers().isEmpty()) {
                    store.saveProgress(snapshot.tickers(), snapshot.savedAtMillis());
                    state.latestSavedTickers = snapshot.tickers();
                    state.lastSavedAtMillis = snapshot.savedAtMillis();
                }
                state.savedTickersVersion = snapshot.tickersVersion();
            } catch (Exception e) {
                Logger.error("Error saving ticker progress", e);
                isSaved = false;
            }
        }

        if (snapshot.failures() != null) {
            try {
                store.saveTickerFailures(snapshot.failures(), snapshot.savedAtMillis());
                state.savedFailuresVersion = snapshot.failuresVersion();
            } catch (Exception e) {
                Logger.error("Error saving ticker failures", e);
                isSaved = false;
            }
        }

        return isSaved;
    }
}