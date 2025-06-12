package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;

import java.util.List;
import java.util.Set;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {

    @Modifying
    @Query(value = "delete from Index")
    void deleteIndex();

    Index findByPageIdAndLemmaId(int pageId, int lemmaId);

    List<Index> findByPageId(int pageId);

    Set<Index> findByLemmaId(int lemmaId);
}
