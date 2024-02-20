package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import java.io.IOException;
import java.util.*;

@Service
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

    ;

    public void collectLemmas(Page page) {
        HashMap<String, Integer> lemmas = new HashMap<>();
        String[] words = getText(page).toLowerCase(Locale.ROOT).replaceAll("[^а-я\\s]", " ").trim().split("\\s+"); //TODO: optimize it

        for (String word : words) {
            List<String> wordBaseForms = luceneMorph.getMorphInfo(word);
            //wordBaseForms.forEach(System.out::println);
            if (wordBaseForms.stream().anyMatch(w -> w.contains("СОЮЗ") || w.contains("МЕЖД") || w.contains("ПРЕДЛ"))) {//TODO: add to array and check in cycle
            } else {
                if (!lemmas.containsKey(getLemma(word))) {
                    lemmas.put(getLemma(word), 1);
                } else {
                    lemmas.put(getLemma(word), lemmas.get(getLemma(word)) + 1);
                }

            }
        }
        //lemmas.forEach((k, v) -> System.out.println(k + " - " + v));
        saveLemmas(lemmas, page);
        //lemmas.forEach(LemmaFinder::saveLemmas); //why it isn't work?
        //return lemmas;

    }

    public String getLemma(String word) {
        return luceneMorph.getNormalForms(word).get(0);
    }

    public void saveLemmas(HashMap<String, Integer> lemmas, Page page) {
        List<Lemma> lemmaList = new ArrayList<>();
        Set<Index> indexSet = new HashSet<>();
        Lemma lemmaEntity = new Lemma();
        Index indexEntity = new Index();
        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            lemmaEntity.setLemma(entry.getKey());
            lemmaEntity.setFrequency(entry.getValue());
            lemmaEntity.setSite(page.getSite());
            synchronized (lemmaRepository) {
                lemmaRepository.save(lemmaEntity);
            }
            //lemmaEntity.setPages();
            //lemmaList.add(lemmaEntity);
            indexEntity.setLemmaId(lemmaEntity.getId());
            indexEntity.setPageId(page.getId());
            indexEntity.setRank(lemmaEntity.getFrequency());
            //indexEntity.setPageId(page.getId());
            indexSet.add(indexEntity);


        }
        indexRepository.saveAll(indexSet);
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
