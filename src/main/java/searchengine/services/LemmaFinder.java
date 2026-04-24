package searchengine.services;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;

import org.springframework.stereotype.Service;
import searchengine.Repositories.IndexRepository;
import searchengine.Repositories.LemmaRepository;
import searchengine.Repositories.PageRepository;
import searchengine.config.Config;
import searchengine.model.Index;
import searchengine.model.Page;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
@Getter
public class LemmaFinder {

    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private LuceneMorphology luceneMorphRus;
    private final PageRepository pageRepository;
    private final Set<Index> indexSet = ConcurrentHashMap.newKeySet();
    private final Config config;

    /**
     * Preserves Cyrillic, Latin, digits, hyphens and slashes so that court case
     * numbers (e.g. "2А-1234/2024", "А33-5678/2023") survive tokenisation.
     */
    private final String symbolRegex = "[^а-яА-Яa-zA-Z0-9/\\-\\s]";

    /**
     * Matches Russian court case number patterns:
     *   2А-1234/2024   (civil / administrative)
     *   А33-5678/2023  (arbitration)
     *   7-890/2024     (short form)
     */
    private static final Pattern COURT_CASE_PATTERN =
            Pattern.compile("[А-Яа-яA-Za-z0-9]+-[А-Яа-яA-Za-z0-9]+/\\d{4}");

    /**
     * Matches unique case identifiers with 2+ hyphens (no /YYYY suffix):
     *   66OV0001-01-2021-000076-43
     */
    private static final Pattern CASE_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+){2,}");

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
        String[] wordsArray = prepareStringArray(page.getContent());

        for (String word : wordsArray) {
            if (word.isEmpty()) continue;
            if (isWordSignificant(word)) {
                String lemma = getLemma(word);
                lemmasMap.merge(lemma, 1, Integer::sum);
            }
        }
        saveLemmas(lemmasMap, page);
    }

    public void saveLemmas(HashMap<String, Integer> lemmasMap, Page page) {
        int siteId = page.getSite().getId();
        for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {
            // Atomic upsert — no Java lock needed; ON CONFLICT handles concurrent threads
            lemmaRepository.upsertLemma(entry.getKey(), siteId);
            Integer lemmaId = lemmaRepository.findIdByLemmaAndSiteId(entry.getKey(), siteId);
            if (lemmaId == null) {
                log.error("lemmaId is null after upsert for lemma='{}', siteId={}", entry.getKey(), siteId);
                continue;
            }
            Index indexEntity = new Index();
            indexEntity.setLemmaId(lemmaId);
            indexEntity.setPageId(page.getId());
            indexEntity.setRank(entry.getValue());
            indexSet.add(indexEntity);
        }
    }

    /**
     * Returns the base (normal) form of a word.
     * Court case numbers are returned lowercase verbatim (no morphological analysis).
     */
    public String getLemma(String word) {
        if (isCourtCaseNumber(word) || isCaseId(word) || isDigitSequence(word)) {
            return word.toLowerCase(Locale.ROOT);
        }
        try {
            return luceneMorphRus.getNormalForms(word).get(0);
        } catch (Exception e) {
            return word.toLowerCase(Locale.ROOT);
        }
    }

    public void saveIndex() {
        indexRepository.saveAllAndFlush(indexSet);
    }

    /**
     * Returns true if the word should be indexed:
     * - court case numbers (А33-5678/2023) always pass
     * - unique case identifiers (66OV0001-01-2021-000076-43) always pass
     * - standalone digit sequences (article numbers, e.g. "337") always pass
     * - Cyrillic words pass if they are not grammatical service words
     */
    public boolean isWordSignificant(String word) {
        if (isCourtCaseNumber(word) || isCaseId(word) || isDigitSequence(word)) {
            return true;
        }
        if (!luceneMorphRus.checkString(word)) {
            return false;
        }
        List<String> exceptions = config.getLemmaExceptions();
        if (exceptions != null && !exceptions.isEmpty()) {
            // getMorphInfo returns "word|TAG" — check if any configured tag appears in the morph string
            for (String wordForm : luceneMorphRus.getMorphInfo(word)) {
                if (exceptions.stream().anyMatch(wordForm::contains)) {
                    return false;
                }
            }
        }
        return true;
    }

    public String[] prepareStringArray(String text) {
        return text.toLowerCase(Locale.ROOT)
                   .replaceAll(symbolRegex, " ")
                   .trim()
                   .split("\\s+");
    }

    private boolean isCourtCaseNumber(String word) {
        return COURT_CASE_PATTERN.matcher(word).matches();
    }

    private boolean isCaseId(String word) {
        return CASE_ID_PATTERN.matcher(word).matches();
    }

    /** Matches standalone digit sequences of 2 or more digits (e.g. article numbers). */
    private boolean isDigitSequence(String word) {
        return word.length() >= 2 && word.chars().allMatch(Character::isDigit);
    }
}
