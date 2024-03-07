package searchengine.services;

import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchServiceImpl implements SearchService {
    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        SearchResponse searchResponse = new SearchResponse();

        if (query.isEmpty()) {
            searchResponse.setResult(false);
            searchResponse.setError("Задан пустой поисковый запрос");
            return searchResponse;
        }


        SearchData searchData = new SearchData();
        List<SearchData> data = new ArrayList<>();
        searchData.setSiteName(query);
        searchData.setUri(String.valueOf(limit));
        searchData.setSite(site);
        searchData.setSnippet("what <b>is</b> snippet?");
        searchData.setTitle("This is Skillbox");
        searchData.setRelevance(0.989F + offset);
        data.add(searchData);
        searchResponse.setResult(true);
        searchResponse.setCount(150);
        searchResponse.setData(data);
        searchResponse.setError("");
        return searchResponse;
    }
}
