package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Site;

import java.util.List;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {
    /*@Modifying
    @Transactional
    @Query(value = "TRUNCATE TABLE site", nativeQuery = true)
    void truncateTable();*/

    @Transactional
    @Modifying
    @Query(value = "delete from search_engine.site", nativeQuery = true)
    void deleteAllSites();

    @Transactional
    @Modifying
    @Query(value = "SET FOREIGN_KEY_CHECKS = 0", nativeQuery = true)
    void setForeignKeyCheckNull();

    @Transactional
    @Modifying
    @Query(value = "SET FOREIGN_KEY_CHECKS = 1", nativeQuery = true)
    void setForeignKeyCheckNotNull();

    Site findByName(String name);

    Site findByUrl(String url);

    //List<Site> findAllSites();
}
