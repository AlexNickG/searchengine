package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Setter
@Getter
@ConfigurationProperties(prefix = "connection-settings")
public class ConnectionSettings {
    private String userAgent;
    private String referrer;
    private int timeout;
    private int delayMin = 500;
    private int delayMax = 2000;
    private List<String> userAgents = new ArrayList<>();
    private int maxConcurrentRequests = 4;
    private List<ProxyEntry> proxies = new ArrayList<>();

    @Getter
    @Setter
    public static class ProxyEntry {
        private String host;
        private int port;

        public String key() {
            return host + ":" + port;
        }
    }
}
