package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.util.List;
import java.util.Optional;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.lemma", nativeQuery = true)
    void deleteLemmas();
    //@Transactional
    //@Query(value = "select * from search_engine.lemma where lemma like ?1", nativeQuery = true)
    List<Lemma> findByLemma(String lemma); //two sites may have the same lemma

    Lemma findByLemmaAndSite_Id(String lemma, int siteId);
    //List<Page> findByLemma(String lemma);
}
