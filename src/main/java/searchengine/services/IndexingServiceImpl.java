package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.config.SitesList;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Site;
import searchengine.model.Status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    int cores =Runtime.getRuntime().availableProcessors();
    private final SitesList sites;
    @Autowired
    SiteRepository siteRepository;
    @Autowired
    PageRepository pageRepository;


    @Override
    public ResponseMessage startIndexing() {

        List<Site> sites = siteRepository.findAll();
        if (sites.isEmpty()) {
            indexing("https://skillbox.ru");
            return sendResponse(false, "Индексация запущена");
        } else {
            boolean status = true;
            for (Site site : sites) {
                if (site.getStatus() == Status.INDEXING) {
                    status = false;
                }
            }
            if (!status) {
                return sendResponse(false, "Индексация уже запущена");
            } else {
                return sendResponse(true, "");
            }
        }
    }
    public ResponseMessage sendResponse (boolean result, String message) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(result);
        responseMessage.setError(message);
        return responseMessage;
    }

    public void indexing(String url) {
        Site site = new Site();
        site.setUrl("https://skillbox.ru");
        site.setStatus(Status.INDEXING);
        site.setName("Skillbox");
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        new ForkJoinPool(cores).invoke(new Indexing(site, url, siteRepository, pageRepository));

//        String url = "http://skillbox.ru";
//        Site site = new Site();
//        site.setUrl(url);
//        site.setName("Skillbox");
//        site.setStatusTime(LocalDateTime.now());
//        site.setStatus(Status.INDEXING);
//        site.setLastError("Ok");
//        siteRepository.save(site);
    }
}
