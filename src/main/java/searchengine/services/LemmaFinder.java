package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.config.Config;
import searchengine.exceptions.WordNotFitToDictionaryException;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.io.IOException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
@Getter
@ConfigurationProperties(prefix = "exceptions")
public class LemmaFinder {
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private LuceneMorphology luceneMorphRus;
    private final PageRepository pageRepository;
    private final Set<Index> indexSet = ConcurrentHashMap.newKeySet(); //Потоконезависимый Set
    private final Config config;
    //private final List<String> lemmaExceptions = config.getLemmaExceptions();

    {
        try {
            luceneMorphRus = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void collectLemmas(int pageId) {
        HashMap<String, Integer> lemmasMap = new HashMap<>();
        Page page = pageRepository.findById(pageId).orElseThrow();
        String[] wordsArray = page.getContent().toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+");

        for (String word : wordsArray) {
            if (word.isEmpty()) {
                continue;
            }
            if (!luceneMorphRus.checkString(word)) {
                // если слово не подходит для морфологического анализа - бросаем исключение (если слово состоит из кириллицы и латиницы или содержит цифры)
                log.info("bad word {}", word);
                throw new WordNotFitToDictionaryException(word);
            }
            if (isWordSignificant(word))
                lemmasMap.put(getLemma(word), lemmasMap.containsKey(getLemma(word)) ? lemmasMap.get(getLemma(word)) + 1 : 1);
        }
        saveLemmas(lemmasMap, page);
    }

    public void saveLemmas(HashMap<String, Integer> lemmasMap, Page page) {

        int lemmaId;
        Lemma dbLemma;

        for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {
            Index indexEntity = new Index();
            synchronized (lemmaRepository) {
                dbLemma = lemmaRepository.findByLemmaAndSite_Id(entry.getKey(), page.getSite().getId());
                if (dbLemma != null) {
                    dbLemma.setFrequency(dbLemma.getFrequency() + 1);
                } else {
                    dbLemma = new Lemma();
                    dbLemma.setSite(page.getSite());
                    dbLemma.setLemma(entry.getKey());
                    dbLemma.setFrequency(1);
                }
                lemmaId = lemmaRepository.save(dbLemma).getId();
            }
            indexEntity.setLemmaId(lemmaId);
            indexEntity.setPageId(page.getId());
            indexEntity.setRank(entry.getValue());
            indexSet.add(indexEntity);
        }
    }

    public String getLemma(String word) {
        return luceneMorphRus.getNormalForms(word).get(0);
    }

    public void saveIndex() {
        indexRepository.saveAllAndFlush(indexSet);
    }

    public boolean isWordSignificant(String word) {
        for (String wordForm : luceneMorphRus.getMorphInfo(word)) {
            if (config.getLemmaExceptions().contains(wordForm)) {
                return false;
            }
        }
        return true;
//        List<String> lemmaExceptions = config.getLemmaExceptions();
//        List<String> fileExceptions = config.getFileExtensions();
//        List<String> pathContaining = config.getPathContaining();
//        List<String> wordBaseForms = luceneMorphRus.getMorphInfo(word);
//        return wordBaseForms.stream().noneMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || getLemma(word).length() < 3);//TODO: 1) add to array and check in cycle
    }
}
