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
//@RequiredArgsConstructor
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
    private Map<Page, Float> rankedPagesMap;
    private List<Lemma> sortedLemmaDbList = new ArrayList<>();
    private final List<Index> indexList;
    private List<Index> localIndexList;

    public SearchServiceImpl(SiteRepository siteRepository, PageRepository pageRepository, LemmaRepository lemmaRepository, IndexRepository indexRepository, LemmaFinder lemmaFinder) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.lemmaFinder = lemmaFinder;
        this.indexList = indexRepository.findAll();
    }

    @Override
    public SearchResponse getSearchResult(String query, int offset, int limit, String site) {

        if (offset == 0) {
            searchResponse = new SearchResponse();
            data = new ArrayList<>();
            rankedPagesMap = new LinkedHashMap<>();
            localIndexList = new ArrayList<>();
            initializeSearch(query, site);
        }
        return paginateResults(offset, limit);
    }

//    private void initializeSearch(String query, String site) {
//        Set<String> queryLemmasSet = extractQueryLemmas(query);
//        List<Site> siteList = (site == null || site.isEmpty()) ? siteRepository.findAll() : Collections.singletonList(siteRepository.findByUrl(site));
//        //System.out.println();
//
//        siteList.parallelStream().forEach(dbSite -> {
//            List<Lemma> lemmas = filterAndSortLemmas(queryLemmasSet, dbSite);
//            if (!lemmas.isEmpty()) {
//                getPagesByLemmas(lemmas);
//            }
//        });
//
//        if (rankedPagesMap.isEmpty()) {
//            searchResponse.setResult(false);
//            searchResponse.setError("Nothing found");
//        } else {
//            calculatePageRelevanceAndSort();
//            //prepareSearchResponse(0, 10);
//        }
//    }

    private void initializeSearch(String query, String site) {
        Set<String> queryLemmasSet = extractQueryLemmas(query);
        List<Site> siteList = (site == null) ? siteRepository.findAll() : Collections.singletonList(siteRepository.findByUrl(site));
        for (Site dbSite : siteList) {
            sortedLemmaDbList = filterAndSortLemmas(queryLemmasSet, dbSite);
            if (!sortedLemmaDbList.isEmpty()) {
                getPagesByLemmas(sortedLemmaDbList);
            }
        }
        if (rankedPagesMap.isEmpty()) {
            searchResponse.setResult(false);
            searchResponse.setError("Nothing found");
        } else {
            calculatePageRelevanceAndSort();
            //prepareSearchResponse();
        }
    }

    private Set<String> extractQueryLemmas(String query) {
        Set<String> queryLemmasSet = new HashSet<>();
        String[] QueryWordsArray = query.toLowerCase(Locale.ROOT).replaceAll("[^а-я0-9\\s]", " ").trim().split("\\s+");

        for (String word : QueryWordsArray) {
            if (!word.isEmpty()) {
                if (lemmaFinder.isWordSignificant(word)) {
                    queryLemmasSet.add(luceneMorph.getNormalForms(word).get(0));
                }
            }
        }
        return queryLemmasSet;
    }

    private List<Lemma> filterAndSortLemmas(Set<String> queryLemmasSet, Site dbSite) {//TODO: ускорить работу этого метода
        long start = System.currentTimeMillis();
        //String word = queryLemmasSet.stream().findFirst().get();
        //Lemma lemmaList1 = lemmaRepository.findByLemmaAndSite_Id(word, dbSite.getId());
        //log.info("Извлечение одной леммы из БД заняло: {}", System.currentTimeMillis() - start);
        int quantityPagesBySite = pageRepository.findBySite_id(dbSite.getId()).size();//TODO: вынести выше уровнем в List количества страниц всех сайтов
        List<Lemma> lemmaList = queryLemmasSet.stream()
                .map(lemma -> lemmaRepository.findByLemmaAndSite_Id(lemma, dbSite.getId()))
                .filter(Objects::nonNull)
                //.filter(lemma -> 100 * lemma.getFrequency() / quantityPagesBySite < 90)
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
        localIndexList.addAll(indexList.stream()
                .filter(index -> sortedLemmaDbList.stream().anyMatch(lemma -> lemma.getId().equals(index.getLemmaId())))
                .toList());
//        Set<Page> listPagesByLemma = null;
//        for (Lemma lemma : sortedLemmaDbList) {
//            Set<Page> lemmaPages = new HashSet<>(lemma.getPages());
//            if (listPagesByLemma == null) {
//                listPagesByLemma = lemmaPages;
//            } else {
//                listPagesByLemma.retainAll(lemmaPages);
//                if (listPagesByLemma.isEmpty()) break;
//            }
//        }

        Set<Page> setPagesByLemma = new HashSet<>();
        Set<Integer> pagesIdForSite = new HashSet<>();
        for (Lemma lemma : sortedLemmaDbList) {
            Set<Integer> localPagesId = localIndexList.stream()
                    .filter(index -> index.getLemmaId().equals(lemma.getId()))
                    .map(Index::getPageId)
                    .collect(Collectors.toSet());
            //List<Integer> pagesIdList = indexRepository.
            //List<Integer> pagesIdList = localIndexList.stream().filter(index -> index.getPageId(lemma.getId()))
            if (pagesIdForSite.isEmpty()) {
                pagesIdForSite.addAll(localPagesId);//падает при поиске слова 'театр'; может падать при отсутствии леммы в базе (например слишком короткое слово, для которого нет леммы)
            } else {
                pagesIdForSite.retainAll(localPagesId);
                if (pagesIdForSite.isEmpty()) {
                    break;
                }
            }
        }
        long start2 = System.currentTimeMillis();
//        Page page;
//        for (int pageId : pagesIdForSite) {
               pageRepository.findAllById(pagesIdForSite).forEach(page -> {
                float totalRank = localIndexList.stream()
                            .filter(index -> index.getPageId().equals(page.getId()))
                            .map(Index::getRank)
                            .reduce(0f, Float::sum);
                    rankedPagesMap.put(page, totalRank);
                });
        /*Map<Integer, List<Index>> indicesByPageId = localIndexList.stream()
                .collect(Collectors.groupingBy(Index::getPageId));

        pageRepository.findAllById(pagesIdForSite).forEach(page -> {
            float totalRank = indicesByPageId.getOrDefault(page.getId(), Collections.emptyList()).stream()
                    .map(Index::getRank)
                    .reduce(0f, Float::sum);
            rankedPagesMap.put(page, totalRank);
        });*/
        log.info("Расчет релевантности всех страниц суммарно занял: {}", System.currentTimeMillis() - start2);
        log.info("Получение страниц по леммам заняло: {}", System.currentTimeMillis() - start);
    }

    private void calculatePageRelevanceAndSort() {
        long start = System.currentTimeMillis();
        float maxRank = rankedPagesMap.values().stream().max(Float::compare).orElse(0.1f);
        rankedPagesMap = rankedPagesMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() / maxRank));
        rankedPagesMap = rankedPagesMap.entrySet().stream().sorted(Map.Entry.<Page, Float>comparingByValue().reversed()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> oldValue, LinkedHashMap::new));
        log.info("Расчет абсолютной релевантности страниц и их сортировка заняла: {}", System.currentTimeMillis() - start);
    }

//    private float calcPageRelevance(Page page, List<Lemma> lemmaList) {
//        //long start = System.currentTimeMillis();
//        float rel = page.getLemmas().stream()
//                .filter(lemmaList::contains)
//                .map(lemma -> indexRepository.findByPageIdAndLemmaId(page.getId(), lemma.getId()).getRank())
//                .reduce(0f, Float::sum);
//        //log.info("Расчет релевантности страницы занял: {}", System.currentTimeMillis() - start);
//        return rel;
//    }

    private float calcPageRelevance(Page page, List<Lemma> lemmaList) {
        long start = System.currentTimeMillis();
        Set<Integer> lemmaIds = page.getLemmas().stream()
                .filter(lemmaList::contains)
                .map(Lemma::getId).collect(Collectors.toSet());
        Float rel = localIndexList.stream()
                .filter(index -> index.getPageId().equals(page.getId()) && lemmaIds.contains(index.getLemmaId()))
                .map(Index::getRank)
                .reduce(0f, Float::sum);
        //log.info("Расчет релевантности страницы занял: {}", System.currentTimeMillis() - start);
        return rel;

        //List<Index> shortIndexList = indexList.stream().filter(i -> Objects.equals(i.getPageId(), page.getId())).toList();
        //Float rel = shortIndexList.stream().filter(index -> actualLemmas.stream().filter(lemma -> lemma.getId().equals(index.getLemmaId())));

//        page.getLemmas().stream()
//                .filter(lemmaList::contains)
//                .filter(lemmaList.getLemma().getId() -> indexList.forEach(lemma.getLemma()));
    }

    private void prepareSearchResponse(int offset, int limit) {//TODO: готовить ответ в соответствии с offset и limit
        long start = System.currentTimeMillis();
        int end = Math.min(offset + limit, rankedPagesMap.size());
        List<Map.Entry<Page, Float>> entries = new ArrayList<>(rankedPagesMap.entrySet());

        for (int i = offset; i < end; i++) {
            Page page = entries.get(i).getKey();
            Float relevance = entries.get(i).getValue();

            Document doc = Jsoup.parse(page.getContent());
            SearchData searchData = new SearchData();
            List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT)
                    .replaceAll("[^а-я\\s]", " ").trim().split("\\s+")).toList();

            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            try {
                searchData.setSite(new URL(page.getSite().getUrl()).getProtocol() + "://" +
                        new URL(page.getSite().getUrl()).getHost());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            searchData.setSnippet(getSnippet(sortedLemmaDbList, text) + " - " + relevance);
            searchData.setTitle(doc.title());
            searchData.setRelevance(relevance);
            data.add(searchData);
        }

        searchResponse.setResult(true);
        searchResponse.setCount(rankedPagesMap.size());//data.size());
        log.info("Подготовка ответа заняла: {}", System.currentTimeMillis() - start);
        //for (int i = offset; i < limit + offset; i++) {
//            Iterator<Map.Entry<Page, Float>> iterator;
//            iterator = rankedPagesMap.entrySet().iterator();
//            while (iterator.hasNext()) {
//                Map.Entry<Page, Float> entry = iterator.next();
//                if (i == 0) {
//                    rankedPagesMap.remove(entry.getKey());
//                    break;
//                }
//                i--;
//            }

        //Page page = rankedPagesMap.entrySet()
        //}


        /*rankedPagesMap.forEach((page, relevance) -> {
            Document doc = Jsoup.parse(page.getContent());
            SearchData searchData = new SearchData();
            List<String> text = Arrays.stream(doc.body().text().toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+")).toList();

            searchData.setSiteName(page.getSite().getName());
            searchData.setUri(page.getPath());
            try {
                searchData.setSite(new URL(page.getSite().getUrl()).getProtocol() + "://" + new URL(page.getSite().getUrl()).getHost());// чтобы корректно открывались ссылки при поиске по сайтам, индексированным не с главной страницы
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            searchData.setSnippet("");//getSnippet(sortedLemmaDbList, text) + " - " + relevance);
            searchData.setTitle(doc.title());
            searchData.setRelevance(relevance);
            data.add(searchData);
        });

        searchResponse.setResult(true);
        searchResponse.setCount(data.size());
        log.info("Подготовка ответа заняла: {}", System.currentTimeMillis() - start);*/
    }

    private SearchResponse paginateResults(int offset, int limit) {
//        if (offset + limit > data.size()) {
//            limit = data.size() - offset;
//        }
        limit = offset + limit > rankedPagesMap.size() ? rankedPagesMap.size() - offset : limit;
        prepareSearchResponse(offset, limit);
        searchResponse.setData(data.subList(offset, offset + limit));
        return searchResponse;
    }

    private String getSnippet(List<Lemma> queryLemmasList, List<String> text) {//TODO: 1) разобраться
        long start = System.currentTimeMillis();
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

        String snippet = "..." + words.stream()
                .map(word -> queryWordsList.contains(getWordNormalForm(word.toLowerCase(Locale.ROOT))) ? "<b>" + word + "</b>" : word)
                .collect(Collectors.joining(" ")) + "...";
        log.info("Создание сниппета заняло: {} мс", System.currentTimeMillis() - start);
        return snippet;
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
