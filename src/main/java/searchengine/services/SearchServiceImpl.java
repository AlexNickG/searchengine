package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
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
    private LuceneMorphology luceneMorph;
    private final LemmaFinder lemmaFinder;

    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<SearchData> data;
    private SearchResponse searchResponse;
    private Map<Integer, Float> rankedPagesIdMap;
    private List<Lemma> sortedLemmaDbList;
    private List<Lemma> globalLemmaList;
    private Set<Index> localIndexList;
    private Set<String> queryLemmasSet;

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        if (offset == 0) {
            searchResponse = new SearchResponse();
            data = new ArrayList<>();
            rankedPagesIdMap = new LinkedHashMap<>();
            sortedLemmaDbList = new ArrayList<>();
            globalLemmaList = new ArrayList<>();
            localIndexList = new HashSet<>();
            initializeSearch(query, site);
        }
        return paginateResults(offset, limit);
    }

    private void initializeSearch(String query, String site) {
        extractQueryLemmas(query);
        List<Site> siteList = (site == null || site.isEmpty()) ? siteRepository.findAll() : Collections.singletonList(siteRepository.findByUrl(site));
        for (Site dbSite : siteList) {
            sortedLemmaDbList = filterAndSortLemmas(queryLemmasSet, dbSite);
            globalLemmaList.addAll(sortedLemmaDbList);
            if (!sortedLemmaDbList.isEmpty()) {
                getPagesByLemmas(sortedLemmaDbList);
            }
        }
        if (rankedPagesIdMap.isEmpty()) {
            searchResponse.setResult(false);
            searchResponse.setError("Nothing found");
        } else {
            calculatePageRelevanceAndSort();
        }
    }

    private void extractQueryLemmas(String query) {
        queryLemmasSet = new HashSet<>();
        String[] queryWordsArray = lemmaFinder.prepareStringArray(query);

        for (String word : queryWordsArray) {
            if (!word.isEmpty()) {
                if (lemmaFinder.isWordSignificant(word)) {
                    queryLemmasSet.add(luceneMorph.getNormalForms(word).get(0));
                }
            }
        }
    }

    private List<Lemma> filterAndSortLemmas(Set<String> queryLemmasSet, Site dbSite) {
        long start = System.currentTimeMillis();
        int quantityPagesBySite = pageRepository.getSizeBySiteId(dbSite.getId());
        List<Lemma> lemmaList = queryLemmasSet.stream()
                .map(lemma -> lemmaRepository.findByLemmaAndSiteId(lemma, dbSite.getId()))
                .filter(Objects::nonNull)
                .filter(lemma -> 100 * lemma.getFrequency() / quantityPagesBySite < 90)
                .sorted(Comparator.comparing(Lemma::getFrequency))
                .collect(Collectors.toList());
        log.info("Фильтрация и сортировка лемм заняла: {}", System.currentTimeMillis() - start);
        return lemmaList;
    }

    /**
     * По первой, самой редкой лемме из списка, находить все страницы, на которых она встречается.
     * Далее искать соответствия следующей леммы из этого списка страниц,
     * а затем повторять операцию по каждой следующей лемме.
     * Список страниц при этом на каждой итерации должен уменьшаться.
     * <p>
     * Если в итоге не осталось ни одной страницы, то выводить пустой список.
     */
    private void getPagesByLemmas(List<Lemma> sortedLemmaDbList) {
        long start = System.currentTimeMillis();
        Set<Integer> setOfLemmaIds = sortedLemmaDbList.stream().map(Lemma::getId).collect(Collectors.toSet());

        for (int lemmaId : setOfLemmaIds) {
            localIndexList.addAll(indexRepository.findByLemmaId(lemmaId));
        }

        Set<Integer> setPagesId = new HashSet<>();
        for (Lemma lemma : sortedLemmaDbList) {
            Set<Integer> localPagesId = localIndexList.stream()
                    .filter(index -> index.getLemmaId().equals(lemma.getId()))
                    .map(Index::getPageId)
                    .collect(Collectors.toSet());
            if (setPagesId.isEmpty()) {
                setPagesId.addAll(localPagesId);
            } else {
                setPagesId.retainAll(localPagesId);
                if (setPagesId.isEmpty()) {
                    break;
                }
            }
        }
        setPagesId.forEach(id -> rankedPagesIdMap.put(id,  calcPageRelevance(id, setOfLemmaIds)));
        log.info("Получение страниц по леммам заняло: {}", System.currentTimeMillis() - start);
    }

    private float calcPageRelevance(int pageId, Set<Integer> setOfLemmaIds) {

        return localIndexList.stream()
                .filter(index -> index.getPageId().equals(pageId))
                .filter(index -> setOfLemmaIds.contains(index.getLemmaId()))
                        .map(Index::getRank)
                .reduce(0f, Float::sum);
    }

    private void calculatePageRelevanceAndSort() {
        long start = System.currentTimeMillis();
        float maxRank = rankedPagesIdMap.values().stream().max(Float::compare).orElse(0.1f);
        rankedPagesIdMap = rankedPagesIdMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() / maxRank));
        rankedPagesIdMap = rankedPagesIdMap.entrySet().stream().sorted(Map.Entry.<Integer, Float>comparingByValue().reversed()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
        log.info("Расчет абсолютной релевантности страниц и их сортировка заняла: {}", System.currentTimeMillis() - start);
    }

    private void prepareSearchResponse(int offset, int limit) {
        long start = System.currentTimeMillis();
        int end = Math.min(offset + limit, rankedPagesIdMap.size());
        List<Map.Entry<Integer, Float>> entries = new ArrayList<>(rankedPagesIdMap.entrySet());

        for (int i = offset; i < end; i++) {
            Page page = pageRepository.findById(entries.get(i).getKey()).orElseThrow();
            Float relevance = entries.get(i).getValue();
            long start1 = System.currentTimeMillis();
            Document doc = Jsoup.parse(page.getContent());
            log.info("Парсинг jsoup занял: {}", System.currentTimeMillis() - start1);
            SearchData searchData = new SearchData();
            List<String> text = Arrays.asList(lemmaFinder.prepareStringArray(doc.body().text()));

            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            try {
                searchData.setSite(new URL(page.getSite().getUrl()).getProtocol() + "://" +
                        new URL(page.getSite().getUrl()).getHost());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            searchData.setSnippet(getSnippet(text) + " - " + relevance);
            searchData.setTitle(doc.title());
            searchData.setRelevance(relevance);
            data.add(searchData);
        }

        searchResponse.setResult(true);
        searchResponse.setCount(rankedPagesIdMap.size());
        log.info("Подготовка ответа заняла: {}", System.currentTimeMillis() - start);
    }

    private SearchResponse paginateResults(int offset, int limit) {
        limit = offset + limit > rankedPagesIdMap.size() ? rankedPagesIdMap.size() - offset : limit;
        prepareSearchResponse(offset, limit);
        searchResponse.setData(data.subList(offset, offset + limit));
        return searchResponse;
    }

    private String getSnippet(List<String> text) {
        Map<List<String>, Integer> snippetMap = new HashMap<>();
        for (String word : text) {
            for (Lemma lemma : globalLemmaList) {
                if (lemma.getLemma().equals(getWordNormalForm(word))) {
                    int index = text.indexOf(word);
                    snippetMap.put(text.subList(Math.max(0, index - 5), Math.min(index + 5, text.size())), 0);
                }
            }
        }

        snippetMap.entrySet().forEach(entry -> {
            List<String> snippetText = entry.getKey();
            int count = 0;
            for (String word : snippetText) {
                for (Lemma lemma : sortedLemmaDbList) {
                    if (lemma.getLemma().equals(getWordNormalForm(word))) {
                        count++;
                    }
                }
            }
            entry.setValue(count);
        });

        List<String> topSnippetList = snippetMap.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> String.join(" ", entry.getKey()))
                .collect(Collectors.toList());

        String wholeSnippetText = String.join(" ... ", topSnippetList);
        List<String> words = Arrays.stream(wholeSnippetText.trim().split("\\s+")).toList();
        List<String> queryWordsList = sortedLemmaDbList.stream().map(Lemma::getLemma).toList();

        return "..." + words.stream()
                .map(word -> queryWordsList.contains(getWordNormalForm(word.toLowerCase(Locale.ROOT))) ? "<b>" + word + "</b>" : word)
                .collect(Collectors.joining(" ")) + "...";
    }

    private String getWordNormalForm(String word) {
        try {
            return luceneMorph.getNormalForms(word).get(0);
        } catch (Exception e) {
            return word;
        }
    }
}
