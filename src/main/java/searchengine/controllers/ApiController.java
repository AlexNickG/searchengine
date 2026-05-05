package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.captcha.CaptchaChallenge;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.CaptchaInteractionService;
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
    private final CaptchaInteractionService captchaInteractionService;

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

    @GetMapping("/captchaPending")
    public ResponseEntity<CaptchaChallenge> captchaPending() {
        CaptchaChallenge challenge = captchaInteractionService.getCurrentChallenge();
        return challenge == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(challenge);
    }

    @PostMapping("/solveCaptcha")
    public ResponseMessage solveCaptcha(@RequestParam String id, @RequestParam String solution) {
        captchaInteractionService.solve(id, solution);
        ResponseMessage response = new ResponseMessage();
        response.setResult(true);
        response.setError("");
        return response;
    }
}
