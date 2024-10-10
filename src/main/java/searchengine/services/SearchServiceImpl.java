package searchengine.services;

import lombok.RequiredArgsConstructor;
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
public class SearchServiceImpl implements SearchService {

    private LuceneMorphology luceneMorph;

    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final LemmaRepository lemmaRepository;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private List<SearchData> data = new ArrayList<>();
    private SearchResponse searchResponse = new SearchResponse();

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        if (offset == 0) {
            data = new ArrayList<>();
            searchResponse = new SearchResponse();
            Set<String> queryLemmasSet = new HashSet<>();
            String[] words = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+");

            for (String word : words) {//TODO: посмотреть документацию метода getMorphInfo() библиотеки luceneMorph
                String wordBaseForms = getWordMorphInfo(word); //Падает при поиске на латинице. Почему бы не брать первую форму слова и не проверять ее на отношение к частям речи?
                if (!wordBaseForms.contains("СОЮЗ") && !wordBaseForms.contains("МЕЖД") && !wordBaseForms.contains("ПРЕДЛ") && !wordBaseForms.contains(" ЧАСТ")) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
                    queryLemmasSet.add(luceneMorph.getNormalForms(word).get(0));
                }
            }

        /*Map<String, Integer> sorted = queryLemmasMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new)); //TODO: find out what does it mean*/

            Lemma lemmaDb;
            List<Site> siteList = new ArrayList<>();
            List<Lemma> sortedLemmaDbList = new ArrayList<>();
            HashMap<Page, Float> pageMapRel = new HashMap<>();
            Map<Page, Float> sortedMapRelRank = new LinkedHashMap<>();

            if (site == null) { //search over all sites of index
                siteList = siteRepository.findAll();
            } else {
                siteList.add(siteRepository.findByUrl(site));
            }

            for (Site dbSite : siteList) { //перебираем все сайты
                int quantityPagesBySite = pageRepository.findBySite_id(dbSite.getId()).size();//суммарное количество страниц на сайте
                List<Lemma> lemmaDbListExisted = new ArrayList<>();
                List<Page> pageByLemmaTotal = new ArrayList<>();
                for (String lemmaWord : queryLemmasSet) { // для каждого слова из запроса получаем леммы из БД, страницы им соответствующие и считаем их суммарный rank
                    lemmaDb = lemmaRepository.findByLemmaAndSite_Id(lemmaWord, siteRepository.findByUrl(dbSite.getUrl()).getId()); //получаем лемму из БД
                    if (lemmaDb != null) {
                        lemmaDbListExisted.add(lemmaDb);// если лемма есть в БД, добавляем ее в список
                    }
                }
                if (lemmaDbListExisted.size() == queryLemmasSet.size()) {//Если все леммы из запроса для этого сайта есть в БД (а если хотя бы одного слова из запроса нет на сайте, не выдавать ничего). Уточнить логику
                    List<Lemma> finishLemmaList = new ArrayList<>();//TODO: проверить правильную работу для запроса "купить по безналичному расчету" DONE!

                    for (Lemma lemma : lemmaDbListExisted) {
                        if (100 * lemma.getFrequency() / quantityPagesBySite < 90) //отношение количества страниц для каждой леммы к общему количеству страниц сайта
                            finishLemmaList.add(lemma);
                    }
                    sortedLemmaDbList = sortLemmasByFreq(finishLemmaList); //сортируем леммы в порядке увеличения частоты встречаемости
                    for (Lemma lemma : sortedLemmaDbList) { //По первой, самой редкой лемме из списка, находим все страницы, на которых она встречается. Далее ищем соответствия следующей леммы из этого списка страниц
                        if (pageByLemmaTotal.isEmpty()) {//TODO: уточнить логику: после того, как список обнуляется леммой, которая в нем отсутствует, следующая лемма добавляет свои страницы в список, который выдается во фронт
                            pageByLemmaTotal.addAll(lemma.getPages());
                        } else {
                            pageByLemmaTotal.retainAll(lemma.getPages());//неправильно?
                            if (pageByLemmaTotal.isEmpty()) {
                                break;//Если в итоге не осталось ни одной страницы, то прервать поиск по этому сайту
                            }
                        }
                    }

                    for (Page page : pageByLemmaTotal) { //для каждой страницы считаем суммарный rank лемм, найденных на этой странице
                        pageMapRel.put(page, calcPageRelevance(page, lemmaDbListExisted));
                    }
                }
            }

            if (pageMapRel.isEmpty()) {
                return returnNothingFound();
            }
            Map<Page, Float> sortedMap = pageMapRel.entrySet()//сортируем страницы в списке по rank'у от большего к меньшему
                    .stream()
                    .sorted(Map.Entry.<Page, Float>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
            float maxRank;
            Optional<Float> maxRankOptional = sortedMap.values().stream().max(Float::compare);// находим максимальный rank
            maxRank = maxRankOptional.isPresent() ? maxRankOptional.get() : 1;  //если удается найти maxRank
            for (Map.Entry<Page, Float> entry : sortedMap.entrySet()) {//считаем и сохраняем относительный rank
                Page page = entry.getKey();
                float value = entry.getValue() / maxRank;
                sortedMapRelRank.put(page, value);
            }

            for (Map.Entry<Page, Float> entry : sortedMapRelRank.entrySet()) {//для каждой страницы, отсортированной по rank'у готовим ответ в соответствующем формате
                Page page = entry.getKey();
                Document doc = Jsoup.parse(page.getContent());
                List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+")).toList();//TODO: перенести внутрь метода getSnippet
                SearchData searchData = new SearchData();
                searchData.setSiteName(entry.getKey().getSite().getName());
                searchData.setUri(page.getPath());
                searchData.setSite(page.getSite().getUrl());
                searchData.setSnippet(getSnippet(sortedLemmaDbList, text) + " - " + entry.getValue());
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
            return searchResponse;
        } else {
            if (offset + limit > data.size()) {
                limit = data.size() - offset;
            }
            searchResponse.setData(data.subList(offset, offset + limit));
            return searchResponse;
        }
    }

    private SearchResponse returnNothingFound() {
        searchResponse.setResult(false);
        searchResponse.setError("Nothing found");
        return searchResponse;
    }

    private List<Lemma> sortLemmasByFreq(List<Lemma> lemmaDbListExisted) {
        Comparator<Lemma> compareByFreq = Comparator.comparing(Lemma::getFrequency);
        return lemmaDbListExisted.stream().sorted(compareByFreq).toList();
    }

    private float calcPageRelevance(Page page, List<Lemma> lemmaList) {
        List<Lemma> lemmaListByPage = page.getLemmas();
        float relevance = 0;
        for (Lemma lemma : lemmaListByPage) {
            if (lemmaList.contains(lemma)) {
                relevance += indexRepository.findByPageIdAndLemmaId(page.getId(), lemma.getId()).getRank();
            }
        }
        return relevance;
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
        } catch (Exception e) {//TODO:добавить обработку других прерываний
            return word;
        }
    }

    private String getSnippet(List<Lemma> queryLemmasList, List<String> text) {
        StringBuilder finalSnippet = new StringBuilder();
        Map<List<String>, Integer> snippetList = new HashMap<>();
        for (Lemma lemmaWord : queryLemmasList) {
            for (String word : text) {
                String wordNormalForm = getWordNormalForm(word);
                if (lemmaWord.getLemma().equals(wordNormalForm)) {
                    int index = text.indexOf(word);
                    if (index != -1) {
                        if ((index - 5) >= 0 && (index + 5) <= text.size()) {
                            snippetList.put(text.subList(index - 5, index + 5), 0);
                        } else if ((index + 5) <= text.size()) {
                            snippetList.put(text.subList(0, index + 5), 0);
                        } else if ((index - 5) >= 0) {
                            snippetList.put(text.subList(index - 5, text.size()), 0);
                        }
                    }
                }
            }

            snippetList = snippetList.entrySet().stream()//формируем map сниппет - количество совпадений слов из поискового запроса
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getKey().stream()
                                    .filter(s -> getWordNormalForm(s) != null)
                                    .anyMatch(s -> getWordNormalForm(s)
                                            .equals(lemmaWord.getLemma())) ? entry.getValue() + 1 : entry.getValue()
                    ));
        }

        Optional<Integer> limitOptional = snippetList.values().stream().max(Integer::compare);
        int limitInt = queryLemmasList.size() == limitOptional.orElse(1) ? 1 : 3; //изменяем число строк сниппета в зависимости от количества слов запроса, попавших в одну строку
        // Сортируем карту по значениям в обратном порядке и берем первые самые релевантные сниппеты. Их количество зависит от разброса слов из поискового запроса по тексту
        List<String> topSnippetList = snippetList.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Integer>comparingByValue().reversed())
                .limit(limitInt)
                .map(entry -> String.join(" ", entry.getKey()))
                .collect(Collectors.toList());

        String wholeSnippetText = String.join(" ... ", topSnippetList);//соединяем сниппеты в строку
        List<String> words = Arrays.stream(wholeSnippetText//преобразуем строку сниппетов в List
                        .trim()
                        .split("\\s+"))
                .toList();

        List<String> queryWordsList = queryLemmasList.stream().map(Lemma::getLemma).toList();
        List<String> t = words.stream()//выделяем в тексте страницы жирным шрифтом все слова из поискового запроса
                .map(word -> queryWordsList.contains(getWordNormalForm(word.toLowerCase(Locale.ROOT))) ? "<b>" + word + "</b>" : word)
                .toList();
        finalSnippet.append("...").append(t.stream().map(String::valueOf).collect(Collectors.joining(" "))).append("...");

        return finalSnippet.toString();
    }
}
