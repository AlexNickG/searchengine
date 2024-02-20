package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ApiController {
    private final StatisticsService statisticsService;

    private final IndexingService indexingService;

    private final SearchService searchService;
    private SiteRepository siteRepository;
    private PageRepository pageRepository;


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

    @PostMapping("/indexPage")
    public ResponseEntity<ResponseMessage> indexPage() {
        siteRepository.deleteAll();
        return ResponseEntity.ok(indexingService.stopIndexing());
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam String query, int offset, Integer limit, String site) {
        return ResponseEntity.ok(searchService.getSearchResult(query, offset, limit, site));
    }
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
