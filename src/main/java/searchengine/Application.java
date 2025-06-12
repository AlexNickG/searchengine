package searchengine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.SpringVersion;

@SpringBootApplication
@Slf4j
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.debug("Debug message is written in std_debug.log");
        log.error("Error message is written in stderr.log");
        log.info("Spring version: {}", SpringVersion.getVersion());
    }
}
