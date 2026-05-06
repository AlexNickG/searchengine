package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.Config;
import searchengine.config.ConnectionSettings;
import searchengine.config.SitesList;
import searchengine.dto.captcha.CaptchaChallenge;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.exceptions.BadRequestException;
import searchengine.exceptions.ResourceDoesNotMatchException;
import searchengine.exceptions.ResourceForbiddenException;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import org.springframework.web.client.UnknownContentTypeException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Getter
@Setter
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private ExecutorService executor;
    private final ConnectionSettings connectionSettings;
    private final SitesList sites;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final PageProcessorService pageProcessorService;
    private final Config config;
    private final CaptchaDetector captchaDetector;
    private final ProxyRotator proxyRotator;
    private final CaptchaInteractionService captchaInteractionService;

    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();
    public static final int INDEXING_WHOLE_SITE = 0;
    public static final int INDEXING_ONE_PAGE = 1;
    public static volatile boolean stop;

    private static final ConcurrentHashMap<String, Map<String, String>> siteCookies = new ConcurrentHashMap<>();

    private int connectionTimeout = 15_000;
    private long start;
    private long start2;

    @Override
    public ResponseMessage startIndexing() {
        if (siteRepository.findAll().stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            throw new BadRequestException("Индексация уже запущена");
        }

        List<searchengine.config.Site> sitesToIndex = sites.getSites().stream()
                .filter(configSite -> {
                    Site dbSite = siteRepository.findByUrl(configSite.getUrl());
                    return dbSite == null || dbSite.getStatus() != Status.INDEXED;
                })
                .toList();

        if (sitesToIndex.isEmpty()) {
            throw new BadRequestException("Все сайты уже проиндексированы");
        }

        globalLinksSet.clear();
        stop = false;
        executor = Executors.newFixedThreadPool(sitesToIndex.size());
        sitesToIndex.forEach(configSite -> executor.submit(new StartIndexing(configSite)));
        start = System.currentTimeMillis();
        return sendResponse(true, "");
    }

    @Override
    public ResponseMessage stopIndexing() {
        List<Site> allSites = siteRepository.findAll();
        if (allSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            stop = true;
            return sendResponse(true, "");
        } else {
            throw new BadRequestException("Индексация не запущена");
        }
    }

    public class StartIndexing implements Runnable {
        private final Site site = new Site();
        private final String baseUrl;
        private final String startLink;
        private final String siteName;

        public StartIndexing(searchengine.config.Site configSite) {
            this.baseUrl = configSite.getUrl();
            String su = configSite.getStartUrl();
            this.startLink = (su != null && !su.isBlank()) ? su : baseUrl;
            this.siteName = configSite.getName();
        }

        @Override
        public void run() {
            Site existing = siteRepository.findByUrl(baseUrl);
            if (existing != null) {
                pageProcessorService.clearSite(existing.getId());
            }
            siteCookies.remove(baseUrl);
            site.setUrl(baseUrl);
            site.setName(siteName);
            setSiteStatus(site, Status.INDEXING, "");

            int concurrency = connectionSettings.getMaxConcurrentRequests() > 0
                    ? connectionSettings.getMaxConcurrentRequests() : 4;
            try {
                new ForkJoinPool(concurrency).invoke(new IndexingTask(startLink, site));
                setSiteStatus(site, Status.INDEXED, "");
            } catch (CancellationException e) {
                setSiteStatus(site, Status.FAILED, "Индексация остановлена пользователем");
                log.info("Индексация остановлена пользователем");
            } catch (RuntimeException e) {
                log.error("Ошибка индексации сайта {}: {}", siteName, e.getMessage(), e);
                setSiteStatus(site, Status.FAILED, e.getMessage());
            } finally {
                boolean allDone = siteRepository.findAll().stream()
                        .allMatch(s -> s.getStatus() == Status.INDEXED || s.getStatus() == Status.FAILED);
                if (allDone) {
                    start2 = System.currentTimeMillis();
                    lemmaFinder.saveIndex();
                    log.info("Index saving took {} seconds", (System.currentTimeMillis() - start2) / 1000);
                    log.info("Parsing took {} seconds", (System.currentTimeMillis() - start) / 1000);
                    shutdownExecutor();
                }
            }
        }
    }

    private synchronized void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            executor = null;
        }
    }

    @Getter
    @Setter
    public class IndexingTask extends RecursiveAction {
        private String link;
        private Site site;
        private List<IndexingTask> subTaskList = new ArrayList<>();

        public IndexingTask(String link, Site site) {
            this.link = link;
            this.site = site;
        }

        @Override
        protected void compute() {
            globalLinksSet.add(link);
            Document document = connectToPageAndSaveIt(link, site, INDEXING_WHOLE_SITE);
            if (document == null) return;

            String rawBase = site.getUrl().replaceFirst("^https?://", "");
            String siteUrlNorm = rawBase.contains("?") ? rawBase.substring(0, rawBase.indexOf('?')) : rawBase;
            List<String> fileExts = config.getFileExtensions() != null ? config.getFileExtensions() : List.of();
            List<String> pathParts = config.getPathContaining() != null ? config.getPathContaining() : List.of();

            List<String> allHrefs = document.select("a").stream()
                    .map(e -> e.attr("abs:href"))
                    .filter(e -> !e.isBlank())
                    .map(e -> e.contains("#") ? e.substring(0, e.indexOf('#')) : e)
                    .filter(e -> !e.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            Set<String> linksSet = allHrefs.stream()
                    .filter(e -> e.replaceFirst("^https?://", "").startsWith(siteUrlNorm)
                            && fileExts.stream().noneMatch(e::endsWith)
                            && pathParts.stream().noneMatch(e::contains))
                    .collect(Collectors.toSet());

            if (IndexingServiceImpl.stop && getPool() != null) {
                getPool().shutdownNow();
                return;
            }

            linksSet.removeIf(url -> !IndexingServiceImpl.globalLinksSet.add(url));

            linksSet.forEach(subLink -> subTaskList.add(new IndexingTask(subLink, site)));
            invokeAll(subTaskList);
        }
    }

    @Override
    public ResponseMessage addPageForIndexing(String link) {
        String host;
        try {
            host = new URL(link).getHost();
        } catch (MalformedURLException e) {
            log.info("MalformedURLException: {}", e.getMessage());
            return sendResponse(false, "Проверьте введенный адрес страницы");
        }
        String finalHost = host;
        Optional<searchengine.config.Site> optionalSite = sites.getSites().stream()
                .filter(s -> s.getUrl().contains(finalHost))
                .findFirst();
        if (optionalSite.isEmpty() || host.isEmpty()) {
            throw new ResourceForbiddenException("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }
        Site site = siteRepository.findByUrl(optionalSite.get().getUrl());
        if (site == null) {
            site = createNewSiteEntity(optionalSite.get().getName(), optionalSite.get().getUrl());
        }
        setSiteStatus(site, Status.INDEXING, "");
        try {
            if (connectToPageAndSaveIt(link, site, INDEXING_ONE_PAGE) == null) {
                log.error("Данная страница недоступна: {}", link);
                throw new ResourceDoesNotMatchException("Данная страница недоступна");
            }
        } catch (RuntimeException e) {
            log.info("Не удалось подключиться к странице: {}", e.getMessage());
            throw new ResourceDoesNotMatchException("Не удалось подключиться к странице: " + e.getMessage());
        }
        lemmaFinder.saveIndex();
        setSiteStatus(site, Status.INDEXED, "");
        return sendResponse(true, "");
    }

    public Document connectToPageAndSaveIt(String link, Site site, int method) throws RuntimeException {
        int delayMin = connectionSettings.getDelayMin() > 0 ? connectionSettings.getDelayMin() : connectionSettings.getTimeout();
        int delayMax = Math.max(connectionSettings.getDelayMax(), delayMin + 1);
        int delay = delayMin + ThreadLocalRandom.current().nextInt(delayMax - delayMin);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new CancellationException("Индексация остановлена пользователем");
        }

        String path;
        try {
            URL parsedUrl = new URL(link);
            String query = parsedUrl.getQuery();
            path = parsedUrl.getPath() + (query != null && !query.isEmpty() ? "?" + query : "");
        } catch (MalformedURLException e) {
            return null;
        }

        for (int captchaRound = 0; captchaRound < 2; captchaRound++) {
            FetchResult result = fetchWithRetry(link, site);
            if (result == null) return null;

            if (result.blockType() == CaptchaDetector.BlockType.SESSION_EXPIRED) {
                if (captchaRound == 0) {
                    log.info("Session expired at {}, recovering via search form", link);
                    if (recoverFromSessionExpired(result.document(), site)) {
                        continue;
                    }
                    return null;
                } else {
                    log.warn("Session still expired after recovery attempt, skipping: {}", link);
                    return null;
                }
            }

            if (result.blockType() == CaptchaDetector.BlockType.CAPTCHA_IMAGE) {
                if (captchaRound == 0) {
                    log.info("CAPTCHA detected at {}, requesting operator input", link);
                    Map<String, String> cookies = new HashMap<>(siteCookies.getOrDefault(site.getUrl(), Map.of()));
                    CaptchaInteractionService.SolvedChallenge solved =
                            captchaInteractionService.awaitSolution(link, result.document(), cookies);
                    if (solved == null) return null; // таймаут — пропускаем страницу
                    if (solved.challenge() != null) {
                        submitCaptchaForm(site, solved.challenge(), solved.solution(), null); // решили сами
                    }
                    // solved.challenge()==null: другая задача решила — просто повторяем с новыми cookies
                    continue;
                } else {
                    // CAPTCHA повторилась после решения — пропускаем, не сохраняем мусор в БД
                    log.warn("CAPTCHA persists after solving attempt, skipping: {}", link);
                    return null;
                }
            }

            if (result.blockType() == CaptchaDetector.BlockType.BLOCKED) {
                log.warn("Access blocked (403): {}", link);
                return null;
            }

            if (link.equals(site.getUrl()) && result.statusCode() >= 400) {
                throw new RuntimeException("Сайт вернул HTTP " + result.statusCode());
            }

            Page page = new Page();
            if (method == INDEXING_ONE_PAGE) {
                page = pageProcessorService.updatePage(path, site.getId());
            }
            lemmaFinder.collectLemmas(
                    pageRepository.save(fillThePage(page, path, site, result.content(), result.statusCode())).getId());
            setSiteStatus(site);
            return result.document();
        }
        return null;
    }

    private record FetchResult(Document document, int statusCode, String content, CaptchaDetector.BlockType blockType) {}

    private FetchResult fetchWithRetry(String link, Site site) {
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(5_000L * (1L << (attempt - 1)));
                } catch (InterruptedException e) {
                    throw new CancellationException("Индексация остановлена пользователем");
                }
            }

            List<String> agents = connectionSettings.getUserAgents();
            String userAgent = (agents != null && !agents.isEmpty())
                    ? agents.get(ThreadLocalRandom.current().nextInt(agents.size()))
                    : connectionSettings.getUserAgent();

            ConnectionSettings.ProxyEntry proxy = proxyRotator.next();

            Connection connection = Jsoup.connect(link)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .userAgent(userAgent)
                    .referrer(connectionSettings.getReferrer())
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    .timeout(connectionTimeout)
                    .cookies(new HashMap<>(siteCookies.getOrDefault(site.getUrl(), Map.of())));
            if (proxy != null) connection.proxy(proxy.getHost(), proxy.getPort());

            Document document = null;
            String content = "";
            int statusCode;

            try {
                document = connection.get();
                content = document.toString();
                statusCode = connection.response().statusCode();
                Map<String, String> responseCookies = connection.response().cookies();
                if (!responseCookies.isEmpty()) {
                    siteCookies.compute(site.getUrl(), (k, existing) -> {
                        Map<String, String> merged = existing != null ? new HashMap<>(existing) : new HashMap<>();
                        merged.putAll(responseCookies);
                        return merged;
                    });
                }
            } catch (IOException e) {
                if (link.equals(site.getUrl())) {
                    throw new RuntimeException("Не удалось подключиться к сайту: " + e.getMessage());
                }
                if (proxy != null) proxyRotator.markFailed(proxy);
                log.error("Страница недоступна: {} {}", e.getMessage(), link);
                statusCode = 404;
            } catch (UnknownContentTypeException e) {
                log.error("Неподдерживаемый контент: {} {}", e.getMessage(), link);
                statusCode = 415;
            }

            CaptchaDetector.BlockType blockType = captchaDetector.detect(statusCode, content);

            if (blockType == CaptchaDetector.BlockType.RATE_LIMIT
                    || blockType == CaptchaDetector.BlockType.CLOUDFLARE) {
                log.warn("Rate limited ({}) attempt {}/{}: {}", blockType, attempt + 1, maxRetries, link);
                if (proxy != null) proxyRotator.markFailed(proxy);
                continue;
            }

            return new FetchResult(document, statusCode, content, blockType);
        }
        log.warn("All {} retry attempts exhausted for: {}", maxRetries, link);
        return null;
    }

    private void submitCaptchaForm(Site site, CaptchaChallenge challenge, String solution,
                                   Map<String, String> extraFields) {
        if (challenge.getFormAction() == null || challenge.getCaptchaFieldName() == null) return;
        try {
            Map<String, String> formData = new LinkedHashMap<>();
            if (challenge.getHiddenFields() != null) formData.putAll(challenge.getHiddenFields());
            if (extraFields != null) formData.putAll(extraFields);
            formData.put(challenge.getCaptchaFieldName(), solution);

            Connection.Response response = Jsoup.connect(challenge.getFormAction())
                    .data(formData)
                    .userAgent(connectionSettings.getUserAgent())
                    .referrer(challenge.getPageUrl())
                    .cookies(new HashMap<>(siteCookies.getOrDefault(site.getUrl(), Map.of())))
                    .method(Connection.Method.POST)
                    .timeout(connectionTimeout)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute();

            Map<String, String> newCookies = response.cookies();
            if (!newCookies.isEmpty()) {
                siteCookies.compute(site.getUrl(), (k, existing) -> {
                    Map<String, String> merged = existing != null ? new HashMap<>(existing) : new HashMap<>();
                    merged.putAll(newCookies);
                    return merged;
                });
                log.info("CAPTCHA form submitted, {} new cookies received", newCookies.size());
            }
        } catch (IOException e) {
            log.warn("Failed to submit CAPTCHA form: {}", e.getMessage());
        }
    }

    private boolean recoverFromSessionExpired(Document expiredPage, Site site) {
        Element backLink = expiredPage.select("a").stream()
                .filter(a -> a.text().toLowerCase().contains("форму поиска"))
                .findFirst().orElse(null);
        if (backLink == null) {
            log.warn("Session expired page has no 'Вернуться на форму поиска' link");
            return false;
        }
        String formPageUrl = backLink.absUrl("href");
        if (formPageUrl.isEmpty()) formPageUrl = backLink.attr("href");
        if (formPageUrl == null || formPageUrl.isEmpty()) {
            log.warn("Empty href on 'back to search form' link");
            return false;
        }

        Document formPage = fetchSearchForm(formPageUrl, site);
        if (formPage == null) return false;

        Map<String, String> cookies = new HashMap<>(siteCookies.getOrDefault(site.getUrl(), Map.of()));
        CaptchaInteractionService.SolvedChallenge solved =
                captchaInteractionService.awaitSolution(formPageUrl, formPage, cookies);
        if (solved == null) return false; // таймаут / прерывание
        if (solved.challenge() == null) return true; // другой поток уже восстановил сессию

        Map<String, String> originalParams = extractQueryParams(getConfiguredStartUrl(site));
        submitCaptchaForm(site, solved.challenge(), solved.solution(), originalParams);
        return true;
    }

    private Document fetchSearchForm(String url, Site site) {
        try {
            Connection.Response response = Jsoup.connect(url)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .userAgent(connectionSettings.getUserAgent())
                    .referrer(connectionSettings.getReferrer())
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                    .timeout(connectionTimeout)
                    .cookies(new HashMap<>(siteCookies.getOrDefault(site.getUrl(), Map.of())))
                    .execute();
            Map<String, String> respCookies = response.cookies();
            if (!respCookies.isEmpty()) {
                siteCookies.compute(site.getUrl(), (k, existing) -> {
                    Map<String, String> merged = existing != null ? new HashMap<>(existing) : new HashMap<>();
                    merged.putAll(respCookies);
                    return merged;
                });
            }
            return response.parse();
        } catch (IOException e) {
            log.warn("Failed to fetch search form at {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String getConfiguredStartUrl(Site site) {
        return sites.getSites().stream()
                .filter(s -> site.getUrl().equals(s.getUrl()))
                .findFirst()
                .map(s -> {
                    String su = s.getStartUrl();
                    return (su != null && !su.isBlank()) ? su : s.getUrl();
                })
                .orElse(site.getUrl());
    }

    private Map<String, String> extractQueryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        try {
            String query = new URL(url).getQuery();
            if (query == null || query.isEmpty()) return params;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String name = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                params.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        } catch (MalformedURLException e) {
            log.warn("Cannot parse query params from {}: {}", url, e.getMessage());
        }
        return params;
    }

    public ResponseMessage sendResponse(boolean result, String message) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(result);
        responseMessage.setError(message);
        return responseMessage;
    }

    public void setSiteStatus(Site site, Status status, String lastError) {
        site.setStatus(status);
        site.setLastError(lastError);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.saveAndFlush(site);
    }

    public void setSiteStatus(Site site) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.saveAndFlush(site);
    }

    public Site createNewSiteEntity(String name, String url) {
        Site newSite = new Site();
        newSite.setName(name);
        newSite.setUrl(url);
        return newSite;
    }

    private Page fillThePage(Page page, String path, Site site, String content, int statusCode) {
        page.setSite(site);
        page.setPath(path);
        page.setCode(statusCode);
        page.setContent(content);
        return page;
    }

    @Override
    public void clearDb() {
        pageProcessorService.clearDb();
    }
}
