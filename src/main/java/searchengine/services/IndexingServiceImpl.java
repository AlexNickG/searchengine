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

import java.util.List;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private final SitesList sites;
    SiteRepository siteRepository;
    PageRepository pageRepository;


    @Override
    public ResponseMessage getResponse() {
        ResponseMessage responseMessage = new ResponseMessage();
        List<Site> sites = siteRepository.findAll();
        if (sites.isEmpty()) {
            responseMessage.setResult(false);
            responseMessage.setError("Индексация уже запущена");
            return responseMessage;
        } else {
            boolean status = true;
            for (Site site : sites) {
                if (site.getStatus() == Status.INDEXING) {
                    status = false;
                }
            }
            if (!status) {
                responseMessage.setResult(false);
                responseMessage.setError("Индексация уже запущена");
                return responseMessage;
            } else {
                responseMessage.setResult(true);
                return responseMessage;
            }
        }
    }
}
