package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.Config;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.IndexingServiceImpl;
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
    private final SiteRepository siteRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;

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
        return new ResponseEntity<>(indexingService.addPageForIndexing(url), HttpStatus.OK); //CREATED
    }

    @DeleteMapping("/deleteAll")
    public void deleteAll() throws InterruptedException {
        siteRepository.setForeignKeyCheckNull();
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        siteRepository.setForeignKeyCheckNotNull();
        log.info("DB cleared");
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestParam String query, int offset, Integer limit, String site) {
        if (query.isEmpty()) {
            SearchResponse searchResponse = new SearchResponse();
            searchResponse.setResult(false);
            searchResponse.setError("Задан пустой поисковый запрос");
            return new ResponseEntity<>(searchResponse, HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(searchService.getSearchResult(query, offset, limit, site), HttpStatus.OK);
    }
}
