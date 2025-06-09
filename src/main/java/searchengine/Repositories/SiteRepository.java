package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Site;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {

    @Transactional
    @Modifying
    @Query(value = "delete from Site")
    void deleteAllSites();

    //@Query(value = "SET FOREIGN_KEY_CHECKS = 0")
    //void setForeignKeyCheckNull();

    //@Query(value = "SET FOREIGN_KEY_CHECKS = 1")
    //void setForeignKeyCheckNotNull();

    Site findByUrl(String url);
}
