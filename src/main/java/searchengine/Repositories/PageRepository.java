package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;

import java.util.List;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {

    @Modifying
    @Query(value = "delete from Page")
    void deletePages();

    @Query(value = "select count(*) from Page p where p.site.id = ?1")
    int getSizeBySiteId(int siteId);

    List<Page> findByPath(String link);

    Page findByPathAndSiteId(String link, int siteId);

    List<Page> findBySiteId(int siteId);
}
