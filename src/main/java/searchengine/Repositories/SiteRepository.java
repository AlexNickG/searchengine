package searchengine.Repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {

    @Modifying
    @Query(value = "delete from Site")
    void deleteAllSites();

    Site findByUrl(String url);
}
