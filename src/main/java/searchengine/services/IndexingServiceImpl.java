package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;

@Getter
@Setter
@Service
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "connection-settings")
public class IndexingServiceImpl implements IndexingService {
    private int cores = Runtime.getRuntime().availableProcessors();
    private ExecutorService executor;
    private final SitesList sites;
    private Site site = new Site();
    private List<Site> sitesList = new ArrayList<>();
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;
    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();
    public static volatile boolean stopIndexing = false;
    public static volatile boolean stop;
    public List<Runnable> threadList = new ArrayList<>();
    public List<ForkJoinTask<Void>> fjpList = new ArrayList<>();
    long start;

    @Override
    public ResponseMessage startIndexing() {
        start = System.currentTimeMillis();


        List<Site> indexingSites = siteRepository.findAll();

//        if (indexingSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
//            return sendResponse(false, "Индексация уже запущена");
//        } else {
        threadList.clear();
        if (executor != null) {
            executor.shutdown();
        }
        globalLinksSet.clear();
        stop = false;
        stopIndexing = false;
        /*siteRepository.setForeignKeyCheckNull();
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        siteRepository.setForeignKeyCheckNotNull();*/
        //siteRepository.deleteAll();
        executor = Executors.newFixedThreadPool(sites.getSites().size());
        for (int i = 0; i < sites.getSites().size(); i++) {
            threadList.add(new StartIndexing(i)); //How to start threads in ExecutorService?
        }

        threadList.forEach(executor::execute);
        System.out.println("Started threads: " + threadList.size());
        //
        return sendResponse(true, "");
//        }
    }

    @Override
    public ResponseMessage stopIndexing() {

        List<Site> sites = siteRepository.findAll();
        if (sites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            stop = true;
            //fjpList.forEach(t -> t.cancel(true)); //TODO: cancel tasks by use iterator

            /*for (Site site : sites) {
                if (site.getStatus() == Status.INDEXING) {
                    site = siteRepository.findById(site.getId()).get();

                }
            }*/
            //TODO: wait for ForkJoinPool shutdown
            while (true) {
                if (stopIndexing) {//TODO: if forkjoin never been ran, stopIndexing always will be false
                    executor.shutdown(); //Executor shutdown completed, but one thread is working
                    threadList.clear();
                    return sendResponse(true, "");
                }
            }
        } else {
            return sendResponse(false, "Индексация не запущена");
        }
    }

    @Override
    public ResponseMessage addPageForIndexing(String link) {
        Document document;
        int consequense = 0;
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
        for (searchengine.config.Site site : sites.getSites()) {
            if (link.contains(site.getUrl())) {
                consequense++;
            }
        }
//        if (consequense == 0) {
//            return sendResponse(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
//        }
        try {
//            Thread.sleep(950);
//            response = Jsoup.connect(link).execute();
            Thread.sleep(550);
            document = Jsoup.connect(link).userAgent(userAgent).get();
        } catch (IOException | InterruptedException e) {
            System.out.println("Broken link: " + link);
            return sendResponse(false, "Broken link");
        }
            /*response = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0 Chrome/51.0.2704.106 Safari/537.36 OPR/38.0.2220.41")
                    .referrer("google.com").timeout(1000).execute().bufferUp();
            document = response.parse();*/
        Page page = pageRepository.findByPath(link);
        if (page == null) {
            page = new Page();
            site.setName("Test");
            site.setUrl("http://test.ru");
            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.saveAndFlush(site);
            page.setSite(site);
        }

        page.setPath(link);
        page.setCode(document.connection().response().statusCode());
        page.setContent(document.text());
        int pageId = pageRepository.saveAndFlush(page).getId();
        try {
            lemmaFinder.collectLemmas(pageId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return sendResponse(true, null);
    }

    public ResponseMessage sendResponse(boolean result, String message) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(result);
        responseMessage.setError(message);
        return responseMessage;
    }


    @RequiredArgsConstructor
    public class StartIndexing implements Runnable {

        private final int siteNumber;

        @Override
        public void run() {
            String link = sites.getSites().get(siteNumber).getUrl();
            Site site = new Site();
            site.setUrl(link);
            site.setStatus(Status.INDEXING);
            site.setName(sites.getSites().get(siteNumber).getName());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.saveAndFlush(site);
            ForkJoinPool forkJoinPool = new ForkJoinPool();
            globalLinksSet.add(link);
            Indexing indexing = new Indexing(link, site, siteRepository, pageRepository, lemmaRepository, lemmaFinder);
            fjpList.add(indexing);
            ForkJoinTask<Void> task = forkJoinPool.submit(indexing);

            while (true) {
                if (forkJoinPool.isTerminating()){
                    System.out.println("FJP is terminating");
                }
                if (task.isCompletedAbnormally()) { //TODO: what is abnormally?
                    System.out.println("isTerminating: " + forkJoinPool.isTerminating());
                    task.getException().printStackTrace();
                    site.setUrl(link);
                    site.setStatus(Status.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.saveAndFlush(site); //save vs saveAndFlush
                    forkJoinPool.shutdown();
                    System.out.println("the task was cancelled");
                    //stopIndexing = true;//it stops all threads
                    break;
                }
                if (task.isCompletedNormally()) {
                    //indexRepository.saveAllAndFlush(lemmaFinder.indexSet);
                    try {
                        lemmaFinder.saveIndex();
                    } catch (PessimisticLockingFailureException plfe) {
                        System.out.println(Arrays.toString(plfe.getStackTrace()));
                    }

                    //List<Page> pageList = pageRepository.findAll();
                    //pageList.forEach(lemmaFinder::collectLemmas);
                    site.setUrl(link);
                    site.setStatus(Status.INDEXED);
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site);
                    forkJoinPool.shutdown();
                    System.out.println("The task was done");
                    System.out.println("It's took " + (System.currentTimeMillis() - start) / 1000 + " seconds");
                    //stopIndexing = true;
                    break;
                }
            }
        }
    }
}
