package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.exceptions.BadRequestException;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaFinder lemmaFinder;

    @Value("${search-settings.searchFilter}")
    private int searchFilter;

    /**
     * All search state is kept in this object — one instance per request.
     * Eliminates the previous race condition where shared fields were overwritten
     * by concurrent searches.
     */
    private static class SearchContext {
        final List<SearchData> data = new ArrayList<>();
        final SearchResponse searchResponse = new SearchResponse();
        final Map<Integer, Float> rankedPagesIdMap = new LinkedHashMap<>();
        final Set<Index> localIndexList = new HashSet<>();
        final Set<String> querySetNormal = new HashSet<>();
    }

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {
        if (query.isEmpty()) {
            throw new BadRequestException("Задан пустой поисковый запрос");
        }
        SearchContext ctx = new SearchContext();
        initializeSearch(ctx, query, site);
        return paginateResults(ctx, offset, limit);
    }

    // -------------------------------------------------------------------------
    // Search pipeline
    // -------------------------------------------------------------------------

    private void initializeSearch(SearchContext ctx, String query, String site) {
        extractQueryLemmas(ctx, query);
        if (ctx.querySetNormal.isEmpty()) {
            ctx.searchResponse.setResult(false);
            ctx.searchResponse.setError("Запрос не содержит значимых слов");
            return;
        }
        List<Site> siteList = (site == null || site.isEmpty())
                ? siteRepository.findAll()
                : Collections.singletonList(siteRepository.findByUrl(site));
        for (Site dbSite : siteList) {
            List<Lemma> sortedLemmaDbList = filterAndSortLemmas(ctx.querySetNormal, dbSite);
            if (!sortedLemmaDbList.isEmpty()) {
                getPagesByLemmas(ctx, sortedLemmaDbList);
            }
        }
        if (ctx.rankedPagesIdMap.isEmpty()) {
            ctx.searchResponse.setResult(false);
            ctx.searchResponse.setError("Nothing found");
        } else {
            calculatePageRelevanceAndSort(ctx);
        }
    }

    private void extractQueryLemmas(SearchContext ctx, String query) {
        for (String word : lemmaFinder.prepareStringArray(query)) {
            if (!word.isEmpty() && lemmaFinder.isWordSignificant(word)) {
                ctx.querySetNormal.add(lemmaFinder.getLemma(word));
            }
        }
    }

    private List<Lemma> filterAndSortLemmas(Set<String> querySetNormal, Site dbSite) {
        List<Lemma> lemmaList = new ArrayList<>();
        int totalPages = pageRepository.getSizeBySiteId(dbSite.getId());
        if (totalPages == 0) return lemmaList;
        for (String queryWord : querySetNormal) {
            Lemma lemma = lemmaRepository.findByLemmaAndSiteId(queryWord, dbSite.getId());
            if (lemma == null) {
                // All query lemmas must be present — bail out early
                return new ArrayList<>();
            } else if (isIdentifier(queryWord) || 100 * lemma.getFrequency() / totalPages <= searchFilter) {
                lemmaList.add(lemma);
            }
        }
        lemmaList.sort(Comparator.comparing(Lemma::getFrequency));
        return lemmaList;
    }

    private void getPagesByLemmas(SearchContext ctx, List<Lemma> sortedLemmaDbList) {
        Set<Integer> setOfLemmaIds = sortedLemmaDbList.stream()
                .map(Lemma::getId)
                .collect(Collectors.toSet());

        for (int lemmaId : setOfLemmaIds) {
            ctx.localIndexList.addAll(indexRepository.findByLemmaId(lemmaId));
        }

        Set<Integer> setPagesId = new HashSet<>();
        for (Lemma lemma : sortedLemmaDbList) {
            Set<Integer> pagesForLemma = ctx.localIndexList.stream()
                    .filter(index -> index.getLemmaId().equals(lemma.getId()))
                    .map(Index::getPageId)
                    .collect(Collectors.toSet());
            if (setPagesId.isEmpty()) {
                setPagesId.addAll(pagesForLemma);
            } else {
                setPagesId.retainAll(pagesForLemma);
                if (setPagesId.isEmpty()) break;
            }
        }
        setPagesId.forEach(id -> ctx.rankedPagesIdMap.put(id, calcPageRelevance(ctx, id, setOfLemmaIds)));
    }

    private float calcPageRelevance(SearchContext ctx, int pageId, Set<Integer> setOfLemmaIds) {
        return ctx.localIndexList.stream()
                .filter(index -> index.getPageId().equals(pageId))
                .filter(index -> setOfLemmaIds.contains(index.getLemmaId()))
                .map(Index::getRank)
                .reduce(0f, Float::sum);
    }

    private void calculatePageRelevanceAndSort(SearchContext ctx) {
        float maxRank = ctx.rankedPagesIdMap.values().stream().max(Float::compare).orElse(0.1f);
        ctx.rankedPagesIdMap.replaceAll((id, rank) -> rank / maxRank);
        Map<Integer, Float> sorted = ctx.rankedPagesIdMap.entrySet().stream()
                .sorted(Map.Entry.<Integer, Float>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
        ctx.rankedPagesIdMap.clear();
        ctx.rankedPagesIdMap.putAll(sorted);
    }

    // -------------------------------------------------------------------------
    // Result assembly
    // -------------------------------------------------------------------------

    private SearchResponse paginateResults(SearchContext ctx, int offset, int limit) {
        if (ctx.rankedPagesIdMap.isEmpty()) {
            return ctx.searchResponse;
        }
        int total = ctx.rankedPagesIdMap.size();
        int end = Math.min(offset + limit, total);
        List<Map.Entry<Integer, Float>> entries = new ArrayList<>(ctx.rankedPagesIdMap.entrySet());

        for (int i = offset; i < end; i++) {
            Page page = pageRepository.findById(entries.get(i).getKey()).orElseThrow();
            float relevance = entries.get(i).getValue();
            Document doc = Jsoup.parse(page.getContent());
            String[] words = lemmaFinder.prepareStringArray(doc.body().text());

            SearchData searchData = new SearchData();
            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            try {
                URL siteUrl = new URL(page.getSite().getUrl());
                String host = siteUrl.getHost();
                int port = siteUrl.getPort();
                String authority = (port == -1) ? host : host + ":" + port;
                searchData.setSite(siteUrl.getProtocol() + "://" + authority);
            } catch (MalformedURLException e) {
                searchData.setSite(page.getSite().getUrl());
            }
            searchData.setTitle(doc.title());
            searchData.setSnippet(buildSnippet(words, ctx.querySetNormal));
            searchData.setRelevance(relevance);
            ctx.data.add(searchData);
        }

        ctx.searchResponse.setResult(true);
        ctx.searchResponse.setCount(total);
        ctx.searchResponse.setData(ctx.data);
        return ctx.searchResponse;
    }

    // -------------------------------------------------------------------------
    // Snippet generation — O(n) single-pass lemmatisation
    // -------------------------------------------------------------------------

    /**
     * Builds a highlighted text snippet for the given page word array.
     *
     * Algorithm:
     *  1. Lemmatise every word in one pass (was O(n*q) per word before).
     *  2. Collect positions of query-lemma hits.
     *  3. Build up to 3 non-overlapping ±5-word windows around the densest hits.
     *  4. Wrap hit words in {@code <b>}.
     */
    private String buildSnippet(String[] words, Set<String> queryLemmas) {
        if (words.length == 0) return "";

        // Pass 1: lemmatise every word (reuse LemmaFinder to handle court case numbers too)
        String[] lemmatized = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            lemmatized[i] = words[i].isEmpty() ? "" : safeGetLemma(words[i]);
        }

        // Pass 2: collect hit positions
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lemmatized.length; i++) {
            if (!lemmatized[i].isEmpty() && queryLemmas.contains(lemmatized[i])) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) return "";

        // Pass 3: build up to 3 non-overlapping windows
        final int WINDOW = 5;
        List<int[]> windows = new ArrayList<>();
        for (int hit : hits) {
            int from = Math.max(0, hit - WINDOW);
            int to = Math.min(words.length, hit + WINDOW + 1);
            if (!windows.isEmpty() && from <= windows.get(windows.size() - 1)[1]) {
                // Extend previous window instead of creating a new one
                windows.get(windows.size() - 1)[1] = to;
            } else {
                windows.add(new int[]{from, to});
            }
            if (windows.size() == 3) break;
        }

        // Pass 4: assemble fragments with <b> highlighting
        List<String> fragments = new ArrayList<>();
        for (int[] w : windows) {
            StringBuilder sb = new StringBuilder();
            for (int i = w[0]; i < w[1]; i++) {
                if (i > w[0]) sb.append(' ');
                if (!lemmatized[i].isEmpty() && queryLemmas.contains(lemmatized[i])) {
                    sb.append("<b>").append(words[i]).append("</b>");
                } else {
                    sb.append(words[i]);
                }
            }
            fragments.add(sb.toString());
        }

        return "..." + String.join(" ... ", fragments) + "...";
    }

    /** Digits, case IDs and court case numbers bypass the frequency filter. */
    private boolean isIdentifier(String word) {
        return word.chars().allMatch(Character::isDigit)
                || word.matches("[a-z0-9]+(?:-[a-z0-9]+){2,}")
                || word.matches("[а-яa-z0-9]+-[а-яa-z0-9]+/\\d{4}");
    }

    private String safeGetLemma(String word) {
        try {
            if (lemmaFinder.isWordSignificant(word)) {
                return lemmaFinder.getLemma(word);
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
