package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Page;

import java.util.List;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {

    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.page", nativeQuery = true)
    void deletePages();

//    @Transactional
//    @Query(value = "select * from search_engine.page where path like ?1", nativeQuery = true)
List<Page> findByPath(String link);

    List<Page> findBySite_id(int site_id);
}
