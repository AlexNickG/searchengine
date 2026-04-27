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

    @Modifying
    @Query("delete from Lemma l where l.site.id = ?1")
    void deleteBySiteId(int siteId);

    /**
     * Atomically inserts a new lemma or increments its frequency if (lemma, site_id) already exists.
     * Requires unique constraint uq_lemma_site on (lemma, site_id) — see v2-unique-lemma-constraint.xml.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO lemma (lemma, site_id, frequency) VALUES (?1, ?2, 1) " +
                   "ON CONFLICT (lemma, site_id) DO UPDATE SET frequency = lemma.frequency + 1",
           nativeQuery = true)
    void upsertLemma(String lemma, int siteId);

    Lemma findByLemmaAndSiteId(String lemma, int siteId);

    @Query("select l.id from Lemma l where l.lemma = ?1 and l.site.id = ?2")
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
