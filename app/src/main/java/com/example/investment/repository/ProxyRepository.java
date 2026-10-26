package com.example.investment.repository;

import static com.example.investment.util.Constants.FILE_RECORD_PROXIES;
import static com.example.investment.util.Constants.MINIMUM_REQUEST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_COOLDOWN_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_LIST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_LIST_URLS;
import static com.example.investment.util.Constants.PROXY_MAX_COOLDOWN_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_MAX_PORTS_PER_IP;
import static com.example.investment.util.Constants.PROXY_POOL_SIZE;
import static com.example.investment.util.Constants.PROXY_REFRESH_MILLISECONDS;
import static com.example.investment.util.Constants.REQUEST_CALL_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.REQUEST_CONNECT_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.FileUtils.loadObject;
import static com.example.investment.util.FileUtils.saveObject;

import android.content.Context;
import android.net.InetAddresses;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.investment.model.ProxyDataRecord;
import com.example.investment.model.ProxyStateRecord;
import com.example.investment.util.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProxyRepository {
    private record Listing(@NonNull InetSocketAddress address, double expectedMilliseconds) {}
    private static class Route {
        long cooldownMilliseconds = 0;
        long availableAtMilliseconds = 0;
    }
    @NonNull private final Map<Proxy, Route> routes = new LinkedHashMap<>();
    @NonNull private final Route ownRoute = new Route();
    @NonNull private static final OkHttpClient client = new OkHttpClient.Builder()
        .callTimeout(PROXY_LIST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .build();
    private boolean isLoaded = false;
    private long nextFetchAtMilliseconds = 0;

    public ProxyRepository() {
        routes.put(Proxy.NO_PROXY, ownRoute);
    }

    public synchronized void invalidateCache() {
        setRoutes(new LinkedHashMap<>());
        nextFetchAtMilliseconds = 0;
        isLoaded = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized @NonNull List<Proxy> getCandidates(@NonNull Context context) throws InterruptedException {
        refreshIfNeeded(context);

        long now = System.currentTimeMillis();
        @NonNull List<Map.Entry<Proxy, Route>> available = new ArrayList<>();
        for (Map.Entry<Proxy, Route> entry : routes.entrySet()) {
            @NonNull Route route = entry.getValue();
            if (route == ownRoute || isResting(route, now)) continue;
            available.add(entry);
        }
        available.sort(Comparator.comparingLong(entry -> entry.getValue().availableAtMilliseconds));

        @NonNull List<Proxy> candidates = new ArrayList<>();
        // Real IP is used first if not throttled and if every proxy is resting
        if (!isResting(ownRoute, now) || available.isEmpty()) {
            candidates.add(Proxy.NO_PROXY);
        }

        for (Map.Entry<Proxy, Route> entry : available) {
            candidates.add(entry.getKey());
        }

        return candidates;
    }

    private static synchronized boolean isResting(@NonNull Route route, long now) {
        return route.cooldownMilliseconds > 0 && route.availableAtMilliseconds > now;
    }

    public synchronized void reportUse(@NonNull Proxy proxy) {
        @Nullable Route route = routes.get(proxy);
        if (route != null) route.availableAtMilliseconds = System.currentTimeMillis();
    }

    public synchronized void reportSuccess(@NonNull Proxy proxy) {
        @Nullable Route route = routes.get(proxy);
        if (route != null) route.cooldownMilliseconds = 0;
    }

    public synchronized void reportFailure(@NonNull Proxy proxy) {
        @Nullable Route route = routes.get(proxy);
        if (route == null) return;

        route.cooldownMilliseconds = Math.min(
            Math.max(route.cooldownMilliseconds * 2, PROXY_COOLDOWN_MILLISECONDS),
            PROXY_MAX_COOLDOWN_MILLISECONDS
        );
        route.availableAtMilliseconds = System.currentTimeMillis() + route.cooldownMilliseconds;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private synchronized void refreshIfNeeded(@NonNull Context context) throws InterruptedException {
        if (!isLoaded) loadCache(context);

        long now = System.currentTimeMillis();
        if (now < nextFetchAtMilliseconds) return;

        nextFetchAtMilliseconds = now + PROXY_REFRESH_MILLISECONDS;
        try {
            @NonNull List<InetSocketAddress> fetched = fetchProxies();

            @NonNull Map<Proxy, Route> refreshed = new LinkedHashMap<>();
            for (InetSocketAddress address : fetched) {
                @NonNull Proxy proxy = new Proxy(Proxy.Type.SOCKS, address);
                @Nullable Route known = routes.get(proxy);
                refreshed.put(proxy, known != null ? known : new Route());
            }
            setRoutes(refreshed);
            saveCache(context, now);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Logger.error("Error refreshing proxies", e);
        }
    }

    private synchronized void setRoutes(@NonNull Map<Proxy, Route> proxyRoutes) {
        routes.clear();
        routes.putAll(proxyRoutes);
        routes.put(Proxy.NO_PROXY, ownRoute);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private synchronized @NonNull List<InetSocketAddress> fetchProxies() throws Exception {
        for (String url : PROXY_LIST_URLS) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted");
            }
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected proxy list response: " + response);
                ResponseBody body = response.body();

                @NonNull List<InetSocketAddress> proxies = parseProxies(body.string());
                if (proxies.isEmpty()) throw new Exception("No usable proxy in the list");

                return proxies;
            } catch (Exception e) {
                Logger.error("Error fetching proxy list from " + url, e);
            }
        }

        throw new Exception("No proxy list source returned usable proxies");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private synchronized @NonNull List<InetSocketAddress> parseProxies(@NonNull String body) throws Exception {
        JSONArray rows = new JSONArray(body);

        @NonNull Map<InetAddress, List<Listing>> listingsPerIp = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                InetAddress address = InetAddresses.parseNumericAddress(row.getString("ip"));
                if (!(address instanceof Inet4Address)) continue;

                double uptime = Math.max(row.optDouble("uptime_percent", 0), 1) / 100;
                double latency = row.optDouble("latency_ms", REQUEST_CALL_TIMEOUT_MILLISECONDS);
                double expectedMilliseconds = latency + (1 - uptime) / uptime * (MINIMUM_REQUEST_TIMEOUT_MILLISECONDS + REQUEST_CONNECT_TIMEOUT_MILLISECONDS);

                @NonNull List<Listing> listings = listingsPerIp.computeIfAbsent(address, key -> new ArrayList<>());
                listings.add(new Listing(new InetSocketAddress(address, row.getInt("port")), expectedMilliseconds));
            } catch (Exception ignored) {}
        }

        @NonNull List<Listing> best = new ArrayList<>();
        for (List<Listing> listings : listingsPerIp.values()) {
            if (listings.size() > PROXY_MAX_PORTS_PER_IP) continue;
            best.add(Collections.min(listings, Comparator.comparingDouble(Listing::expectedMilliseconds)));
        }
        best.sort(Comparator.comparingDouble(Listing::expectedMilliseconds));

        @NonNull List<InetSocketAddress> proxies = new ArrayList<>();
        for (Listing listing : best.subList(0, Math.min(best.size(), PROXY_POOL_SIZE))) proxies.add(listing.address());
        return proxies;
    }

    private synchronized void loadCache(@NonNull Context context) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Thread interrupted");
        }
        try {
            @Nullable ProxyDataRecord record = (ProxyDataRecord) loadObject(context, FILE_RECORD_PROXIES);
            if (record != null) {
                @NonNull Map<Proxy, Route> loaded = new LinkedHashMap<>();
                for (ProxyStateRecord saved : record.proxies()) {
                    @NonNull Route route = new Route();
                    route.cooldownMilliseconds = saved.cooldownMilliseconds();
                    route.availableAtMilliseconds = saved.availableAtMilliseconds();
                    loaded.put(new Proxy(Proxy.Type.SOCKS, saved.address()), route);
                }
                setRoutes(loaded);
                nextFetchAtMilliseconds = record.timestamp() + PROXY_REFRESH_MILLISECONDS;
            }
        } catch (Exception e) {
            Logger.error("Error loading proxies", e);
        }
        isLoaded = true;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private synchronized void saveCache(@NonNull Context context, long timestamp) {
        try {
            @NonNull List<ProxyStateRecord> saved = new ArrayList<>();
            for (Map.Entry<Proxy, Route> entry : routes.entrySet()) {
                if (!(entry.getKey().address() instanceof InetSocketAddress address)) continue;

                @NonNull Route route = entry.getValue();
                saved.add(new ProxyStateRecord(address, route.cooldownMilliseconds, route.availableAtMilliseconds));
            }
            saveObject(context, FILE_RECORD_PROXIES, new ProxyDataRecord(saved, timestamp));
        } catch (Exception e) {
            Logger.error("Error saving proxies", e);
        }
    }
}