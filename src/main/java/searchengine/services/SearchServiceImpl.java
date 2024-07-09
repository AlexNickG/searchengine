package searchengine.services;

import lombok.RequiredArgsConstructor;
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
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

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
    private final IndexRepository indexRepository;

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        List<SearchData> data = new ArrayList<>();
        SearchResponse searchResponse = new SearchResponse();
        Set<String> queryLemmasSet = new HashSet<>();
        String[] words = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+");
        if (words.length == 0) {
            searchResponse.setResult(false);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Некорректный запрос");
            return searchResponse;
        }
        for (String word : words) {
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
            if (wordBaseForms.stream().noneMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || w.length() < 3)) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
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

        List<Page> pageByLemmaTotal = new ArrayList<>();
        HashMap<Page, Float> pageMapRel = new HashMap<>();
        Map<Page, Float> sortedMapRelRank = new LinkedHashMap<>();
        if (site == null) { //search over all sites of index
            for (Site dbSite : siteRepository.findAll()) { //перебираем все сайты
                List<Lemma> lemmaDbListExisted = new ArrayList<>();
                for (String lemmaWord : queryLemmasSet) { // для каждого слова из запроса получаем леммы из БД, страницы им соответствующие и считаем их суммарный rank
                    lemmaDb = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(dbSite.getUrl()).getId()); //получаем лемму из БД
                    if (lemmaDb != null) {
                        lemmaDbListExisted.add(lemmaDb);// если лемма есть в БД, добавляем ее в список
                    }
                }
                if (lemmaDbListExisted.isEmpty())
                    continue; //если для данного сайта лемм в базе не нашлось, переходим к другому сайту
                if (lemmaDbListExisted.size() == queryLemmasSet.size()) {//если количество лемм в БД равно количеству слов в запросе (а если не равно, меньше?)
                    List<Lemma> sortedLemmaDbList = sortLemmasByFreq(lemmaDbListExisted); //сортируем леммы в порядке частоты встречаемости
                    for (Lemma lemma : sortedLemmaDbList) {
                        pageByLemmaTotal.addAll(lemma.getPages());//добавляем все страницы в список
                    }
                    for (Page page : pageByLemmaTotal) { //для каждой страницы считаем суммарный rank лемм, найденных на этой странице
                        pageMapRel.put(page, calcPageRelevance(page, lemmaDbListExisted));
                    }
                }
            }
            Map<Page, Float> sortedMap = pageMapRel.entrySet()//сортируем страницы в списке по rank'у от большего к меньшему
                    .stream()
                    .sorted(Map.Entry.<Page, Float>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
            float maxRank = sortedMap.values().stream().max(Float::compare).get();// находим максимальный rank

            for (Map.Entry<Page, Float> map : sortedMap.entrySet()) {//считаем и сохраняем относительный rank
                Page page = map.getKey();
                float value = map.getValue() / maxRank;
                sortedMapRelRank.put(page, value);
            }
        } else { //search over selected site of index
            List<Lemma> lemmaDbListExisted = new ArrayList<>();
            for (String lemmaWord : queryLemmasSet) {
                lemmaDb = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(site).getId());
                if (lemmaDb != null) {
                    lemmaDbListExisted.add(lemmaDb);
                }
            }
            if (lemmaDbListExisted.size() == queryLemmasSet.size()) {
                List<Lemma> sortedLemmaDbList = sortLemmasByFreq(lemmaDbListExisted); //сортируем леммы в порядке частоты встречаемости
                pageByLemmaTotal.addAll(sortedLemmaDbList.get(0).getPages());
                if (sortedLemmaDbList.size() > 1) {
                    for (int i = 1; i < sortedLemmaDbList.size(); i++) {
                        List<Page> pageByLemmaLocal = new ArrayList<>(sortedLemmaDbList.get(i).getPages());
                        pageByLemmaTotal = pageByLemmaTotal.stream().filter(pageByLemmaLocal::contains).collect(Collectors.toList());
                    }
                }
            }
        }
        if (pageByLemmaTotal.isEmpty()) {
            searchResponse.setResult(true);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Nothing found");
            return searchResponse;
        }

        for (Map.Entry<Page, Float> map : sortedMapRelRank.entrySet()) {//для каждой страницы, отсортированной по rank'у готовим ответ в соответствующем формате
            Page page = map.getKey();
            StringBuilder snippet = new StringBuilder();
            Document doc = Jsoup.parse(page.getContent());
            List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+")).toList();
            SearchData searchData = new SearchData();
            searchData.setSiteName(map.getKey().getSite().getName());
            searchData.setUri(page.getPath());
            searchData.setSite(page.getSite().getUrl());
            for (String word : text) {
                if (queryLemmasSet.contains(getWordNormalForm(word))) {
                    int index = text.indexOf(word);
                    if (index != -1) {
                        snippet.append(text.get(index - 2)).append(" ").append(text.get(index - 1)).append(" <b>").append(word).append("</b> ").append(text.get(index + 1)).append(" ").append(text.get(index + 2)).append("<br>");
                    }
                }
            }
            searchData.setSnippet(String.valueOf(snippet));
            searchData.setTitle(doc.title());
            searchData.setRelevance(map.getValue());
            data.add(searchData);
        }

        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
        searchResponse.setData(data);
        searchResponse.setError("");
        return searchResponse;
    }

    List<Lemma> sortLemmasByFreq(List<Lemma> lemmaDbListExisted) {
        Comparator<Lemma> compareByFreq = Comparator.comparing(Lemma::getFrequency);
        return lemmaDbListExisted.stream().sorted(compareByFreq).toList();
    }

    float calcPageRelevance(Page page, List<Lemma> lemmaList) {
        List<Lemma> lemmaListByPage = page.getLemmas();
        float relevance = 0;
        for (Lemma lemma : lemmaListByPage) {
            if (lemmaList.contains(lemma)) {
                relevance += indexRepository.findByPageIdAndLemmaId(page.getId(), lemma.getId()).getRank();
            }
        }
        return relevance;
    }

    String getWordNormalForm(String word) {
        String wordNormalForm = null;
        List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
        if (wordBaseForms.stream().noneMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || w.length() < 3)) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
            wordNormalForm = luceneMorph.getNormalForms(word).get(0);
        }
        return wordNormalForm;
    }
}
