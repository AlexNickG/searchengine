package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "exceptions")
public class Config {
    private List<String> lemmaExceptions;
    private List<String> fileExtensions;
    private List<String> pathContaining;
}
