package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.Repositories.SiteRepository;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private LuceneMorphology luceneMorph;

    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final LemmaFinder lemmaFinder;
    private final LemmaRepository lemmaRepository;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        List<SearchData> data = new ArrayList<>();
        SearchResponse searchResponse = new SearchResponse();
        Set<String> queryLemmasSet = new HashSet<>();
        String[] words = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+");
        if (words[0].isEmpty()) {
            searchResponse.setResult(false);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Некорректный запрос");
            return searchResponse;
        }
        for (String word : words) {
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
            if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || w.length() < 3)) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
            } else {
                queryLemmasSet.add(luceneMorph.getNormalForms(word).get(0));
            }
        }

        /*Map<String, Integer> sorted = queryLemmasMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new)); //TODO: find out what does it mean*/

        /*for (Map.Entry<String, Integer> map : sorted.entrySet()) {
            System.out.println(map.getKey() + " " + map.getValue());
        }*/

        Lemma lemmaDb;
        List<Lemma> lemmaDbList = new ArrayList<>();
        List<Lemma> lemmaDbListExisted = new ArrayList<>();
        if (site == null) { //search over all sites of index
            for (String lemmaWord : queryLemmasSet) {
                lemmaDbList.addAll(lemmaRepository.findByLemma(lemmaWord));
                lemmaDbListExisted = lemmaDbList.stream().filter(Objects::nonNull).toList();
            }
        } else { //search over selected site of index
            for (String lemmaWord : queryLemmasSet) {
                lemmaDbList = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(site).getId());
                lemmaDb = lemmaDbList.get(0);
                if (lemmaDb != null) {
                    lemmaDbListExisted.add(lemmaDb);
                }
            }
        }
        if (lemmaDbListExisted.isEmpty()) {
            searchResponse.setResult(true);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Nothing found");
            return searchResponse;
        }

        Comparator<Lemma> compareByFreq = Comparator.comparing(Lemma::getFrequency);
        List<Lemma> sortedLemmaDbList = lemmaDbListExisted.stream().sorted(compareByFreq).toList();
        Document doc;
        List<Page> pageByLemmaTotal = new ArrayList<>(sortedLemmaDbList.get(0).getPages());
        for (int i = 0; i < sortedLemmaDbList.size(); i++) {//check logic
            //List<Page> pages = lemma.getPages();
            List<Page> pageByLemma = new ArrayList<>();
            for (Page page : pageByLemmaTotal) {

                if (page.getLemmas().contains(sortedLemmaDbList.get(i))) {
                    pageByLemma.add(page);
                }

            }
            pageByLemmaTotal = pageByLemma;
        }
        for (Page page : pageByLemmaTotal) {
            doc = Jsoup.parse(page.getContent());
            SearchData searchData = new SearchData();
            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            searchData.setSite(page.getSite().getUrl());//repository.findByName(site).getUrl());
            searchData.setSnippet("what <b>is</b> snippet?");
            searchData.setTitle(doc.title());
            searchData.setRelevance(0.989F + offset);
            data.add(searchData);
        }

        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
        searchResponse.setData(data);
        searchResponse.setError("");
        return searchResponse;
    }
}
