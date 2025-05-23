package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Optional;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.lemma", nativeQuery = true)
    void deleteLemmas();

    List<Lemma> findByLemma(String lemma); //two sites may have the same lemma

    @Query(value = "select * from Lemma l where l.lemma = ?1 and l.site_id = ?2", nativeQuery = true)//немного (примерно в 1,5 раза) ускоряет выполнение метода
    Lemma findByLemmaAndSite_Id(String lemma, int siteId);

    @Query("select l from Lemma l where l.lemma = ?1 and l.site.id = ?2")
    Optional<Lemma> getLemma(String lemma, Integer id);

    List<Lemma> findBySite_id(int site_id);

    @Query("delete from Lemma l where l.id = ?1")
    @Transactional
    @Modifying
    void deleteLemmaById(int id);
}
