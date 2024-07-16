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
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word); //падает при поиске на латинице
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


        List<Site> siteList = new ArrayList<>();
        HashMap<Page, Float> pageMapRel = new HashMap<>();
        Map<Page, Float> sortedMapRelRank = new LinkedHashMap<>();
        if (site == null) { //search over all sites of index
            siteList = siteRepository.findAll();
        } else {
            siteList.add(siteRepository.findByUrl(site));
        }
        for (Site dbSite : siteList) { //перебираем все сайты
            List<Lemma> lemmaDbListExisted = new ArrayList<>();
            List<Page> pageByLemmaTotal = new ArrayList<>();
            for (String lemmaWord : queryLemmasSet) { // для каждого слова из запроса получаем леммы из БД, страницы им соответствующие и считаем их суммарный rank
                lemmaDb = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(dbSite.getUrl()).getId()); //получаем лемму из БД
                if (lemmaDb != null) {
                    lemmaDbListExisted.add(lemmaDb);// если лемма есть в БД, добавляем ее в список
                }
            }
            if (lemmaDbListExisted.size() == queryLemmasSet.size()) {//если количество лемм в БД равно количеству слов в запросе (а если хотя бы одного слова из запроса нет на сайте, не выдавать ничего
                List<Lemma> finishLemmaList = new ArrayList<>();
                int quantityPages = lemmaDbListExisted.stream().mapToInt(l -> l.getPages().size()).sum();
                for (Lemma lemma : lemmaDbListExisted) {
                    if (100 * lemma.getPages().size() / quantityPages <= 100) { //если число страниц для данной леммы слишком большое TODO: продумать алгоритм снижения выдачи результатов
                        finishLemmaList.add(lemma);
                    }
                }
                List<Lemma> sortedLemmaDbList = sortLemmasByFreq(finishLemmaList); //сортируем леммы в порядке частоты встречаемости
                /*List<Lemma> lemmasToRemove = new ArrayList<>();
                for (Lemma lemma : sortedLemmaDbList) {
                 if (lemma.getPages().size() <= 10000) { //если число страниц для данной леммы слишком большое TODO: продумать алгоритм снижения выдачи результатов
                        pageByLemmaTotal.addAll(lemma.getPages());//добавляем все страницы в список
                    } else {
                        lemmasToRemove.add(lemma);// то добавляем такую лемму в список на удаление
                    }

                }
                sortedLemmaDbList.removeAll(lemmasToRemove);
                lemmaDbListExisted = sortedLemmaDbList.stream().filter(lemmasToRemove::contains).toList();*/
                //List<Page> pagesList = new ArrayList<>();
                for (Lemma lemma : sortedLemmaDbList) { //По первой, самой редкой лемме из списка, находим все страницы, на которых она встречается. Далее ищем соответствия следующей леммы из этого списка страниц
                    if (pageByLemmaTotal.isEmpty()) {
                        pageByLemmaTotal.addAll(lemma.getPages());
                    } else {
                        pageByLemmaTotal.retainAll(lemma.getPages());
                    }
                }

                for (Page page : pageByLemmaTotal) { //для каждой страницы считаем суммарный rank лемм, найденных на этой странице
                    pageMapRel.put(page, calcPageRelevance(page, lemmaDbListExisted));
                }
                for (Map.Entry<Page, Float> entry : pageMapRel.entrySet()) {// test method
                    System.out.println(entry.getKey().getId() + " - " + entry.getValue());
                }
            }
        }

        if (pageMapRel.isEmpty()) {
            searchResponse.setResult(true);
            searchResponse.setCount(0);
            searchResponse.setData(null);
            searchResponse.setError("Nothing found");
            return searchResponse;
        }
        Map<Page, Float> sortedMap = pageMapRel.entrySet()//сортируем страницы в списке по rank'у от большего к меньшему
                .stream()
                .sorted(Map.Entry.<Page, Float>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
        float maxRank;
        Optional<Float> maxRankOptional = sortedMap.values().stream().max(Float::compare);// находим максимальный rank
        maxRank = maxRankOptional.isPresent() ? maxRankOptional.get() : 1;  //если удается найти maxRank
        System.out.println("MaxRank: " + maxRank);
        for (Map.Entry<Page, Float> entry : sortedMap.entrySet()) {//считаем и сохраняем относительный rank
            Page page = entry.getKey();
            float value = entry.getValue() / maxRank;
            System.out.println("Relative rank: " + value);
            sortedMapRelRank.put(page, value);
        }

        for (Map.Entry<Page, Float> entry : sortedMapRelRank.entrySet()) {//для каждой страницы, отсортированной по rank'у готовим ответ в соответствующем формате
            Page page = entry.getKey();
            Document doc = Jsoup.parse(page.getContent());
            List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+")).toList();
            SearchData searchData = new SearchData();
            searchData.setSiteName(entry.getKey().getSite().getName());
            searchData.setUri(page.getPath());
            searchData.setSite(page.getSite().getUrl());
            searchData.setSnippet(getSnippet(queryLemmasSet, text) + " - " + entry.getValue());
            searchData.setTitle(doc.title());
            searchData.setRelevance(entry.getValue());
            data.add(searchData);
        }

        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
        if (offset + limit > data.size()) {
            limit = data.size() - offset;
        }
        searchResponse.setData(data.subList(offset, offset + limit));
        //searchResponse.setError("");
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
                //System.out.println(lemma + " - " + indexRepository.findByPageIdAndLemmaId(page.getId(), lemma.getId()).getRank());
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

    /*String getSnippet(Set<String> queryLemmasSet, List<String> text) {
        StringBuilder snippet = new StringBuilder();
        for (String word : text) {
            if (queryLemmasSet.contains(getWordNormalForm(word))) {
                int index = text.indexOf(word);
                if (index != -1) {
                    //snippet.append(text.get(index - 2)).append(" ").append(text.get(index - 1)).append(" <b>").append(word).append("</b> ").append(text.get(index + 1)).append(" ").append(text.get(index + 2)).append("<br>");
                    snippet.append("...").append(" <b>").append(word).append("</b> ").append("...").append("<br>");
                }
            }
        }
        return String.valueOf(snippet);
    }*/

    String getSnippet(Set<String> queryLemmasSet, List<String> text) {

        List<StringBuilder> snippetList = new ArrayList<>();
        for (String lemmaWord : queryLemmasSet) {
            StringBuilder snippet = new StringBuilder();
            StringBuilder snippet1 = new StringBuilder();
            StringBuilder snippet2 = new StringBuilder();
            for (String word : text) {
                String wordNormalForm = getWordNormalForm(word);
                if (lemmaWord.equals(wordNormalForm)) {
                    int index = text.indexOf(word);
                    if (index != -1) {
                        if ((index - 5) >= 0 && (index + 5) <= text.size()) {
                            List<String> text1 = new ArrayList<>(text.subList(index - 5, index - 1));
                            List<String> text2 = new ArrayList<>(text.subList(index + 1, index + 5));
                            text1.forEach(e -> snippet1.append(e).append(" "));
                            text2.forEach(e -> snippet2.append(e).append(" "));
                        }
                        //snippet.append(text.get(index - 2)).append(" ").append(text.get(index - 1)).append(" <b>").append(word).append("</b> ").append(text.get(index + 1)).append(" ").append(text.get(index + 2)).append("<br>");
                        snippetList.add(snippet.append(snippet1).append(" <b>").append(word).append("</b> ").append(snippet2));
                        break;
                    }

                }

            }

        }
        return String.join("...", snippetList);
    }
}
