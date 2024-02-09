package searchengine.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;

import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private SiteRepository siteRepository;
    private PageRepository pageRepository;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService, SiteRepository siteRepository, PageRepository pageRepository) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<ResponseMessage> startIndexing() {
        return ResponseEntity.ok(indexingService.startIndexing());
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<ResponseMessage> stopIndexing() {
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

//    @PostMapping("/indexPage")
//    public ResponseEntity<ResponseMessage> indexPage() {
//        return ResponseEntity.ok(indexingService.stopIndexing());
//    }
//
//    @GetMapping("/search")
//    public ResponseEntity<ResponseMessage> search() {
//        return ResponseEntity.ok();
//    }

    /*@PreDestroy
    public void deleteTables() {
        System.out.println("deleting tables");
        pageRepository.deleteAll();
        siteRepository.deleteAll();

    }*/
}
