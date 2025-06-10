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
    public ResponseEntity<StatisticsResponse> statistics() {
        StatisticsResponse responseMessage = statisticsService.getStatistics();
        if (responseMessage.isResult()) {
            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(responseMessage, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<ResponseMessage> startIndexing() {
        ResponseMessage responseMessage = indexingService.startIndexing();
        if (responseMessage.isResult()) {
            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(responseMessage, HttpStatus.BAD_REQUEST);
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<ResponseMessage> stopIndexing() {
        ResponseMessage responseMessage = indexingService.stopIndexing();
        if (responseMessage.isResult()) {
            return new ResponseEntity<>(responseMessage, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(responseMessage, HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<ResponseMessage> indexPage(@RequestParam String url) {
        ResponseMessage responseMessage = indexingService.addPageForIndexing(url);
        if (responseMessage.isResult()) {
            return new ResponseEntity<>(responseMessage, HttpStatus.OK); //CREATED - 201
        } else {
            return new ResponseEntity<>(responseMessage, HttpStatus.NOT_FOUND);
        }

    }

    @DeleteMapping("/deleteAll")
    public void deleteAll() throws InterruptedException {
        indexingService.clearDb();
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam String query, int offset, Integer limit, String site) {
        //long start = System.currentTimeMillis();
        if (query.isEmpty()) {
            SearchResponse searchResponse = new SearchResponse();
            searchResponse.setResult(false);
            searchResponse.setError("Задан пустой поисковый запрос");
            return searchResponse;
            //return new ResponseEntity<>(searchResponse, HttpStatus.BAD_REQUEST);
        }
        //log.info("Время выполнения поиска: {} мс", System.currentTimeMillis() - start);
        return searchService.getSearchResult(query, offset, limit, site);
    }
}
