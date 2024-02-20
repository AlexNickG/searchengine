package searchengine.services;

import searchengine.dto.search.SearchResponse;
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Site;

public interface SearchService {
    SearchResponse getSearchResult(String query, int offset, int limit, String site);
}
