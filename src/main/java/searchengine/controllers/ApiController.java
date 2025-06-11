package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
//        StatisticsResponse responseMessage = statisticsService.getStatistics();
//        if (responseMessage.isResult()) {
//            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
//        } else {
//            return new ResponseEntity<>(responseMessage, HttpStatus.INTERNAL_SERVER_ERROR);
//        }
    }

    @GetMapping("/startIndexing")
    public ResponseMessage startIndexing() {
        return indexingService.startIndexing();
//        ResponseMessage responseMessage = indexingService.startIndexing();
//        if (responseMessage.isResult()) {
//            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
//        } else {
//            return new ResponseEntity<>(responseMessage, HttpStatus.BAD_REQUEST);
//        }
    }

    @GetMapping("/stopIndexing")
    public ResponseMessage stopIndexing() {
        return indexingService.stopIndexing();
//        ResponseMessage responseMessage = indexingService.stopIndexing();
//        if (responseMessage.isResult()) {
//            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
//        } else {
//            return new ResponseEntity<>(responseMessage, HttpStatus.BAD_REQUEST);
//        }
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
