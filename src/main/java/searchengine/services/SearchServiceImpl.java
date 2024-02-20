package searchengine.services;

import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Site;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchServiceImpl implements SearchService {
    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {
        SearchData searchData = new SearchData();
        List<SearchData> data = new ArrayList<>();

        searchData.setSiteName(query);
        searchData.setUri(String.valueOf(limit));
        searchData.setSite(site);
        searchData.setSnippet("what <b>is</b> snippet?");
        searchData.setTitle("This is Skillbox");
        searchData.setRelevance(0.989F + offset);
        data.add(searchData);
        SearchResponse searchResponse = new SearchResponse();
        searchResponse.setResult(true);
        searchResponse.setCount(150);
        searchResponse.setData(data);
        return searchResponse;
    }
}
