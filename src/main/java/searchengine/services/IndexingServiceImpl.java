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
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    public List<Callable<Integer>> threadList = new ArrayList<>();
    public List<ForkJoinTask<Void>> fjpList = new ArrayList<>();
    private List<Future<Integer>> futureList = new ArrayList<>();
    private static volatile AtomicInteger counter = new AtomicInteger(0);
    long start;
    private String userAgent;
    private String referrer;
    private int timeout;

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
        clearDb();
        executor = Executors.newFixedThreadPool(sites.getSites().size());
        for (int i = 0; i < sites.getSites().size(); i++) {
            threadList.add( new StartIndexing(i)); //How to start threads in ExecutorService?
        }

        for (Callable<Integer> thread: threadList) {
            Future<Integer> futureThread = executor.submit(thread);
            futureList.add(futureThread);
        }
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
    public ResponseMessage addPageForIndexing(String url) {
        String urlRegex = "[a-z0-9]+\\.[a-z]+/";
        String cleanUrl = "";
        Pattern pattern = Pattern.compile(urlRegex);
        Matcher matcher = pattern.matcher(url);
        while (matcher.find()) {
            cleanUrl = matcher.group();
        }
        String finalCleanUrl = cleanUrl;

        Optional<searchengine.config.Site> optionalSite = sites.getSites().stream().filter(s -> s.getUrl().contains(finalCleanUrl)).findFirst();
        if (optionalSite.isEmpty()) {
            return sendResponse(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }
        site = siteRepository.findByUrl(optionalSite.get().getUrl());
        if (site == null) {
            site = new Site();
        }
        site.setUrl(optionalSite.get().getUrl());
        site.setName(optionalSite.get().getName());
        site.setStatus(Status.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        try {
            saveOnePage(url, site);
        } catch (InterruptedException | MalformedURLException | SQLException e) {
            throw new RuntimeException(e);
        }
       /* try {
//            Thread.sleep(950);
//            response = Jsoup.connect(link).execute();
            Thread.sleep(550);
            document = Jsoup.connect(url).userAgent(userAgent).get();
        } catch (IOException | InterruptedException e) {
            System.out.println("Broken link: " + url);
            return sendResponse(false, "Broken link");
        }
            //response = Jsoup.connect(link)
            //        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:25.0) Gecko/20100101 Firefox/25.0 Chrome/51.0.2704.106 Safari/537.36 OPR/38.0.2220.41")
            //        .referrer("google.com").timeout(1000).execute().bufferUp();
            //document = response.parse();
        Page page = pageRepository.findByPath(url);
        if (page == null) {
            page = new Page();
            site.setName(siteName);
            site.setUrl(siteUrl);
            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            page.setSite(site);
        }

        try {
            page.setPath(new URL(url).getPath());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        page.setCode(document.connection().response().statusCode());
        page.setContent(document.toString());
        int pageId = pageRepository.save(page).getId();
        try {
            lemmaFinder.collectLemmas(pageId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }*/
        lemmaFinder.saveIndex();
        return sendResponse(true);
    }

    public ResponseMessage sendResponse(boolean result, String message) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(result);
        responseMessage.setError(message);
        return responseMessage;
    }

    public ResponseMessage sendResponse(boolean result) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(result);
        return responseMessage;
    }

    @RequiredArgsConstructor
    public class StartIndexing implements Callable<Integer> {

        private final int siteNumber;


        @Override
        public Integer call() {
            String link = sites.getSites().get(siteNumber).getUrl();
            Site site = new Site();
            site.setUrl(link);
            site.setStatus(Status.INDEXING);
            site.setName(sites.getSites().get(siteNumber).getName());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            ForkJoinPool forkJoinPool = new ForkJoinPool();
            globalLinksSet.add(link);
            Indexing indexing = new Indexing(link, site);
            fjpList.add(indexing);
            ForkJoinTask<Void> task = forkJoinPool.submit(indexing);

            do {
                if (forkJoinPool.isTerminating()) {
                    System.out.println("FJP is terminating");
                }
                if (task.isCompletedAbnormally()) { //TODO: what is abnormally?
                    log.info("isTerminating: {}", forkJoinPool.isTerminating());
                    log.error("Exception!: {}", task.getException().getMessage(), task.getException());
                    site.setUrl(link);
                    site.setStatus(Status.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.saveAndFlush(site); //save vs saveAndFlush
                    forkJoinPool.shutdown();
                    log.info("the task was cancelled");
                    //stopIndexing = true;//it stops all threads
                    //break;
                }
                if (task.isCompletedNormally()) {
                    //log.error("Exception task.isCompletedNormally!: {}", task.getException().getMessage(), task.getException());
                    //indexRepository.saveAllAndFlush(lemmaFinder.indexSet);
                    //log.info("saveIndex starts!");
                    log.info("indexSet size = {}", lemmaFinder.getIndexSet().size());
                    //System.err.println("System.err.println");
                    //System.out.println("System.out.println");
                    //lemmaFinder.saveIndex(); //use multiinsert
                    //List<Page> pageList = pageRepository.findAll();
                    //pageList.forEach(lemmaFinder::collectLemmas);
                    site.setUrl(link);
                    site.setStatus(Status.INDEXED);
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site);
                    forkJoinPool.shutdown();
                    log.info("The task was done \n It's took {} seconds", (System.currentTimeMillis() - start) / 1000);
                    log.info("Task is done: {}", task.isDone());
                    //stopIndexing = true;
                    //break;
                }
            } while (!task.isDone());
            log.info("Trying to increment counter");
            counter.addAndGet(1);
            log.info("Counter: {}", counter);
            if (counter.get() == sites.getSites().size()) {
                lemmaFinder.saveIndex();
            }
            return 1;
        }
    }

    @Getter
    @Setter
    public class Indexing extends RecursiveAction {
        //private PageRepository pageRepository;
        //private SiteRepository siteRepository;
        //private LemmaRepository lemmaRepository;
        //private LemmaFinder lemmaFinder;
        private Site site;
        private String link;
        private List<Indexing> taskList = new ArrayList<>();

        public Indexing(String link, Site site) {
            this.link = link;
            this.site = site;
        }

        @Override
        public boolean cancel(boolean c) {
            taskList.forEach(t -> t.cancel(true));
            return super.cancel(true);
        }

        //@SneakyThrows
        @Override
        protected void compute() {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (IndexingServiceImpl.stop) {
                cancel(true);
            }

            Document document = null;
            try {
                document = connectToPageAndSaveIt(link, site);
            } catch (InterruptedException | MalformedURLException | SQLException e) {
                throw new RuntimeException(e);
            }
//            int statusCode;
//            System.out.println("Thread" + Thread.currentThread().getName() + "connect to: "+ link);
//            Connection connection = Jsoup.connect(link).ignoreHttpErrors(true);
//            try {
//                document = connection.userAgent(userAgent).referrer(referrer).get();
//                statusCode = connection.response().statusCode();
//            } catch (SocketTimeoutException ste) {
//                statusCode = 494;
//            }
//
//
//            String content = document.toString();
//            if (String.valueOf(statusCode).startsWith("4") || String.valueOf(statusCode).startsWith("5")) {
//                content = document.toString();
//            }
//            Page page = new Page();
//            page.setSite(site);
//            page.setPath(new URL(link).getPath());
//            page.setCode(statusCode);
//            page.setContent(content);
//            int pageId = pageRepository.saveAndFlush(page).getId();
//            lemmaFinder.collectLemmas(pageId);//why does the location where this method is called have no effect?
//            site.setStatusTime(LocalDateTime.now());
//            siteRepository.saveAndFlush(site);
if(document == null) {
    return;
}
            Set<String> linksSet = document.select("a").stream()
                    .map(e -> e.attr("abs:href"))
                    .filter(e -> e.contains(site.getUrl())
                            && !e.contains("#") //TODO: add to config
                            && !e.endsWith(".jpg")
                            && !e.endsWith(".pdf")
                            && !e.endsWith(".png")
                            && !e.contains("?"))
                    .collect(Collectors.toSet());

            if (IndexingServiceImpl.stop) {
                System.out.println("cancelled");
                linksSet.clear();
            }

            linksSet.removeAll(IndexingServiceImpl.globalLinksSet);
            IndexingServiceImpl.globalLinksSet.addAll(linksSet);

            //System.out.println("linksSet size: " + linksSet.size());
            for (String subLink : linksSet) {
                Indexing parse = new Indexing(subLink, site);
                taskList.add(parse);
            }
            ForkJoinTask.invokeAll(taskList);

            //System.out.println(Thread.currentThread().getName() + " isCancelled: " + isCancelled());
            //System.out.println(Thread.currentThread().getName() + " isCompletedAbnormally: " + isCompletedAbnormally());
            //System.out.println("Set size: " + IndexingServiceImpl.globalLinksSet.size());
            taskList.forEach(Indexing::join);
        }
    }

    public Document connectToPageAndSaveIt(String link, Site site) throws InterruptedException, MalformedURLException, SQLException {
        Thread.sleep(timeout);
        Connection connection = Jsoup.connect(link).ignoreHttpErrors(true);
        Document document;
        String content;
        int statusCode;
        try {
            document = connection.userAgent(userAgent).referrer(referrer).get();
            content = document.toString();
            statusCode = connection.response().statusCode();
        } catch (IOException e) {
            document = null;
            content = "";
            statusCode = 404; //if server can't answer
        }

        Page page = new Page();
        page.setSite(site);
        page.setPath(new URL(link).getPath());
        page.setCode(statusCode);
        page.setContent(content);
        int pageId = pageRepository.save(page).getId();
        lemmaFinder.collectLemmas(pageId);//why does the location where this method is called have no effect?
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        return document;
    }

    public void saveOnePage(String link, Site site) throws InterruptedException, MalformedURLException, SQLException {
        Thread.sleep(timeout);
        Connection connection = Jsoup.connect(link).ignoreHttpErrors(true);
        Document document;
        String content;
        Page page = new Page();
        int statusCode;
        try {
            document = connection.userAgent(userAgent).referrer(referrer).get();
            content = document.toString();
            statusCode = connection.response().statusCode();
        } catch (IOException e) {
            document = null;
            content = "";
            statusCode = 404; //if server can't answer
        }
        List<Page> pageList = pageRepository.findByPath(String.valueOf(new URL(link).getPath()));
        if (!pageList.isEmpty()) {
            page = pageList.get(0);
        }
        //page = pageList.get(0);
        page.setSite(site);
        page.setPath(new URL(link).getPath());
        page.setCode(statusCode);
        page.setContent(content);
        int pageId = pageRepository.save(page).getId();
        lemmaFinder.collectLemmas(pageId);//why does the location where this method is called have no effect?
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
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
