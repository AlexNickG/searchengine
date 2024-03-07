package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

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
        return new ResponseEntity<>(statisticsService.getStatistics(), HttpStatus.OK);
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<ResponseMessage> startIndexing() {
        return new ResponseEntity<>(indexingService.startIndexing(), HttpStatus.OK);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<ResponseMessage> stopIndexing() {
        return new ResponseEntity<>(indexingService.stopIndexing(), HttpStatus.OK);
    }

    @PostMapping("/indexPage")
    public ResponseEntity<ResponseMessage> indexPage(@RequestParam String url) {
        if (url.contains("000")) {
            return new ResponseEntity<>(indexingService.addPageForIndexing(url), HttpStatus.BAD_REQUEST);
        }
        //siteRepository.deleteAll();
        return new ResponseEntity<>(indexingService.addPageForIndexing(url), HttpStatus.OK); //CREATED
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam String query, int offset, Integer limit, String site) {
        if (query.isEmpty()) {
            return new ResponseEntity<>(searchService.getSearchResult(query, offset, limit, site), HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(searchService.getSearchResult(query, offset, limit, site), HttpStatus.OK);
    }



    /*@PreDestroy
    public void deleteTables() {
        System.out.println("deleting tables");
        pageRepository.deleteAll();
        siteRepository.deleteAll();

    }*/
}
