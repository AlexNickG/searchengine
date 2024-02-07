package searchengine.services;

import searchengine.dto.statistics.ResponseMessage;

public interface IndexingService {
    ResponseMessage startIndexing();
    ResponseMessage stopIndexing();

//    ResponseMessage addPageForIndexing();
}
