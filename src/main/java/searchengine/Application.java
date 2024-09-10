package searchengine;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import searchengine.services.LemmaFinder;

@SpringBootApplication
@Slf4j
public class Application {
    //public static Logger logger = LoggerFactory.getLogger(Application.class);
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.debug("Debug message is written in std_debug.log");
        log.error("Error message is written in stderr.log");
    }
}
