package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;
import searchengine.Repositories.LemmaRepository;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
@Service
@RequiredArgsConstructor
public class LemmaFinder { //нужно ли создавать экземпляр класса? или использовать статические методы?
    private final LemmaRepository lemmaRepository;
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
           for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
               String key = entry.getKey();
               Integer value = entry.getValue();
               saveLemmas(key, value, page);
           }
           //lemmas.forEach(LemmaFinder::saveLemmas); //why it isn't work?
           //return lemmas;

    }
    public String getLemma(String word) {
        return luceneMorph.getNormalForms(word).get(0);
    }

    public void saveLemmas(String lemma, Integer frequency, Page page) {
        Lemma lemmaEntity = new Lemma();
        lemmaEntity.setLemma(lemma);
        lemmaEntity.setSite(page.getSite());
        lemmaEntity.setFrequency(frequency);
        lemmaRepository.save(lemmaEntity);
    }
    private String getText(Page page) {
        return page.getContent();
    }
}
