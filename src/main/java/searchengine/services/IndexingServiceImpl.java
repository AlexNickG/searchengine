package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Site;
import searchengine.model.Status;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();

    public static volatile boolean stopIndexing = false;
    public static volatile boolean stop;

    //private ForkJoinPool forkJoinPool = new ForkJoinPool();

    public List<Runnable> threadList = new ArrayList<>();

    public List<ForkJoinTask<Void>> fjpList = new ArrayList<>();

    @Override
    public ResponseMessage startIndexing() {


        List<Site> indexingSites = siteRepository.findAll();

        if (indexingSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            return sendResponse(false, "Индексация уже запущена");
        } else {
            threadList.clear();
            if (executor != null) {
                executor.shutdown();
            }
            globalLinksSet.clear();
            stop = false;
            stopIndexing = false;
            pageRepository.deleteAll();
            siteRepository.deleteAll();
            executor = Executors.newFixedThreadPool(sites.getSites().size());
            for (int i = 0; i < sites.getSites().size(); i++) {
                threadList.add(new StartIndexing(i)); //How to start threads in ExecutorService?
            }

            threadList.forEach(executor::execute);
            System.out.println("Started threads: " + threadList.size());
            //
            return sendResponse(true, "");
        }
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
                if (stopIndexing) {
                    executor.shutdown(); //Executor shutdown completed, but one thread is working
                    threadList.clear();
                    return sendResponse(true, "");
                }
            }
        } else {
            return sendResponse(false, "Индексация не запущена");
        }
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
            siteRepository.save(site);
            ForkJoinPool forkJoinPool = new ForkJoinPool();
            Indexing indexing = new Indexing(link, site, siteRepository, pageRepository);
            fjpList.add(indexing);
            ForkJoinTask<Void> task = forkJoinPool.submit(indexing);
            while (true) {
                if (task.isCompletedAbnormally()) {
                    site.setUrl(link);
                    site.setStatus(Status.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site); //save vs saveAndFlush
                    forkJoinPool.shutdown();
                    System.out.println("the task was cancelled");
                    stopIndexing = true;
                    break;
                }
                if (task.isCompletedNormally()) {
                    site.setUrl(link);
                    site.setStatus(Status.INDEXED);
                    site.setName(sites.getSites().get(siteNumber).getName());
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site);
                    forkJoinPool.shutdown();
                    System.out.println("The task was done");
                    stopIndexing = true;
                    break;
                }
            }
        }
    }
}
