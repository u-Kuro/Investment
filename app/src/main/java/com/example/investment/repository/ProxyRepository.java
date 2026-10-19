package com.example.investment.repository;

import static com.example.investment.util.Constants.PROXY_CACHE_TTL_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_EVICTION_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_LIST_TIMEOUT_MILLISECONDS;
import static com.example.investment.util.Constants.PROXY_LIST_URLS;
import static com.example.investment.util.Constants.PROXY_MAX_AGE_SECONDS;
import static com.example.investment.util.Constants.PROXY_MAX_CONSECUTIVE_FAILURES;
import static com.example.investment.util.Constants.PROXY_MIN_POOL_SIZE;
import static com.example.investment.util.Constants.PROXY_MIN_UPTIME_PERCENT;
import static com.example.investment.util.Constants.PROXY_POOL_SIZE;
import static com.example.investment.util.Constants.PROXY_REFRESH_COOLDOWN_MILLISECONDS;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.investment.util.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class ProxyRepository {
    private record ProxyCandidate(@NonNull String ip, int port, double uptimePercent, double latencyMilliseconds) {}

    @NonNull private static final OkHttpClient client = new OkHttpClient.Builder()
        .callTimeout(PROXY_LIST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        .build();
    // Strict on purpose: anything else would make InetAddress try a DNS lookup
    @NonNull private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    );

    @NonNull private final List<Proxy> pool = new ArrayList<>();
    @NonNull private final Map<Proxy, Integer> consecutiveFailures = new HashMap<>();
    @NonNull private final Map<Proxy, Long> evictedAtMilliseconds = new HashMap<>();
    private int cursor = 0;
    private long poolExpiresAtMilliseconds = 0;
    private long nextRefreshAtMilliseconds = 0;

    // Pool
    public synchronized boolean hasProxies() {
        refreshIfNeeded();
        return !pool.isEmpty();
    }

    // Round-robin, so two requests in a row never share an IP
    public synchronized @Nullable Proxy getNextProxy() {
        refreshIfNeeded();
        if (pool.isEmpty()) return null;

        if (cursor >= pool.size()) cursor = 0;
        @NonNull Proxy proxy = pool.get(cursor);
        cursor = (cursor + 1) % pool.size();
        return proxy;
    }

    public synchronized void markSuccess(@NonNull Proxy proxy) {
        consecutiveFailures.remove(proxy);
    }

    public synchronized void markFailure(@NonNull Proxy proxy) {
        int failures = consecutiveFailures.merge(proxy, 1, Integer::sum);
        if (failures < PROXY_MAX_CONSECUTIVE_FAILURES) return;

        consecutiveFailures.remove(proxy);
        evictedAtMilliseconds.put(proxy, System.currentTimeMillis());

        int index = pool.indexOf(proxy);
        if (index < 0) return;
        pool.remove(index);
        if (index < cursor) cursor--;
        Logger.debug("Proxy evicted: " + proxy.address() + " (" + pool.size() + " left)");
    }

    private void refreshIfNeeded() {
        if (Thread.currentThread().isInterrupted()) return;

        long now = System.currentTimeMillis();
        if (now < nextRefreshAtMilliseconds) return;
        if (now < poolExpiresAtMilliseconds && pool.size() >= PROXY_MIN_POOL_SIZE) return;

        // Set first, so a failed download is not retried on every request
        nextRefreshAtMilliseconds = now + PROXY_REFRESH_COOLDOWN_MILLISECONDS;
        try {
            @NonNull List<Proxy> proxies = fetchProxies(now);
            pool.clear();
            pool.addAll(proxies);
            consecutiveFailures.clear();
            cursor = 0;
            poolExpiresAtMilliseconds = now + PROXY_CACHE_TTL_MILLISECONDS;
        } catch (Exception e) {
            // The previous pool, if any, stays in use
            Logger.error("Error refreshing proxy pool", e);
        }
    }

    // Proxy Fetching
    private @NonNull List<Proxy> fetchProxies(long now) throws Exception {
        evictedAtMilliseconds.values().removeIf(evictedAt -> now - evictedAt >= PROXY_EVICTION_MILLISECONDS);

        for (String url : PROXY_LIST_URLS) {
            try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) throw new Exception("Unexpected proxy list response: " + response);
                ResponseBody body = response.body();
                if (body == null) throw new Exception("Empty response body");

                @NonNull List<Proxy> proxies = parseProxies(body.string(), now);
                if (proxies.isEmpty()) throw new Exception("No usable proxy in the list");

                Logger.debug("Proxy pool refreshed: " + proxies.size() + " proxies from " + url);
                return proxies;
            } catch (Exception e) {
                // Next source
                Logger.error("Error fetching proxy list from " + url, e);
            }
        }

        throw new Exception("No proxy list source returned usable proxies");
    }

    private @NonNull List<Proxy> parseProxies(@NonNull String body, long now) throws Exception {
        Object root = new JSONTokener(body).nextValue();
        // ProxyScrape is a bare array, Proxmint wraps it in "rows"
        JSONArray rows = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).getJSONArray("rows");

        @NonNull List<ProxyCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                if (!"socks5".equalsIgnoreCase(row.optString("protocol", "socks5"))) continue;

                // ProxyScrape: uptime_percent, latency_ms, last_checked (seconds) | Proxmint: uptimePct, latencyMs
                double uptimePercent = row.has("uptime_percent") ? row.getDouble("uptime_percent") : row.getDouble("uptimePct");
                double latencyMilliseconds = row.has("latency_ms") ? row.getDouble("latency_ms") : row.optDouble("latencyMs", Double.MAX_VALUE);
                double lastCheckedSeconds = row.optDouble("last_checked", 0);

                if (uptimePercent < PROXY_MIN_UPTIME_PERCENT) continue;
                if (lastCheckedSeconds > 0 && now / 1000.0 - lastCheckedSeconds > PROXY_MAX_AGE_SECONDS) continue;

                candidates.add(new ProxyCandidate(
                    row.getString("ip"),
                    row.getInt("port"),
                    uptimePercent,
                    latencyMilliseconds
                ));
            } catch (Exception ignored) {}
        }

        // Most reliable first, the list's own order is not trusted
        candidates.sort(
            Comparator.comparingDouble(ProxyCandidate::uptimePercent).reversed()
            .thenComparingDouble(ProxyCandidate::latencyMilliseconds)
        );

        @NonNull List<Proxy> proxies = new ArrayList<>();
        @NonNull Set<String> seenIps = new HashSet<>();
        for (ProxyCandidate candidate : candidates) {
            if (proxies.size() >= PROXY_POOL_SIZE) break;

            @Nullable InetAddress address = toPublicAddress(candidate.ip());
            if (address == null || candidate.port() < 1 || candidate.port() > 65_535) continue;

            @NonNull Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(address, candidate.port()));
            if (evictedAtMilliseconds.containsKey(proxy)) continue;

            // The same IP on several ports is still a single IP for Yahoo
            if (!seenIps.add(candidate.ip())) continue;
            proxies.add(proxy);
        }

        return proxies;
    }

    // Helpers
    private @Nullable InetAddress toPublicAddress(@NonNull String ip) {
        if (!IPV4_PATTERN.matcher(ip).matches()) return null;
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (
                address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
            ) return null;
            return address;
        } catch (Exception e) {
            return null;
        }
    }
}
