package searchengine.services;

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
import searchengine.dto.statistics.ResponseMessage;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {

    private final LuceneMorphology luceneMorph = new RussianLuceneMorphology();

    private final LemmaFinder lemmaFinder;
    private final LemmaRepository lemmaRepository;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    public SearchServiceImpl(LemmaFinder lemmaFinder,
                             LemmaRepository lemmaRepository, SiteRepository siteRepository,
                             PageRepository pageRepository) throws IOException {
        this.lemmaFinder = lemmaFinder;
        this.lemmaRepository = lemmaRepository;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        List<SearchData> data = new ArrayList<>();
        SearchResponse searchResponse = new SearchResponse();
        Set<String> queryLemmasSet = new HashSet<>();
        String[] words = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+");
        if (words[0].isEmpty()) {
            //System.out.println(("Некорректный запрос"));
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

        //Map<String, Integer> dbLemmasMap = new TreeMap<>();
        //Lemma lemma;
        //Page page = pageRepository.findById(15013).orElseThrow();
        //Lemma lemmaDb = lemmaRepository.findById(40914).orElseThrow();
        //System.out.println();
        //----------------------------------------------------------
        Lemma lemmaDb;
        List<Lemma> lemmaDbList;
        List<Lemma> lemmaDbListExisted = new ArrayList<>();
        if (site == null) { //search over all sites of index
            for (String lemmaWord : queryLemmasSet) {
                lemmaDbList = lemmaRepository.findByLemma(lemmaWord);
                lemmaDbListExisted = lemmaDbList.stream().filter(Objects::nonNull).toList();
                /*if (lemmaDb != null) {
                    lemmaDbList.add(lemmaDb);
                }*/
            }
        } else { //search over selected site of index
            for (String lemmaWord : queryLemmasSet) {
                //if (siteRepository.findByName(site).getId())
                lemmaDb = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(site).getId());
                if (lemmaDb != null) {
                    lemmaDbListExisted.add(lemmaDb);
                    //List<Page> pages = lemmaDb.getPages();
                }
            }
        }
        if (lemmaDbListExisted.isEmpty()) {
            //System.out.println(("Nothing found"));
            searchResponse.setResult(true);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Nothing found");
            return searchResponse;
        }

        Comparator<Lemma> compareByFreq = Comparator.comparing(Lemma::getFrequency);
        List<Lemma> sortedLemmaDbList = lemmaDbListExisted.stream().sorted(compareByFreq).toList();
        Document doc;
        for (Lemma lemma : sortedLemmaDbList) {
            List<Page> pages = lemma.getPages();
            for (Page page : pages) {
                doc = Jsoup.parse(page.getContent());
                SearchData searchData = new SearchData();
                searchData.setSiteName(lemma.getSite().getName());
                searchData.setUri(page.getPath());
                searchData.setSite(lemma.getSite().getUrl());//repository.findByName(site).getUrl());
                searchData.setSnippet("what <b>is</b> snippet?");
                searchData.setTitle(doc.title());
                //System.out.println(doc.title());
                searchData.setRelevance(0.989F + offset);
                data.add(searchData);
            }

            /*System.out.println("Text: " + doc.body().text());
            System.out.println("Title: " + doc.title());
            System.out.println("Base uri: " + doc.baseUri());
            System.out.println("Abs uri: " + doc.absUrl("https://nopaper.ru/manpower-nopaper"));*/

        }

        //System.out.println(pages);
        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
        searchResponse.setData(data);
        searchResponse.setError("");
        //System.out.println(sortedLemmaDbList);

//        Page page = pageRepository.findById(15013).orElseThrow();
//        List<Lemma> lemmas = page.getLemmas();
//        lemmas.forEach(l -> System.out.println(l.getLemma()));
        //---------------------------------------------------------------------------------

//        searchData.setSiteName(query);
//        searchData.setUri(String.valueOf(limit));
//        searchData.setSite(site);
//        searchData.setSnippet("what <b>is</b> snippet?");
//        searchData.setTitle("This is Skillbox");
//        searchData.setRelevance(0.989F + offset);
//        data.add(searchData);
//        searchResponse.setResult(true);
//        searchResponse.setCount(150);
//        searchResponse.setData(data);
//        searchResponse.setError("");
        return searchResponse;
    }
}
