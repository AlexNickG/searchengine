package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;

import java.util.List;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    @Modifying
    @Query(value = "delete from Lemma")
    void deleteLemmas();

    List<Lemma> findByLemma(String lemma); //two sites may have the same lemma


    Lemma findByLemmaAndSiteId(String lemma, int siteId);

    List<Lemma> findBySiteId(int siteId);

    @Modifying
    @Query("update Lemma l set l.frequency = l.frequency - 1 where l.id = ?1")
    void decreaseLemmaFreqById(int id);
}
