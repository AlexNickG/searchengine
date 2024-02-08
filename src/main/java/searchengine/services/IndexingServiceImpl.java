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

    private final SitesList sites;

    private Site site = new Site();

    private List<Site> sitesList = new ArrayList<>();

    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    public static ConcurrentSkipListSet<String> globalLinksSet = new ConcurrentSkipListSet<>();

    public static volatile boolean stopIndexing = false;

    private ForkJoinPool forkJoinPool = new ForkJoinPool();

    public List<Runnable> threadList = new ArrayList<>();

    public List<ForkJoinTask<Void>> fjpList = new ArrayList<>();

    @Override
    public ResponseMessage startIndexing() {
//        stopIndexing = false;
//        threadList.clear();
        List<Site> indexingSites = siteRepository.findAll();

        if (indexingSites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            return sendResponse(false, "Индексация уже запущена");
        } else {
            pageRepository.deleteAll();
            siteRepository.deleteAll();
            for (int i = 0; i < sites.getSites().size(); i++) {
                threadList.add(new StartIndexing(i));
            }
            ExecutorService executor = Executors.newFixedThreadPool(5);
            threadList.forEach(executor::execute);
            //executor.shutdown();
            return sendResponse(true, "");
        }
    }

    @Override
    public ResponseMessage stopIndexing() {

        Interrupter interrupter = new Interrupter(fjpList);
        interrupter.interrupt();
        //forkJoinPool.shutdownNow();
        //stopIndexing = true;
        //forkJoinPool.shutdownNow();
        /*for (Iterator<ForkJoinTask<Void>> futureIterator = fjpList.iterator(); futureIterator.hasNext(); ) {

            ForkJoinTask<Void> future = futureIterator.next();

            if (future.isDone()) {
                try {
                    System.out.println("Выполнена " + future.get());
                    futureIterator.remove();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            } else {
                future.cancel(true);
                System.out.println("Задача остановлена, результат её работы не требуется");
            }
        }*/

        //threadList.forEach(Thread::interrupt);
        //forkJoinPool.shutdownNow();
        List<Site> sites = siteRepository.findAll();
        if (sites.stream().anyMatch(site -> site.getStatus() == Status.INDEXING)) {
            //TODO: update statuses and errors for Site
            for (Site site : sites) {
                if (site.getStatus() == Status.INDEXING) {
                    site = siteRepository.findById(site.getId()).get(); //TODO: add check ifPresent
                    site.setStatus(Status.FAILED);
                    site.setLastError("Индексация остановлена пользователем");
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.saveAndFlush(site); //save vs saveAndFlush
                }
            }
            return sendResponse(true, "");
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
            Indexing indexing = new Indexing(link, site, siteRepository, pageRepository);
            fjpList.add(indexing);
            ForkJoinTask<Void> task = forkJoinPool.submit(indexing);
            /*if (task.isCancelled() || task.isCompletedAbnormally()) {
                site.setUrl(link);
                site.setStatus(Status.FAILED);
                site.setName(sites.getSites().get(siteNumber).getName());
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            }
            if (task.isDone()) {
                site.setUrl(link);
                site.setStatus(Status.INDEXED);
                site.setName(sites.getSites().get(siteNumber).getName());
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            }*/
        }
    }
    @RequiredArgsConstructor
    public class Interrupter {
        final List<ForkJoinTask<Void>> fjpList;

        public void interrupt() {
            fjpList.forEach(t -> t.cancel(true));
        }
    }
}
