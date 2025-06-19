package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.UnknownContentTypeException;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.Config;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.exceptions.BadRequestException;
import searchengine.exceptions.ResourceDoesNotMatchException;
import searchengine.exceptions.ResourceForbiddenException;
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
    @Lazy
    private final IndexingServiceImpl self;
    private final Config config;
    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();
    public static final int INDEXING_WHOLE_SITE = 0;
    public static final int INDEXING_ONE_PAGE = 1;
    public static volatile boolean stop;
    private String userAgent;
    private String referrer;
    private int timeout;
    @Value("${indexing-settings.clearDb}")
    private boolean clearDb;
    private long start;
    private long start2;

    @Override
    public ResponseMessage startIndexing() {

        List<Site> indexingSites = siteRepository.findAll();

        if (indexingSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            throw new BadRequestException("Индексация уже запущена");
        }
        globalLinksSet.clear();
        stop = false;
        if (clearDb) self.clearDb();
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
            throw new BadRequestException("Индексация не запущена");
        }
    }

    public class StartIndexing implements Runnable {
        private final Site site = new Site();
        private final int siteNumber;
        private final String link;

        public StartIndexing(int siteNumber) throws MalformedURLException {
            this.siteNumber = siteNumber;
            link = sites.getSites().get(siteNumber).getUrl();
        }

        @Override
        public void run() {
            String siteName = sites.getSites().get(siteNumber).getName();
            site.setUrl(link);
            site.setName(siteName);
            setSiteStatus(site, Status.INDEXING, "");

            try {
                new ForkJoinPool().invoke(new IndexingTask(link, site));
                setSiteStatus(site, Status.INDEXED, "");
            } catch (CancellationException e) {
                setSiteStatus(site, Status.FAILED, "Индексация остановлена пользователем");
                log.info("Индексация остановлена пользователем");//при нажатии кнопки "остановить индексацию" происходит CancellationException
            } catch (RuntimeException e) {
                log.info("Не удалось подключиться к сайту {}", siteName);
                setSiteStatus(site, Status.FAILED, "Не удалось подключиться к сайту");
            } finally {
                if ((siteRepository.findAll()).stream().allMatch(s -> s.getStatus() == Status.INDEXED || s.getStatus() == Status.FAILED)) {
                    start2 = System.currentTimeMillis();
                    lemmaFinder.saveIndex();
                    log.info("Index saving took {} seconds", (System.currentTimeMillis() - start2) / 1000);
                    log.info("Parsing took {} seconds", (System.currentTimeMillis() - start) / 1000);
                }
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
            Document document;
            globalLinksSet.add(link);

            document = connectToPageAndSaveIt(link, site, INDEXING_WHOLE_SITE);
            if (document == null) return;

            Set<String> linksSet = document.select("a").stream()
                    .map(e -> e.attr("abs:href"))
                    .filter(e -> e.contains(site.getUrl())
                            && config.getFileExtensions().stream().noneMatch(e::endsWith)
                            && config.getPathContaining().stream().noneMatch(e::contains))
                    .collect(Collectors.toSet());
            if (IndexingServiceImpl.stop && getPool() != null) {
                getPool().shutdownNow();
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
            log.info("Не удалось подключиться к сайту");
            throw new ResourceDoesNotMatchException("Не удалось подключиться к сайту");
        }
        lemmaFinder.saveIndex();
        setSiteStatus(site, Status.INDEXED, "");
        return sendResponse(true, "");//По ТЗ формат ответа не использует поле error; можно ли отправить пустое сообщение?
    }

    public Document connectToPageAndSaveIt(String link, Site site, int method) throws RuntimeException {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            throw new CancellationException("Индексация остановлена пользователем");
        }
        Connection connection = Jsoup.connect(link).ignoreHttpErrors(true).followRedirects(false);
        Document document;
        String content = "";
        String path;
        try {
            path = new URL(link).getPath();
        } catch (MalformedURLException e) {
            return null;
        }
        Page page = new Page();
        int statusCode;

        try {
            document = connection.userAgent(userAgent).referrer(referrer).get();
            content = document.toString();
            statusCode = connection.response().statusCode();
        } catch (IOException e) {
            if (link.equals(site.getUrl())) {
                throw new RuntimeException("Не удалось подключиться к сайту");
            }
            log.error("Страница недоступна: {} {}", e.getMessage(), link);
            document = null;
            statusCode = 404; //if server can't answer
        } catch (UnknownContentTypeException e) {
            log.error("Неподдерживаемый контент: {}{}", e.getMessage(), link);
            document = null;
            statusCode = 415; //Unsupported Media Type
        }

        if (method == INDEXING_ONE_PAGE) page = self.updatePage(path, site);
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

    public Site createNewSiteEntity(String name, String url) {
        Site site = new Site();
        site.setName(name);
        site.setUrl(url);
        return site;
    }

    @Transactional
    public Page updatePage(String path, Site site) {
        Page page = pageRepository.findByPathAndSiteId(path, site.getId());
        if (page == null) {
            page = new Page();
        } else {
            List<Index> indexList = indexRepository.findByPageId(page.getId());
            if (indexList != null) {
                indexList.forEach(i -> lemmaRepository.decreaseLemmaFreqById(i.getLemmaId()));
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

    @Transactional
    @Override
    public void clearDb() {
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        log.info("DB cleared");
    }
}
