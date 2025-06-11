package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Set;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    @Transactional
    @Modifying
    @Query(value = "delete from Lemma")
    void deleteLemmas();

    List<Lemma> findByLemma(String lemma); //two sites may have the same lemma


    Lemma findByLemmaAndSiteId(String lemma, int siteId);

    //@Query(value = "select l from Lemma l where l.lemma = ?1 and l.site = ?2")
    //List<Lemma> findAllByLemmaAndSiteId(Set<String> lemmaSet, int siteId);

    List<Lemma> findBySiteId(int site_id);

    @Transactional
    @Modifying
    @Query("delete from Lemma l where l.id = ?1")
    void deleteLemmaById(int id);

    @Query("update Lemma l set l.frequency = l.frequency - 1 where l.id = ?1")
    void decreaseLemmaFreqById(int id);
}
