package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.Config;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Index;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Getter
@Setter
@Service
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "connection-settings")
public class IndexingServiceImpl implements IndexingService {
    private ExecutorService executor;
    private final SitesList sites;
    private List<Site> sitesList = new ArrayList<>();
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    private final Config config;
    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();
    public static final int INDEXING_WHOLE_SITE = 0;
    public static final int INDEXING_ONE_PAGE = 1;
    public static volatile boolean stop;
    long start;
    long start2;
    private String userAgent;
    private String referrer;
    private int timeout;

    @Override
    public ResponseMessage startIndexing() {

        List<Site> indexingSites = siteRepository.findAll();

        if (indexingSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            return sendResponse(false, "Индексация уже запущена");
        }
        globalLinksSet.clear();
        stop = false;
        //clearDb();
        if (executor == null) executor = Executors.newFixedThreadPool(sites.getSites().size());
        for (int i = 0; i < sites.getSites().size(); i++) {
            try {
                executor.submit(new StartIndexing(i));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        start = System.currentTimeMillis();
        return sendResponse(true, "");
    }

    @Override
    public ResponseMessage stopIndexing() {
        List<Site> sites = siteRepository.findAll();

        if (sites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            stop = true;
            return sendResponse(true, "");
        } else {
            return sendResponse(false, "Индексация не запущена");
        }
    }

    public class StartIndexing implements Runnable {
        private final ForkJoinPool forkJoinPool = new ForkJoinPool();
        private final Site site = new Site();
        private final int siteNumber;
        private final String siteURL;

        public StartIndexing(int siteNumber) throws MalformedURLException {
            this.siteNumber = siteNumber;
            siteURL = sites.getSites().get(siteNumber).getUrl();
        }

        @Override
        public void run() {
            String siteName = sites.getSites().get(siteNumber).getName();
            site.setUrl(siteURL);
            site.setName(siteName);
            setSiteStatus(site, Status.INDEXING, "");

            try {//TODO: разобраться с ошибками
                forkJoinPool.invoke(new IndexingTask(siteURL, site));
                if (forkJoinPool.isShutdown() || forkJoinPool.isTerminated()) { //what is the difference between isShutdown and isTerminated?
                    log.info("isShutdown: {}", forkJoinPool.isShutdown());
                    setSiteStatus(site, Status.FAILED, "Индексация остановлена пользователем");
                } else if (forkJoinPool.isQuiescent()) {
                    log.info("indexSet size = {}", lemmaFinder.getIndexSet().size());
                    setSiteStatus(site, Status.INDEXED, "");
                    forkJoinPool.shutdown();
                } else {
                    log.info("pool is stopped by some reason {}", forkJoinPool.getPoolSize());
                    setSiteStatus(site, Status.FAILED, "Unknown error");
                }
            } catch (Exception e) {
                log.error("Exception!: {}", e.getMessage(), e);//при нажатии кнопки "остановить индексацию" происходит CancellationException
                setSiteStatus(site, Status.FAILED, "Индексация остановлена пользователем");
            }
            if ((siteRepository.findAll()).stream().allMatch(s -> s.getStatus() == Status.INDEXED)) {
                start2 = System.currentTimeMillis();
                lemmaFinder.saveIndex();
                log.info("Index saving took {} seconds", (System.currentTimeMillis() - start2) / 1000);
                log.info("Parsing took {} seconds", (System.currentTimeMillis() - start) / 1000);
            }
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
            Document document;
            try {
                Thread.sleep(timeout);
                document = connectToPageAndSaveIt(link, site, INDEXING_WHOLE_SITE);
            } catch (InterruptedException | MalformedURLException e) {//TODO: разобраться с исключениями
                log.error("Error: {}", e.getMessage(), e);
                return;
            }
            if (document == null) return;

            Set<String> linksSet = document.select("a").stream()
                    .map(e -> e.attr("abs:href"))
                    .filter(e -> e.contains(site.getUrl())
                            && config.getFileExtensions().stream().noneMatch(e::endsWith)
                            && config.getPathContaining().stream().noneMatch(e::contains))
//                            && !e.contains("#") //TODO: add to config
//                            && !e.endsWith(".jpg")
//                            && !e.endsWith(".pdf")
//                            && !e.endsWith(".png")
//                            && !e.endsWith(".mp4")
//                            && !e.contains("?"))
                    .collect(Collectors.toSet());
            if (IndexingServiceImpl.stop && getPool() != null) {
                getPool().shutdownNow();
                return;
            }
            linksSet.removeAll(IndexingServiceImpl.globalLinksSet);
            IndexingServiceImpl.globalLinksSet.addAll(linksSet);
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
        Optional<searchengine.config.Site> optionalSite = sites.getSites().stream().filter(s -> s.getUrl().contains(finalHost)).findFirst();
        if (optionalSite.isEmpty() || host.isEmpty()) {
            return sendResponse(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }
        Site site = siteRepository.findByUrl(optionalSite.get().getUrl());
        if (site == null) {
            site = createNewSiteEntity(optionalSite.get().getName(), optionalSite.get().getUrl());
        }
        setSiteStatus(site, Status.INDEXING, "");
        try {
            if (connectToPageAndSaveIt(link, site, INDEXING_ONE_PAGE) == null)
                return sendResponse(false, "Данная страница недоступна");
        } catch (InterruptedException e) {
            log.error("Индексация прервана : {}", e.getMessage());
            return sendResponse(false, "Индексация прервана");
        } catch (MalformedURLException e) {
            log.info("Неправильный адрес страницы: {}{}", e.getMessage(), link);
            return sendResponse(false, "Неправильный адрес страницы");
        }
        lemmaFinder.saveIndex();
        setSiteStatus(site, Status.INDEXED, "");
        return sendResponse(true, "");//По ТЗ формат ответа не использует поле error; можно ли отправить пустое сообщение?
    }

    public Document connectToPageAndSaveIt(String link, Site site, int method) throws MalformedURLException, InterruptedException {
        Thread.sleep(timeout);
        Connection connection = Jsoup.connect(link).ignoreHttpErrors(true).followRedirects(false);
        Document document;
        String content = "";
        String path = new URL(link).getPath();
        Page page = new Page();
        int statusCode;

        try {
            document = connection.userAgent(userAgent).referrer(referrer).get();
            content = document.toString();
            statusCode = connection.response().statusCode();
        } catch (IOException e) {
            log.error("HTTP error fetching URL. Status: {}{}", e.getMessage(), link);
            document = null;
            statusCode = 404; //if server can't answer
//        } catch (Exception e) {
//            log.error("Exception: {}{}", e.getMessage(), link);
//            return null;
        }

        if (method == INDEXING_ONE_PAGE) page = updatePage(path, site);
        lemmaFinder.collectLemmas(pageRepository.save(fillThePage(page, path, site, content, statusCode)).getId());
        setSiteStatus(site);
        return document;
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

    public Site createNewSiteEntity (String name, String url) {
        Site site = new Site();
        site.setName(name);
        site.setUrl(url);
        return site;
    }

    public Page updatePage(String path, Site site) {
        Page page = pageRepository.findByPathAndSiteId(path, site.getId());
        if (page == null) {
            page = new Page();
        } else {
            List<Index> indexList = indexRepository.findByPageId(page.getId());
            if (indexList != null) {
                indexList.forEach(i -> lemmaRepository.deleteLemmaById(i.getLemmaId()));
                indexRepository.deleteAllInBatch(indexList);
            }
        }
        return page;
    }

    private Page fillThePage(Page page, String path, Site site, String content, int statusCode) {
        page.setSite(site);
        page.setPath(path);
        page.setCode(statusCode);
        page.setContent(content);
        return page;
    }

    public void clearDb() {
        siteRepository.setForeignKeyCheckNull();
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        siteRepository.setForeignKeyCheckNotNull();
    }
}
