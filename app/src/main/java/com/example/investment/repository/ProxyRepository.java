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
    // What the list says about a proxy, as the time a request through it is expected to take
    private record Listing(@NonNull InetSocketAddress address, double expectedMilliseconds) {}

    // What is known about a route, it stays with the route in every new list
    private static class Route {
        // The time the route was last left out for, doubled by every failure in a row, zero once it answers
        long cooldownMilliseconds = 0;
        // Routes go in the order of this time, so the one unused for longest is first and an IP is never asked again while others rested longer
        // For a route that failed it is the time it may be asked again
        long availableAtMilliseconds = 0;
    }

    @NonNull private static final OkHttpClient client = new OkHttpClient.Builder()
        .callTimeout(PROXY_LIST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .build();

    // Ranked, best first, one per IP, the own IP is last
    @NonNull private final Map<Proxy, Route> routes = new LinkedHashMap<>();
    // The own IP is what is left when every proxy died, so it is never left out
    @NonNull private final Route ownRoute = new Route();
    private boolean isLoaded = false;
    private long nextFetchAtMilliseconds = 0;

    public ProxyRepository() {
        // Not saved, so it starts as just used, after the routes that were used before, never first
        ownRoute.availableAtMilliseconds = System.currentTimeMillis();
        routes.put(Proxy.NO_PROXY, ownRoute);
    }

    // Candidates
    // Every route that is not left out, the one unused for longest first, so the same IP is not asked again while others are available
    // Routes never used keep their ranking (the sort is stable), so the best listed go first and the own IP is the last of them
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized @NonNull List<Proxy> getCandidates(@NonNull Context context) {
        refreshIfNeeded(context);

        long now = System.currentTimeMillis();
        @NonNull List<Map.Entry<Proxy, Route>> available = new ArrayList<>();
        for (Map.Entry<Proxy, Route> entry : routes.entrySet()) {
            @NonNull Route route = entry.getValue();
            // Only a failed route has to wait, for the others the time is just when they were last used
            if (route.cooldownMilliseconds > 0 && route.availableAtMilliseconds > now) continue;
            available.add(entry);
        }
        available.sort(Comparator.comparingLong(entry -> entry.getValue().availableAtMilliseconds));

        @NonNull List<Proxy> candidates = new ArrayList<>();
        for (Map.Entry<Proxy, Route> entry : available) candidates.add(entry.getKey());
        return candidates;
    }

    // Every try counts, the one that fails and the one Yahoo answers with nothing as well
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
        // The own IP is the last resort, it never leaves the list
        if (route == null || route == ownRoute) return;

        // One that was only down for a while is back soon, one that keeps failing is hardly asked
        route.cooldownMilliseconds = Math.min(
            Math.max(route.cooldownMilliseconds * 2, PROXY_COOLDOWN_MILLISECONDS),
            PROXY_MAX_COOLDOWN_MILLISECONDS
        );
        route.availableAtMilliseconds = System.currentTimeMillis() + route.cooldownMilliseconds;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void refreshIfNeeded(@NonNull Context context) {
        if (!isLoaded) loadCache(context);
        if (Thread.currentThread().isInterrupted()) return;

        long now = System.currentTimeMillis();
        if (now < nextFetchAtMilliseconds) return;

        // Set first, so a failed download is not retried on every request
        nextFetchAtMilliseconds = now + PROXY_REFRESH_MILLISECONDS;
        try {
            @NonNull List<InetSocketAddress> fetched = fetchProxies();

            // What is known about a route stays with it, one that is not in the new list is dropped with all of it
            @NonNull Map<Proxy, Route> refreshed = new LinkedHashMap<>();
            for (InetSocketAddress address : fetched) {
                @NonNull Proxy proxy = new Proxy(Proxy.Type.SOCKS, address);
                @Nullable Route known = routes.get(proxy);
                refreshed.put(proxy, known != null ? known : new Route());
            }
            setRoutes(refreshed);
            saveCache(context, now);
        } catch (Exception e) {
            // The cached proxies, if any, stay in use
            Logger.error("Error refreshing proxies", e);
        }
    }

    // The own IP is not in any list, it goes with every one of them
    private void setRoutes(@NonNull Map<Proxy, Route> proxyRoutes) {
        routes.clear();
        routes.putAll(proxyRoutes);
        routes.put(Proxy.NO_PROXY, ownRoute);
    }

    // Proxy Fetching
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private @NonNull List<InetSocketAddress> fetchProxies() throws Exception {
        for (String url : PROXY_LIST_URLS) {
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected proxy list response: " + response);
                ResponseBody body = response.body();

                @NonNull List<InetSocketAddress> proxies = parseProxies(body.string());
                if (proxies.isEmpty()) throw new Exception("No usable proxy in the list");

                Logger.debug("Proxies fetched: " + proxies.size() + " from " + url);
                return proxies;
            } catch (Exception e) {
                // Next source
                Logger.error("Error fetching proxy list from " + url, e);
            }
        }

        throw new Exception("No proxy list source returned usable proxies");
    }

    // Only the best proxies are kept, one per IP, best first, a slow request is mostly the tries on the ones that do not answer
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private @NonNull List<InetSocketAddress> parseProxies(@NonNull String body) throws Exception {
        JSONArray rows = new JSONArray(body);

        @NonNull Map<InetAddress, List<Listing>> listingsPerIp = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                // Numeric only, so there is never a DNS lookup
                InetAddress address = InetAddresses.parseNumericAddress(row.getString("ip"));
                if (!(address instanceof Inet4Address)) continue;

                // Every failed try before the one that answers costs the throttle and a connect timeout
                double uptime = Math.max(row.optDouble("uptime_percent", 0), 1) / 100;
                double latency = row.optDouble("latency_ms", REQUEST_CALL_TIMEOUT_MILLISECONDS);
                double expected = latency + (1 - uptime) / uptime * (MINIMUM_REQUEST_TIMEOUT_MILLISECONDS + REQUEST_CONNECT_TIMEOUT_MILLISECONDS);

                @NonNull List<Listing> listings = listingsPerIp.computeIfAbsent(address, key -> new ArrayList<>());
                listings.add(new Listing(new InetSocketAddress(address, row.getInt("port")), expected));
            } catch (Exception ignored) {}
        }

        @NonNull List<Listing> best = new ArrayList<>();
        for (List<Listing> listings : listingsPerIp.values()) {
            // A host listed on a lot of ports answers on every one of them, it is not that many proxies
            if (listings.size() > PROXY_MAX_PORTS_PER_IP) continue;
            // One per IP, the requests are spaced per IP so another port of it is no other route
            best.add(Collections.min(listings, Comparator.comparingDouble(Listing::expectedMilliseconds)));
        }
        best.sort(Comparator.comparingDouble(Listing::expectedMilliseconds));

        @NonNull List<InetSocketAddress> proxies = new ArrayList<>();
        for (Listing listing : best.subList(0, Math.min(best.size(), PROXY_POOL_SIZE))) proxies.add(listing.address());
        return proxies;
    }

    // Cache
    private void loadCache(@NonNull Context context) {
        isLoaded = true;
        try {
            @Nullable ProxyDataRecord record = (ProxyDataRecord) loadObject(context, FILE_RECORD_PROXIES);
            if (record == null) return;

            @NonNull Map<Proxy, Route> loaded = new LinkedHashMap<>();
            for (ProxyStateRecord saved : record.proxies()) {
                @NonNull Route route = new Route();
                route.cooldownMilliseconds = saved.cooldownMilliseconds();
                route.availableAtMilliseconds = saved.availableAtMilliseconds();
                loaded.put(new Proxy(Proxy.Type.SOCKS, saved.address()), route);
            }
            setRoutes(loaded);
            // A file from the last 5 minutes is as good as a new download
            nextFetchAtMilliseconds = record.timestamp() + PROXY_REFRESH_MILLISECONDS;
        } catch (Exception e) {
            Logger.error("Error loading proxies", e);
        }
    }

    // Saved with every new list, so a restart goes on where it stopped
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void saveCache(@NonNull Context context, long timestamp) {
        try {
            @NonNull List<ProxyStateRecord> saved = new ArrayList<>();
            for (Map.Entry<Proxy, Route> entry : routes.entrySet()) {
                // The own IP has no address, there is nothing to save for it
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