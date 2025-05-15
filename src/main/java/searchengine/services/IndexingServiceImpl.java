package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Index;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    @Override
    public ResponseMessage addPageForIndexing(String link) {
        String host;
        try {
            host = new URL(link).getHost();
        } catch (MalformedURLException e) {
            //log.error("MalformedURLException: {}", e.getMessage());
            log.info("MalformedURLException: {}", e.getMessage());
            //throw new RuntimeException(e);
            return sendResponse(false, "Проверьте введенный адрес страницы");
        }
//        String urlRegex = "[a-z0-9]+\\.[a-z]+";
//        String cleanUrl = "";
//        Pattern pattern = Pattern.compile(urlRegex);
//        Matcher matcher = pattern.matcher(url);
//        while (matcher.find()) {
//            cleanUrl = matcher.group();
//        }
//        String finalCleanUrl = cleanUrl;

//        if (host == null) {
//            return sendResponse(false, "Проверьте введенный адрес страницы");
//        }
                String finalHost = host;
        Optional<searchengine.config.Site> optionalSite = sites.getSites().stream().filter(s -> s.getUrl().contains(finalHost)).findFirst();
        if (optionalSite.isEmpty() || host.isEmpty()) {
            return sendResponse(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }
        Site site = siteRepository.findByUrl(optionalSite.get().getUrl());
        if (site == null) {
            site = new Site();//TODO: вынести в метод createSiteEntity
            site.setName(optionalSite.get().getName());
            site.setUrl(optionalSite.get().getUrl());
        }
        setSiteStatus(site, Status.INDEXING, "");

        try {
            if (connectToPageAndSaveIt(link, site, INDEXING_ONE_PAGE) == null)//TODO: send page to indexing
                return sendResponse(false, "Данная страница недоступна");
        } catch (InterruptedException | MalformedURLException | SQLException e) {
            throw new RuntimeException(e);
        }
        lemmaFinder.saveIndex();
        setSiteStatus(site, Status.INDEXED, "");

        return sendResponse(true, "");//По ТЗ формат ответа не использует поле error; можно ли отправить пустое сообщение?
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

    public Document connectToPageAndSaveIt(String link, Site site, int method) throws InterruptedException, MalformedURLException, SQLException {//optimize method length
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
        } catch (HttpStatusException e) {
            log.error("HTTP error fetching URL. Status: {}{}", e.getMessage(), link);
            document = null;
            statusCode = 404; //if server can't answer
        } catch (Exception e) {
            log.error("Exception: {}{}", e.getMessage(), link);
            return null;
        }
        if (method == INDEXING_ONE_PAGE) {
            page = pageRepository.findByPathAndSite_id(path, site.getId());//TODO: if Page does not exist, create it. Done!
            if (page == null) {
                page = new Page();
            } else {
                List<Index> indexList = indexRepository.findByPageId(page.getId());//TODO: get all lemmas for this page and delete it in DB. Done!
                if (indexList != null) {
                    indexList.forEach(i -> lemmaRepository.deleteLemmaById(i.getLemmaId()));
                    indexRepository.deleteAllInBatch(indexList);//TODO: check how it works
                }
            }
        }
        page.setSite(site);//TODO: при возможности отправлять в метод createPage(), чтобы не дублировать код
        page.setPath(path);
        page.setCode(statusCode);
        page.setContent(content);
        lemmaFinder.collectLemmas(pageRepository.save(page).getId());
        site.setStatusTime(LocalDateTime.now());
        siteRepository.saveAndFlush(site);
        return document;
    }


    public class StartIndexing implements Runnable {
        private final ForkJoinPool forkJoinPool = new ForkJoinPool();
        private final Site site = new Site();
        private final StringBuilder linkBuild = new StringBuilder();
        private final int siteNumber;
        private final URL siteURL;

        public StartIndexing(int siteNumber) throws MalformedURLException {
            this.siteNumber = siteNumber;
            siteURL = new URL(sites.getSites().get(siteNumber).getUrl());
        }

        @Override
        public void run() {
            String siteName = sites.getSites().get(siteNumber).getName();

            linkBuild.append(siteURL.getProtocol()).append("://").append(siteURL.getHost()).append("/");//TODO: переделать с использование класса URL
            if (siteURL.getPath().length() > 1) linkBuild.append(siteURL.getPath());//если начинаем индексировать сайт не с корневой страницы
            //if (linkBuild.toString().endsWith("/")) linkBuild.deleteCharAt(linkBuild.length() - 1);
            String link = linkBuild.toString();
            site.setUrl(link);
            site.setName(siteName);
            setSiteStatus(site, Status.INDEXING, "");

            try {
                forkJoinPool.invoke(new IndexingTask(link, site));
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
                log.error("Exception!: {}", e.getMessage(), e);
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
            } catch (InterruptedException | MalformedURLException | SQLException e) {//TODO: разобраться с исключениями
                log.error("Error: {}", e.getMessage(), e);
                return;
            }
            if (document == null) return;

            Set<String> linksSet = document.select("a").stream()
                    .map(e -> e.attr("abs:href"))
                    .filter(e -> e.contains(site.getUrl())
                            && !e.contains("#") //TODO: add to config
                            && !e.endsWith(".jpg")
                            && !e.endsWith(".pdf")
                            && !e.endsWith(".png")
                            && !e.contains("?"))
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

    public void clearDb() {
        siteRepository.setForeignKeyCheckNull();
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        siteRepository.setForeignKeyCheckNotNull();
    }
}
