package searchengine.services;

import searchengine.dto.search.SearchResponse;

public interface SearchService {
    SearchResponse getSearchResult(String query, int offset, int limit, String site);
}
