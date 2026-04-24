package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Setter
@Getter
@ConfigurationProperties(prefix = "connection-settings")
public class ConnectionSettings {
    private String userAgent;
    private String referrer;
    private int timeout;          // politeness delay between requests, ms
}
