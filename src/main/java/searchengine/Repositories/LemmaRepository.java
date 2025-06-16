package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    @Modifying
    @Query(value = "delete from Lemma")
    void deleteLemmas();

    Lemma findByLemmaAndSiteId(String lemma, int siteId);

    @Query("select id from Lemma l where l.lemma = ?1 and l.site.id = ?2")
    Integer findIdByLemmaAndSiteId(String lemma, int siteId);

    @Query("select count(*) from Lemma l where l.site.id = ?1")
    int getSizeBySiteId(int siteId);

    @Modifying
    @Query("update Lemma l set l.frequency = l.frequency - 1 where l.id = ?1")
    void decreaseLemmaFreqById(int id);

    @Transactional
    @Modifying
    @Query("update Lemma l set l.frequency = l.frequency + 1 where l.id = ?1")
    void increaseLemmaFreqById(int id);
}
