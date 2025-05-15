package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.exceptions.WordNotFitToDictionaryException;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
@Getter
public class LemmaFinder { //нужно ли создавать экземпляр класса? или использовать статические методы?
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private LuceneMorphology luceneMorph;
    private final PageRepository pageRepository;
    Set<Index> indexSet = ConcurrentHashMap.newKeySet(); //TODO: what is it?
    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void collectLemmas(int pageId) throws SQLException {
        HashMap<String, Integer> lemmasMap = new HashMap<>();
        Page page = pageRepository.findById(pageId).orElseThrow();//зачем бросать exception? - Если по какой-то причине страница с данным id не найдена в БД
        String[] words = getText(page).toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+"); //TODO: optimize it; попробовать добавить в regex английские буквы. при этом должно выбрасываться исключение
        List<String> wordBaseForms;

        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!luceneMorph.checkString(word)) {
                // если слово не подходит для морфологического анализа - бросаем исключение (если слово состоит из кириллицы и латиницы)
                // такое исключение можно перехватить внутри Spring и создать специальный ответ
                // смотри exceptions/DefaultAdvice.java
                log.info("bad word {}", word);
                throw new WordNotFitToDictionaryException(word);
            }
            //wordBaseForms = luceneMorph.getMorphInfo(word);
            //if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || getLemma(word).length() < 3)) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less; 3) Преобразовать в обратную форму
            //} else {
            if (isWordSignificant(word)) {
                //lemmasMap.computeIfPresent(getLemma(word), (k, v) -> v + 1);
                //var i = lemmasMap.containsKey(getLemma(word)) ? lemmasMap.put(getLemma(word), lemmasMap.get(getLemma(word)) + 1) : lemmasMap.put(getLemma(word), 1);
                lemmasMap.put(getLemma(word), lemmasMap.containsKey(getLemma(word)) ? lemmasMap.get(getLemma(word)) + 1 : 1);
//                if (!lemmasMap.containsKey(getLemma(word))) {//TODO: переписать код, используя методы computeIfAbsent и computeIfPresent
//                    lemmasMap.put(getLemma(word), 1);
//                } else {
//                    lemmasMap.put(getLemma(word), lemmasMap.get(getLemma(word)) + 1);
//                }
            }
        }
        saveLemmas(lemmasMap, page);
    }

    public String getLemma(String word) {//TODO: зачем? можно сразу в collectLemmas() вызывать getNormalForms(word).get(0) и не создавать лишний метод
        return luceneMorph.getNormalForms(word).get(0);
    }

    //@Transactional
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

    public void saveIndex() {
        indexRepository.saveAllAndFlush(indexSet);
    }

    private String getText(Page page) {//TODO: нужно ли это? или можно использовать page.getContent()?
        return page.getContent();
    }

    public boolean isWordSignificant(String word) {//TODO: проверить
        List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
        return wordBaseForms.stream().noneMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || getLemma(word).length() < 3);//TODO: 1) add to array and check in cycle
    }
}
