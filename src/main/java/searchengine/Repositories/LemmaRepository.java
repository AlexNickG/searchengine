package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.site", nativeQuery = true)
    void deleteLemmas();

    Lemma findByLemma(String lemma);
}
