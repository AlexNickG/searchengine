package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.WrongCharaterException;
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
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
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

    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<SearchData> data;
    private SearchResponse searchResponse;
//    private List<Page> pageByLemmaAndSite;
    private Map<Page, Float> rankedPagesMap = new LinkedHashMap<>();
    private List<Lemma> sortedLemmaDbList = new ArrayList<>();

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        if (offset == 0) {
            searchResponse = new SearchResponse();
            data = new ArrayList<>();
            //pageByLemmaAndSite = new ArrayList<>();
            initializeSearch(query, site);
        }
        return paginateResults(offset, limit);
    }

    private void initializeSearch(String query, String site) {
        //searchResponse = new SearchResponse();
        Set<String> queryLemmasSet = extractQueryLemmas(query);
        List<Site> siteList = (site == null) ? siteRepository.findAll() : Collections.singletonList(siteRepository.findByUrl(site));
        for (Site dbSite : siteList) {//для каждого сайта
            sortedLemmaDbList = filterAndSortLemmas(queryLemmasSet, dbSite);
            if (!sortedLemmaDbList.isEmpty()) {
                getPagesByLemmas(sortedLemmaDbList);
            }
        }
        if (rankedPagesMap.isEmpty()) {
            searchResponse.setResult(false);
            searchResponse.setError("Nothing found");
        }else {
            calculatePageRelevanceAndSort();
            prepareSearchResponse();
        }
    }

    private Set<String> extractQueryLemmas(String query) {
        Set<String> queryLemmasSet = new HashSet<>();
        String[] words = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+");

        for (String word : words) {//TODO: посмотреть документацию метода getMorphInfo() библиотеки luceneMorph
            String wordBaseForms = getWordMorphInfo(word); //Падает при поиске на латинице. Почему бы не брать первую форму слова и не проверять ее на отношение к частям речи?
            if (!wordBaseForms.contains("СОЮЗ") && !wordBaseForms.contains("МЕЖД") && !wordBaseForms.contains("ПРЕДЛ") && !wordBaseForms.contains(" ЧАСТ")) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
                queryLemmasSet.add(luceneMorph.getNormalForms(word).get(0));
            }
        }
        return queryLemmasSet;
        /*Arrays.stream(query.toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+"))
                .map(this::getWordMorphInfo)
                .filter(word -> !luceneMorph.getMorphInfo(word).get(0).contains("СОЮЗ") && !luceneMorph.getMorphInfo(word).get(0).contains("МЕЖД") && !luceneMorph.getMorphInfo(word).get(0).contains("ПРЕДЛ") && !luceneMorph.getMorphInfo(word).get(0).contains(" ЧАСТ"))
                .map(word -> luceneMorph.getNormalForms(word).get(0))
                .collect(Collectors.toSet());*/
    }

    private List<Lemma> filterAndSortLemmas(Set<String> queryLemmasSet, Site dbSite) {
        int quantityPagesBySite = pageRepository.findBySite_id(dbSite.getId()).size();
        return queryLemmasSet.stream()
                .map(lemma -> lemmaRepository.findByLemmaAndSite_Id(lemma, dbSite.getId()))
                .filter(Objects::nonNull)
                .filter(lemma -> 100 * lemma.getFrequency() / quantityPagesBySite < 90)
                .sorted(Comparator.comparing(Lemma::getFrequency))
                .collect(Collectors.toList());
    }

    private void getPagesByLemmas(List<Lemma> sortedLemmaDbList) {
        List<Page> pageByLemmaAndSite = new ArrayList<>();
        for (Lemma lemma : sortedLemmaDbList) {
            if (pageByLemmaAndSite.isEmpty()) {
                pageByLemmaAndSite.addAll(lemma.getPages());
            } else {
                pageByLemmaAndSite.retainAll(lemma.getPages());//TODO: проверять: если множества не пересекаются, суммировать их
                if (pageByLemmaAndSite.isEmpty()) {
                    break;
                }
            }
        }
        rankedPagesMap = pageByLemmaAndSite.stream()
                .collect(Collectors.toMap(page -> page, page -> calcPageRelevance(page, sortedLemmaDbList)));
        }

    private void calculatePageRelevanceAndSort() {
        //rankedPagesMap = pageByLemmaAndSite.stream()
        //        .collect(Collectors.toMap(page -> page, page -> calcPageRelevance(page, sortedLemmaDbList)));
        float maxRank = rankedPagesMap.values().stream().max(Float::compare).orElse(0.1f);
        rankedPagesMap = rankedPagesMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() / maxRank));
        rankedPagesMap = rankedPagesMap.entrySet().stream().sorted(Map.Entry.<Page, Float>comparingByValue().reversed()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
    }

    private float calcPageRelevance(Page page, List<Lemma> lemmaList) {
        return page.getLemmas().stream()
                .filter(lemmaList::contains)
                .map(lemma -> indexRepository.findByPageIdAndLemmaId(page.getId(), lemma.getId()).getRank())
                .reduce(0f, Float::sum);
    }

    private void prepareSearchResponse() {
        rankedPagesMap.forEach((page, relevance) -> {
            Document doc = Jsoup.parse(page.getContent());
            List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+")).toList();
            SearchData searchData = new SearchData();
            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            searchData.setSite(page.getSite().getUrl());
            searchData.setSnippet(getSnippet(sortedLemmaDbList, text) + " - " + relevance);
            searchData.setTitle(doc.title());
            searchData.setRelevance(relevance);
            data.add(searchData);
        });

        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
    }

    private SearchResponse paginateResults (int offset, int limit) {
        if (offset + limit > data.size()) {
            limit = data.size() - offset;
        }
        searchResponse.setData(data.subList(offset, offset + limit));
        return searchResponse;
    }

    private String getSnippet(List<Lemma> queryLemmasList, List<String> text) {
        Map<List<String>, Integer> snippetList = new HashMap<>();
        for (String word : text) {
            for (Lemma lemmaWord : queryLemmasList) {
                if (lemmaWord.getLemma().equals(getWordNormalForm(word))) {
                    int index = text.indexOf(word);
                    snippetList.put(text.subList(Math.max(0, index - 5), Math.min(index + 5, text.size())), 0);
                }
            }
        }

        int limitInt = queryLemmasList.size() == snippetList.values().stream().max(Integer::compare).orElse(1) ? 1 : 3;
        List<String> topSnippetList = snippetList.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Integer>comparingByValue().reversed())
                .limit(limitInt)
                .map(entry -> String.join(" ", entry.getKey()))
                .collect(Collectors.toList());

        String wholeSnippetText = String.join(" ... ", topSnippetList);
        List<String> words = Arrays.stream(wholeSnippetText.trim().split("\\s+")).toList();
        List<String> queryWordsList = queryLemmasList.stream().map(Lemma::getLemma).toList();

        return "..." + words.stream()
                .map(word -> queryWordsList.contains(getWordNormalForm(word.toLowerCase(Locale.ROOT))) ? "<b>" + word + "</b>" : word)
                .collect(Collectors.joining(" ")) + "...";
    }

    private String getWordMorphInfo(String word) {
        try {
            return luceneMorph.getMorphInfo(word).get(0);
        } catch (WrongCharaterException wce) {
            return word;
        }
    }

    private String getWordNormalForm(String word) {
        try {
            return luceneMorph.getNormalForms(word).get(0);
        } catch (Exception e) {
            return word;
        }
    }

//    private SearchResponse returnNothingFound() {
//        searchResponse.setResult(false);
//        searchResponse.setError("Nothing found");
//        return searchResponse;
//    }
}
