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
    @Query(value = "delete from Page")
    void deletePages();

    @Query(value = "select count(*) from Page p where p.site = ?1")
    int getSizeBySiteId(int site_id);

    List<Page> findByPath(String link);

    Page findByPathAndSiteId(String link, int site_id);

    List<Page> findBySiteId(int site_id);
}
