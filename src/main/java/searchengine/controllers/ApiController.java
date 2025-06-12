package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ApiController {
    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;

    @GetMapping("/statistics")
    public StatisticsResponse statistics() {
        return statisticsService.getStatistics();
    }

    @GetMapping("/startIndexing")
    public ResponseMessage startIndexing() {
        return indexingService.startIndexing();
    }

    @GetMapping("/stopIndexing")
    public ResponseMessage stopIndexing() {
        return indexingService.stopIndexing();
    }

    @PostMapping("/indexPage")
    public ResponseMessage indexPage(@RequestParam String url) {
        return indexingService.addPageForIndexing(url);
    }

    @DeleteMapping("/deleteAll")
    public void deleteAll() {
        indexingService.clearDb();
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String query, int offset, Integer limit, String site) {
        return searchService.getSearchResult(query, offset, limit, site);
    }
}
