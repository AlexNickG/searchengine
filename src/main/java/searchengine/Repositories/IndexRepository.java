package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {

    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.index", nativeQuery = true)
    void deleteIndex();

    @Transactional
    @Modifying
    @Query(value = "INSERT INTO search_engine.index(lemma_id, page_id, rank) VALUES ?1", nativeQuery = true)
    void executeMultiInsert(String insertQuery);
}
