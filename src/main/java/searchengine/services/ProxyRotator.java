package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import searchengine.config.ConnectionSettings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyRotator {

    private final ConnectionSettings connectionSettings;
    private final AtomicInteger idx = new AtomicInteger(0);
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();

    private static final long FAILURE_BAN_MS = 10 * 60 * 1000L;

    public ConnectionSettings.ProxyEntry next() {
        List<ConnectionSettings.ProxyEntry> proxies = connectionSettings.getProxies();
        if (proxies == null || proxies.isEmpty()) return null;

        long now = System.currentTimeMillis();
        int size = proxies.size();
        for (int i = 0; i < size; i++) {
            int index = Math.abs(idx.getAndIncrement() % size);
            ConnectionSettings.ProxyEntry proxy = proxies.get(index);
            Long bannedUntil = failedUntil.get(proxy.key());
            if (bannedUntil == null || now > bannedUntil) {
                return proxy;
            }
        }
        log.warn("All proxies are currently marked as failed, using direct connection");
        return null;
    }

    public void markFailed(ConnectionSettings.ProxyEntry proxy) {
        if (proxy != null) {
            failedUntil.put(proxy.key(), System.currentTimeMillis() + FAILURE_BAN_MS);
            log.warn("Proxy {}:{} marked as failed for 10 minutes", proxy.getHost(), proxy.getPort());
        }
    }
}
