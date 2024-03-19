package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.io.IOException;
import java.util.*;
@Service
//@Scope("prototype")
@RequiredArgsConstructor
public class LemmaFinder { //нужно ли создавать экземпляр класса? или использовать статические методы?
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    //private final Page page;
    private LuceneMorphology luceneMorph;

    {
        try {
            luceneMorph = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /*public void collectLemmas(Page page) {
        HashSet<String> lemmasSet = new HashSet<>();
        String[] words = getText(page).toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+"); //TODO: optimize it

        for (String word : words) {
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
            if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ"))) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
            } else {
                lemmasSet.add(getLemma(word));
            }
        }
        saveLemmas(lemmasSet, page);
    }*/

    public void collectLemmas(Page page) {
        HashMap<String, Integer> lemmasMap = new HashMap<>();
        String[] words = getText(page).toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+"); //TODO: optimize it

        for (String word : words) {
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
            //wordBaseForms.forEach(System.out::println);
            if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ") || w.contains(" ЧАСТ"))) {//TODO: 1) add to array and check in cycle; 2) remove words of three letters or less
            } else {
                if (!lemmasMap.containsKey(getLemma(word))) {
                    lemmasMap.put(getLemma(word), 1);
                } else {
                    lemmasMap.put(getLemma(word), lemmasMap.get(getLemma(word)) + 1);
                }
            }
        }
        //lemmas.forEach((k, v) -> System.out.println(k + " - " + v));

        //lemmas.forEach(LemmaFinder::saveLemmas); //why it isn't work?
        //return lemmas;
        saveLemmas(lemmasMap, page);
    }




public String getLemma(String word) {
    return luceneMorph.getNormalForms(word).get(0);
}

public void saveLemmas(HashMap<String, Integer> lemmasMap, Page page) { //TODO: продумать сохранение лемм и индексов
    //System.out.println(Thread.currentThread());
    //System.out.println(page.getPath());
    List<Lemma> lemmaList = new ArrayList<>();
    Set<Index> indexSet = new HashSet<>();


    for (Map.Entry<String, Integer> entry: lemmasMap.entrySet()) {

        Index indexEntity = new Index();

        //System.out.println(entry.getKey());

        Lemma dbLemma = lemmaRepository.findByLemma(entry.getKey());
        if (dbLemma != null) {
            dbLemma.setFrequency(dbLemma.getFrequency() + 1);
            /*synchronized (lemmaRepository) {
                lemmaRepository.save(dbLemma);
            }*/
            /*indexEntity.setLemmaId(dbLemma.getId());
            indexEntity.setPageId(page.getId());
            indexEntity.setRank(entry.getValue());*/
        } else {
            dbLemma = new Lemma();
            dbLemma.setSite(page.getSite());
            dbLemma.setLemma(entry.getKey());
            dbLemma.setFrequency(1);

        }
        //synchronized (lemmaRepository) {
            lemmaRepository.saveAndFlush(dbLemma);
        //}
        indexEntity.setLemmaId(dbLemma.getId());
        indexEntity.setPageId(page.getId());
        indexEntity.setRank(entry.getValue());

        /* synchronized (lemmaRepository) {
            lemmaRepository.save(lemmaEntity);
        }*/
        //lemmaEntity.setPages();
        //lemmaList.add(lemmaEntity);

        //indexEntity.setPageId(page.getId());
        indexSet.add(indexEntity);
    }
    indexRepository.saveAllAndFlush(indexSet);
        /*synchronized (lemmaRepository) {
            lemmaRepository.saveAll(lemmaList);
        }

        synchronized (indexRepository) {
            indexRepository.saveAll(indexList);
        }*/
}

public void saveIndex(Page page) {


}

private String getText(Page page) {
    return page.getContent();
}
}
