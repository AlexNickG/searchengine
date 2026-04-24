package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.model.Index;
import searchengine.model.Page;

import java.util.List;

/**
 * Transactional operations on Page/Lemma/Index that need a Spring proxy.
 * Extracted from IndexingServiceImpl to eliminate the @Lazy self-injection pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageProcessorService {

    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final SiteRepository siteRepository;

    /**
     * Returns an existing Page for the given path+site (clearing its old index entries),
     * or a new empty Page if not found.
     */
    @Transactional
    public Page updatePage(String path, int siteId) {
        Page page = pageRepository.findByPathAndSiteId(path, siteId);
        if (page == null) {
            return new Page();
        }
        List<Index> indexList = indexRepository.findByPageId(page.getId());
        if (indexList != null && !indexList.isEmpty()) {
            indexList.forEach(i -> lemmaRepository.decreaseLemmaFreqById(i.getLemmaId()));
            indexRepository.deleteAllInBatch(indexList);
        }
        return page;
    }

    @Transactional
    public void clearDb() {
        indexRepository.deleteIndex();
        lemmaRepository.deleteLemmas();
        pageRepository.deletePages();
        siteRepository.deleteAllSites();
        log.info("DB cleared");
    }
}