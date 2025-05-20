package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.model.Site;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    @Autowired
    private final SiteRepository siteRepository;

    private final PageRepository pageRepository;

    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<Site> sitesList = siteRepository.findAll();
        TotalStatistics total = new TotalStatistics();
        total.setSites(sitesList.size());
        total.setIndexing(true);


        for (Site site : sitesList) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            int pagesCount = pageRepository.findBySite_id(site.getId()).size();
            int lemmasCount = lemmaRepository.findBySite_id(site.getId()).size();
            item.setPages(pagesCount);
            item.setLemmas(lemmasCount);
            item.setStatus(String.valueOf(site.getStatus()));
            item.setError(site.getLastError());
            item.setStatusTime(ZonedDateTime.of(site.getStatusTime(), ZoneId.systemDefault()).toInstant().toEpochMilli());
            total.setPages(total.getPages() + pagesCount);
            total.setLemmas(total.getLemmas() + lemmasCount);
            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }
}
