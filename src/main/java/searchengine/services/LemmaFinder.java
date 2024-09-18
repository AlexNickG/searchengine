package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static StringBuilder insertQuery = new StringBuilder();
    Set<Index> indexSet = ConcurrentHashMap.newKeySet();
    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Tread name: " + Thread.currentThread().getName());
    }

    public void collectLemmas(int pageId) throws SQLException {
        HashMap<String, Integer> lemmasMap = new HashMap<>();
        Page page = pageRepository.findById(pageId).orElseThrow();//зачем бросать exception?
        String[] words = getText(page).toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+"); //TODO: optimize it
        List<String> wordBaseForms;

        for (String word : words) {
            if (word.isEmpty()) {
                //log.info("Empty word");
                continue;
            }
            if (!luceneMorph.checkString(word)) {
                // если слово не подходит для морфологического анализа - бросаем исключение
                // такое исключение можно перехватить внутри Spring и создать специальный ответ
                // смотри exceptions/DefaultAdvice.java
                log.info("bad word {}", word);
                throw new WordNotFitToDictionaryException(word);
            }
            //wordBaseForms = luceneMorph.getMorphInfo(word);
            //log.info("good word {}", word);
//            System.out.println("-" + word + "-");
            wordBaseForms = luceneMorph.getMorphInfo(word);
            if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ") || getLemma(word).length() < 3)) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
                //System.out.println("match");
            } else {
                if (!lemmasMap.containsKey(getLemma(word))) {
                    lemmasMap.put(getLemma(word), 1);
                } else {
                    lemmasMap.put(getLemma(word), lemmasMap.get(getLemma(word)) + 1);
                }
            }
        }
        saveLemmas(lemmasMap, page);
    }


    public String getLemma(String word) {
        return luceneMorph.getNormalForms(word).get(0);
    }

    @Transactional
    public void saveLemmas(HashMap<String, Integer> lemmasMap, Page page) throws SQLException { //TODO: продумать сохранение лемм и индексов


        int lemmaId;
        Lemma dbLemma;
        List<Lemma> dbLemmaS;

        for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {

            Index indexEntity = new Index();
            //long start = System.currentTimeMillis();
            synchronized (lemmaRepository) {

                dbLemma = lemmaRepository.findByLemmaAndSite_Id(entry.getKey(), page.getSite().getId());
                if (dbLemma != null) {
                    //dbLemma = dbLemmaS.get(0);
                    dbLemma.setFrequency(dbLemma.getFrequency() + 1);
//                    if (dbLemmaS.size() > 1) {
//                        System.out.println("Two identical lemmas!");
//                        dbLemmaS.forEach(System.out::println);
//                    }
                } else {
                    dbLemma = new Lemma();
                    dbLemma.setSite(page.getSite());
                    dbLemma.setLemma(entry.getKey());
                    dbLemma.setFrequency(1);
                }
                //log.info("lemma word: {}", entry.getKey());
                lemmaId = lemmaRepository.save(dbLemma).getId();
                //System.out.println("Save lemma duration: " + (System.currentTimeMillis() - start));
                indexEntity.setLemmaId(lemmaId);
                indexEntity.setPageId(page.getId());
                indexEntity.setRank(entry.getValue());
                indexSet.add(indexEntity);
            }
            //log.info("Save lemma duration: {}", (System.currentTimeMillis() - start));
            //indexMultiInsertQuery(lemmaId, page.getId(), entry.getValue());

        }

        //indexRepository.executeMultiInsert(insertQuery.toString());
        //indexRepository.saveAllAndFlush(indexSet);
    }

    public void saveIndex() {
        indexRepository.saveAll(indexSet);
    }
    private String getText(Page page) {
        return page.getContent();
    }

    public static void indexMultiInsertQuery(int lemma_id, int page_id, float rank) {
        insertQuery.append(insertQuery.isEmpty() ? "" : ", ").append("(").append(lemma_id).append(", ").append(page_id).append(", ").append(rank).append(")");
        //insertQuery.append((insertQuery.isEmpty() ? "" : ", ") + "('" + lemma_id + "', '" + page_id + "', '" + rank + "')");
    }
}
